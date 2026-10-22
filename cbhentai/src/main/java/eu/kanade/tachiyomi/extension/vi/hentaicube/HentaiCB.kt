package eu.kanade.tachiyomi.extension.vi.hentaicube

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
import kotlinx.coroutines.runBlocking
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

class HentaiCB : HttpSource(), ConfigurableSource {

    override val id: Long = 823638192569572166L

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
        encodeDefaults = true
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

    private val xhrHeaders: Headers
        get() = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

    // ------------------------------------------------------------------------------------
    // Auto FlareSolverr Interceptor
    // ------------------------------------------------------------------------------------

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

        val payload = """{"cmd":"request.get","url":"$targetUrl","maxTimeout":60000}"""

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
            try {
                val now = System.currentTimeMillis()
                if (now - lastSolvedTimestamp > 10_000L) {
                    solveCloudflare("$baseUrl/")
                }
            } catch (_: Exception) {
                return response
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

    // ------------------------------------------------------------------------------------
    // Popular, Latest, Search (Madara AJAX)
    // ------------------------------------------------------------------------------------

    private enum class BrowseMode {
        Popular,
        Latest,
        Search,
    }

    override suspend fun getPopularManga(page: Int): MangasPage = ajaxList(page, BrowseMode.Popular)

    override suspend fun getLatestUpdates(page: Int): MangasPage = ajaxList(page, BrowseMode.Latest)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        ajaxList(page, BrowseMode.Search, query)

    private suspend fun ajaxList(page: Int, mode: BrowseMode, query: String = ""): MangasPage {
        val pageSize = 25
        val body = FormBody.Builder().apply {
            add("action", "madara_load_more")
            add("page", (page - 1).toString())
            add("template", "madara-core/content/content-archive")
            add("vars[paged]", "1")
            add("vars[template]", "archive")
            add("vars[posts_per_page]", pageSize.toString())
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
            when (mode) {
                BrowseMode.Popular -> {
                    add("vars[orderby]", "meta_value_num")
                    add("vars[meta_key]", "_wp_manga_views")
                    add("vars[order]", "DESC")
                }
                BrowseMode.Latest -> {
                    add("vars[orderby]", "meta_value_num")
                    add("vars[meta_key]", "_latest_update")
                    add("vars[order]", "DESC")
                }
                BrowseMode.Search -> {
                    if (query.isNotBlank()) {
                        add("vars[s]", query.trim())
                    } else {
                        add("vars[orderby]", "meta_value_num")
                        add("vars[meta_key]", "_latest_update")
                        add("vars[order]", "DESC")
                    }
                }
            }
        }.build()

        val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, body)).awaitSuccess()
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

        return MangasPage(mangas, mangas.size >= pageSize)
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
        val document = client.newCall(GET("$baseUrl${manga.url}", headers)).awaitSuccess().let {
            Jsoup.parse(it.body.string(), baseUrl)
        }

        val updated = SManga.create()
        val title = document.selectFirst("h1, .post-title h1")?.text()?.trim() ?: manga.title
        updated.title = title

        val author = document.select(".author-content a").eachText().joinToString(", ")
        val artist = document.select(".artist-content a").eachText().joinToString(", ")
        updated.author = author.ifBlank { artist }.ifBlank { null }
        updated.artist = artist.ifBlank { author }.ifBlank { null }

        val desc = document.selectFirst(".description-summary .summary__content, .manga-excerpt, .summary__content")
            ?.text()?.trim()
        updated.description = desc

        val status = when (document.selectFirst(".post-status .summary-content")?.text()?.lowercase()) {
            "on-going", "ongoing", "đang tiến hành" -> SManga.ONGOING
            "completed", "hoàn thành" -> SManga.COMPLETED
            "canceled", "hủy" -> SManga.CANCELLED
            "on hold", "tạm ngưng" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        updated.status = status

        val genres = document.select(".genres-content a").eachText()
        updated.genre = if (genres.isNotEmpty()) genres.joinToString(", ") else null

        val img = document.selectFirst(".summary_image img")
        val dataSrc = img?.attr("abs:data-src")
        val src = img?.attr("abs:src")
        updated.thumbnail_url = if (!dataSrc.isNullOrBlank()) dataSrc else src

        return updated
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = getChapterListInternal(manga)

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> = runBlocking {
        val path = response.request.url.encodedPath
        getChapterListInternal(SManga.create().apply { url = path })
    }

    private suspend fun getChapterListInternal(manga: SManga): List<SChapter> {
        val mangaUrl = manga.url
        val mangaPath = if (mangaUrl.startsWith("http")) {
            mangaUrl.substringAfter(baseUrl).trimEnd('/')
        } else {
            mangaUrl.trimEnd('/')
        }
        val pathWithSlash = if (mangaPath.startsWith("/")) mangaPath else "/$mangaPath"

        val result = mutableListOf<SChapter>()
        var lastUrl: String? = null
        var page = 1

        while (true) {
            val chapterAjaxUrl = "$baseUrl$pathWithSlash/ajax/chapters/?t=$page"
            val body = FormBody.Builder().build()
            val response = try {
                client.newCall(POST(chapterAjaxUrl, xhrHeaders, body)).awaitSuccess()
            } catch (_: Exception) {
                break
            }

            val doc = Jsoup.parse(response.body.string(), baseUrl)
            val pageChapters = mutableListOf<SChapter>()
            parseChapterElements(doc, pageChapters)

            if (pageChapters.isEmpty() || pageChapters.last().url == lastUrl) {
                break
            }

            result.addAll(pageChapters)
            lastUrl = pageChapters.last().url
            page++
        }

        if (result.isEmpty()) {
            val document = client.newCall(GET("$baseUrl$pathWithSlash", headers)).awaitSuccess().let {
                Jsoup.parse(it.body.string(), baseUrl)
            }
            val mangaId = document.selectFirst("#manga-chapters-holder")?.attr("data-id")
            if (!mangaId.isNullOrEmpty()) {
                val body = FormBody.Builder().add("action", "manga_get_chapters").add("manga", mangaId).build()
                try {
                    val ajaxResp = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, body)).awaitSuccess()
                    val ajaxDoc = Jsoup.parse(ajaxResp.body.string(), baseUrl)
                    parseChapterElements(ajaxDoc, result)
                } catch (_: Exception) {}
            }
            if (result.isEmpty()) {
                parseChapterElements(document, result)
            }
        }

        return result
    }

    private fun parseChapterElements(document: Document, chapters: MutableList<SChapter>) {
        val elements = document.select("li.wp-manga-chapter, .chapter-item")
        for (element in elements) {
            val link = element.selectFirst("a") ?: continue
            val chapter = SChapter.create().apply {
                val href = link.attr("abs:href").ifBlank { link.attr("href") }
                url = if (href.startsWith(baseUrl)) href.substringAfter(baseUrl) else href
                name = link.text().trim()
                val dateText = element.selectFirst("span.chapter-release-date, .c-new-tag")?.text()?.trim()
                date_upload = parseChapterDate(dateText)
            }
            chapters.add(chapter)
        }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ------------------------------------------------------------------------------------
    // Page List & Reader Implementation
    // ------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val document = client.newCall(GET(chapterUrl, headers)).awaitSuccess().let {
            Jsoup.parse(it.body.string(), baseUrl)
        }

        // 1. Kiểm tra MSR v3 Reader (.msr-reader[data-msr-api])
        val msr3Element = document.selectFirst(".msr-reader[data-msr-api]")
        if (msr3Element != null) {
            return getMsr3Pages(msr3Element, chapterUrl)
        }

        // 2. Kiểm tra MASR v2 Reader (.masr2-reader[data-masr2-token])
        val masr2Element = document.selectFirst(".masr2-reader[data-masr2-token]")
        if (masr2Element != null) {
            return getMasr2Pages(masr2Element, chapterUrl)
        }

        // 3. Fallback: Parse ảnh HTML tiêu chuẩn của Madara CMS
        val images = document.select(".reading-content img, .page-break img, .wp-manga-chapter-img")
        if (images.isNotEmpty()) {
            return images.mapIndexed { index, img ->
                val src = img.attr("abs:data-src").ifBlank { img.attr("abs:src") }.trim()
                Page(index, chapterUrl, src)
            }
        }

        throw Exception("Không tìm thấy trang đọc hoặc cơ chế reader trên chương này.")
    }

    private suspend fun getMsr3Pages(msr3Element: Element, chapterUrl: String): List<Page> {
        val mangaId = msr3Element.attr("data-msr-manga").toIntOrNull()
            ?: throw Exception("Không tìm thấy manga ID trong reader element")
        val chapterId = msr3Element.attr("data-msr-chapter").toIntOrNull()
            ?: throw Exception("Không tìm thấy chapter ID trong reader element")
        val apiEndpoint = msr3Element.attr("data-msr-api").ifEmpty { "/wp-json/manga-reader/v3/" }

        val baseApiUrl = if (apiEndpoint.startsWith("http")) {
            apiEndpoint.removeSuffix("/")
        } else {
            "$baseUrl${if (apiEndpoint.startsWith("/")) "" else "/"}${apiEndpoint.removeSuffix("/")}"
        }

        val apiHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("X-MSR-Request", "1")
            .set("Referer", chapterUrl)
            .build()

        val pagesPayload = json.encodeToString(MsrPagesRequest(manga = mangaId, chapter = chapterId))
        val pagesReq = Request.Builder()
            .url("$baseApiUrl/pages")
            .headers(apiHeaders)
            .post(pagesPayload.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(pagesReq).execute()
        if (response.code == 401) {
            response.close()
            // Cloudflare Turnstile token required by MSR v3
            throw Exception(
                "Chương này yêu cầu xác minh Cloudflare Turnstile (MSR v3).\n" +
                "Vui lòng mở web trên trình duyệt hoặc cài đặt Cookie xác minh trong Settings extension để đọc tiếp."
            )
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception("MSR v3 API trả về lỗi HTTP $code")
        }

        val body = response.body.string()
        val data = json.decodeFromString<MsrPagesResponse>(body)
        return data.items.mapIndexed { index, imageUrl ->
            Page(index, chapterUrl, imageUrl)
        }
    }

    private suspend fun getMasr2Pages(readerElement: Element, chapterUrl: String): List<Page> {
        val token = readerElement.attr("data-masr2-token")
        if (token.isEmpty()) throw Exception("Không tìm thấy token trong reader element")

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
    // Preference Screen
    // ------------------------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = CUSTOM_BASE_URL_KEY
            title = "Tùy chỉnh tên miền nguồn"
            summary = "Mặc định: $DEFAULT_BASE_URL (Đổi khi website đổi sang domain mới)"
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "Nhập URL nguồn"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(context).apply {
            key = FLARESOLVERR_ENABLE_KEY
            title = "Bật tự động vượt Cloudflare qua FlareSolverr"
            summary = "Tự động bắt lỗi 403 và gửi yêu cầu giải Cloudflare tới máy chủ FlareSolverr"
            setDefaultValue(DEFAULT_FLARESOLVERR_ENABLE)
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = FLARESOLVERR_URL_KEY
            title = "Địa chỉ máy chủ FlareSolverr"
            summary = "Mặc định: $DEFAULT_FLARESOLVERR_URL"
            setDefaultValue(DEFAULT_FLARESOLVERR_URL)
            dialogTitle = "Nhập URL FlareSolverr"
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = CUSTOM_COOKIE_KEY
            title = "Tùy chỉnh Cookie (cf_clearance, cf_turnstile,...)"
            summary = "Nhập chuỗi cookie nếu muốn chỉ định thủ công"
            dialogTitle = "Nhập chuỗi Cookie"
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = CUSTOM_USER_AGENT_KEY
            title = "Tùy chỉnh User-Agent"
            summary = "Khớp với User-Agent của trình duyệt khi lấy Cookie thủ công"
            dialogTitle = "Nhập User-Agent"
        }.also(screen::addPreference)
    }

    // ------------------------------------------------------------------------------------
    // Unsupported Legacy Observables
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
