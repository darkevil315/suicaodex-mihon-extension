package eu.kanade.tachiyomi.extension.vi.goctruyentranhvui

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class ResultDto<T>(
    val result: T? = null,
    val status: Boolean? = null,
    val errorCode: Int? = null,
    private val messages: ErrorMessagesDto? = null,
) {
    val errorMessage: String? get() = messages?.messages?.firstOrNull()
}

@Serializable
class ErrorMessagesDto(
    val messages: List<String>? = null,
)

@Serializable
class CategoryDto(
    val id: String,
    val name: String,
) {
    fun toOption() = Option(name, id)
}

@Serializable
class ChapterDto(
    val comicId: String,
    val numberChapter: String,
    val updateTime: Long,
) {
    fun toSChapter(slug: String): SChapter = SChapter.create().apply {
        val raw = numberChapter.trim()
        name = when {
            raw.equals("oneshot", ignoreCase = true) -> "Oneshot"
            raw.toDoubleOrNull() != null -> "Chương $raw"
            raw.startsWith("chuong", ignoreCase = true) || raw.startsWith("chương", ignoreCase = true) -> raw
            else -> raw
        }
        chapter_number = raw.toFloatOrNull() ?: -1f
        date_upload = updateTime
        url = "/truyen/$slug/chuong-$numberChapter#$comicId"
    }
}

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto>,
)

@Serializable
class ListingDto(
    val next: Boolean,
    val data: List<MangaDto>,
)

@Serializable
class MangaDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val statusCode: String? = null,
    val photo: String? = null,
    val nameEn: String,
    val author: String? = null,
    val category: List<String>? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        title = name
        thumbnail_url = if (photo != null) {
            if (photo.startsWith("http")) photo else baseUrl + photo
        } else null
        url = "$id:$nameEn"
        this.author = this@MangaDto.author
        this.description = this@MangaDto.description
        genre = category?.joinToString(", ")
        status = when (statusCode) {
            "PRG" -> SManga.ONGOING
            "END" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

@Serializable
class ImageListDto(
    val data: List<String>? = null,
)
