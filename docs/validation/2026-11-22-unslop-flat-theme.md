# Giai đoạn 1 · Giao diện đen phẳng của VN Code

Ngày 22.11.2026, giờ Moskva. Tiếp nối audit tại `12fbcf5`. Hướng đã duyệt dùng nền đen thuần, bề mặt đặc và một màu nhấn cho thao tác. [Audit](2026-11-22-unslop-ui-audit.md)

## Thay đổi

- `theme.css` và `workspace-controls.css` dùng nền tối `#000000`, xám đen đặc cho vùng nâng và đường phân cách mảnh. Giao diện sáng cũng dùng bề mặt đặc. Bảng và các trang làm việc ưu tiên nền chính. Bo góc của nút, menu và dialog có phân cấp rõ. Nút Cài đặt có một mặt liền, tập trung tiêu điểm vào viền ngoài.
- `ThemeService.java` áp dụng bảng màu qua CSS khi mở hoặc đổi theme. Đã bỏ bộ tạo ảnh nền và mẫu kính trong `WorkspaceBackdropMaterial.java`. Lớp nền ở góc của dialog Windows theo bảng màu mới. Nền hoặc thuộc tính do màn hình tự thiết lập tiếp tục được kiểm qua các fixture.
- `WorkspaceAppearancePane.java`, `ShopSidebarController.java`, FXML của sidebar và bốn bộ ngôn ngữ bỏ mục kính đã ngừng dùng. Tùy chọn Sáng, Tối, Theo hệ thống, Giảm chuyển động, Ngôn ngữ và các nút cài đặt khác vẫn có. Khóa cấu hình kính cũ được bỏ qua khi đọc giao diện, dữ liệu trong hồ sơ cũ được giữ.
- Các bộ kiểm thử giao diện dùng màu nền và pixel thực để kiểm bề mặt phẳng. Chúng tiếp tục kiểm tên nút, focus, độ tương phản, trạng thái bị vô hiệu hóa, góc bo và các lệnh từ menu.

## Bằng chứng

- Lượt kiểm đầu tìm thấy màu nền của nút sửa sản phẩm trùng nhau giữa hai trạng thái được phép bấm và bị vô hiệu hóa. Đã tách màu và giữ nguyên ngưỡng tương phản của kiểm thử.
- Bộ bằng chứng cuối có 66 ảnh JavaFX sáng/tối ở 1366 × 768 và 1000 × 650, với 9/9 tiến trình chụp hoàn tất. Vùng làm việc WB FBS, WB FBO và Liên kết KIZ có fill đen với alpha 1. Năm nút sửa Sản phẩm khi bị vô hiệu hóa có tỷ lệ tương phản tính từ màu JavaFX là 8,16:1 ở chế độ tối và 5,16:1 ở chế độ sáng. Đo trên các nút được kiểm, trong hồ sơ tổng hợp. Ảnh và log ở `/workspace/reports/unslop-flat-theme-20261122/after-verified`.
- Kiểm thử công cụ Node đạt 280/280.
- Java 25 với Xvfb đạt 221/221 ca trong 32 bộ kiểm thử. Lệnh Maven `verify` đã tạo JAR chứa đúng hai stylesheet cuối và bỏ các lớp tạo nền kính. Phạm vi bao gồm điều hướng, FXML, theme, popup, GS1, FBS Ozon, Sản phẩm, khởi động và giảm chuyển động. Lượt cũ dùng assertion bóng ở bộ chọn cửa hàng đã được thay bằng kiểm tra nền phẳng và khoảng cách giữa hai nút.
- Phạm vi mốc này là khoảng cách, bảng màu và bề mặt. Lỗi cột Thao tác của Znack ở cửa sổ hẹp thuộc audit bố cục và vẫn tái hiện với fixture. Chưa kiểm Windows trên máy seller. Bản này chưa phát hành.
