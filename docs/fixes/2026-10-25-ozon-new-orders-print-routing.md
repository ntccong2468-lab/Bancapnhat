# FBS Ozon: Đơn mới → in thành công → Đang giao hàng

Yêu cầu bổ sung trong lúc đóng gói 1.5.17: nhận đơn xong mở Đơn mới; In tất cả xong mở Đang giao hàng để tách các đơn mới nhận thêm và chỉ in những đơn chưa in. Yêu cầu phát hành Latest trước đó vẫn còn; gộp phần sửa này rồi tiếp tục kiểm tra/đóng gói.

## Hành vi

- Nhận đơn thành công vẫn chuyển đến `packingOrdersTab` mang nhãn Đơn mới. Các đơn được Ozon chấp nhận có trạng thái `awaiting_deliver`; trạng thái này được chia theo receipt in của đúng cửa hàng.
- In tất cả vẫn lấy toàn bộ đơn chưa in theo bộ lọc, qua mọi trang, không lấy lại các đơn đã có receipt in thành công. `OzonPrintBundleService` chỉ lưu receipt sau khi PDF nhãn và PDF danh sách đã được xuất hoàn tất.
- Sửa callback xuất PDF thành công: đặt trang Đang giao hàng về đầu, tự chọn `printedOrdersTab` và đọc lại dữ liệu lưu. Dùng sự kiện chọn tab hiện có để tránh gọi tải lại hai lần.
- Đơn mới đồng bộ/nhận thêm tiếp tục vào Đơn mới. Việc làm mới danh sách không tự kéo người dùng khỏi Đang giao hàng; In tất cả lần sau vẫn chỉ chứa các đơn chưa in.
- Hủy xác nhận hoặc xuất PDF lỗi giữ nguyên tab Đơn mới và không thêm receipt in thành công. Lịch sử in vẫn tách đúng đơn sau khi đọc lại kho dữ liệu.
- Chuyển tab này là nhóm công việc theo lịch sử in; giữ trạng thái sàn. Tab Đã bàn giao riêng vẫn hiện các đơn `delivering`.

Thêm điểm tiêm nội bộ cho thao tác mở file đã xuất để bài UI không mở chương trình PDF thật. Luồng production vẫn dùng Desktop.open như trước; cửa sổ dữ liệu/luồng gọi giữ kiểm tra shop, lease và kết quả tác vụ.

## Kiểm chứng trước full suite

**34/34 ca đạt**, không lỗi/thất bại/bỏ qua: `OzonPrintedQueueUiTest`, `OzonBulkUiTest`, `OzonFbsPrintStateTest`, `OzonPrintBundleServiceTest`, `OzonManualSyncUiTest`.

- Nút In tất cả thực xác nhận đúng một đơn NEW, loại OLD đã in; hủy giữ tab Đơn mới.
- Tác vụ in bất đồng bộ thực của controller nhận kết quả fixture tạo đủ hai PDF iText và lưu receipt. Sau thành công UI tự chuyển Đang giao hàng, ẩn In tất cả ở tab này; hàng NEW rời danh sách mới và xuất hiện cùng OLD.
- Thêm ARRIVED sau đó, làm mới: ARRIVED ở Đơn mới, OLD/NEW ở Đang giao hàng. Bấm In tất cả lại vẫn chỉ xác nhận một đơn; khóa danh sách xuất trả riêng ARRIVED.
- Tác vụ PDF lỗi hiện thông báo, giữ NEW trong Đơn mới, không tạo receipt.
- Bài nhận hàng loạt bấm nút thật, adapter Ozon giả lập tách đơn, xác nhận đã chọn Đơn mới sau khi nhận. Các bài PDF backend kiểm tra lỗi không chuyển các đơn của lô sang đã in.

Dữ liệu tổng hợp, SQLite thật, JavaFX/Xvfb và PDF fixture; không nhận/in đơn seller production hoặc gán/tiêu/mua KIZ thật trong lượt này. Log `/tmp/vncode-1517-new-orders-print-queue.log`. Bộ cài nguồn trước yêu cầu bổ sung đã hủy; chỉ phát hành từ nguồn chứa phần sửa này sau full suite và kiểm tra Windows mới.
