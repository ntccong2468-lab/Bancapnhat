# VN Code 1.6.10 — bản sao mã nguồn

Gói này phục vụ tải về và lưu mã nguồn. Người dùng đã yêu cầu tạo bộ cài và phát hành 1.6.10 lên Latest. Tại checkpoint tài liệu này, Latest được đọc gần nhất vẫn là **1.6.9** và bộ cài mới còn đang kiểm tra; đối chiếu bàn giao phát hành cuối để biết trạng thái công bố. Riêng ZIP nguồn không cập nhật app đang cài.

Nguồn runtime/test/CI đã chốt: **`b74b5edfd46636e8779c94f467eef01c03c50a14`**, nhánh `release/vn-code-1.6.10`. Gói bàn giao mới dùng tên **`VN-code-1.6.10-source-floating-glass-windowsfix-20261114.zip`**, giữ nguyên hai ZIP nguồn đã giao trước đó. Khi có gói Windows chính thức, `VN-code-1.6.10-source.zip` trong artifact Windows là archive của đúng commit CI; hai ZIP có thể khác tài liệu và checksum, nhưng phải giữ nguyên cùng input runtime/test/build đã kiểm. Trạng thái build hiện hành được ghi trong [báo cáo UI](docs/validation/ios-motion-20261114/REPORT.md).

Các sửa nghiệp vụ kế thừa từ `a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3` và bản nền 1.6.9:

- Tiếp tục đúng lô mua KIZ bị dừng chờ ký, dùng hồ sơ gốc; không mua lại hoặc mở quyền ký cho lô/cửa hàng khác.
- Ghép GTIN Ozon theo toàn bộ mã mẫu và size biến thể, dùng chung cho đăng ký, cập nhật GTIN và liên kết mua KIZ; sửa reader và chống tạo trùng khi bằng chứng ghép hết hiệu lực.
- Sửa popup còn dùng skin theme cũ khi chuyển nền tối/sáng.

Các sửa giao diện đã gộp:

- Kính nổi ở trang, panel, bảng/danh sách, ô nhập, nút, menu, Cài đặt và báo cáo trong cả hai theme. Bốn raster wallpaper dùng chung; chữ và dữ liệu không bị blur.
- Báo cáo app có khung ngoài bo tròn trong suốt và tiêu đề riêng; Cài đặt bỏ card lớn bao quanh. Giữ kết quả, quyền sở hữu, modality và hành vi đóng cửa sổ.
- Chuyển cảnh/thu phóng theo ngữ cảnh và phản hồi thao tác dùng chung, có Giảm chuyển động và bảo vệ nội dung nhập. Sản phẩm chia cột rõ; footer/tiến độ không che hàng; bảng có clip bo góc.
- Sửa frame focus phủ xanh làm chữ khó đọc và lỗi trang mới thiếu kính ở chế độ Giảm chuyển động trước khi nội dung ScrollPane được gắn vào Scene.
- Giữ quyền nền do caller sở hữu qua đổi theme, tháo/gắn và dựng lại material; không để nền riêng bị thay thành kính sau vòng đời CSS.
- Bỏ đường viền vuông1px còn thừa ở body bảng Tài chính/FBO nằm trong sheet kính, giữ header/hàng và khung bo bên ngoài.

Tài liệu trong gói:

- [Bốn yêu cầu đầu, nguồn VNCode đầu và sửa KIZ](docs/validation/2026-11-14-kiz-source-repair.md).
- [Ghép GTIN Ozon đã tích hợp và kiểm thử](docs/validation/2026-11-14-ozon-exact-model-size.md).
- [Repo rút gọn/hồi quy để duyệt; chưa cài](docs/research/2026-11-14-code-quality-proposals.md).
- [WBCode 1.3.25 đọc GTIN và ghép thẻ Ozon](docs/research/wbcode-20261114/README.md).
- [Phép thử API đọc theo mẫu và size đã duyệt](docs/research/simple-ozon-match-20261114/README.md).
- [UI: phạm vi, kiểm thử và ảnh/video JavaFX thật](docs/validation/ios-motion-20261114/REPORT.md).
- [Audit lịch sử trước thay đổi UI](docs/validation/ios-ui-audit-20261114/REPORT.md).
- [Điểm tiếp tục cho AI](docs/memory/CURRENT.md).

Build cần **JDK 25**. Trên Windows dùng `mvnw.cmd -B clean verify`; trên Linux có display dùng `./mvnw -B clean verify`, hoặc `xvfb-run -a ./mvnw -B clean verify`. Kiểm thử tổng có các bài danh mục lớn và có thể mất nhiều thời gian. Không cần dữ liệu/API seller để build.

Nguồn `86b4a2a` đạt **200 ca/21 suite focused** ở local và trên Windows thật; input main/test/POM/wrapper của f3e được đối chiếu byte bằng 86b. Các bước sửa tiếp theo ở f3e chỉ chuẩn hóa máy CI Windows để kiểm cả chuyển động thường và Giảm chuyển động; app tiếp tục tôn trọng cài đặt trợ năng của người dùng. Không coi kiểm thử focused là full app. Sau đó b74 chỉ bỏ gradient viền thừa ở bảng embedded và thêm regression pixel FXML: RED5/6→GREEN6/6, giữ nguyên năm test material cũ. Nhóm21 suite trên đúng b74 đã đạt **201 ca,0 failure/error/skip**, verifyexit0; kết quả200 của86b vẫn giữ nhãn lịch sử riêng. Receipt hiện hành trong báo cáo UI.

Full Windows cũ `43625748501` của 43b **FAIL: 4.697 ca/585 suite, 1 failure, 19 error, 2 skip**, không dùng làm nghiệm thu. Sau sửa có căn cứ, full Windows mới **`43641520595`** đang chạy trên đúng b74; còn phải đạt kiểm toàn bộ Java, native, DPAPI, cài/gỡ/cài song song và nâng cấp từ 1.6.9. Chưa xác nhận phát hành tại checkpoint tài liệu này. Chi tiết security và ảnh native có phạm vi riêng trong bàn giao; không gọi security toàn bộ xanh. Full 4.540 ca/569 suite của checkpoint KIZ/theme `75691c6` và các lượt Ozon/UI cũ là bằng chứng lịch sử, không chứng nhận toàn bộ mã hiện hành. Kiểm thử local không chứng minh mua/ký hoặc ghi API seller thật.

ZIP nguồn cũ `VN-code-1.6.10-source-floating-glass-20261114.zip` (SHA-256 `15ab17ed6c867ff81aebe15773bdf403ece2420b8a4b616eec3f407e72a1defb`) giữ nguyên checkpoint 43b và tài liệu c5a. ZIP đầu (SHA-256 `1630458672f9be335c09f8f3a624c5ec844139ce1cdc4510fccbb304877d5e4c`) cũng giữ nguyên. Chỉ gói mang hậu tố `windowsfix` mới bao gồm các sửa sau lần full Windows lỗi.

Giữ đầy đủ source, test, resource, Maven wrapper và build scripts. Checksum/receipt của chính ZIP giao bên ngoài để tránh tự tham chiếu. Khóa API, thông tin license và dữ liệu cửa hàng thật không thuộc bản sao mã nguồn.
