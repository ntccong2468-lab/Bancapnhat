# Triển khai VNCode 1.4.12

Ngày làm việc: 15/10/2026, theo múi giờ Moscow. Nền nguồn rà soát ban đầu: `2d4eeacee9195c005226dcd5fd8696f79013f60f`; bản Published tại thời điểm bắt đầu: 1.4.11.

## Phạm vi đã viết mã

- `SyncRuntime`/`SyncCoordinator`: một lịch nền của ứng dụng, hai worker FBS và một worker nền. Giới hạn tổng request marketplace đang dùng vẫn là hai; không nâng thành bốn như con số gợi ý trong thiết kế ban đầu. Cooldown giải phóng worker đọc FBS; yêu cầu làm mới không bỏ qua cooldown.
- `FbsSyncService`: phân trang Ozon, trạng thái chi tiết của đơn rời hàng đợi, lô trạng thái WB tối đa 1.000 ID, phân trang lịch sử, danh sách và nội dung shipment WB. Dùng checkpoint đã lưu; dữ liệu chỉ được ghi khi còn đúng chủ cửa hàng. Polling có trễ khi chạm giới hạn API/mạng; không có webhook thời gian thực của sàn.
- `ZnackAutoSyncService`: source Catalog → đối chiếu biến thể → kế hoạch Catalog/GTIN bền vững → gửi/đọc lại → báo cáo. Kiểm tra product/offer/SKU Ozon; liên kết native phải giữ đúng scope. Luồng tự động hiện chỉ chọn trường GTIN. Luồng xem trước đã có của Catalog tiếp tục xử lý TN VED/giấy tờ/chất liệu/thuộc tính với xác nhận riêng.
- `Gs1AutomaticSyncService`: đọc trực tiếp qua xác thực chứng thư từ Cấu hình Znack; không cần trình duyệt. Có cache riêng theo chủ/chứng thư, phiên riêng Catalog/ELK và một lượt xác thực lại khi phiên bị từ chối. Lưu trạng thái đơn GS1 đã gửi để giao diện không đề nghị gửi lặp chỉ vì vừa đọc cache.
- `SyncRunRepository` và báo cáo: ID tác vụ, dòng sản phẩm, tiến độ, mã lỗi an toàn, timeline, CSV/JSON có phân trang. Giao diện bỏ công cụ GTIN riêng và mở đối chiếu trong luồng Znack.
- Cơ sở dữ liệu bổ sung bảng, không xóa journal cũ; khóa chờ SQLite không giữ monitor của các lệnh chọn cửa hàng/làm mới. `SyncDataScope` chặn phản hồi cũ mở database khác khi môi trường dữ liệu thay đổi.
- FBO giữ hợp đồng phân trang/cursor đang dùng của dịch vụ hiện có và nhận lịch nền chung. Tải chi tiết mặt hàng FBO vẫn theo lựa chọn của người dùng. Catalog nguồn và một số lượt lên kế hoạch vẫn có thể đọc nhiều trang trong một lượt nền; chưa chuyển toàn bộ thành một request mỗi lượt như mục tiêu dài hạn.

## Bằng chứng kiểm thử

- Lượt tập trung trước hoàn thiện: **100 bài, không lỗi/không bỏ qua**, gồm GS1 phiên HTTP, điều phối 10 cửa hàng, SQLite bận, pipeline WB/Ozon, lô 1.205 trạng thái WB, nội dung shipment 105 đơn và 54 bài FXML. Đây không phải thử tải seller 10 cửa hàng thật.
- Lượt Java đầy đủ: **1.359 bài, 1.357 qua, 2 bỏ qua theo nền tảng, không lỗi**. Sau đó hoàn thiện guard chỉ ký thử thách xác thực và kiểm tra giữ cache đúng barcode; lượt tập trung xác thực/GS1/CryptoPro/cooldown/hiển thị có **69 bài qua**. Windows sẽ chạy lại toàn bộ trên đúng commit cuối để xác nhận thay đổi sau lượt đầy đủ này.
- Hợp đồng công cụ phát hành: **53/53 qua** trên thay đổi hiện tại. Bổ sung yêu cầu kiểm tra nâng cấp bộ cài từ Published 1.4.10 và 1.4.11.
- Một lượt đầy đủ trước đó có 1 lỗi timeout của mock HTTP Ozon với deadline 500 ms. Đã điều chỉnh timeout của transport kiểm thử hợp đồng (không thay timeout production), giữ kiểm tra không gửi lại mutation mơ hồ; bài đó đã qua trong lượt tập trung.

## Chưa được chứng minh

Chưa có phiên seller để thử API production, CryptoPro/PIN thật, đơn GS1, GTIN, gán KIZ hoặc mua mã thật. Không gửi email, không mua mã và không dùng dữ liệu KIZ thật trong kiểm thử này. Bằng chứng WBCode là release 1.3.8 và UI/mã WCode công khai; không tuyên bố đã chạy WBCode mới nhất với tài khoản seller.

Windows cần chạy workflow hiện có để kiểm tra launcher, DPAPI, bố cục/DPI, dữ liệu 10 cửa hàng và nâng cấp từ các bộ cài cũ. Kết quả Linux JavaFX không thay thế các kiểm tra Windows. Bản mới chưa được gắn Published/Latest trong lúc đang kiểm tra; điều kiện phát hành license thật vẫn được giữ trong công cụ phát hành.
