# VN code 1.6.3

- Áp dụng giao diện sáng/tối đã duyệt: thanh điều hướng gọn, danh sách ưu tiên diện tích, bảng màu xanh trung tính và nút quản lý cửa hàng cạnh tên cửa hàng.
- Tổng quan bỏ ba ô số liệu và khoảng trống; chuyển thẳng đến các công việc hiện có. Thông tin đồng bộ vẫn xem được trong menu tác vụ.
- Xem chi tiết đơn Ozon/sản phẩm ở khung bên phải, giữ nguyên danh sách. Bản nháp cấu hình Znack được giữ riêng theo cửa hàng; có tùy chọn giảm chuyển động.
- Nhận đơn Ozon: đối soát chung các biên nhận sau khi gửi cả lô, kiểm tra lại trạng thái trả chậm và giữ phần đang chờ để đồng bộ nền tiếp tục. Không gửi nhận trùng khi chưa biết kết quả.
- PDF Ozon tải theo lô, sắp xếp cùng cấu hình WB trên toàn bộ lô và đặt nhãn KIZ kết hợp ngay sau vận đơn gốc của đúng đơn. Bỏ trang barcode sản phẩm OZN/EAN-13 riêng, giữ nguyên nội dung vận đơn Ozon. Phiếu nhặt hàng giữ đúng số vận đơn trên từng dòng.
- Nếu KIZ không bắt buộc bị từ chối hoặc chưa có mã nhưng Ozon vẫn cho nhận đơn, ứng dụng tiếp tục nhận đơn; một đơn con bị lỗi không chặn các đơn con còn lại. Phản hồi chưa rõ và KIZ bắt buộc vẫn được đối soát đúng trạng thái API.
- KIZ bị Ozon từ chối vẫn có thể in nhãn kết hợp bằng đúng mã đã dành cho đơn, giữ riêng báo cáo từ chối. Kết quả xuất PDF ghi rõ số vận đơn, nhãn KIZ kết hợp và các đơn chưa kèm KIZ.

Quy định nhận đơn, KIZ bắt buộc và cấp nhãn vẫn do API Ozon quyết định. Việc in KIZ không thay đổi kết quả xác minh KIZ của Ozon. Ứng dụng giữ mã đã dành cho đúng đơn và không ghi nhận gán KIZ thành công thay cho phản hồi chấp nhận của sàn.

Bản cập nhật giữ dữ liệu cửa hàng, hàng đợi, giấy tờ, GTIN, lịch sử in và thiết lập license. Không yêu cầu đồng bộ lại toàn bộ dữ liệu khi nâng cấp.
