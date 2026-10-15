# VNCode 1.4.10

Cải thiện kích hoạt license trên máy thứ hai, Wi-Fi và điểm phát di động bằng cơ chế tự chọn kết nối.

- Tự kiểm tra API bằng yêu cầu rỗng trước khi gửi thông tin tài khoản; nếu cần, chuyển HTTP/1.1 và ưu tiên IPv4, vẫn giữ IPv6.
- Hỗ trợ API dự phòng `license.vncode.id.vn`. App xác minh chữ ký issuer, nonce, hostname và thời hạn trước khi gửi thông tin license. Quản trị viên cần triển khai Portal 0.3.6 và Worker gateway kèm theo để bật đường này.
- Khi mất phản hồi kích hoạt/kiểm tra license, thử lại tối đa một lần bằng challenge và chữ ký thiết bị mới. Giữ cùng danh tính máy; máy chủ không cộng lại ngày hoặc chiếm thêm thiết bị khi lần trước đã thành công.
- Thêm **Kiểm tra kết nối** và **Sao chép kết quả** trong mục License. Báo cáo dùng HTTP client của app, thể hiện giao thức, giai đoạn và thời gian chờ; không chứa Gmail, khóa, token, IP hoặc dữ liệu proxy.
- Có thể chọn ưu tiên kết nối tương thích và lưu riêng trên từng máy. Kiểm tra mạng không xóa license hoặc tạo lại danh tính máy.

Giữ HTTPS, chữ ký Ed25519, giới hạn thiết bị và offline đã cấp. Không gắn license theo IP hoặc quốc gia. API dự phòng dùng chung dữ liệu và khóa ký `vncode-2` với website.

Chưa xác nhận kích hoạt thật trên chính máy thứ hai/nhà mạng của người dùng. Hai API cùng thuộc Cloudflare, nên vẫn phụ thuộc khả năng nhà mạng truy cập Cloudflare. Xem hướng dẫn trong gói Portal 0.3.6 để triển khai và kiểm tra.

Nếu đang dùng 1.4.4–1.4.7, tải EXE, đóng VNCode rồi cài thủ công một lần để thay updater cũ; giữ dữ liệu và license hiện có.
