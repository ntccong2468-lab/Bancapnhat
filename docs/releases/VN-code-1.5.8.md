# VNCode 1.5.8

Bản sửa luồng đăng ký Znack WB trên nền 1.5.7. Giữ nguyên màn hình, vị trí/nội dung nút,
cách chọn sản phẩm và các hộp duyệt hiện có; không có màn hình hay nút V3 riêng.

- Lưu dấu vết bền vững trước cấp GTIN, gửi thẻ và gửi chữ ký, dùng chung cho nút đăng ký,
  xử lý theo lô và tác vụ nền. Khôi phục kết quả đã nhận, chặn gửi lặp khi chưa rõ kết quả.
- Giữ chỗ GTIN ngay khi Catalog trả mã; lần tạo thẻ thay thế có mã lượt cấp riêng và giữ
  lịch sử cũ. Lỗi đọc trước khi cấp GTIN không khóa nhầm toàn bộ lượt cấp.
- Đối chiếu đúng thẻ/nội dung đã duyệt trước khi báo công bố; xử lý riêng feed bị từ chối,
  feed mất phản hồi và chữ ký đang chờ đọc lại. Không coi tiếp nhận chữ ký là đã công bố.
- Giữ nguyên dữ liệu, hàng đợi, giấy tờ, cấu hình, cơ chế license, đồng bộ WB và luồng Ozon
  của bản 1.5.7; giữ định danh bộ cài để nâng cấp ứng dụng hiện có.

Đây là ứng viên cập nhật. Cần bộ cài vượt kiểm thử Windows, khởi động native và nâng cấp
bảo toàn dữ liệu trước khi công bố. Chưa nghiệm thu ký CryptoPro/chứng thư hay thao tác
API seller production thật cho thay đổi này. JAR hoặc kiểm thử mô phỏng không thay cho
nghiệm thu các thao tác đó. Không có bộ cài Windows đã công bố ở thời điểm chuẩn bị mã.
