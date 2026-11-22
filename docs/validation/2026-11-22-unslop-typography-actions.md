# VN Code — Unslop giai đoạn 2

Nguồn bắt đầu là `e673016` trên `design/unslop-audit-20261122`. Giai đoạn này thực hiện hướng giao diện đen phẳng đã duyệt và tiếp nối [audit Unslop](2026-11-22-unslop-ui-audit.md).

## Thay đổi

| Khu vực | Thay đổi | Tệp chính |
| --- | --- | --- |
| Giao diện chung | Tiêu đề trang 24 px, tiêu đề phần 20 px, ký hiệu thao tác chung 16 px. Chữ báo lỗi và chú thích kho dễ đọc hơn. Bản dịch Tổng quan tiếng Nga và Trung, cùng mã đơn tiếng Việt và Nga, thay những nhãn còn tiếng Anh. | [Theme](../../src/main/resources/com/vncode/app/styles/theme.css), [điều khiển](../../src/main/resources/com/vncode/app/styles/workspace-controls.css), [bản dịch](../../src/main/resources/com/vncode/app/i18n) |
| Đơn FBS và FBO | Nhãn trợ năng và tooltip dùng đúng đơn, lô hoặc biến thể. WB mở chi tiết lô đã chọn bằng Enter. Ozon giải thích phạm vi in theo bộ lọc trước khi hiển thị số lượng xác nhận. FBO nêu rõ phạm vi sản phẩm đã tải có số nhãn dương. | [FBS WB](../../src/main/java/com/vncode/app/ui/packing/PackingController.java), [FBS Ozon](../../src/main/java/com/vncode/app/ui/ozon/OzonDashboardController.java), [FBO](../../src/main/java/com/vncode/app/ui/fbo/FboPackingController.java), [FBO FXML](../../src/main/resources/com/vncode/app/ui/fbo/fbo-packing-view.fxml) |
| Znack | Tiêu đề chung dùng được cho WB và Ozon. Bản tiếng Nga hiện ngắn hơn để tiêu đề và nút vừa với cửa sổ hẹp. Màn duyệt lô hiện chế độ ký đã chọn trước khi xác nhận. Nút Hủy cấu hình giấy tờ theo ngôn ngữ đang dùng. | [Znack FXML](../../src/main/resources/com/vncode/app/ui/znackregistration/znack-card-registration-view.fxml), [duyệt lô](../../src/main/java/com/vncode/app/ui/znackregistration/RegistrationSourceReviewDialog.java), [cấu hình giấy tờ](../../src/main/java/com/vncode/app/ui/znackregistration/RegistrationDocumentConfigDialog.java), [WB](../../src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java), [Ozon](../../src/main/java/com/vncode/app/ui/znackregistration/OzonRegistrationPane.java) |
| GS1 | Menu Tác vụ tài khoản mở cấu hình Znack hoặc báo cáo đồng bộ từ bàn phím, dùng lệnh hiện có. | [GS1](../../src/main/java/com/vncode/app/ui/gs1/Gs1Pane.java) |
| Kiểm tra trường nhập | Catalog phân biệt giá trị bỏ trống và TN VED sai định dạng. Sản phẩm nêu đúng trường giấy tờ thiếu hoặc ngày nhập sai và đưa focus về đó. | [Catalog](../../src/main/java/com/vncode/app/ui/catalogsync/CatalogSyncPane.java), [Sản phẩm](../../src/main/java/com/vncode/app/ui/products/ProductBulkEditPane.java) |
| Trợ năng khác | Lịch sử in đặt tên cho nút in lại, Tài chính gắn nhãn với hai ô lọc ngày. | [Lịch sử in](../../src/main/java/com/vncode/app/ui/history/PrintHistoryController.java), [Tài chính](../../src/main/java/com/vncode/app/ui/finance/FinanceDashboardController.java) |

Tập đơn được chọn hoặc in, kiểm tra giấy tờ, lựa chọn ký của seller, API sàn, cấu hình vận hành và lưu trữ đều theo hành vi hiện hành. Giai đoạn này sửa lớp trình bày và bổ sung kiểm thử hồi quy. Bộ lọc nghiệp vụ và nút Thao tác khác của Znack giữ theo yêu cầu trước.

## Kiểm thử

- Maven `verify` trên Linux/Xvfb với Java 25 chạy 374 ca trong 40 bộ, không lỗi hoặc bỏ qua. Các nhóm kiểm gồm FXML, bố cục, FBS/FBO, Znack, GS1, Sản phẩm, Tài chính và Lịch sử in. Lượt đầu dừng ở một fixture truy vấn bảng consent chưa được tạo. Fixture đã kiểm trực tiếp rằng thao tác Hủy không tạo bảng và cả bốn ca WB/Ozon đạt. [Log Maven](/workspace/reports/unslop-phase2-20261122/desktop-verify-final.log).
- Sau khi chốt hai nhãn tiêu đề cuối, Maven `verify` chạy lại 109 ca Znack/GS1, không lỗi hoặc bỏ qua. JAR đọc được và cả 8 tài nguyên thay đổi khớp mã nguồn theo byte. [Log đóng gói](/workspace/reports/unslop-phase2-20261122/desktop-verify-title-final.log).
- JavaFX dựng bộ 80 ảnh ở 1000 × 650 và 1366 × 768, trên hai nền, bằng tiếng Việt, Nga, Anh và Trung. Lượt đầu phát hiện tiêu đề Znack tiếng Nga chồng thanh trạng thái. Sau khi rút gọn bản dịch, dựng lại tám ảnh bị ảnh hưởng và cả 16 phép đo tiêu đề Znack không có chồng vùng. Đo thực tế cỡ tiêu đề trang 24 px, tiêu đề phần 20 px và chữ thân 14 px. [Bộ ảnh](/workspace/reports/unslop-typography-20261122/after-final2/captures-combined) · [Đo vị trí](/workspace/reports/unslop-typography-20261122/after-final2/geometry.log).
- GS1 qua 8 phép thử bàn phím với Space hoặc Enter để mở, Esc để đóng và trả focus. Kiểm ngày giấy tờ sai qua 8 trường hợp gồm bốn ngôn ngữ và hai nền. 17 khóa bản dịch mới có mặt một lần trong mỗi gói và cùng tham số. [Bàn phím](/workspace/reports/unslop-typography-20261122/after/gs1-keyboard.log) · [Hộp giấy tờ](/workspace/reports/unslop-typography-20261122/after-final/validation.log) · [Bản dịch](/workspace/reports/unslop-phase2-20261122/resource-check.log).
- Node chạy 280/280 ca công cụ dự án. [Log Node](/workspace/reports/unslop-phase2-20261122/node-test.log).

## Phạm vi còn lại

Cột **Thao tác** của bảng Znack có thể nằm ngoài vùng nhìn ở cửa sổ hẹp. Phần bố cục bảng thuộc giai đoạn 3. Kiểm thử dùng hồ sơ tổng hợp trên Linux. Nghiệm thu Windows, giao dịch seller thật và phát hành Latest chưa thực hiện.
