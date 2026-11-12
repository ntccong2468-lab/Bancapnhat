# VN Code 1.6.9

Kế thừa toàn bộ chức năng và dữ liệu của 1.6.8, gộp các sửa về giấy tờ, mua KIZ và đồng bộ sản phẩm WB/Ozon.

- Thêm ngày hết hạn trong cấu hình giấy tờ; kiểm tra ngày nhập và giữ đúng thông tin khi sửa giấy tờ hoặc kết nối cửa hàng.
- Mua KIZ luôn tiếp tục theo dõi lưu thông, không cần bật tùy chọn riêng. Phân biệt rõ đã tải mã, đang đối soát và đã lưu thông; chỉ xác nhận lưu thông sau khi kiểm tra đủ mã.
- Tiếp tục đối soát các lô KIZ cũ bằng hồ sơ đã lưu, giữ mã đã mua và trạng thái sử dụng. Khi mất mạng hoặc chưa rõ kết quả gửi, ứng dụng đối soát trước để tránh mua hoặc gửi chứng từ trùng.
- Nhận diện mã đã lưu thông trước khi kiểm tra dữ liệu dành cho chứng từ mới; giữ nguyên các yêu cầu giấy tờ và nghiệp vụ khi cần gửi mới.
- Đồng bộ sản phẩm WB và nguồn GTIN tiếp tục qua các trang ngắn; Ozon kiểm tra đúng tổng sản phẩm, cursor và trang cuối để tránh bỏ sót danh mục.
- Đọc chi tiết WB theo lô để giảm các lượt tìm riêng từng bài. Báo riêng tổng sản phẩm, chi tiết đã đọc và các sản phẩm chưa đọc được; có nút xem lý do theo từng sản phẩm.
- Hiển thị đúng giấy tờ đang chờ kiểm tra, hợp lệ, bị từ chối hoặc chưa xác minh trên WB/Ozon, có kiểm tra ngày hiệu lực và chủ sở hữu dữ liệu.
- Giữ giấy tờ đã lưu khi phản hồi mới thiếu dữ liệu hoặc sai cấu trúc; không biến lỗi đọc thành danh sách giấy tờ rỗng. Kiểm tra lại giấy tờ Ozon trước khi gán để nhận biết giấy tờ vừa bị từ chối.
- Gọn phần báo cáo đồng bộ sản phẩm, hỗ trợ xuống dòng và xem chi tiết ở cửa sổ nhỏ.

Giữ toàn bộ sửa trước về đăng ký Znack theo lô, cập nhật GTIN, nhận và in FBS Ozon, vận đơn gốc và KIZ kết hợp, phiếu nhặt hàng, Đơn nợ WB, chỉnh sửa sản phẩm, chữ ký điện tử và giao diện sáng/tối.

Sau cập nhật, bấm **Đồng bộ** ở mục Sản phẩm của cửa hàng cần đối chiếu lại; phần chưa đọc được sẽ có lý do riêng. Giữ hồ sơ, lịch sử mua KIZ và các yêu cầu đang theo dõi để ứng dụng tiếp tục đối soát.
