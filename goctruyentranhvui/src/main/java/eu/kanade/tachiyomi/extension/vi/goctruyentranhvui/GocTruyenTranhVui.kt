package eu.kanade.tachiyomi.extension.vi.goctruyentranhvui

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class GocTruyenTranhVui : HttpSource(), ConfigurableSource {

    override val name = "Góc Truyện Tranh Vui"

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
        val token = preferences.getString(CUSTOM_TOKEN_KEY, null)?.trim()?.ifBlank { null }
        val customCookie = preferences.getString(CUSTOM_COOKIE_KEY, null)?.trim()?.ifBlank { null }
            ?: preferences.getString(AUTO_FLARE_COOKIE_KEY, null)?.trim()?.ifBlank { null }

        val initialRequest = request.newBuilder().apply {
            if (customUA != null) {
                header("User-Agent", customUA)
            }
            if (customCookie != null) {
                header("Cookie", customCookie)
            }
            if (request.url.encodedPath.startsWith("/api/")) {
                header("X-Requested-With", "XMLHttpRequest")
                token?.let { header("Authorization", it) }
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
                if (request.url.encodedPath.startsWith("/api/")) {
                    header("X-Requested-With", "XMLHttpRequest")
                    token?.let { header("Authorization", it) }
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

    private val xhrHeaders: Headers
        get() = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

    // ------------------------------------------------------------------------------------
    // Popular & Latest & Search
    // ------------------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        val filterList = FilterList(
            SortByList(getSortByList()).apply {
                state[0].state = true // viewCount
            },
        )
        return getSearchManga(page, "", filterList)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val filterList = FilterList(
            SortByList(getSortByList()).apply {
                state[3].state = true // recentDate
            },
        )
        return getSearchManga(page, "", filterList)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/v2/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("p", (page - 1).coerceAtLeast(0).toString())
            if (query.isNotBlank()) {
                addQueryParameter("searchValue", query.trim())
            }

            var hasSort = false
            for (filter in filters) {
                if (filter is FilterGroup) {
                    for (checkbox in filter.state) {
                        if (checkbox.state) {
                            addQueryParameter(filter.query, checkbox.id)
                            if (filter is SortByList) hasSort = true
                        }
                    }
                }
            }

            if (!hasSort && query.isBlank()) {
                addQueryParameter("orders[]", "recentDate")
            }
        }.build()

        val response = client.newCall(GET(url, xhrHeaders)).awaitSuccess()
        val body = response.body.string()
        if (body.startsWith("<") || response.code == 403) {
            throw Exception("Bị chặn bởi Cloudflare (Turnstile). Hãy bật FlareSolverr (http://127.0.0.1:8191) trên PC/Suwayomi hoặc mở WebView trên Mihon.")
        }
        val res = json.decodeFromString<ResultDto<ListingDto>>(body)
        val result = res.result ?: throw Exception(res.errorMessage ?: "Không thể tải danh sách truyện")
        val mangas = result.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, result.next)
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
        val slug = manga.url.substringAfter(':')
        val url = "$baseUrl/truyen/$slug"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val document = Jsoup.parse(response.body.string(), url)

        val parsedManga = parseMangaDetails(document, slug)
        manga.title = parsedManga.title
        manga.genre = parsedManga.genre
        manga.thumbnail_url = parsedManga.thumbnail_url ?: manga.thumbnail_url
        manga.status = parsedManga.status
        manga.author = parsedManga.author
        manga.description = parsedManga.description
        if (parsedManga.url.isNotBlank()) {
            manga.url = parsedManga.url
        }
        return manga
    }

    private fun parseMangaDetails(document: Document, slug: String): SManga = SManga.create().apply {
        title = document.select(".v-card-title").text()
        genre = document.select(".group-content > .v-chip-link").joinToString(", ") { it.text() }
        thumbnail_url = document.selectFirst("img.image")?.absUrl("src")
        status = parseStatus(document.selectFirst(".mb-1:contains(Trạng thái:) span")?.text())
        author = document.selectFirst(".mb-1:contains(Tác giả:) span")?.text()
        description = document.select(".v-card-text").joinToString("\n") { it.wholeText().trim() }

        val script = document.select("script").firstOrNull { it.data().contains("const comic = {") }?.data()
        val id = script?.let { COMIC_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("#comic-id-comment")?.attr("value")
        val nameEn = script?.let { COMIC_NAME_EN_REGEX.find(it)?.groupValues?.get(1) }
            ?: slug

        if (id != null && nameEn != null) {
            url = "$id:$nameEn"
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("Đang thực hiện", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.substringAfter(':')
        return "$baseUrl/truyen/$slug"
    }

    private suspend fun getChapterListInternal(manga: SManga): List<SChapter> {
        val mangaId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')
        val url = "$baseUrl/api/comic/$mangaId/chapter?limit=-1"

        val response = client.newCall(GET(url, xhrHeaders)).awaitSuccess()
        val body = response.body.string()
        if (body.startsWith("<") || response.code == 403) {
            throw Exception("Bị chặn bởi Cloudflare (Turnstile). Hãy kiểm tra User-Agent trong Cài đặt Mihon hoặc dán Cookie/Token trong Cài đặt extension.")
        }
        val res = json.decodeFromString<ResultDto<ChapterListDto>>(body)
        val chapters = res.result?.chapters
            ?: throw Exception(res.errorMessage ?: "Phiên làm việc đã hết hạn, vui lòng mở WebView hoặc kiểm tra kết nối.")

        return chapters.map { it.toSChapter(slug) }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl${chapter.url.substringBefore('#')}"
    }

    // ------------------------------------------------------------------------------------
    // Page List
    // ------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = chapter.url
        val slug = url.substringAfter("/truyen/").substringBefore("/chuong-")
        val numberChapter = url.substringAfter("/chuong-").substringBefore("#")
        val comicId = url.substringAfter("#")

        val formBody = FormBody.Builder()
            .add("comicId", comicId)
            .add("chapterNumber", numberChapter)
            .add("nameEn", slug)
            .build()

        fun requestImages(): List<Page>? {
            return try {
                client.newCall(POST("$baseUrl/api/chapter/loadAll", xhrHeaders, formBody)).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body.string()
                    val res = runCatching { json.decodeFromString<ResultDto<ImageListDto>>(body) }.getOrNull()
                    val list = res?.result?.data ?: return@use null
                    list.mapIndexed { idx, imgUrl ->
                        val finalUrl = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl"
                        Page(idx, imageUrl = finalUrl)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        var pages = requestImages()
        if (pages == null) {
            // "Warm-up" cookie session by requesting the manga page once
            runCatching {
                client.newCall(GET("$baseUrl/truyen/$slug", headers)).execute().close()
            }
            pages = requestImages()
        }

        return pages ?: throw Exception("Không thể tải ảnh chương. Hãy kiểm tra kết nối, mở WebView hoặc dán Authorization Token trong Cài đặt extension.")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ------------------------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------------------------

    override fun getFilterList(): FilterList {
        return FilterList(
            StatusList(getStatusList()),
            SortByList(getSortByList()),
        )
    }

    // ------------------------------------------------------------------------------------
    // Preferences Screen
    // ------------------------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FLARESOLVERR_ENABLE_KEY
            title = "Tự động vượt Cloudflare qua FlareSolverr"
            summary = "Tự động kết nối tới FlareSolverr để giải Cloudflare và tự động lưu/làm mới cookie khi bị chặn."
            setDefaultValue(DEFAULT_FLARESOLVERR_ENABLE)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(FLARESOLVERR_ENABLE_KEY, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = FLARESOLVERR_URL_KEY
            title = "FlareSolverr API URL"
            summary = "Mặc định: $DEFAULT_FLARESOLVERR_URL. Nếu dùng điện thoại kết nối tới máy tính chạy FlareSolverr, đổi 127.0.0.1 thành IP LAN máy tính."
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
            summary = "Mặc định: $DEFAULT_BASE_URL. Tự sửa khi trang đổi tên miền (ví dụ: goctruyentranhvui31.com)."
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
            title = "Cookie (cf_clearance thủ công)"
            summary = "Dán thủ công nếu không dùng FlareSolverr hoặc muốn ghi đè cookie phiên."
            dialogTitle = "Cookie vượt Cloudflare"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(CUSTOM_COOKIE_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_TOKEN_KEY
            title = "Authorization Token (Dán thủ công)"
            summary = "Nhập token Authorization nếu lấy được từ F12 DevTools (Local Storage)."
            dialogTitle = "Authorization Token"
            setDefaultValue("")
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(CUSTOM_TOKEN_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_USER_AGENT_KEY
            title = "User-Agent tùy chỉnh"
            summary = "Mặc định để trống để tự động lấy User-Agent từ FlareSolverr hoặc cài đặt Mihon."
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
    // Unsupported default methods (Replaced by modern suspend equivalents)
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
        private const val DEFAULT_BASE_URL = "https://goctruyentranhvui30.com"

        private const val FLARESOLVERR_ENABLE_KEY = "flaresolverr_enable"
        private const val DEFAULT_FLARESOLVERR_ENABLE = true

        private const val FLARESOLVERR_URL_KEY = "flaresolverr_url"
        private const val DEFAULT_FLARESOLVERR_URL = "http://127.0.0.1:8191/v1"

        private const val AUTO_FLARE_COOKIE_KEY = "auto_flare_cookie"
        private const val AUTO_FLARE_UA_KEY = "auto_flare_ua"

        private const val CUSTOM_COOKIE_KEY = "custom_cookie"
        private const val CUSTOM_USER_AGENT_KEY = "custom_user_agent"
        private const val CUSTOM_TOKEN_KEY = "custom_token"

        private val COMIC_ID_REGEX = Regex("""id:\s*"([^"]+)"""")
        private val COMIC_NAME_EN_REGEX = Regex("""nameEn:\s*`([^`]+)`""")
    }
}
