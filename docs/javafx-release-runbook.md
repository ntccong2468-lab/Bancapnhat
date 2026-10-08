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
1.1.10, identity WCode là `0356BE08-487C-4E04-A2C2-353AF93DB2DE`; VN code dùng identity riêng bên dưới.

Bản VN code 1.1.34 dùng upgrade UUID riêng `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`; không dùng lại identity WCode. Cài tại `%LOCALAPPDATA%\VNcodeApp`, dữ liệu riêng `%LOCALAPPDATA%\VNcodeData`, khởi tạo shop trống, dùng miễn phí và không cần giấy phép WCode. Không tự nhập bất kỳ dữ liệu hay backup WCode nào.

Trên repository `ntccong2468-lab/Vncode`, workflow `build-java.yml` build EXE, chạy native migration fixture cô lập rồi cài MSI nhúng trong EXE song song với WCode 1.1.9 thật (checksum pin). Probe kiểm tra ProductName/Version/UpgradeCode, registration riêng, database trống, toàn vẹn dữ liệu WCode và gỡ VN code không ảnh hưởng WCode. Probe từ chối chạy trên máy người dùng hoặc thư mục ứng dụng đã có.

Sau khi CI thành công, tạo tag trên đúng commit được kiểm thử và một draft prerelease. Dispatch `release.yml` cùng input `windows_build_run_id`: publisher đối chiếu commit/tag, checksum, kiến trúc EXE, số kiểm thử, native history/snapshot và bằng chứng cài/gỡ song song trước khi phát hành. Workflow này dùng GITHUB_TOKEN trên runner, không yêu cầu RELEASE_TOKEN của upstream hoặc build macOS.

macOS phát hành hai kiến trúc độc lập:

- `VN-code-macos-x64.dmg` và `.zip` cho Mac Intel;
- `VN-code-macos-arm64.dmg` và `.zip` cho Apple Silicon.

Launcher trong mỗi app-image phải đúng kiến trúc runner. Các gói macOS hiện chưa ký Developer ID
và chưa Apple notarize, vì vậy phải ghi rõ trạng thái này trong release notes.

## Sau khi workflow hoàn tất

1. Nếu đã cấu hình chứng thư, verify Authenticode của `VN code.exe` và `VN-code.msi`.
2. Verify `checksums.sha256` bao phủ toàn bộ Windows/macOS assets; nếu có
   `update-manifest.json`, verify signed manifest khớp MSI cuối cùng.
3. Xác nhận `side-by-side-smoke.json`: WCode giữ nguyên, VN code có database trống và gỡ riêng an toàn.
4. Mở app, kiểm tra Wildberries regression và Ozon read-only trước khi live mutation.
5. Chỉ đánh dấu release `latest` sau khi canary operator hoàn tất một flow đóng gói thực.

## Rollback

Không hạ schema bằng tay. Dùng snapshot đã verify do `LocalDataMigrationGate` tạo, và chỉ restore
khi app đã đóng cùng với app-data lock được giữ bởi recovery procedure. Luôn giữ lại installer và
checksum của bản N-1.
