# VNCode 1.4.9

Cải thiện kết nối license khi dùng điểm phát sóng di động và chuyển mạng Việt Nam/Nga.

- Thử địa chỉ kết nối dự phòng do DNS hệ thống trả về nếu một địa chỉ không kết nối được; tăng thời gian chờ phản hồi trên đường truyền chậm.
- Giữ khóa gắn với thiết bị, không gắn với IP, Wi-Fi, SIM hay quốc gia. Đổi mạng trên cùng máy không cần cấp lại license; máy khác vẫn theo giới hạn quản trị viên cấp.
- Không tự gửi lặp yêu cầu kích hoạt đã truyền đi khi mất phản hồi. Có thể thử kích hoạt lại cùng khóa trên cùng thiết bị; không cộng thêm ngày hoặc chiếm thêm máy.
- Phân biệt yêu cầu xác minh trình duyệt của Cloudflare với khóa bị thu hồi. Thông báo timeout hướng dẫn kiểm tra kết nối tới vncode.id.vn.
- Giữ bản sửa tải cập nhật đạt 100% từ 1.4.8. Nếu đang dùng 1.4.4–1.4.7, tải EXE, đóng VNCode rồi cài thủ công một lần; không xóa dữ liệu trước.

Không cần cấp lại khóa, thay khóa ký hoặc triển khai lại Portal 0.3.5. Giữ HTTPS, chữ ký license, giới hạn thiết bị và thời gian offline đã cấp. Ngày giờ máy cần đúng; múi giờ Việt Nam hoặc Nga đều dùng được.

Kiểm thử chính sách mạng dùng dữ liệu mạng mô phỏng; không đại diện việc đo kết nối từ nhà mạng Việt Nam/Nga. Nếu mạng chặn kết nối HTTPS hoặc có rule Cloudflare theo IP/quốc gia, quản trị viên cần xử lý đúng mạng/rule đó. Thay đổi ứng dụng không bảo đảm vượt qua việc chặn kết nối.
