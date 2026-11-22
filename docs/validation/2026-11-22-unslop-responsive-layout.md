# VN Code — Unslop giai đoạn 3

Nhánh `design/unslop-audit-20261122` tiếp nối `c61865e`. Bố cục ưu tiên bảng đơn và sản phẩm ở 1000 × 650, sau đó kiểm lại khi mở rộng và thu hẹp cửa sổ. Hồ sơ kiểm thử sử dụng dữ liệu tổng hợp.

## Thay đổi

- **FBS Ozon và FBO WB/Ozon** — Thanh thao tác cho phép xuống hàng khi nội dung dài, có trạng thái tải hoặc xuất hiện lệnh hủy chọn. Nút thao tác còn đọc được và bảng vẫn có vùng riêng. [FBS Ozon](../../src/main/resources/com/vncode/app/ui/ozon/ozon-dashboard-view.fxml) · [FBO](../../src/main/resources/com/vncode/app/ui/fbo/fbo-packing-view.fxml).
- **Sản phẩm** — Ô tìm kiếm co giãn trong phần chiều rộng còn lại, bộ lọc trạng thái giấy tờ có thể cùng hàng ở cửa sổ 1000 px. Nhắc tìm kiếm đầy đủ vẫn truy cập được qua tooltip. [Sản phẩm](../../src/main/java/com/vncode/app/ui/products/ProductsPane.java).
- **Znack và Liên kết KIZ** — Gom thuộc tính của biến thể trong hàng khi bảng hẹp để giữ các cột nhận diện, trạng thái và thao tác. Hover hoặc Enter mở thông tin chi tiết từ hàng. Mở rộng cửa sổ khôi phục các cột tách riêng. [Znack WB](../../src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java) · [Znack Ozon](../../src/main/java/com/vncode/app/ui/znackregistration/OzonRegistrationPane.java) · [Liên kết KIZ](../../src/main/java/com/vncode/app/ui/kizmapping/KizMappingController.java).
- **GS1** — Ở cửa sổ hẹp, thông tin tài khoản cuộn liền với phần thư. Ở cửa sổ rộng, hai vùng nằm cạnh nhau. [GS1](../../src/main/java/com/vncode/app/ui/gs1/Gs1Pane.java).
- **Tài chính, Lịch sử in và Thiết kế tem** — Bộ lọc ngày đi theo cặp nhãn và ô nhập. Các nhóm KPI và cột tài chính có thanh cuộn khi cần. Lịch sử in giữ chỗ cho hai nút trong mỗi hàng. Thiết kế tem giữ chiều rộng tối thiểu của các vùng làm việc và kích thước thực của mẫu. [Tài chính](../../src/main/resources/com/vncode/app/ui/finance/finance-dashboard-view.fxml) · [Lịch sử in](../../src/main/resources/com/vncode/app/ui/history/print-history-view.fxml) · [Thiết kế tem](../../src/main/resources/com/vncode/app/ui/print/print-template-designer-view.fxml).
- **Hướng dẫn** — Mở bài và thêm ảnh/video nằm trước vùng xem trước media. Nhãn nêu khả năng thay media nếu mục đã có tệp. [Hướng dẫn](../../src/main/java/com/vncode/app/ui/guides/GuidesPane.java).

## Bằng chứng ban đầu

- Ở nguồn `c61865e`, fixture Znack 1000 × 650 có SKU mẫu vẫn không đưa được cột lệnh cuối vào vùng nhìn, kể cả sau `scrollToColumn`. GS1 dùng hai panel dọc, panel thông tin tài khoản chỉ còn khoảng 55% chiều cao trang. [Thư mục đo trước sửa](/workspace/reports/unslop-layout-motion-20261122/baseline).
- Tái hiện hai ca chữ nút FBS/FBO bị cắt. Sau chỉnh FXML, hai ca gồm 24 tổ hợp ngôn ngữ, marketplace và độ rộng đạt. [Trước sửa](/workspace/reports/unslop-phase3-fbs-fbo/red-junit.log) · [Sau sửa](/workspace/reports/unslop-phase3-fbs-fbo/green-junit.log).
- Maven trong worktree riêng đạt 15/15 ca Sản phẩm, Hướng dẫn, License và TN VED. License dùng dịch vụ giả. TN VED kiểm thao tác sao chép và áp dụng với dữ liệu cục bộ. [Log](/workspace/reports/unslop-layout-motion-20261122/desktop-pages-final.log).
- Fixture sau sửa dựng 12/12 tổ hợp màn chính và nền khi đổi 1366 → 1000 → 1366 px. Ở 1000 × 650, phần bảng Sản phẩm tăng từ 43% lên 49% chiều cao scene. Nút Znack nằm trong khung bảng trên cả hai nền. Vùng thông tin GS1 dùng hết 507 px ở giữa trang, rồi khôi phục hai cột khi mở rộng. Tám tổ hợp bổ sung kiểm License, TN VED, Hướng dẫn và Cài đặt. Bốn lượt mở menu Cài đặt và tám lượt kiểm hộp ngày giấy tờ đạt. [Số đo](/workspace/reports/unslop-layout-motion-20261122/after-phase3-retry/layout.log) · [Ảnh](/workspace/reports/unslop-layout-motion-20261122/after-phase3-retry/captures).
- Tài chính đo ở scene 1000 × 650 có sidebar. Vùng bảng cao 151 px so với 167 px trước sửa do có thanh cuộn ngang cho KPI. Cả hai hàng KPI nằm trong khung. Lịch sử in với 30 dòng tổng hợp có cột thao tác rộng 187 px, đủ chỗ cho cả hai nút tiếng Nga. FBO quản lý lô hàng tại 940 × 650 vẫn dùng thanh cuộn ngang hiện có để đến cột Cập nhật và KIZ. [FBO lô hàng](/workspace/reports/unslop-phase3-fbs-fbo/fbo-supply-scroll.log).

## Hồi quy tích hợp

Lượt rộng chạy 342 ca và phát hiện khoảng cách từ tiêu đề đến nút Cập nhật Ozon thiếu 4 px so với WB. Giữ khoảng cách cũ và kiểm lại 21/21 ca Ozon FBS, đối chiếu WB và bố cục thao tác hẹp. [Lượt rộng](/workspace/reports/unslop-layout-motion-20261122/phase3-verify.log) · [Kiểm lại FBS](/workspace/reports/unslop-layout-motion-20261122/phase3-gap.log).

Ảnh ở 1000 × 650 còn phát hiện hàng bộ lọc thứ hai đè lên bảng Znack Ozon. Đã dành đủ chiều cao cho các hàng bộ lọc và thanh tác vụ cuối trang. Nội dung trạng thái dài rút gọn trong hàng 71 px và giữ nguyên văn trong tooltip và báo cáo. [Ảnh trước](/workspace/reports/unslop-layout-motion-20261122/after-phase3-retry/ozon-kiz-captures/ozon-registration-dark-1000-650.png) · [Ảnh sau](/workspace/reports/unslop-layout-motion-20261122/after-phase3-final/ozon-kiz-captures/ozon-registration-dark-1000-650.png).

Maven `verify` của bản chốt chạy 63/63 ca gồm vùng danh sách, FBS và Znack Ozon, không lỗi hoặc bỏ qua. Node đạt 280/280 ca công cụ. Nguồn thử trên Linux/Xvfb với hồ sơ tổng hợp, chưa chạy Windows hoặc phát hành. [Maven bản chốt](/workspace/reports/unslop-layout-motion-20261122/phase3-final-rerun.log) · [Node](/workspace/reports/unslop-layout-motion-20261122/node-test.log).
