# VN code

**VN code** là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý, phục vụ seller trên Wildberries và Ozon.

Bản phát hành và bộ cài Windows 64-bit mới nhất: [GitHub Releases / Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest). [Hướng dẫn bản nền](docs/releases/BASELINE.md). Phiên bản trong nhánh mã nguồn có thể chưa được phát hành; hãy dùng bộ cài từ Releases khi nâng cấp.

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

Workflow **Build complete VNCode baseline** kiểm tra và đóng gói từ một SHA bất biến. Đường build Windows mặc định sử dụng bản Latest được pin làm mốc nâng cấp, thay cho việc tải lại toàn bộ release lịch sử. [Hướng dẫn bản nền](docs/releases/BASELINE.md) mô tả các file cần giữ và cách khôi phục từ Git bundle.

## Phạm vi kiểm chứng

Đợt sửa mới: [đối chiếu phiên bản, sửa đăng ký Znack/GTIN và ba hướng phát triển](docs/fixes/2026-10-30-znack-gtin-recovery.md). Có 420 ca kiểm thử liên quan đạt; chưa phát hành bộ cài cho đợt sửa này.

Xem [báo cáo WB 10.000 GTIN](docs/validation/2026-10-22-wb-10000-audit.md) và [đăng ký, bộ lọc, bảng đề xuất](docs/validation/2026-10-22-znack-registration-filters-proposals.md). Kết quả mô phỏng được ghi riêng với nghiệm thu trên shop thật; số liệu thực tế Mpp fashion hiện chưa được đọc trong phiên cloud này. Không coi HTTP đã nhận yêu cầu là xác nhận đã cập nhật GTIN.

Trạng thái, quyết định và kinh nghiệm để tiếp tục phát triển: [Bộ nhớ dự án](docs/PROJECT_MEMORY.md).
