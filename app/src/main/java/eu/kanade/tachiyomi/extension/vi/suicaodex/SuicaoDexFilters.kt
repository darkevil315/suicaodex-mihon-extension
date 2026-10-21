package eu.kanade.tachiyomi.extension.vi.suicaodex

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter : Filter.Select<String>(
    "Sắp xếp",
    arrayOf("Mới cập nhật", "Lượt xem", "Điểm đánh giá", "Mới đăng"),
) {
    fun toSortParam(): Pair<String, String> = when (state) {
        0 -> Pair("latestChapter", "desc")
        1 -> Pair("views", "desc")
        2 -> Pair("score", "desc")
        3 -> Pair("createdAt", "desc")
        else -> Pair("latestChapter", "desc")
    }
}

class StatusFilter : Filter.Select<String>(
    "Trạng thái",
    arrayOf("Tất cả", "Đang tiến hành", "Đã hoàn thành", "Tạm ngưng", "Hủy bỏ"),
) {
    fun toStatusParam(): String? = when (state) {
        1 -> "ongoing"
        2 -> "completed"
        3 -> "hiatus"
        4 -> "cancelled"
        else -> null
    }
}
