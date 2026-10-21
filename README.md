# Extension SuicaoDex for Mihon / Suwayomi

[![CI](https://github.com/USER/suicaodex-mihon-extension/actions/workflows/build.yml/badge.svg)](https://github.com/USER/suicaodex-mihon-extension/actions/workflows/build.yml)

Extension mã nguồn mở cho phép đọc truyện từ [SuicaoDex](https://suicaodex.com) trực tiếp trên các ứng dụng đọc truyện **Mihon**, **Tachiyomi** và **Suwayomi (Suwayomi-Server)**.

---

## 🚀 Hướng dẫn cài đặt vào Mihon / Suwayomi

### Cách 1: Thêm Repository trực tiếp (Khuyên dùng)
Bạn chỉ cần thêm URL repository này vào app, Mihon / Suwayomi sẽ tự nhận diện và cập nhật mỗi khi có bản mới:

1. Mở **Mihon** hoặc giao diện Web **Suwayomi-Server**.
2. Vào **Cài đặt (Settings)** -> **Duyệt (Browse)** -> **Kho extension (Extension repositories)** -> Nhấn **Thêm (Add)**.
3. Dán đường link sau:
   ```text
   https://raw.githubusercontent.com/darkevil315/suicaodex-mihon-extension/repo/index.pb
   ```
4. Quay lại mục **Khám phá (Browse)** -> **Tiện ích mở rộng (Extensions)**, tìm kiếm `SuicaoDex` và bấm **Cài đặt (Install)**.

---

## 🛠️ Hướng dẫn đưa dự án lên GitHub và kích hoạt tự động Build APK

Dự án đã tích hợp sẵn GitHub Actions (`.github/workflows/build.yml`), tự động biên dịch APK release và phát hành sang nhánh `repo`.

### Bước 1: Tạo Keystore ký Release
Mở terminal (PowerShell hoặc Bash) và chạy lệnh sau để tạo file keystore:
```bash
keytool -genkey -v -keystore release.jks -alias suicaodex -keyalg RSA -keysize 2048 -validity 10000
```
*(Hãy ghi nhớ mật khẩu bạn đã nhập).*

### Bước 2: Lấy mã Base64 của file `release.jks`
* **Trên Windows PowerShell:**
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
  ```
  *(Chuỗi base64 đã được copy vào Clipboard của bạn).*

* **Trên Linux / macOS:**
  ```bash
  base64 -w 0 release.jks | pbcopy # hoặc xclip
  ```

### Bước 3: Thêm Secrets trên GitHub Repository
1. Tạo một repository mới trên GitHub (ví dụ: `suicaodex-mihon-extension`).
2. Vào **Settings** -> **Secrets and variables** -> **Actions** -> Nhấn **New repository secret**.
3. Thêm các Secret sau:
   * `KEYSTORE_BASE64`: Dán toàn bộ chuỗi base64 vừa copy ở Bước 2.
   * `KEYSTORE_PASSWORD`: Mật khẩu bạn đã đặt cho keystore.
   * `KEY_ALIAS`: `suicaodex` (hoặc alias bạn đã đặt).

### Bước 4: Đẩy mã nguồn lên GitHub
```bash
git init
git add .
git commit -m "Initial commit SuicaoDex Mihon Extension"
git branch -M main
git remote add origin https://github.com/darkevil315/suicaodex-mihon-extension.git
git push -u origin main
```
Sau khi push, GitHub Actions sẽ tự động chạy:
- Build `SuicaoDex.apk` release.
- Sinh `index.pb` và `index.json`.
- Tự động tạo và push sang nhánh `repo`.

---

## 💻 Biên dịch tại máy cục bộ (Local Build)

Nếu muốn test hoặc build file APK ngay trên máy tính của bạn:

1. Copy file `keystore.properties.example` thành `keystore.properties`:
   ```properties
   storeFile=release.jks
   storePassword=mật_khẩu_của_bạn
   keyAlias=suicaodex
   keyPassword=mật_khẩu_của_bạn
   ```
2. Chạy lệnh build:
   * **Windows:**
     ```powershell
     .\gradlew.bat assembleRelease
     ```
   * **Linux / macOS:**
     ```bash
     ./gradlew assembleRelease
     ```
3. File APK thành phẩm sẽ nằm tại:
   `app/build/outputs/apk/release/app-release.apk`

---

## ⚖️ Giấy phép & Tuyên bố từ chối trách nhiệm

* Dự án được phát triển phi lợi nhuận phục vụ mục đích học tập và tích hợp nguồn đọc truyện cá nhân.
* Toàn bộ dữ liệu truyện và hình ảnh thuộc về trang gốc [SuicaoDex](https://suicaodex.com) và các nhóm dịch liên quan.
