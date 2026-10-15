# VNCode 1.4.10

Thêm kiểm tra kết nối ngay trong app và lựa chọn kết nối tương thích cho máy bị `LICENSE_TIMEOUT` qua điểm phát di động.

- Trong mục **License**, bật **Kết nối tương thích (HTTP/1.1, ưu tiên IPv4)** rồi thử lại khóa hiện tại. IPv6 vẫn được giữ làm dự phòng. Lựa chọn được lưu riêng trên từng máy; không thay chính sách số máy hay cấp lại khóa.
- **Kiểm tra kết nối** dùng chính Java/HTTP client của VNCode, so sánh chế độ tự động và tương thích. Kiểm tra cả GET cấu hình và POST tới API kích hoạt mà không gửi token, Gmail, khóa hay chữ ký thiết bị. HTTP 401 tại bước POST này là phản hồi đúng.
- **Sao chép kết quả** cung cấp giao thức HTTP, giai đoạn DNS/TCP/TLS/gửi/chờ phản hồi và thời gian chờ. Không đưa nội dung máy chủ, địa chỉ proxy, IP hay thông tin license vào báo cáo.
- Kiểm tra mạng và thay lựa chọn kết nối không xóa/đổi license, không cộng ngày và không chiếm thêm thiết bị. Yêu cầu kích hoạt đã gửi vẫn không tự lặp khi mất phản hồi.

Giữ HTTPS, kiểm tra chứng chỉ, chữ ký Ed25519, giới hạn thiết bị và thời gian offline. Portal 0.3.5/key ID vncode-2 giữ nguyên; không cần triển khai lại website.

Đây là lựa chọn tương thích và công cụ xác định lỗi cụ thể. Chưa xác nhận kích hoạt thật trên chính máy thứ hai/nhà mạng của người dùng. Nếu cả hai chế độ cùng lỗi, gửi kết quả kiểm tra từ app để xác định bước tiếp theo; không gửi khóa license. Chạy curl thành công chưa chứng minh Java trong app kết nối được.

Nếu đang dùng 1.4.4–1.4.7, tải EXE, đóng VNCode rồi cài thủ công một lần để thay bản updater cũ. Giữ dữ liệu và khóa hiện có.
