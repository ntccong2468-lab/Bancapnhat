# VN code

**VN code** là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý, phục vụ seller trên Wildberries và Ozon.

Bản phát hành mới nhất: **VNCode 1.6.6**. [Tải bộ cài Windows 64-bit](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.6/VN-code-1.6.6-Windows-x64.exe) · [Release Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest) · [Bản nền](docs/releases/BASELINE.md) · [Ghi chú 1.6.6](docs/releases/VN-code-1.6.6.md).

**1.6.6 đã phát hành lên Latest lúc 21:46:11 UTC ngày 07.11.2026 (00:46:11 MSK ngày 08.11).** Kế thừa toàn bộ 1.6.5, gộp Ozon tự thay KIZ/PDF kết hợp, Znack nhận feed/GTIN WB nền, shared writer, báo cáo đúng phạm vi và audit giao diện. [Bàn giao và giới hạn kiểm chứng](docs/validation/2026-11-07-vncode-166-release-handoff.md).

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

[Bàn giao 1.6.6](docs/validation/2026-11-07-vncode-166-release-handoff.md) và [biên nhận công bố](docs/validation/2026-11-07-vncode-166-publication.json): **3.979 ca Windows** (3.977 đạt, 2 bỏ qua, 0 lỗi/thất bại), native/migration/DPAPI/cài-gỡ/nâng cấp từ 1.6.5 giữ dữ liệu đạt; đủ 16 tệp đã tải và kiểm hash. [Payload](docs/validation/2026-11-07-vncode-166-package.json): 2.917 tệp source ZIP khớp Git, JAR 1.956 class đúng nguồn. Updater binary 1.6.5 nhận AVAILABLE 1.6.6 và đúng MSI/hash/kích thước; chưa quan sát popup máy seller. Node 160/160, portal 67/67 trong phạm vi riêng. [Security](docs/validation/2026-11-07-vncode-166-security.json) giữ Java gate thất bại ở tám finding cũ/1 high thumbprint; không có finding mới. [Audit và ảnh native](docs/validation/2026-11-07-report-pdf-app-audit.md).

Hồ sơ Ozon thật trước đó ngày 04.11: 22 đơn và 2 đơn mới được nhận, đọc lại xác nhận; PDF 25 vận đơn + 13 KIZ gilet = 38 trang, 2 tác vụ theo lô, không tải nhãn riêng, xuất 6,999 giây. KIZ in từ reservation đúng đơn; kết quả này không xác nhận Ozon chấp nhận KIZ. Lô 1.000 dùng mô phỏng, chưa có benchmark 1.000 đơn thật. Nghiệm thu sửa sản phẩm/Catalog của bản nền vẫn giữ giới hạn trong hồ sơ: gửi lại giá trị đang có của năm trường, thẻ Catalog mới ở draft/moderation chưa ký/công bố. Không coi HTTP 200, số liệu mô phỏng hoặc ảnh giao diện là kết quả nghiệp vụ trên shop thật. [Giới hạn bảo mật còn lại](docs/validation/2026-11-03-release-security-review.md) tiếp tục áp dụng.

Trạng thái, quyết định và kinh nghiệm để tiếp tục phát triển: [Bộ nhớ dự án](docs/PROJECT_MEMORY.md).

Lượt mới ngày 07.11 đã nghiệm thu riêng 9 đơn gilet/9 mã được Ozon xác nhận và một GTIN thêm/đọc lại đúng biến thể, giữ barcode cũ. Không cộng các mốc này thành một lô mới; không coi harness API là chuỗi approve/submit theo chứng thư hoặc mua KIZ có phí trong app. [Bằng chứng Ozon](docs/validation/ozon-165-20261107/live-api.json), [GTIN thật](docs/validation/2026-11-07-ozon-live-gtin-acceptance.md).
