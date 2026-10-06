# JavaFX release runbook

## Trước khi tạo tag

1. Xác nhận project version và `app.version` trong `pom.xml` giống nhau.
2. Chạy `node --test tools/*.test.mjs` và `./mvnw -B clean verify`.
3. Kiểm tra migration bằng database fixture 1.1.9; không dùng database thật của operator.
4. Kiểm tra không có secret/KIZ/database/WAL trong diff hoặc artifact.
5. Tạo tag đúng dạng `vMAJOR.MINOR.PATCH` từ nhánh mặc định.
6. Chạy workflow thủ công từ nhánh mặc định trước khi tag. Lần chạy thử phải build đủ Windows,
   macOS Intel và macOS Apple Silicon nhưng không được publish release.

## Release trust

Protected GitHub environment `release` cần `RELEASE_TOKEN` có `contents:write` trên
`rupphi/relatest-wcode`. Các nhóm secret ký số sau là tùy chọn, nhưng mỗi nhóm phải được cấu hình
đầy đủ hoặc bỏ trống hoàn toàn:

- Authenticode: `WINDOWS_SIGNING_CERTIFICATE`, `WINDOWS_SIGNING_PASSWORD` và
  `UPDATE_SIGNING_PUBLISHER` khớp chính xác subject của certificate;
- manifest: `UPDATE_MANIFEST_PRIVATE_KEY` và `UPDATE_MANIFEST_PUBLIC_KEY`.

Nếu chưa có chứng thư/khóa, workflow vẫn build và kiểm tra bộ cài như các release hiện tại, nhưng
không được mô tả artifact là đã ký. Khi có secret, workflow tự ký và verify trước khi upload.

Windows Upgrade UUID `D0FC7057-DA6C-3181-ADF9-C21DB2C9152A` là identity legacy của 1.1.8/1.1.9;
không được dùng lại vì uninstaller đó xóa thư mục `%LOCALAPPDATA%\WCode` chứa cả dữ liệu. Từ
1.1.10, identity vĩnh viễn là `0356BE08-487C-4E04-A2C2-353AF93DB2DE` trong cả `build.bat` và
workflow.

Từ 1.1.10, chương trình Windows được cài tại `%LOCALAPPDATA%\WCodeApp`, dữ liệu hoạt động ở
`%LOCALAPPDATA%\WCodeData`. Lần mở đầu tiên sao chép dữ liệu người dùng từ thư mục legacy
`%LOCALAPPDATA%\WCode`, bỏ qua `WCode.exe`, `app` và `runtime`. Workflow phải cài thật MSI 1.1.9,
seed dữ liệu, cài MSI mới song song, mở app đóng gói và xác minh SQLite/schema/shop/sentinel đã
migrate. Registration legacy được giữ tạm thời để Windows Installer không xóa dữ liệu trước khi app
mới sao chép xong.

macOS phát hành hai kiến trúc độc lập:

- `WCode-macos-x64.dmg` và `.zip` cho Mac Intel;
- `WCode-macos-arm64.dmg` và `.zip` cho Apple Silicon.

Launcher trong mỗi app-image phải đúng kiến trúc runner. Các gói macOS hiện chưa ký Developer ID
và chưa Apple notarize, vì vậy phải ghi rõ trạng thái này trong release notes.

## Sau khi workflow hoàn tất

1. Nếu đã cấu hình chứng thư, verify Authenticode của `WCode.exe` và `WCode.msi`.
2. Verify `checksums.sha256` bao phủ toàn bộ Windows/macOS assets; nếu có
   `update-manifest.json`, verify signed manifest khớp MSI cuối cùng.
3. Cài từ 1.1.9, xác nhận registration 1.1.9 không bị uninstall trước migration, app phiên bản mới mở được
   và dữ liệu shop/history đã chuyển sang `WCodeData` còn nguyên.
4. Mở app, kiểm tra Wildberries regression và Ozon read-only trước khi live mutation.
5. Chỉ đánh dấu release `latest` sau khi canary operator hoàn tất một flow đóng gói thực.

## Rollback

Không hạ schema bằng tay. Dùng snapshot đã verify do `LocalDataMigrationGate` tạo, và chỉ restore
khi app đã đóng cùng với app-data lock được giữ bởi recovery procedure. Luôn giữ lại installer và
checksum của bản N-1.
