# VNCode 1.5.2

- Đăng ký Znack: tiếp tục tự động các thẻ chắc chắn chưa gửi; không yêu cầu bấm Đăng ký lần thứ hai. Giữ nguyên danh tính và kiểm tra kết quả trước khi thử tiếp các yêu cầu chưa rõ kết quả.
- Chọn toàn bộ thẻ không làm đứng giao diện; báo cáo lưu và đối chiếu toàn bộ lô, gồm cả các thẻ ngoài trang đang xem.
- Trạng thái đăng ký và cập nhật GTIN có lý do rõ ràng, số đã cập nhật, chưa cập nhật, chưa có GTIN và đang chờ đọc lại. Viên thuốc hoàn tất tự ẩn.
- Sản phẩm WB/Ozon: bảng sửa TN VED, giấy tờ, chất liệu, giới tính và thương hiệu có đề xuất từ dữ liệu cửa hàng. Bấm Áp dụng đóng bảng và chạy nền; viên thuốc hiển thị tổng, còn lại, đã sửa, thất bại và đang chờ sàn xác nhận. Chỉ ghi nhận thành công sau khi đọc lại kết quả từ sàn.
- Giấy tờ Ozon đã tải lên được giữ nguyên danh tính khi chọn lại. Đề xuất không tự tạo thương hiệu, thành phần hoặc giấy tờ khi chưa có dữ liệu đáng tin cậy.
- Cải thiện đồng bộ Catalog khi bị giới hạn yêu cầu, chẩn đoán chữ ký/KIZ, cấu hình thương hiệu Ozon, trạng thái KIZ Mapping và bố cục hộp thoại.

Bản cập nhật được phát hành theo yêu cầu chủ ứng dụng sau kiểm thử tự động và bộ cài Windows. Ký thật bằng CryptoPro, tạo thẻ Catalog thật và ghi GTIN trên tài khoản production chưa được nghiệm thu trong lượt này.
