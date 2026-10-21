package eu.kanade.tachiyomi.extension.vi.suicaodex

import kotlinx.serialization.Serializable

@Serializable
data class SuicaoMangaListResponse(
    val data: List<SuicaoManga> = emptyList(),
    val meta: SuicaoMeta? = null,
)

@Serializable
data class SuicaoMeta(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
)

@Serializable
data class SuicaoManga(
    val id: String,
    val slug: String? = null,
    val title: String,
    val description: String? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val contentRating: String? = null,
    val year: Int? = null,
    val cover: SuicaoCover? = null,
    val tags: List<SuicaoTag> = emptyList(),
    val authors: List<SuicaoPerson> = emptyList(),
    val artists: List<SuicaoPerson> = emptyList(),
)

@Serializable
data class SuicaoCover(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SuicaoTag(
    val id: String,
    val slug: String? = null,
    val group: String? = null,
    val name: String,
)

@Serializable
data class SuicaoPerson(
    val id: String,
    val slug: String? = null,
    val name: String,
)

@Serializable
data class SuicaoChapterListResponse(
    val data: List<SuicaoChapter> = emptyList(),
    val meta: SuicaoMeta? = null,
)

@Serializable
data class SuicaoChapter(
    val id: String,
    val title: String? = null,
    val number: String? = null,
    val volume: String? = null,
    val language: String? = null,
    val publishedAt: String? = null,
    val groups: List<SuicaoGroup> = emptyList(),
)

@Serializable
data class SuicaoGroup(
    val id: String,
    val slug: String? = null,
    val name: String,
)

@Serializable
data class SuicaoChapterDetail(
    val id: String,
    val title: String? = null,
    val number: String? = null,
    val pages: List<SuicaoPage> = emptyList(),
)

@Serializable
data class SuicaoPage(
    val position: Int? = null,
    val url: String,
)
