# VN code

**VN code** là ứng dụng Windows do Nguyễn Thành Công phát triển và quản lý, phục vụ seller trên Wildberries và Ozon.

Bản phát hành mới nhất: **VNCode 1.6.4**. [Tải bộ cài Windows 64-bit](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.4/VN-code-1.6.4-Windows-x64.exe) · [Release Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest) · [Bản nền](docs/releases/BASELINE.md) · [Ghi chú 1.6.4](docs/releases/VN-code-1.6.4.md).

**1.6.5 đang được chuẩn bị, chưa phát hành lên Latest.** [Phạm vi bản gộp](docs/releases/VN-code-1.6.5.md) gồm các sửa Code Doctor, đồng bộ Ozon, Znack/GTIN, giấy tờ và mapping KIZ mới nhất.

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

[Bàn giao 1.6.4](docs/validation/2026-11-05-vncode-164-handoff.md) và [biên nhận Latest](docs/validation/2026-11-05-vncode-164-published-latest.json) ghi rõ 3.864 ca Windows, nâng cấp từ 1.6.3, 16 tệp tải công khai và bộ cập nhật của binary 1.6.3. Kết quả này thuộc nguồn 1.6.4, không phải chứng nhận cho bản 1.6.5 đang chuẩn bị. Xem [30 ảnh giao diện native sáng/tối đã duyệt](docs/design/native/2026-11-04/README.md).

Nghiệm thu Ozon thật trước đóng gói: 22 đơn và 2 đơn mới được nhận, đọc lại xác nhận; PDF 25 vận đơn + 13 KIZ gilet = 38 trang, 2 tác vụ theo lô, không tải nhãn riêng, xuất 6,999 giây. KIZ in từ reservation đúng đơn; kết quả này không xác nhận Ozon chấp nhận KIZ. Lô 1.000 dùng mô phỏng, chưa có benchmark 1.000 đơn thật. Nghiệm thu sửa sản phẩm/Catalog của bản nền vẫn giữ giới hạn trong hồ sơ: gửi lại giá trị đang có của năm trường, thẻ Catalog mới ở draft/moderation chưa ký/công bố. Không coi HTTP 200, số liệu mô phỏng hoặc ảnh giao diện là kết quả nghiệp vụ trên shop thật. [Giới hạn bảo mật còn lại](docs/validation/2026-11-03-release-security-review.md) tiếp tục áp dụng.

Trạng thái, quyết định và kinh nghiệm để tiếp tục phát triển: [Bộ nhớ dự án](docs/PROJECT_MEMORY.md).
