# VN Code 1.6.6

Bản này kế thừa đầy đủ 1.6.5 và gộp các sửa Ozon, Znack/GTIN WB, báo cáo và giao diện sau bản đó.

- Đăng ký Znack: từng thẻ chuyển sang **Đang xử lý** ngay khi Catalog trả và app lưu `feed_id`. App tiếp tục đọc kết quả của yêu cầu cũ; không tạo trùng khi chưa rõ kết quả hoặc khi mở lại ứng dụng.
- WB: biến thể đã có barcode GTIN 046 được đưa vào **Hoàn tất**. Thẻ Catalog đã công bố, ghép đúng biến thể và còn thiếu GTIN trên WB được đưa vào hàng đợi cập nhật nền, giữ barcode cũ và đọc lại kết quả.
- Khắc phục cạnh tranh ghi SQLite có thể gây `SYNC_STORAGE_BUSY` trước khi gửi đăng ký. Cho phép thêm các thẻ mới đã duyệt vào lô đang chạy mà vẫn giữ danh tính và chống đăng ký trùng.
- Ozon: tự thay KIZ seller nhập theo đúng danh mục khi có phản hồi từ chối xác định; nhường lượt để các đơn khác tiếp tục rồi quay lại. Bỏ nút Thay KIZ thủ công, cập nhật KN3 theo kết quả đọc lại.
- PDF Ozon: kiểm tra lại KIZ và dữ liệu sản phẩm sau bước chuẩn bị, đọc bổ sung barcode sản phẩm còn thiếu theo lô. Giữ vận đơn Ozon gốc, sắp xếp cùng quy tắc WB và ghép nhãn KIZ đúng đơn, đúng số lượng đã gắn.
- Khắc phục lỗi in riêng vận đơn Ozon của đơn không cần KIZ khi không có ngữ cảnh in theo lô; không yêu cầu metadata nhãn kết hợp cho đơn chỉ in vận đơn.
- Báo rõ từng đơn chỉ có vận đơn khi chưa có KIZ kết hợp; không tính một PDF vận đơn thành đã in đủ nhãn kết hợp. Giữ nguyên quy tắc nguồn KIZ và đối chiếu danh tính, không tự tạo mã khi kho không có mã phù hợp.
- Báo cáo GTIN dùng tổng phạm vi đã chọn, tách thẻ chưa ghép GTIN khỏi các lượt cập nhật cần xử lý. Lỗi kích hoạt liên kết cục bộ không phủ nhận barcode đã được sàn xác nhận; chi tiết lỗi vẫn được giữ trong báo cáo.
- Danh sách tiếp tục làm mới khi tác vụ gửi GTIN đã kết thúc nhưng còn chờ sàn xác nhận. Kiểm tra lại bố cục, hai nền sáng/tối và các ngôn ngữ; thống nhất nhãn tài chính và thanh tiến độ.

Giữ dữ liệu, lịch sử in, cấu hình cửa hàng và cơ chế nâng cấp hiện tại. Các biên nhận kiểm thử và nguồn đóng gói đi kèm bản phát hành; kiểm thử mô phỏng không thay thế việc xác nhận giao dịch thực tế trên sàn.
