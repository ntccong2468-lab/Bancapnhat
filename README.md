# VN code

**VN code là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý**, phục vụ người bán trên **Wildberries** và **Ozon**.

Repository này chứa các bản cập nhật của cùng ứng dụng VN code, giữ định danh bộ cài và dữ liệu đã cài.


GS1 1.4.0 đã bổ sung bảng kiểm tra/cảnh báo, danh tính chứng thư CMS thực,
phí gia nhập/phục hồi và bảo vệ đơn đã gửi; hồ sơ tự lấy từ National Catalog,
đơn Word do seller cung cấp tự điền/tải/ký thành PDF hoặc đính kèm PDF/ảnh giấy.
Xin tiền tố 999/99.999 mã kiểm tra mọi hạn mức trước gửi; tự nhắc một lần sau
7 ngày có duyệt. Xem
[phạm vi GS1 và điều kiện còn thiếu](docs/releases/VN-code-1.4.0.md).
Mẫu Word cá nhân không đóng gói; ký thỏa thuận CZ trong app và nghiệm thu thật
chưa hoàn tất; chưa phát hành EXE 1.4.0.

Đã đối chiếu thêm [Wbcode 1.3.8 và hai màn hình Cấu hình/Đăng ký Znack](docs/research/2026-10-13-vn-code-wbcode-138-znack-audit.md):
đã bổ sung thông tin tài khoản, scope đăng ký, giấy tờ sản phẩm và thẻ mới giữ GTIN cũ.
GTIN ADD WB/Ozon có hợp đồng/fixture, chưa nghiệm thu seller thật.
[Báo cáo hiệu năng mới](docs/validation/2026-10-14-vn-code-140-scaling.md) và
[kiến trúc mỗi khách một máy](docs/architecture/vncode-140-multi-client.md).

## Tải và sử dụng

**VN code 1.4.0 đang phát triển, chưa phát hành bộ cài.** Đã viết client đăng ký Gmail/OTP và kích hoạt bằng khóa VNC, giao diện bốn ngôn ngữ và kiểm tra quyền tại service. [Hợp đồng license và giới hạn](docs/research/2026-10-13-vn-code-desktop-license-contract.md). Còn cần public pin thật, deploy/nghiệm thu website và Windows; publisher chặn khi thiếu bằng chứng.

Bản đã phát hành gần nhất: [VN code 1.3.0 Preview](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.3.0-preview.1).

Đóng VN code rồi chạy EXE để nâng cấp. Java được đóng gói kèm. Bộ cài chưa ký Authenticode; bản Preview cài thủ công và chưa bật tự cập nhật. Bản trước: [VN code 1.1.34](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.34).

Chương trình dùng `%LOCALAPPDATA%\VNcodeApp`, dữ liệu dùng `%LOCALAPPDATA%\VNcodeData`. Các bản cá nhân đã phát hành giữ quyền miễn phí. Bản 1.4.0 dùng Gmail đăng ký trong ứng dụng, chủ phần mềm duyệt và cấp khóa; tích hợp production còn chờ public pin, triển khai Portal và nghiệm thu. Người dùng thiết lập shop, tài khoản nghiệp vụ và chứng thư của mình. Nâng cấp giữ dữ liệu, mẫu tem và lịch sử VN code.

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

Chưa nghiệm thu bằng chứng thư doanh nghiệp, SMTP/IMAP, shop hoặc máy in thật; chưa đo Windows 2 CPU/4 GiB. GTIN ADD WB/Ozon chưa nghiệm thu trên shop thật, thay/xóa barcode sàn chưa mở; FBO GTIN/KIZ và các phần ngoài đợt Products/Copy/10-shop chưa được công bố hoàn tất. Xem [hướng dẫn bản 1.4.0](docs/releases/VN-code-1.4.0.md) và [phạm vi còn cần nghiệm thu](docs/releases/VN-code-1.4.0-status.json).

Đợt client license mới: **920/921 kiểm thử Java/JavaFX đạt** (1 kiểm thử DPAPI chưa chạy trên Linux), **42 kiểm thử công cụ đạt**. [Bằng chứng và cổng production còn thiếu](docs/validation/2026-10-13-vn-code-license-client.md). Các con số ở trên là kết quả các đợt cũ, không chứng minh nghiệm thu license thật.

Source website đăng ký/license Portal 0.3.1 nằm tại [website/license-portal](website/license-portal/README.md); chưa triển khai production.

Đợt Znack/Ozon và nhiều máy khách: **1.174/1.175 Java/JavaFX đạt** (1 DPAPI chỉ chạy Windows), **42/42 công cụ**, **28/28 website**; JAR Linux khởi động được. [Bằng chứng và số đo](docs/validation/2026-10-14-vn-code-140-scaling.md).

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
