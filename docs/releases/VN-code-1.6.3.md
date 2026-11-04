# VN code 1.6.3

- Áp dụng giao diện sáng/tối đã duyệt: thanh điều hướng gọn, danh sách ưu tiên diện tích, bảng màu xanh trung tính và nút quản lý cửa hàng cạnh tên cửa hàng.
- Tổng quan bỏ ba ô số liệu và khoảng trống; chuyển thẳng đến các công việc hiện có. Thông tin đồng bộ vẫn xem được trong menu tác vụ.
- Xem chi tiết đơn Ozon/sản phẩm ở khung bên phải, giữ nguyên danh sách. Bản nháp cấu hình Znack được giữ riêng theo cửa hàng; có tùy chọn giảm chuyển động.
- Nhận đơn Ozon: đối soát chung các biên nhận sau khi gửi cả lô, kiểm tra lại trạng thái trả chậm và giữ phần đang chờ để đồng bộ nền tiếp tục. Không gửi nhận trùng khi chưa biết kết quả.
- Phiếu nhặt hàng Ozon sắp xếp theo cùng cấu hình WB, giữ đúng số vận đơn trên từng dòng. Giữ nhãn vận đơn gốc và nhãn kết hợp; lọc trang barcode sản phẩm OZN/EAN-13 khi đã nhận diện chắc chắn đúng sản phẩm.

Quy định nhận đơn, KIZ bắt buộc và cấp nhãn vẫn do API Ozon quyết định. Mã KIZ thiếu phần mật mã hoặc sai trạng thái cần được bổ sung đúng mã; ứng dụng không ghi nhận thành công thay cho phản hồi chấp nhận của sàn.

Bản cập nhật giữ dữ liệu cửa hàng, hàng đợi, giấy tờ, GTIN, lịch sử in và thiết lập license. Không yêu cầu đồng bộ lại toàn bộ dữ liệu khi nâng cấp.
