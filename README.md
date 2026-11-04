# VN code

**VN code** là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý, phục vụ seller trên Wildberries và Ozon.

Bản phát hành mới nhất: **VNCode 1.6.1**. [Tải bộ cài Windows 64-bit](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.1/VN-code-1.6.1-Windows-x64.exe) · [Release Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest) · [Bản nền](docs/releases/BASELINE.md) · [Ghi chú 1.6.1](docs/releases/VN-code-1.6.1.md).

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

[Bàn giao 1.6.1](docs/validation/2026-11-03-vncode-161-handoff.md) và [biên nhận Latest](docs/validation/2026-11-04-vncode-161-published-latest.json) ghi rõ Windows/Linux, nâng cấp từ 1.6.0, 16 tệp tải công khai và bộ cập nhật của binary 1.6.0. Xem [92 ảnh giao diện sáng/tối](docs/validation/2026-11-03-ui-ux-pro-max/README.md).

Nghiệm thu API thật: 10 lượt gửi lại giá trị hiện có của năm trường WB/Ozon được đọc lại xác nhận; một thẻ Catalog được đọc thấy ở draft/moderation, chưa ký/công bố. PDF Ozon thật được đo trên một đơn/hai trang; lô 100/1.000 dùng mô phỏng. Không coi HTTP 200, số liệu mô phỏng hoặc ảnh giao diện là kết quả nghiệp vụ trên shop thật. [Giới hạn bảo mật còn lại](docs/validation/2026-11-03-release-security-review.md) được giữ trong báo cáo, không tuyên bố mọi gate bảo mật đạt.

Trạng thái, quyết định và kinh nghiệm để tiếp tục phát triển: [Bộ nhớ dự án](docs/PROJECT_MEMORY.md).
