# VN Code 1.6.10 — bản sao mã nguồn

Gói này phục vụ tải về và lưu mã nguồn. Bản công khai Latest vẫn là **1.6.9** tại lần đọc metadata 14.11.2026; gói ZIP này không cập nhật app đang cài.

Nguồn nghiệp vụ mới: `a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3`, kế thừa bản nền1.6.9 và các sửa chưa phát hành của phiên này:

- Tiếp tục đúng lô mua KIZ bị dừng chờ ký, dùng hồ sơ gốc, không mua lại hoặc mở quyền ký cho lô/cửa hàng khác.
- Sửa theme của popup dùng lại skin cũ khi chuyển nền tối/sáng.
- Ghép GTIN Ozon theo toàn bộ mã mẫu và size biến thể, dùng chung cho đăng ký, cập nhật GTIN và liên kết mua KIZ; sửa reader và chống tạo trùng khi claim hết hiệu lực.

Tài liệu trong gói:

- [Báo cáo bốn yêu cầu, nguồn VNCode đầu và sửa KIZ](docs/validation/2026-11-14-kiz-source-repair.md).
- [Luồng Ozon vừa tích hợp và bằng chứng kiểm thử](docs/validation/2026-11-14-ozon-exact-model-size.md).
- [Repo rút gọn/hồi quy để duyệt; chưa cài](docs/research/2026-11-14-code-quality-proposals.md).
- [WBCode1.3.25 đọc GTIN và ghép thẻ Ozon](docs/research/wbcode-20261114/README.md).
- [Phép thử API đọc theo mẫu và size đã được duyệt](docs/research/simple-ozon-match-20261114/README.md).
- [Vì sao UI chưa giống kỳ vọng iOS27: audit và ảnh đối chiếu, không đổi source UI](docs/validation/ios-ui-audit-20261114/REPORT.md).
- [Điểm tiếp tục cho AI](docs/memory/CURRENT.md).

Build cần **JDK25**. Trên Windows dùng `mvnw.cmd -B clean verify`; trên Linux có display dùng `./mvnw -B clean verify`, hoặc `xvfb-run -a ./mvnw -B clean verify`. Kiểm thử tổng bao gồm các bài danh mục lớn và có thể mất nhiều thời gian. Kết quả theo từng nguồn/phạm vi được ghi trong tài liệu bàn giao; kiểm thử local không chứng minh mua/ký hoặc ghi seller API thật.

Full4.540 ca/569 suite,0failure/error/2skip là bằng chứng riêng của checkpoint KIZ/theme `75691c6`. Nguồn Ozon mới có receipt hồi quy/build riêng; không dùng số full cũ để chứng nhận toàn bộ mã mới. Giữ src/main,src/test,resources,wrapper và build scripts; không cần dữ liệu/API seller để build.

Checksum của chính ZIP và receipt kiểm archive được giao bên ngoài ZIP để không tạo checksum tự tham chiếu. Tài khoản, khóa và dữ liệu cửa hàng thật không phải thành phần của bản sao mã nguồn.
