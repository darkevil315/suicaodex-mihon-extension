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

data class SuicaoDexTag(val name: String, val id: String, val slug: String)

private val TAGS = arrayOf(
    SuicaoDexTag("Tất cả", "", ""),
    SuicaoDexTag("4-Koma", "01a0de90-31e4-74b6-9dca-914eae0752d7", "4-koma"),
    SuicaoDexTag("Action", "01a0de90-31e4-7c91-917f-6abf08e464ae", "action"),
    SuicaoDexTag("Adaptation", "01a0de90-31e4-7db1-b762-e3c6a3f0715f", "adaptation"),
    SuicaoDexTag("Adult", "01a11f9d-e1b2-7d8b-8ca1-f4f184877253", "web1-5f0b4e463e2490fb49aba56d1e230520"),
    SuicaoDexTag("Adventure", "01a0de90-31e5-7412-a4fc-4cf3dcd06208", "adventure"),
    SuicaoDexTag("Aliens", "01a0de90-31e4-7694-ac12-da465a05d11b", "aliens"),
    SuicaoDexTag("Animals", "01a0de90-31e5-7005-8c24-37b69ec70a46", "animals"),
    SuicaoDexTag("Anthology", "01a0de90-31e3-75c8-badd-ccf0b029987f", "anthology"),
    SuicaoDexTag("Award Winning", "01a0de90-31e4-739a-a384-3c08b35d155a", "award-winning"),
    SuicaoDexTag("Boys' Love", "01a0de90-31e3-7373-ae4c-e4b5c6538968", "boys-love"),
    SuicaoDexTag("Comedy", "01a0de90-31e4-7083-b4f7-fad23df13d9d", "comedy"),
    SuicaoDexTag("Comics", "01a12bda-2cc2-781b-904d-0448323af3f8", "web1-652c3f1baf4574c460aa41a3ab7941cb"),
    SuicaoDexTag("Cooking", "01a0de90-31e3-71cd-82a3-e2fcaab2760a", "cooking"),
    SuicaoDexTag("Crime", "01a0de90-31e3-70cf-9aa0-9d1a7feef26c", "crime"),
    SuicaoDexTag("Crossdressing", "01a0de90-31e3-7d66-97ce-300e793a4861", "crossdressing"),
    SuicaoDexTag("Delinquents", "01a0de90-31e4-7817-81ee-f53bd864e0ef", "delinquents"),
    SuicaoDexTag("Demons", "01a0de90-31e4-79c0-bc6b-8765c243746a", "demons"),
    SuicaoDexTag("Doujinshi", "01a0de90-31e4-7055-bb80-53ff21a795c9", "doujinshi"),
    SuicaoDexTag("Drama", "01a0de90-31e1-7c37-ba7a-7599abd27331", "drama"),
    SuicaoDexTag("Ecchi", "01a0fcfd-6c17-778a-8f50-5a706be2508d", "web1-2f0d7b53faaa40bc21eb45e740cd3384"),
    SuicaoDexTag("Fantasy", "01a0de90-31e3-7192-b280-b303e050a6c0", "fantasy"),
    SuicaoDexTag("Full Color", "01a0de90-31e3-7635-a5dd-311fc5aafb94", "full-color"),
    SuicaoDexTag("Gender Bender", "01a0fd7f-4f1c-7d31-a01e-b8a6ef6365da", "web1-8ea3af9ad299984b151c4c8bbaaa4364"),
    SuicaoDexTag("Genderswap", "01a0de90-31e3-778f-908c-cc7ecfe09735", "genderswap"),
    SuicaoDexTag("Ghosts", "01a0de90-31e5-7a3c-b4b4-c39b5d4c4daf", "ghosts"),
    SuicaoDexTag("Girls' Love", "01a0de90-31e4-7075-9e81-0d35d1c124f7", "girls-love"),
    SuicaoDexTag("Gore", "01a0de90-31e4-7c16-843d-9c4b2fb2dac6", "gore"),
    SuicaoDexTag("Gyaru", "01a0de90-31e3-799a-bcd9-85d85cc990ec", "gyaru"),
    SuicaoDexTag("Harem", "01a0de90-31e4-73fe-b2ef-95bd910c479a", "harem"),
    SuicaoDexTag("Historical", "01a0de90-31e4-75dc-a2e6-696049727132", "historical"),
    SuicaoDexTag("Horror", "01a0de90-31e5-7c1f-8142-0f11a0715e67", "horror"),
    SuicaoDexTag("Incest", "01a0de90-31e4-7412-8029-6d86a7c6cea4", "incest"),
    SuicaoDexTag("Isekai", "01a0de90-31e5-732e-8c21-20eac254c1a2", "isekai"),
    SuicaoDexTag("Josei", "01a0e105-476d-74c1-991a-9e669229d970", "josei"),
    SuicaoDexTag("Loli", "01a0de90-31e3-7b6b-a825-5ec641dbaba1", "loli"),
    SuicaoDexTag("Long Strip", "01a0de90-31e5-7558-974b-a58d0a0e944d", "long-strip"),
    SuicaoDexTag("Mafia", "01a0de90-31e5-760c-9c42-a31fdc8a591c", "mafia"),
    SuicaoDexTag("Magic", "01a0de90-31e4-7acf-a947-46baf083a8aa", "magic"),
    SuicaoDexTag("Magical Girls", "01a0de90-31e4-70e9-ab5a-ff3623c1ac4d", "magical-girls"),
    SuicaoDexTag("Martial Arts", "01a0de90-31e3-7d25-84f9-4c15df2d1c8e", "martial-arts"),
    SuicaoDexTag("Mature", "01a11fad-cc6d-7a32-9b1e-fd57b1e24644", "web1-39981aee3da75c5119e8951bccac90c9"),
    SuicaoDexTag("Mecha", "01a0de90-31e4-77d2-8ba3-54d97e531410", "mecha"),
    SuicaoDexTag("Medical", "01a0de90-31e3-7a3d-a1ab-f8ab5a539d2b", "medical"),
    SuicaoDexTag("Military", "01a0de90-31e5-73c6-bba8-cea94671538b", "military"),
    SuicaoDexTag("Monster Girls", "01a0de90-31e4-7621-98cb-7f3c004a4786", "monster-girls"),
    SuicaoDexTag("Monsters", "01a0de90-31e3-7c12-8e10-4e166cfee302", "monsters"),
    SuicaoDexTag("Music", "01a0de90-31e3-7fc0-96f1-dd13016724a9", "music"),
    SuicaoDexTag("Mystery", "01a0de90-31e4-7e26-a199-33b6f2e37529", "mystery"),
    SuicaoDexTag("Ninja", "01a0de90-31e5-768b-b726-0b6522532c04", "ninja"),
    SuicaoDexTag("Office Workers", "01a0de90-31e3-72cc-9775-9cb06596aba7", "office-workers"),
    SuicaoDexTag("Official Colored", "01a0de90-31e3-70ae-b3ef-b0e0f97b1fb1", "official-colored"),
    SuicaoDexTag("Oneshot", "01a0de90-31e3-7afd-b2ef-ee59a1a8aca9", "oneshot"),
    SuicaoDexTag("Philosophical", "01a0de90-31e3-759c-ab95-14dda2a0c491", "philosophical"),
    SuicaoDexTag("Police", "01a0de90-31e3-73fb-84cb-5e4b1ba5a78d", "police"),
    SuicaoDexTag("Post-Apocalyptic", "01a0de90-31e4-7de7-ae57-7827d15d75a7", "post-apocalyptic"),
    SuicaoDexTag("Psychological", "01a0de90-31e4-72e6-8390-0cd4fbb6cb7a", "psychological"),
    SuicaoDexTag("Reincarnation", "01a0de90-31e5-7a0b-817b-6acd3dd70351", "reincarnation"),
    SuicaoDexTag("Romance", "01a0de90-31e4-7aed-86a2-b7dd4cab7d93", "romance"),
    SuicaoDexTag("Samurai", "01a0de90-31e3-715d-8348-503707844ba9", "samurai"),
    SuicaoDexTag("School Life", "01a0de90-31e4-7a70-ab75-fa739acf5a6d", "school-life"),
    SuicaoDexTag("Sci-Fi", "01a0de90-31e3-776c-8187-c001dfa34a2d", "sci-fi"),
    SuicaoDexTag("Seinen", "01a0e105-1379-71e6-937c-82b0b4562a5a", "seinen"),
    SuicaoDexTag("Sexual Violence", "01a0de90-31e5-7e4a-a1fc-de1fad8cd1be", "sexual-violence"),
    SuicaoDexTag("Shota", "01a0de90-31e4-7aa8-9d90-b0daf8b2c319", "shota"),
    SuicaoDexTag("Shoujo", "01a0e103-82a9-7936-b08c-eb27d04599b1", "shoujo"),
    SuicaoDexTag("Shounen", "01a0e103-3ada-7e53-b6ac-c08f610e50aa", "shounen"),
    SuicaoDexTag("Slice of Life", "01a0de90-31e3-7d6a-8961-141e7a7b9b27", "slice-of-life"),
    SuicaoDexTag("Sport", "01a10671-7b7c-7a40-8f47-49160acac78c", "web1-df237b6d1b09614fdf37664b2684ca59"),
    SuicaoDexTag("Sports", "01a0de90-31e5-7aef-9150-41c2123ff468", "sports"),
    SuicaoDexTag("Superhero", "01a0de90-31e4-7362-a20c-85f1e104f27a", "superhero"),
    SuicaoDexTag("Supernatural", "01a0de90-31e4-7859-b837-0cc3eb24aaa4", "supernatural"),
    SuicaoDexTag("Survival", "01a0de90-31e4-7282-be54-fd41cb5e5a04", "survival"),
    SuicaoDexTag("Thriller", "01a0de90-31e5-7c2a-bada-db42ed9bffd0", "thriller"),
    SuicaoDexTag("Time Travel", "01a0de90-31e5-7a95-a37f-99728707d291", "time-travel"),
    SuicaoDexTag("Traditional Games", "01a0de90-31e3-7ba8-adb0-907615688cda", "traditional-games"),
    SuicaoDexTag("Tragedy", "01a0de90-31e3-7f54-8241-0b89c72cc7a4", "tragedy"),
    SuicaoDexTag("Truy\u1ec7n Vi\u1ec7t", "01a12b0d-062b-78de-8fe2-1fb7d0f69a99", "web1-0633fd95b5a0a41d048e72a800ca16eb"),
    SuicaoDexTag("Vampires", "01a0de90-31e4-7d2f-9a4f-65634c1e7271", "vampires"),
    SuicaoDexTag("Video Games", "01a0de90-31e3-7119-925f-cf50fc9b3a6a", "video-games"),
    SuicaoDexTag("Villainess", "01a0de90-31e3-788a-95a7-a98dfe0b6363", "villainess"),
    SuicaoDexTag("Virtual Reality", "01a0de90-31e4-798d-911e-0cb2560fee8b", "virtual-reality"),
    SuicaoDexTag("Web Comic", "01a0de90-31e3-71f5-b685-b3e281ce341d", "web-comic"),
    SuicaoDexTag("Webtoon", "01a1031b-b6ea-7d66-a1af-8522207aca43", "web1-27b9fdfeddb7f2ab38ab1bf545b07154"),
    SuicaoDexTag("Yaoi", "01a0fe8d-c9d3-7612-ad23-5e066133283b", "web1-1ab76f46704b27ea392b12f9c1ed463c"),
    SuicaoDexTag("Yuri", "01a10076-a31d-7ce3-b3c5-6c270b45947e", "web1-8545c9e3fa40f5706e638cabe4a5830a"),
    SuicaoDexTag("Zombies", "01a0de90-31e5-7183-860b-b01b030cc7d4", "zombies"),
)

class TagFilter : Filter.Select<String>(
    "Thể loại",
    TAGS.map { it.name }.toTypedArray(),
) {
    fun selectedTag(): SuicaoDexTag? = if (state > 0) TAGS[state] else null
}
