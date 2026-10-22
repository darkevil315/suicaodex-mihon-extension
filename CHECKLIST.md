# Cẩm Nang Chuẩn Hóa Phát Triển Mihon / Suwayomi Extension

Tài liệu này tổng hợp toàn bộ các vấn đề, bài học kinh nghiệm và checklist cần tự động áp dụng khi phát triển extension độc lập (Standalone Mihon Extension) cho các website đọc truyện, nhằm tránh lặp lại các lỗi tương tự trong tương lai.

---

## 1. Cấu Trúc Metadata & Nhận Diện Nguồn (Branding)

- [ ] **Tên hiển thị & Ký hiệu nhận diện (Badge)**:
  - Khi fork hoặc nhân bản từ repo mẫu (ví dụ từ `cuutruyen`), phải đồng bộ ngay toàn bộ tên nguồn:
    - `extName = "TênNguon"`
    - `pkgNameSuffix = "vi.tennguon"`
    - `extClass = ".TenNguon"`
    - Script `generate_index.py`: `--repo-name "TênNguon" --badge-label "TN"`
- [ ] **Icon ứng dụng**:
  - Tạo đầy đủ bộ icon Android mipmap (`mdpi`: 48px, `hdpi`: 72px, `xhdpi`: 96px, `xxhdpi`: 144px, `xxxhdpi`: 192px) và `repo/icon/*.png` (192px).
- [ ] **Cảnh báo độ tuổi (contentWarning)**:
  - Khai báo đúng `manifestPlaceholders["contentWarning"]`:
    - `0` = Safe (Truyện thông thường)
    - `1` = Mixed (Hỗn hợp có cả truyện 18+)
    - `2` = NSFW (Nguồn thuần 18+)

---

## 2. Xử Lý Kiến Trúc & Thư Viện (Runtime & Kotlin)

- [ ] **Không dùng Dependency Injection `injectLazy()` cho Json**:
  - *Lỗi đã gặp*: `No definition found for type 'kotlinx.serialization.json.Json'` trong Tachiyomix runtime.
  - *Quy chuẩn*: Khởi tạo instance Json trực tiếp trong class:
    ```kotlin
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    ```
- [ ] **Thứ tự khai báo dữ liệu tĩnh (Static Array vs Filter Class)**:
  - *Lỗi đã gặp*: Khai báo mảng `TAGS` bên dưới class `TagFilter` gây lỗi hoặc NullPointer lúc nạp class.
  - *Quy chuẩn*: Luôn khai báo mảng dữ liệu `TAGS` **trước** class Filter hoặc gom vào `companion object`.
- [ ] **Gán thuộc tính SManga tường minh**:
  - *Lỗi đã gặp*: `'val' cannot be reassigned` khi viết `this.title = title` bên trong `.apply { ... }`.
  - *Quy chuẩn*: Đặt tên biến cục bộ rõ ràng (`mangaTitle`, `mangaId`) và gán tường minh:
    ```kotlin
    val manga = SManga.create()
    manga.url = "/v1/manga/$mangaId"
    manga.title = mangaTitle
    manga.thumbnail_url = coverUrl
    ```

---

## 3. Xử Lý Chi Tiết Truyện (Metadata & Tagging)

- [ ] **Tự động ánh xạ `contentRating` vào danh sách Thể loại (Genre)**:
  - *Lỗi đã gặp*: Truyện có gắn nhãn Adult trên web nhưng trong app không thấy tag Adult vì API để riêng trường `contentRating: "erotica" / "pornographic"` chứ không nằm trong mảng `tags`.
  - *Quy chuẩn*: Luôn tự động kiểm tra trường độ tuổi và bổ sung tag tương ứng vào `SManga.genre`:
    ```kotlin
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
    genre = tagList.joinToString(", ").ifBlank { null }
    ```

---

## 4. Xử Lý Danh Sách Chương (Chapter Listing & Naming)

- [ ] **Xử lý hiển thị chuẩn cho truyện Oneshot**:
  - *Lỗi đã gặp*: Chương oneshot không có số chương (`number == null`) và không có tiêu đề riêng (`title == null`), dẫn đến fallback thành mã UUID `Chương 01a151cf-c350-7e5e...`.
  - *Quy chuẩn*: Khi cả `number` và `title` đều trống, hiển thị là `"Oneshot"` và gán số chương là `1.0f`:
    ```kotlin
    val chapterNum = number?.toFloatOrNull() ?: 1f
    val displayName = when {
        !title.isNullOrBlank() && !number.isNullOrBlank() -> "Ch. $number - $title"
        title.isNullOrBlank() && !number.isNullOrBlank() -> "Ch. $number"
        !title.isNullOrBlank() -> title
        !volume.isNullOrBlank() -> "Vol. $volume"
        else -> "Oneshot"
    }
    ```

---

## 5. Hệ Thống Bộ Lọc & Tab Phổ Biến (Filters & Popular Tab)

- [ ] **Tab Phổ biến (getPopularManga)**:
  - *Lỗi đã gặp*: Truyền `sort=views` vào `/v1/manga` bị backend trả về `HTTP 400: Invalid option: expected one of "latestChapter"|"publishedAt"|"title"|"year"`.
  - *Quy chuẩn*:
    - Kiểm tra xem backend có endpoint xếp hạng riêng không (ví dụ: `/v1/rankings/manga?window=all-time&limit=50`).
    - Nếu có, gọi đúng endpoint bảng xếp hạng này cho trang phổ biến thay vì truyền param không được hỗ trợ.
- [ ] **Kiểm tra enum hợp lệ của bộ lọc Sắp xếp (SortFilter)**:
  - *Lỗi đã gặp*: Tự ý đặt `sort=views`, `sort=score`, `sort=createdAt` dẫn đến khi người dùng chọn sắp xếp hoặc kết hợp trạng thái thì toàn bộ màn hình tìm kiếm bị lỗi HTTP 400.
  - *Quy chuẩn*: Luôn dùng đúng enum backend yêu cầu (ví dụ: `"latestChapter"`, `"publishedAt"`, `"title"`, `"year"`).
- [ ] **Tích hợp sẵn bộ lọc Thể loại (Tag Filter)**:
  - Mặc định người dùng luôn cần:
    1. **Sắp xếp (Sort)**: Mới cập nhật, Mới đăng, Tên A-Z, Tên Z-A, Năm phát hành,...
    2. **Trạng thái (Status)**: Tất cả, Đang tiến hành, Đã hoàn thành, Tạm ngưng, Hủy bỏ.
    3. **Thể loại (Genre / Tag)**: Dropdown danh sách các thể loại của site.
  - *Nếu API backend không hỗ trợ lọc tag cùng lúc*:
    - Xây dựng fallback chuyển hướng tải danh sách truyện từ endpoint/trang category của tag đó (`/tag/{id}/{slug}` hoặc `/the-loai/{slug}`).

---

## 6. Quy Trình Release & GitHub CI/CD

- [ ] **Tự động tăng `extVersionCode`**:
  - Bất cứ khi nào cập nhật code Kotlin hoặc sửa logic, phải tăng `extVersionCode` thêm 1 đơn vị trong `app/build.gradle.kts` để Mihon / Suwayomi nhận diện có bản cập nhật mới.
- [ ] **GitHub Personal Access Token (PAT)**:
  - Token dùng để push commit có sửa file workflow (`.github/workflows/*.yml`) bắt buộc phải có scope `workflow`.
- [ ] **GitHub Action Secrets**:
  - Cần 4 secret cơ bản:
    1. `RELEASE_KEYSTORE_BASE64` (Chuỗi base64 của file `.jks`)
    2. `RELEASE_KEYSTORE_PASSWORD`
    3. `RELEASE_KEY_ALIAS`
    4. `RELEASE_KEY_PASSWORD`
