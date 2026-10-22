package eu.kanade.tachiyomi.extension.vi.goctruyentranhvui

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::authInterceptor)
        .build()

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val userAgent = preferences.getString(CUSTOM_USER_AGENT_KEY, DEFAULT_USER_AGENT)
            ?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val token = preferences.getString(CUSTOM_TOKEN_KEY, null)
            ?.trim()?.takeIf { it.isNotBlank() }

        val newRequest = request.newBuilder().apply {
            header("User-Agent", userAgent)
            if (request.url.encodedPath.startsWith("/api/")) {
                header("X-Requested-With", "XMLHttpRequest")
                token?.let { header("Authorization", it) }
            }
        }.build()

        return chain.proceed(newRequest)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        val userAgent = preferences.getString(CUSTOM_USER_AGENT_KEY, DEFAULT_USER_AGENT)
            ?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        set("User-Agent", userAgent)
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
        val detailsDeferred = if (fetchDetails) async { fetchMangaDetails(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { fetchChapterList(manga) } else null

        val detailsResult = detailsDeferred?.await()
        val newChapters = chaptersDeferred?.await()

        SMangaUpdate(
            detailsResult ?: manga,
            newChapters ?: chapters,
        )
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga {
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

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val mangaId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')
        val url = "$baseUrl/api/comic/$mangaId/chapter?limit=-1"

        val response = client.newCall(GET(url, xhrHeaders)).awaitSuccess()
        val body = response.body.string()
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
            key = CUSTOM_USER_AGENT_KEY
            title = "User-Agent"
            summary = "User-Agent gửi đến máy chủ (Mặc định: Chrome Desktop sạch để tránh lỗi Cloudflare trên WebView)."
            dialogTitle = "Tùy chỉnh User-Agent"
            setDefaultValue(DEFAULT_USER_AGENT)
            setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(CUSTOM_USER_AGENT_KEY, str).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_TOKEN_KEY
            title = "Authorization Token (Dán thủ công)"
            summary = "Nhập token Authorization nếu lấy được từ F12 DevTools (Local Storage) để bypass captcha."
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

        private const val CUSTOM_USER_AGENT_KEY = "custom_user_agent"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val CUSTOM_TOKEN_KEY = "custom_token"

        private val COMIC_ID_REGEX = Regex("""id:\s*"([^"]+)"""")
        private val COMIC_NAME_EN_REGEX = Regex("""nameEn:\s*`([^`]+)`""")
    }
}
