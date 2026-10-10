# Phát hành VN code JavaFX

VN code do **Nguyễn Thành Công** phát triển và quản lý. Mã ứng dụng và bản cập nhật ở các repository VN code của chủ dự án.

## Trước khi phát hành

1. Phiên bản trong `pom.xml` là nguồn duy nhất; tag chính thức khớp `vMAJOR.MINOR.PATCH`, Preview dùng `vMAJOR.MINOR.PATCH-preview.N`.
2. Chạy `./mvnw -B clean verify` và `node --test tools/*.test.mjs`.
3. Kiểm tra Windows launcher, migration/snapshot bằng dữ liệu tạm, DPAPI khi có GS1, cài/gỡ độc lập và nâng cấp giữ dữ liệu từ các bản VN code đã phát hành.
4. Không đưa database/WAL, khóa seller, mật khẩu, chữ ký hoặc KIZ thật vào artifact.
5. Công bố đúng phần đã làm/còn thiếu, trạng thái chữ ký và nghiệm thu thật.

## Định danh và chữ ký

Giữ UpgradeCode production `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, thư mục `%LOCALAPPDATA%\VNcodeApp` và `%LOCALAPPDATA%\VNcodeData`. Không đổi định danh khi phát hành bản cập nhật. Shop mới bắt đầu trống; nâng cấp giữ dữ liệu VN code.

Workflow dùng quyền GitHub của repository đích. Authenticode chỉ bật khi cấu hình đầy đủ `WINDOWS_SIGNING_CERTIFICATE`, `WINDOWS_SIGNING_PASSWORD` và `UPDATE_SIGNING_PUBLISHER`. Manifest chỉ bật khi có đủ `UPDATE_MANIFEST_PRIVATE_KEY` và `UPDATE_MANIFEST_PUBLIC_KEY`. Không mô tả artifact chưa ký là đã ký.

Preview dùng đúng commit/run/artifact đã kiểm thử; publisher đối chiếu phiên bản, checksum, kiến trúc EXE, số kiểm thử và các báo cáo Windows trước upload. Giữ nguyên cấu trúc báo cáo/protocol kỹ thuật đã có. Không đưa Preview vào tự cập nhật.

## Sau phát hành

Đối chiếu digest GitHub với `checksums.sha256`; mở launcher và kiểm tra nâng cấp. Với manifest có chữ ký, verify khớp MSI cuối cùng. Chỉ đánh dấu bản chính thức latest sau nghiệm thu phù hợp; Preview giữ prerelease.

Sửa mô tả hoặc metadata sau phát hành không đổi bộ cài/tag/commit đã kiểm thử. Nếu metadata đính kèm thay đổi, cập nhật checksum cho đúng tệp; giữ hash của EXE và các bằng chứng chạy.

## Phục hồi

Không hạ schema bằng tay. Chỉ dùng snapshot đã verify khi app đóng và recovery giữ app-data lock. Giữ installer/checksum phiên bản trước. macOS build theo từng kiến trúc; công bố đúng trạng thái Developer ID/notarization khi phát hành.
