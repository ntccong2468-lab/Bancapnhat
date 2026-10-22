# VN code

**VN code** là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý, phục vụ seller trên Wildberries và Ozon.

Bản nền đầy đủ đã công bố: **VNCode 1.5.11 — Latest**. Đã gỡ 30 bản phát hành cũ, giữ lịch sử và tag mã nguồn. [Release Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest) · [Bản nền và cách phát triển tiếp](docs/releases/BASELINE.md) · [Ghi chú 1.5.11](docs/releases/VN-code-1.5.11.md).

Ứng dụng quản lý đơn, đồng bộ sản phẩm/GTIN, đăng ký thẻ Catalog, mua và gán KIZ, in tem và báo cáo. Repository chứa toàn bộ mã nguồn ứng dụng, website/Portal, kiểm thử và công cụ build. Bản phát hành mới đóng gói mã nguồn ZIP, Git bundle, bộ cài EXE/MSI, runtime Windows kèm Java và checksum để tiếp tục chỉnh sửa từ một bản nền.

## Cài đặt và dữ liệu

Tải EXE từ Release Latest, đóng VN code rồi nâng cấp. Bộ cài giữ UUID nâng cấp và dữ liệu schema 6. Thư mục chương trình: `%LOCALAPPDATA%\VNcodeApp`; dữ liệu: `%LOCALAPPDATA%\VNcodeData`. Không xóa thư mục dữ liệu khi nâng cấp. Bộ cài chưa ký Authenticode.

Thông tin shop, chứng thư và license được cấu hình trong ứng dụng; không đóng gói vào mã nguồn. Các thao tác ghi, mua KIZ và ký vẫn kiểm tra quyền, phạm vi shop và kết quả phía dịch vụ. Mã chưa đủ điều kiện lưu thông được giữ chờ đối soát.

## Build và kiểm tra

Cần JDK 25 và Node.js 22. Dùng Maven Wrapper trong repository:

```text
mvnw.cmd -B clean verify
node --test tools/*.test.mjs
build.bat exe
```

Trên Linux dùng `./mvnw`; các kiểm thử JavaFX cần display. `build.bat app-image` tạo runtime Windows độc lập. Mỗi lượt build dọn `out`, vì vậy lưu bộ cài/bằng chứng trước khi dựng dạng gói khác.

Workflow **Build complete VNCode baseline** kiểm tra và đóng gói từ một SHA bất biến. **Publish verified VNCode baseline** công bố các tệp đã kiểm chứng ngay trên GitHub Actions. Windows của bản 1.5.11 chạy 2.879 kiểm thử: không lỗi/thất bại, 2 ca bỏ qua; nâng cấp từ 1.5.10 và bộ cập nhật chọn đúng MSI đều đã xác minh. [Biên bản công bố](docs/validation/evidence/1511/public-verification.json) và [kiểm tra bộ cập nhật](docs/validation/evidence/1511/updater-verification.json). Đường build Windows mặc định sử dụng bản Latest được pin làm mốc nâng cấp, thay cho việc tải lại toàn bộ release lịch sử. [Hướng dẫn bản nền](docs/releases/BASELINE.md) mô tả các file cần giữ và cách khôi phục từ Git bundle.

## Phạm vi kiểm chứng

Xem [báo cáo WB 10.000 GTIN](docs/validation/2026-10-22-wb-10000-audit.md) và [đăng ký, bộ lọc, bảng đề xuất](docs/validation/2026-10-22-znack-registration-filters-proposals.md). Kết quả mô phỏng được ghi riêng với nghiệm thu trên shop thật; số liệu thực tế Mpp fashion hiện chưa được đọc trong phiên cloud này. Không coi HTTP đã nhận yêu cầu là xác nhận đã cập nhật GTIN.
