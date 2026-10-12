# VN code

**VN code là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý**, phục vụ người bán trên **Wildberries** và **Ozon**.

Repository này chứa các bản cập nhật của cùng ứng dụng VN code, giữ định danh bộ cài và dữ liệu đã cài.

## Tải và sử dụng

**VN code 1.4.0 đang phát triển, chưa phát hành bộ cài.** [Thiết kế tài khoản/offline/thiết bị đã duyệt](docs/superpowers/specs/2026-10-12-vn-code-web-license-design.md), [kế hoạch tích hợp Windows để rà soát](docs/superpowers/plans/2026-10-12-vn-code-account-license-client.md) và [đầu vào backend còn thiếu](docs/research/2026-10-12-vn-code-license-backend-intake.md).

Địa chỉ dự kiến khi hoàn tất kiểm tra: [VN code 1.4.0 Preview — Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.0-preview.1/VN-code-1.4.0-preview.1-Windows-x64.exe) · [release dự kiến](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.0-preview.1).

Đóng VN code rồi chạy EXE để nâng cấp. Java được đóng gói kèm. Bộ cài chưa ký Authenticode; bản Preview cài thủ công và chưa bật tự cập nhật. Bản trước: [VN code 1.1.34](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.34).

Chương trình dùng `%LOCALAPPDATA%\VNcodeApp`, dữ liệu dùng `%LOCALAPPDATA%\VNcodeData`. Các bản cá nhân đã phát hành giữ quyền miễn phí. Bản 1.4.0 dự kiến dùng tài khoản website và quyền do chủ phần mềm duyệt/gia hạn/khóa; phần này chờ mã backend để tích hợp. Người dùng thiết lập shop, tài khoản nghiệp vụ và chứng thư của mình. Nâng cấp giữ dữ liệu, mẫu tem và lịch sử VN code.

## Chức năng

- Quản lý nhiều shop; kết nối WB và Ozon theo đúng marketplace.
- Đồng bộ đơn/supply WB và posting Ozon FBS Standard.
- Kho KIZ/Znack, mapping GTIN, gán mã, in barcode/Data Matrix và nhãn PDF.
- Thiết kế mẫu tem, lịch sử in và xuất dữ liệu.
- Sản phẩm: tìm/lọc/GTIN đúng biến thể, chọn tập kết quả và sửa thương hiệu/chất liệu/TN VED/giấy tờ có preview.
- Sao chép bài đăng WB→WB, Ozon→Ozon, WB→Ozon, Ozon→WB; ánh xạ và kiểm tra từng shop đích.
- GS1: thành viên/GCP/GLN, hồ sơ/XML CryptoPro, thư/hóa đơn/chứng từ; hạn mức GTIN và số dư tiền Честный ЗНАК là hai nguồn riêng.
- Tin tức chính thức WB; dịch tiếng Việt tùy cấu hình Yandex, giữ bản gốc khi dịch lỗi.
- FBS Ozon phân trang, ba trạng thái, tìm/sắp xếp, thông tin GTIN/KIZ và xác nhận đóng gói từng đơn.
- TN VED chạy bên trong tác vụ. 10 shop dùng chung các luồng/hàng đợi và cache ảnh, không có pool theo mỗi shop.

## Kiểm chứng và giới hạn

Bước D2 tại cloud Linux đạt **798 kiểm thử Java/JavaFX và 33 kiểm thử công cụ**. Hai bộ dữ liệu 10 shop tạo 64 ảnh giao diện bốn ngôn ngữ/hai kích thước, có số đo render và hàng đợi. Kết quả native Windows được công bố theo đúng source commit trong tài sản release; xem [báo cáo kiểm chứng](docs/validation/2026-10-11-vn-code-1.4.0.md).

Sau sửa lỗi review ngày 12/10/2026, `clean verify` đạt **822 kiểm thử Java/JavaFX**, bộ công cụ đạt **39 kiểm thử**, không lỗi hoặc bỏ qua. Xem [bằng chứng và giới hạn](docs/validation/2026-10-12-vn-code-review-corrections.md).

Chưa nghiệm thu bằng chứng thư doanh nghiệp, SMTP/IMAP, shop hoặc máy in thật; chưa đo Windows 2 CPU/4 GiB. Luồng thêm/thay GTIN production độc lập còn khóa; FBO GTIN/KIZ và các phần ngoài đợt Products/Copy/10-shop chưa được công bố hoàn tất. Xem [hướng dẫn bản 1.4.0](docs/releases/VN-code-1.4.0.md) và [phạm vi còn cần nghiệm thu](docs/releases/VN-code-1.4.0-status.json).

## Công nghệ và phát triển

Java 25, JavaFX 25/FXML, Maven, SQLite, OkHttp/Gson, CryptoPro, Jakarta Mail/Angus, iText/PDFBox, ZXing và Apache POI. Node.js 22 dùng cho kiểm thử công cụ phát hành. Mỗi nhánh giữ phiên bản và phạm vi chức năng riêng trong `pom.xml`.

```bash
./mvnw -B clean verify
./mvnw javafx:run
node --test tools/*.test.mjs
```

Đóng gói Windows bằng `build.bat app-image`, `build.bat exe` hoặc `build.bat msi`. Dùng dữ liệu thử cô lập khi phát triển; không đưa database, khóa seller, mật khẩu hoặc KIZ thật vào GitHub.

## Dự án và tác giả

- Phát triển và quản lý: **Nguyễn Thành Công**.
- Mã VN code: https://github.com/ntccong2468-lab/Vncode.
- Các bản cập nhật: https://github.com/ntccong2468-lab/Bancapnhat.
- Ghi nhận bản quyền thành phần: [NOTICE](NOTICE.md).
