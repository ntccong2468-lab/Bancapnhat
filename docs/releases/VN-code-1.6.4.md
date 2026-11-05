# VN code 1.6.4

- Sửa PDF Ozon thiếu nhãn kết hợp: giữ KIZ đã dành cho đúng đơn, kể cả khi Ozon từ chối mã nhưng vẫn cấp vận đơn. In lại sử dụng đúng mã đã lưu và giữ riêng trạng thái phản hồi của Ozon.
- Giữ tải vận đơn theo lô, sắp xếp vận đơn và KIZ đúng đơn. Một trang chưa nhận diện được không làm mất nhãn kết hợp của các đơn đã nhận diện. Cải thiện độ rõ DataMatrix ở 203 dpi.
- Sửa nhận diện và hiển thị trạng thái thẻ Catalog; cập nhật quan sát mới, đọc bổ sung thẻ đã biết, tách thẻ chờ ký khỏi lỗi và giữ chi tiết cần xử lý.
- Thống nhất đối chiếu màu/size khi ghép GTIN và cập nhật WB, giữ màu sản phẩm khi API trả dạng chuỗi.
- Đơn giản hóa đăng ký Znack ở WB/Ozon: một vùng tiến độ, báo cáo tập trung vào thao tác người dùng; lịch sử và chức năng chi tiết vẫn truy cập được.

Cập nhật kế thừa 1.6.3, giữ dữ liệu cửa hàng, kho KIZ, liên kết GTIN và lịch sử in. Nếu đơn chưa có KIZ phù hợp hoặc chưa xác định được trang vận đơn, báo cáo vẫn nêu rõ phần thiếu để bổ sung.
