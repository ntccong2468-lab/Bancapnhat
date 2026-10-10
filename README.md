# VN code

**VN code là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý**, phục vụ người bán trên **Wildberries** và **Ozon**.

Repository này chứa các bản cập nhật của cùng ứng dụng VN code, giữ định danh bộ cài và dữ liệu đã cài.

## Tải và sử dụng

[Tải VN code 1.3.0 Preview — Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.3.0-preview.1/VN-code-1.3.0-preview.1-Windows-x64.exe) · [Bản phát hành và checksum](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.3.0-preview.1)

Đóng VN code rồi chạy EXE để nâng cấp. Java được đóng gói kèm. Bộ cài chưa ký Authenticode; bản Preview cài thủ công và chưa bật tự cập nhật. Bản trước: [VN code 1.1.34](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.34).

Chương trình dùng `%LOCALAPPDATA%\VNcodeApp`, dữ liệu dùng `%LOCALAPPDATA%\VNcodeData`. Bản cá nhân miễn phí; người dùng thiết lập shop, tài khoản nghiệp vụ và chứng thư của mình. Nâng cấp giữ dữ liệu, mẫu tem và lịch sử VN code.

## Chức năng

- Quản lý nhiều shop; kết nối WB và Ozon theo đúng marketplace.
- Đồng bộ đơn/supply WB và posting Ozon FBS Standard.
- Kho KIZ/Znack, mapping GTIN, gán mã, in barcode/Data Matrix và nhãn PDF.
- Thiết kế mẫu tem, lịch sử in và xuất dữ liệu.
- GS1 trong bản 1.3.0 Preview: thành viên/GCP/GLN, hồ sơ National Catalog, duyệt và ký XML CryptoPro, hộp thư riêng, hóa đơn và chứng từ.
- TN VED giữ lõi tra cứu và dữ liệu nội bộ; phần liên kết sâu trong tác vụ còn đang phát triển.

## Kiểm chứng và giới hạn

Bộ cài 1.3.0 Preview đạt **648 kiểm thử Java/JavaFX và 33 kiểm thử công cụ** trên [Windows CI](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38028666240). Launcher, migration, DPAPI thực, cài/gỡ độc lập và nâng cấp giữ dữ liệu từ 1.1.34 / 1.2.0 / 1.2.1 / 1.2.2 đều đã được kiểm tra.

Chưa nghiệm thu bằng chứng thư doanh nghiệp, SMTP/IMAP, shop hoặc máy in thật. Thêm/thay GTIN production WB/Ozon vẫn bị khóa chờ hợp đồng API đã xác minh. Chỉnh sửa sản phẩm hàng loạt, FBO GTIN/KIZ và các phần còn lại của kế hoạch cập nhật chưa hoàn tất. Xem [hướng dẫn và phạm vi bản 1.3.0](https://github.com/ntccong2468-lab/Bancapnhat/blob/HEAD/docs/releases/VN-code-1.3.0.md).

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
