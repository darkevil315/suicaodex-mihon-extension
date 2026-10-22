package eu.kanade.tachiyomi.extension.vi.cbhentai

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class CBHentai : HttpSource(), ConfigurableSource {

    override val name = "CBHentai"

    override val lang = "vi"

    override val versionId = 1

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(CUSTOM_BASE_URL_KEY, DEFAULT_BASE_URL)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    private val flareLock = Any()
    private var lastSolvedTimestamp: Long = 0L

    private val flareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(65, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(65, TimeUnit.SECONDS)
            .build()
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::authInterceptor)
        .build()

    private fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code in listOf(403, 503)) return true
        if (response.header("cf-mitigated") == "challenge") return true
        val peek = try {
            response.peekBody(2048).string()
        } catch (_: Exception) {
            ""
        }
        return peek.startsWith("<") && (
            peek.contains("Just a moment", ignoreCase = true) ||
            peek.contains("challenge-running", ignoreCase = true) ||
            peek.contains("cf-browser-verification", ignoreCase = true) ||
            peek.contains("cf-mitigated", ignoreCase = true)
        )
    }

    private fun solveCloudflare(targetUrl: String) {
        val flareUrl = preferences.getString(FLARESOLVERR_URL_KEY, DEFAULT_FLARESOLVERR_URL)
            ?.trim()?.ifBlank { DEFAULT_FLARESOLVERR_URL } ?: DEFAULT_FLARESOLVERR_URL

        val payload = json.encodeToString(
            FlareSolverrRequest(
                cmd = "request.get",
                url = targetUrl,
                maxTimeout = 60000,
            ),
        )

        val flareReq = Request.Builder()
            .url(flareUrl)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            flareClient.newCall(flareReq).execute().use { flareRes ->
                if (!flareRes.isSuccessful) {
                    throw Exception("FlareSolverr trả về HTTP ${flareRes.code}")
                }
                val bodyString = flareRes.body.string()
                val flareResponse = json.decodeFromString<FlareSolverrResponse>(bodyString)
                if (flareResponse.status != "ok") {
                    throw Exception("FlareSolverr không giải được: ${flareResponse.message}")
                }
                val solution = flareResponse.solution ?: throw Exception("FlareSolverr không trả về solution")
                val cookies = solution.cookies?.joinToString("; ") { "${it.name}=${it.value}" }
                val ua = solution.userAgent

                preferences.edit().apply {
                    if (!cookies.isNullOrBlank()) {
                        putString(AUTO_FLARE_COOKIE_KEY, cookies)
                    }
                    if (!ua.isNullOrBlank()) {
                        putString(AUTO_FLARE_UA_KEY, ua)
                    }
                    apply()
                }
                lastSolvedTimestamp = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            throw Exception("Không thể kết nối hoặc giải qua FlareSolverr ($flareUrl): ${e.localizedMessage ?: e.message}")
        }
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val customUA = preferences.getString(CUSTOM_USER_AGENT_KEY, null)?.trim()?.ifBlank { null }
            ?: preferences.getString(AUTO_FLARE_UA_KEY, null)?.trim()?.ifBlank { null }
        val customCookie = preferences.getString(CUSTOM_COOKIE_KEY, null)?.trim()?.ifBlank { null }
            ?: preferences.getString(AUTO_FLARE_COOKIE_KEY, null)?.trim()?.ifBlank { null }

        val initialRequest = request.newBuilder().apply {
            if (customUA != null) {
                header("User-Agent", customUA)
            }
            if (customCookie != null) {
                header("Cookie", customCookie)
            }
        }.build()

        val response = chain.proceed(initialRequest)

        val flareEnabled = preferences.getBoolean(FLARESOLVERR_ENABLE_KEY, DEFAULT_FLARESOLVERR_ENABLE)
        if (!flareEnabled || !isCloudflareChallenge(response)) {
            return response
        }

        // Bị Cloudflare chặn & FlareSolverr đang được bật -> Tự động giải và retry
        return synchronized(flareLock) {
            val now = System.currentTimeMillis()
            if (now - lastSolvedTimestamp > 10_000L) {
                solveCloudflare("$baseUrl/")
            }

            response.close()

            val retryUA = preferences.getString(CUSTOM_USER_AGENT_KEY, null)?.trim()?.ifBlank { null }
                ?: preferences.getString(AUTO_FLARE_UA_KEY, null)?.trim()?.ifBlank { null }
            val retryCookie = preferences.getString(CUSTOM_COOKIE_KEY, null)?.trim()?.ifBlank { null }
                ?: preferences.getString(AUTO_FLARE_COOKIE_KEY, null)?.trim()?.ifBlank { null }

            val retryRequest = request.newBuilder().apply {
                if (retryUA != null) {
                    header("User-Agent", retryUA)
                }
                if (retryCookie != null) {
                    header("Cookie", retryCookie)
                }
            }.build()

            chain.proceed(retryRequest)
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        val customUA = preferences.getString(CUSTOM_USER_AGENT_KEY, null)?.trim()?.ifBlank { null }
            ?: preferences.getString(AUTO_FLARE_UA_KEY, null)?.trim()?.ifBlank { null }
        if (customUA != null) {
            set("User-Agent", customUA)
        }
        val customCookie = preferences.getString(CUSTOM_COOKIE_KEY, null)?.trim()?.ifBlank { null }
            ?: preferences.getString(AUTO_FLARE_COOKIE_KEY, null)?.trim()?.ifBlank { null }
        if (customCookie != null) {
            set("Cookie", customCookie)
        }
        set("Referer", "$baseUrl/")
    }

    // ------------------------------------------------------------------------------------
    // Popular & Latest & Search (Madara standard)
    // ------------------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page > 1) {
            "$baseUrl/read/page/$page/?m_orderby=views"
        } else {
            "$baseUrl/read/?m_orderby=views"
        }
        return parseMangaListing(client.newCall(GET(url, headers)).awaitSuccess())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page > 1) {
            "$baseUrl/read/page/$page/?m_orderby=latest"
        } else {
            "$baseUrl/read/?m_orderby=latest"
        }
        return parseMangaListing(client.newCall(GET(url, headers)).awaitSuccess())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = if (page > 1) {
            "$baseUrl/read/page/$page/".toHttpUrl().newBuilder()
        } else {
            "$baseUrl/read/".toHttpUrl().newBuilder()
        }

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("s", query.trim())
            urlBuilder.addQueryParameter("post_type", "wp-manga")
        } else {
            urlBuilder.addQueryParameter("m_orderby", "latest")
        }

        val url = urlBuilder.build()
        return parseMangaListing(client.newCall(GET(url, headers)).awaitSuccess())
    }

    private fun parseMangaListing(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val elements = document.select(".page-item-detail, .c-tabs-item__content, .manga-item")
        val mangas = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".post-title a, h3 a") ?: return@mapNotNull null
            val imgElement = element.selectFirst("img")

            SManga.create().apply {
                url = titleElement.attr("href").substringAfter(baseUrl)
                title = titleElement.text().trim()
                val dataSrc = imgElement?.attr("abs:data-src")
                val src = imgElement?.attr("abs:src")
                thumbnail_url = if (!dataSrc.isNullOrBlank()) dataSrc else src
            }
        }

        val hasNextPage = document.selectFirst(".nav-previous a, .pagination a.next, a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ------------------------------------------------------------------------------------
    // Manga Details & Chapters (Tachiyomix 1.6 getMangaUpdate)
    // ------------------------------------------------------------------------------------

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { getMangaDetailsInternal(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { getChapterListInternal(manga) } else null

        val detailsResult = detailsDeferred?.await()
        val newChapters = chaptersDeferred?.await()

        SMangaUpdate(
            detailsResult ?: manga,
            newChapters ?: chapters,
        )
    }

    private suspend fun getMangaDetailsInternal(manga: SManga): SManga {
        val url = "$baseUrl${manga.url}"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        val title = document.selectFirst("h1, .post-title h1")?.text()?.trim() ?: manga.title
        val author = document.select(".author-content a, .post-content_item:contains(Tác giả) .summary-content a")
            .joinToString { it.text().trim() }
            .takeIf { it.isNotBlank() }
        val genre = document.select(".genres-content a").joinToString { it.text().trim() }
            .takeIf { it.isNotBlank() }
        val desc = document.selectFirst(".description-summary .summary__content, .manga-excerpt, .summary__content")
            ?.text()?.trim()

        val status = when (document.selectFirst(".post-status .summary-content")?.text()?.lowercase()) {
            "on-going", "đang tiến hành" -> SManga.ONGOING
            "completed", "hoàn thành" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return manga.apply {
            this.title = title
            if (author != null) this.author = author
            if (genre != null) this.genre = genre
            if (desc != null) this.description = desc
            this.status = status
            this.initialized = true
        }
    }

    private suspend fun getChapterListInternal(manga: SManga): List<SChapter> {
        val url = "$baseUrl${manga.url}"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        var chapterElements = document.select("li.wp-manga-chapter")
        if (chapterElements.isEmpty()) {
            // Try Madara Ajax chapter list
            val mangaId = document.selectFirst("#manga-chapters-holder")?.attr("data-id")
            if (!mangaId.isNullOrBlank()) {
                val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
                val formBody = FormBody.Builder()
                    .add("action", "manga_get_chapters")
                    .add("manga", mangaId)
                    .build()
                val ajaxReq = POST(ajaxUrl, headers, formBody)
                val ajaxRes = client.newCall(ajaxReq).awaitSuccess()
                val ajaxDoc = Jsoup.parse(ajaxRes.body.string(), baseUrl)
                chapterElements = ajaxDoc.select("li.wp-manga-chapter")
            }
        }

        return chapterElements.mapNotNull { parseChapter(it) }
    }

    private fun parseChapter(element: Element): SChapter? {
        val link = element.selectFirst("a") ?: return null
        return SChapter.create().apply {
            url = link.attr("href").substringAfter(baseUrl)
            name = link.text().trim()
            val dateText = element.selectFirst("span.chapter-release-date, .c-new-tag")?.text()?.trim()
            if (!dateText.isNullOrBlank()) {
                date_upload = runCatching {
                    dateFormat.parse(dateText)?.time ?: 0L
                }.getOrDefault(0L)
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Page list (Images) - Safe implementation without Android WebView crash
    // ------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val response = client.newCall(GET(chapterUrl, headers)).awaitSuccess()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        // 1. Kiểm tra xem có phải Reader MSR v3 không
        val msr3Element = document.selectFirst(".msr-reader[data-msr-api]")
        if (msr3Element != null) {
            return getMsr3Pages(msr3Element, chapterUrl)
        }

        // 2. Kiểm tra xem có phải Reader MASR v2 không
        val masr2Element = document.selectFirst(".masr2-reader[data-masr2-token]")
        if (masr2Element != null) {
            return getMasr2Pages(masr2Element, chapterUrl)
        }

        // 3. Reader Madara tiêu chuẩn (các chương mới sau 66)
        val imageElements = document.select(".page-break img, .reading-content img, .wp-manga-chapter-img")
        if (imageElements.isNotEmpty()) {
            return imageElements.mapIndexed { index, img ->
                val imageUrl = img.attr("abs:data-src")
                    .ifBlank { img.attr("abs:src") }
                    .ifBlank { img.attr("abs:data-full-url") }
                Page(index, chapterUrl, imageUrl)
            }
        }

        throw Exception("Không tìm thấy hình ảnh chương hoặc cơ chế đọc không được hỗ trợ.")
    }

    private suspend fun getMsr3Pages(msr3Element: Element, chapterUrl: String): List<Page> {
        val mangaId = msr3Element.attr("data-msr-manga").toIntOrNull()
            ?: throw Exception("Không tìm thấy Manga ID trong MSR reader")
        val chapterId = msr3Element.attr("data-msr-chapter").toIntOrNull()
            ?: throw Exception("Không tìm thấy Chapter ID trong MSR reader")
        val apiEndpoint = msr3Element.attr("data-msr-api").ifEmpty { "/wp-json/manga-reader/v3/" }

        val baseApiUrl = if (apiEndpoint.startsWith("http")) {
            apiEndpoint.trimEnd('/')
        } else {
            "$baseUrl${if (apiEndpoint.startsWith("/")) "" else "/"}${apiEndpoint.trimEnd('/')}"
        }

        val apiHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("X-MSR-Request", "1")
            .set("Referer", chapterUrl)
            .build()

        val pagesPayload = json.encodeToString(MsrPagesRequest(manga = mangaId, chapter = chapterId))
        val pagesRequest = Request.Builder()
            .url("$baseApiUrl/pages")
            .headers(apiHeaders)
            .post(pagesPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val pagesResponse = client.newCall(pagesRequest).awaitSuccess()
        val responseBody = pagesResponse.body.string()

        if (pagesResponse.code == 401 || responseBody.contains("msr_verify_required")) {
            // Thay vì gọi getTurnstileToken làm crash Suwayomi:
            throw Exception(
                "Chapter này được bảo vệ bởi Cloudflare Turnstile (MSR v3). " +
                "Vui lòng mở link chapter trên trình duyệt máy tính, tick xác minh 1 lần rồi dán Cookie vào Cài đặt extension!"
            )
        }

        val data = json.decodeFromString<MsrPagesResponse>(responseBody)
        return data.items.mapIndexed { index, imageUrl ->
            Page(index, chapterUrl, imageUrl)
        }
    }

    private suspend fun getMasr2Pages(masr2Element: Element, chapterUrl: String): List<Page> {
        val token = masr2Element.attr("data-masr2-token")
        if (token.isBlank()) throw Exception("Không tìm thấy MASR2 token")

        val clientId = generateClientId()
        val apiHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Referer", chapterUrl)
            .build()

        val imageUrls = mutableListOf<String>()
        var currentToken = token

        do {
            val url = "$baseUrl/wp-json/manga-reader/v2/pages".toHttpUrl().newBuilder()
                .addQueryParameter("token", currentToken)
                .addQueryParameter("cid", clientId)
                .build()
            val response = client.newCall(GET(url, apiHeaders)).awaitSuccess()
            val data = json.decodeFromString<ReaderPageResponse>(response.body.string())
            imageUrls.addAll(data.items)
            if (data.done || data.nextToken.isNullOrEmpty()) break
            currentToken = data.nextToken
        } while (true)

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, chapterUrl, imageUrl)
        }
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // ------------------------------------------------------------------------------------
    // Preferences Screen
    // ------------------------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FLARESOLVERR_ENABLE_KEY
            title = "Tự động vượt Cloudflare qua FlareSolverr"
            summary = "Tự động giải Cloudflare bằng FlareSolverr khi trang web chặn request."
            setDefaultValue(DEFAULT_FLARESOLVERR_ENABLE)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(FLARESOLVERR_ENABLE_KEY, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = FLARESOLVERR_URL_KEY
            title = "FlareSolverr API URL"
            summary = "Mặc định: $DEFAULT_FLARESOLVERR_URL."
            dialogTitle = "FlareSolverr URL"
            setDefaultValue(DEFAULT_FLARESOLVERR_URL)
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(FLARESOLVERR_URL_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_BASE_URL_KEY
            title = "Tên miền (Base URL)"
            summary = "Mặc định: $DEFAULT_BASE_URL. Đổi khi trang web đổi tên miền mới."
            dialogTitle = "Tên miền trang web"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim().trimEnd('/')
                preferences.edit().putString(CUSTOM_BASE_URL_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_COOKIE_KEY
            title = "Cookie xác minh (MSR / Cloudflare)"
            summary = "Dán cookie từ trình duyệt sau khi tick Turnstile trên web nếu chapter cũ bị khóa."
            dialogTitle = "Cookie xác minh"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(CUSTOM_COOKIE_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_USER_AGENT_KEY
            title = "User-Agent tùy chỉnh"
            summary = "Mặc định để trống để tự động lấy từ FlareSolverr hoặc cài đặt Mihon."
            dialogTitle = "User-Agent"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(CUSTOM_USER_AGENT_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ------------------------------------------------------------------------------------
    // Unsupported default methods
    // ------------------------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    companion object {
        private const val CUSTOM_BASE_URL_KEY = "custom_base_url"
        private const val DEFAULT_BASE_URL = "https://2tencb.pro"

        private const val FLARESOLVERR_ENABLE_KEY = "flaresolverr_enable"
        private const val DEFAULT_FLARESOLVERR_ENABLE = true

        private const val FLARESOLVERR_URL_KEY = "flaresolverr_url"
        private const val DEFAULT_FLARESOLVERR_URL = "http://127.0.0.1:8191/v1"

        private const val AUTO_FLARE_COOKIE_KEY = "auto_flare_cookie"
        private const val AUTO_FLARE_UA_KEY = "auto_flare_ua"

        private const val CUSTOM_COOKIE_KEY = "custom_cookie"
        private const val CUSTOM_USER_AGENT_KEY = "custom_user_agent"
    }
}

// ------------------------------------------------------------------------------------
// DTOs
// ------------------------------------------------------------------------------------

@Serializable
class MsrPagesRequest(
    val manga: Int,
    val chapter: Int,
)

@Serializable
class MsrPagesResponse(
    val items: List<String>,
    val count: Int? = null,
    val protocol: Int? = null,
)

@Serializable
class ReaderPageResponse(
    val items: List<String>,
    val done: Boolean,
    val nextToken: String? = null,
)

@Serializable
class FlareSolverrRequest(
    val cmd: String = "request.get",
    val url: String,
    val maxTimeout: Int = 60000,
)

@Serializable
class FlareSolverrResponse(
    val status: String? = null,
    val message: String? = null,
    val solution: FlareSolverrSolution? = null,
)

@Serializable
class FlareSolverrSolution(
    val url: String? = null,
    val status: Int? = null,
    val cookies: List<FlareSolverrCookie>? = null,
    val userAgent: String? = null,
)

@Serializable
class FlareSolverrCookie(
    val name: String,
    val value: String,
)
