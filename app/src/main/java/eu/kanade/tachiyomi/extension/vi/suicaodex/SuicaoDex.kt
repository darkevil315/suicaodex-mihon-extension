package eu.kanade.tachiyomi.extension.vi.suicaodex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * SuicaoDex Extension for Mihon / Suwayomi.
 * Uses the clean REST API provided by https://redive.suicaodex.com.
 */
class SuicaoDex : HttpSource() {

    override val name = "SuicaoDex"

    override val baseUrl = "https://suicaodex.com"

    private val apiBaseUrl = "https://redive.suicaodex.com"

    override val lang = "vi"

    override val versionId = 1

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ------------------------------------------------------------------------------------
    // Popular & Latest
    // ------------------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiBaseUrl/v1/manga?page=$page&limit=20&sort=views&direction=desc"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return parseMangaList(response, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiBaseUrl/v1/manga?page=$page&limit=20&sort=latestChapter&direction=desc"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return parseMangaList(response, page)
    }

    // ------------------------------------------------------------------------------------
    // Search & Filters
    // ------------------------------------------------------------------------------------

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TagFilter(),
    )

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()
        val selectedTag = tagFilter?.selectedTag()

        if (selectedTag != null && query.isBlank()) {
            return getMangaByTag(selectedTag, page)
        }

        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()

        val (sort, direction) = sortFilter?.toSortParam() ?: Pair("latestChapter", "desc")
        val status = statusFilter?.toStatusParam()

        val urlBuilder = "$apiBaseUrl/v1/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .addQueryParameter("sort", sort)
            .addQueryParameter("direction", direction)

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.addQueryParameter("status", status)
        }

        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).awaitSuccess()
        return parseMangaList(response, page)
    }

    private suspend fun getMangaByTag(tag: SuicaoDexTag, page: Int): MangasPage {
        val url = "$baseUrl/tag/${tag.id}/${tag.slug}?page=$page"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val html = response.body.string()

        val cardPattern = """href\\":\\"/manga/([0-9a-f\-]{36})/([^\\"]+)\\",\\"title\\":\\"([^\\"]+)\\"(?:.*?)\\"src\\":\\"([^\\"]+)""".toRegex()
        val mangas = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        for (match in cardPattern.findAll(html)) {
            val mangaId = match.groupValues[1]
            val rawTitle = match.groupValues[3]
            val coverUrl = match.groupValues[4]
            if (seen.add(mangaId)) {
                val manga = SManga.create()
                manga.url = "/v1/manga/$mangaId"
                manga.title = unescapeUnicode(rawTitle)
                manga.thumbnail_url = coverUrl
                mangas.add(manga)
            }
        }

        val nextPage = page + 1
        val hasNextPage = html.contains("page=$nextPage")
        return MangasPage(mangas, hasNextPage)
    }

    private fun unescapeUnicode(str: String): String {
        val regex = """\\u([0-9a-fA-F]{4})""".toRegex()
        return regex.replace(str) { matchResult ->
            matchResult.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun parseMangaList(response: Response, page: Int): MangasPage {
        val body = response.body.string()
        val envelope = json.decodeFromString<SuicaoMangaListResponse>(body)
        val mangas = envelope.data.map { it.toSManga() }
        val hasNextPage = envelope.meta?.totalPages?.let { page < it } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    private fun SuicaoManga.toSManga() = SManga.create().apply {
        url = "/v1/manga/$id"
        title = this@toSManga.title
        thumbnail_url = cover?.url
    }

    // ------------------------------------------------------------------------------------
    // Manga Details + Chapters (Suspend API)
    // ------------------------------------------------------------------------------------

    override fun getMangaUrl(manga: SManga): String {
        val id = manga.url.substringAfterLast("/")
        return "$baseUrl/manga/$id"
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.substringAfterLast("/")

        val detailsDeferred = if (fetchDetails) async { fetchMangaDetails(mangaId) } else null
        val chaptersDeferred = if (fetchChapters) async { fetchChapterList(mangaId) } else null

        val detailsResult = detailsDeferred?.await()
        val newChapters = chaptersDeferred?.await()

        SMangaUpdate(
            detailsResult ?: manga,
            newChapters ?: chapters,
        )
    }

    private suspend fun fetchMangaDetails(mangaId: String): SManga {
        val response = client.newCall(GET("$apiBaseUrl/v1/manga/$mangaId", headers)).awaitSuccess()
        val body = response.body.string()
        val mangaDto = json.decodeFromString<SuicaoManga>(body)

        val tagList = mangaDto.tags.map { it.name }.toMutableList()
        when (mangaDto.contentRating?.lowercase()) {
            "erotica", "pornographic", "adult" -> {
                if (!tagList.any { it.equals("Adult", ignoreCase = true) }) {
                    tagList.add(0, "Adult")
                }
            }
            "suggestive" -> {
                if (!tagList.any { it.equals("Suggestive", ignoreCase = true) }) {
                    tagList.add("Suggestive")
                }
            }
        }

        return SManga.create().apply {
            url = "/v1/manga/${mangaDto.id}"
            title = mangaDto.title
            thumbnail_url = mangaDto.cover?.url
            description = mangaDto.description
            author = mangaDto.authors.joinToString(", ") { it.name }.ifBlank { null }
            artist = mangaDto.artists.joinToString(", ") { it.name }.ifBlank { null }
            genre = tagList.joinToString(", ").ifBlank { null }
            status = when (mangaDto.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            update_strategy = if (status == SManga.COMPLETED || status == SManga.CANCELLED) {
                UpdateStrategy.ONLY_FETCH_ONCE
            } else {
                UpdateStrategy.ALWAYS_UPDATE
            }
        }
    }

    private suspend fun fetchChapterList(mangaId: String): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var currentPage = 1
        var totalPages = 1

        while (currentPage <= totalPages) {
            val url = "$apiBaseUrl/v1/manga/$mangaId/chapters?page=$currentPage&limit=100&direction=desc"
            val response = client.newCall(GET(url, headers)).awaitSuccess()
            val body = response.body.string()
            val envelope = json.decodeFromString<SuicaoChapterListResponse>(body)

            allChapters.addAll(envelope.data.map { it.toSChapter(mangaId) })
            totalPages = envelope.meta?.totalPages ?: 1
            currentPage++
            if (currentPage > 30) break // Guard against runaway loops
        }

        return allChapters
    }

    private fun SuicaoChapter.toSChapter(mangaId: String): SChapter {
        val chapterNum = number?.toFloatOrNull() ?: 1f
        val displayName = when {
            !title.isNullOrBlank() && !number.isNullOrBlank() -> "Ch. $number - $title"
            title.isNullOrBlank() && !number.isNullOrBlank() -> "Ch. $number"
            !title.isNullOrBlank() -> title
            !volume.isNullOrBlank() -> "Vol. $volume"
            else -> "Oneshot"
        }
        val scanlatorName = groups.joinToString(", ") { it.name }.ifBlank { null }

        return SChapter.create().apply {
            url = "/v1/chapters/$id?m=$mangaId"
            name = displayName
            chapter_number = chapterNum
            date_upload = parseIsoDate(publishedAt)
            scanlator = scanlatorName
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val chapterId = chapter.url.substringAfter("/v1/chapters/").substringBefore("?")
        return "$baseUrl/chapter/$chapterId"
    }

    // ------------------------------------------------------------------------------------
    // Page List
    // ------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("/v1/chapters/").substringBefore("?")
        val response = client.newCall(GET("$apiBaseUrl/v1/chapters/$chapterId", headers)).awaitSuccess()
        val body = response.body.string()
        val detail = json.decodeFromString<SuicaoChapterDetail>(body)

        return detail.pages.mapIndexed { index, p ->
            Page(index, imageUrl = p.url)
        }
    }

    // ------------------------------------------------------------------------------------
    // Date Parsing
    // ------------------------------------------------------------------------------------

    private fun parseIsoDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val cleanDate = dateStr.substring(0, minOf(19, dateStr.length))
            format.parse(cleanDate)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
