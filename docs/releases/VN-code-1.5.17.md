# VN code 1.5.17

- Giữ dữ liệu sản phẩm, thuộc tính, giấy tờ, GTIN, thẻ Znack, kho KIZ và lịch sử in khi cập nhật hoặc mở lại ứng dụng. Trang Znack dùng lại danh sách đã đồng bộ, kể cả khi lần làm mới sau lỗi hoặc kết quả đã cũ; hiển thị thời điểm đã đọc.
- Mở rộng khung danh sách Sản phẩm, Đăng ký Znack và FBS Ozon theo khung FBS WB, tận dụng chiều rộng và chiều cao vùng làm việc; giữ cỡ chữ, ảnh và chiều cao hàng.
- WB nhận diện GTIN đã có trên barcode của đúng size, cả khi chưa có đăng ký hoặc quy tắc danh mục trong VNCode. Ozon giữ GTIN cho luồng mua/in khi thẻ đã xuất bản nhưng chưa ghi barcode về sàn.
- Nhãn riêng Ozon dùng API tạo v3 và đọc kết quả v2. Chờ tác vụ tạo nhãn lâu hơn, lưu mã tác vụ để tiếp tục; phục hồi tác vụ bị Ozon xác nhận thất bại và dùng lại tác vụ cũ khi chỉ lỗi ghi file.
- Lưu dấu mốc hoàn tất catalog Ozon cùng dữ liệu và cursor; đường tổng quan dự phòng dùng lại kết quả đã đủ và tiếp tục lượt đang dở từ vị trí đã lưu. Giữ đồng bộ thủ công và lịch đồng bộ nền.

Kế thừa toàn bộ chức năng của 1.5.16. Giữ thư mục dữ liệu và định danh cài đặt hiện có. Dữ liệu lịch sử vẫn được kiểm tra theo điều kiện hiện hành trước các thao tác ghi mới; không tự tạo lại yêu cầu chưa rõ kết quả.
