# VNCode 1.5.2

- Đăng ký Znack: tiếp tục tự động các thẻ chắc chắn chưa gửi; không yêu cầu bấm Đăng ký lần thứ hai. Giữ nguyên danh tính và kiểm tra kết quả trước khi thử tiếp các yêu cầu chưa rõ kết quả.
- Chọn toàn bộ thẻ không làm đứng giao diện; báo cáo lưu và đối chiếu toàn bộ lô, gồm cả các thẻ ngoài trang đang xem.
- Trạng thái đăng ký và cập nhật GTIN có lý do rõ ràng, số đã cập nhật, chưa cập nhật, chưa có GTIN và đang chờ đọc lại. Viên thuốc hoàn tất tự ẩn.
- Sản phẩm WB/Ozon: bảng sửa TN VED, giấy tờ, chất liệu, giới tính và thương hiệu có đề xuất từ dữ liệu cửa hàng. Bấm Áp dụng đóng bảng và chạy nền; viên thuốc hiển thị tổng, còn lại, đã sửa, thất bại và đang chờ sàn xác nhận. Chỉ ghi nhận thành công sau khi đọc lại kết quả từ sàn.
- Giấy tờ Ozon đã tải lên được giữ nguyên danh tính khi chọn lại. Đề xuất không tự tạo thương hiệu, thành phần hoặc giấy tờ khi chưa có dữ liệu đáng tin cậy.
- Shipment WB/Ozon: chỉ giữ một nút Thay KIZ bên cạnh Kho KIZ. Thay liên tiếp không giới hạn số lần bằng mã sẵn có trong kho đã chọn, khác mã đỏ; tiếp tục đến khi sàn xác nhận hoặc hết mã. Mã đã bị sàn báo đỏ bị loại vĩnh viễn, kể cả khi nhập lại file có mã đó.
- Chuyển giao hàng WB: tự chọn điểm giao gần nhất trong lịch sử, không còn biểu mẫu quốc gia, ngày giao hoặc nhập thành phố và không thêm lần xác nhận phụ. Giữ kiểm tra nhãn, KIZ và xác nhận thông số thực từ WB trước khi chuyển giao.
- Cải thiện đồng bộ Catalog khi bị giới hạn yêu cầu, chẩn đoán chữ ký/KIZ, cấu hình thương hiệu Ozon, trạng thái KIZ Mapping và bố cục hộp thoại.

Bản cập nhật được phát hành theo yêu cầu chủ ứng dụng sau kiểm thử tự động và bộ cài Windows. Ký thật bằng CryptoPro, tạo thẻ Catalog thật và ghi GTIN trên tài khoản production chưa được nghiệm thu trong lượt này.
