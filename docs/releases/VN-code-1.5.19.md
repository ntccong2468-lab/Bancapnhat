# VN code 1.5.19

- Sửa ô chọn tất cả đơn FBS Ozon: chọn toàn bộ đơn theo bộ lọc qua các trang, không chỉ 100 đơn đang hiển thị. Bỏ chọn áp dụng cho toàn bộ lựa chọn; trạng thái tích và số lượng phản ánh đúng cả lô.
- Tăng tốc lấy nhãn Ozon khi in tất cả: gộp nhãn lẻ đã hoàn tất cùng đơn mới vào tác vụ chung tối đa 1.000 đơn, thay cho tải lại từng nhãn lẻ. Lô lớn hơn tự chia tiếp; giữ đúng mã đơn khi thứ tự trang PDF khác thứ tự yêu cầu.
- Giảm thao tác dữ liệu lặp khi chuẩn bị nhãn và đọc trước chi tiết đơn trong giới hạn kết nối hiện có. Giữ thứ tự giữ KIZ và các giới hạn yêu cầu của sàn.
- Giữ riêng nhãn cần xác minh danh tính; bảo toàn trạng thái cách ly khi tải xong để tránh gộp rồi tách lặp. Tác vụ chưa xác định kết quả không được tự tạo lại; in lại nhãn đã có vẫn dùng kết quả lưu khi không được tạo tác vụ mới.

Kế thừa toàn bộ dữ liệu, sửa mục Sản phẩm và tối ưu PDF WB/Ozon của 1.5.18. Giữ định danh cài đặt, thư mục dữ liệu và lịch sử hiện có.
