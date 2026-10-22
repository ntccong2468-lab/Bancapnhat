# VNCode 1.5.12

- Giao diện nền đen theo ảnh tham chiếu: nút xám, thao tác chính màu tím, logo VN nền đen chữ trắng. Căn lại Đăng ký Znack, Sản phẩm, chọn TN VED, GS1 RUS và Cấu hình Znack. Kèm font Inter đậm để chữ và nút hiển thị nhất quán.
- Cấu hình Znack có 5 tab: Thông tin, Lịch sử mua KIZ, Đã xóa, Cài đặt và Kho KIZ. Seller tải tệp vào Kho KIZ hiện có; danh mục và mã đã lưu tiếp tục được giữ. Sửa cấu hình có chế độ Chỉnh sửa/Hủy; khóa API được che và phản hồi lỗi vẫn hiện khi có thao tác.
- Đăng ký WB/Ozon tiếp tục từng thẻ từ hàng đợi đã lưu. Phân biệt yêu cầu đã gửi, thẻ đã đọc được trên Catalog, chờ duyệt/ký và Catalog từ chối; không tính HTTP nhận yêu cầu là đã tạo thẻ thành công. Giữ GTIN và giấy tờ của đúng giao dịch, dùng TN VED từ sản phẩm trên sàn, không đổi mã để khớp giấy tờ.
- Ozon có thể tự sửa một lần trường tùy chọn ảnh hoặc `is_kit` khi Catalog trả lỗi xác định và cho phép sửa, rồi gửi lại cùng GTIN. Kết quả gửi chưa rõ được đối soát trước khi tiếp tục.
- Catalog lớn có đường quét theo phiên bản khi danh sách vượt cửa sổ 10.000, lưu tiến trình và đọc thẻ theo nhóm tối đa 25 ID. Lỗi mạng/giới hạn yêu cầu giữ dữ liệu và điểm quét để tiếp tục; không suy đoán thẻ không tồn tại từ trang đọc thiếu.
- Đồng bộ GTIN dùng lô tối đa 3.000 thẻ WB và 100 cặp mã Ozon theo giới hạn đang triển khai, giữ barcode cũ và đọc lại từng biến thể. WB giữ khoảng nghỉ 15 giây giữa các lô. Báo cáo tách số đã xác nhận, đang chờ và chưa cập nhật cùng lý do.
- Mua KIZ kiểm tra số mã sẵn dùng trước khi mua thêm, dùng UUID hợp lệ và giữ yêu cầu đã lưu khi khởi động lại hoặc mất phản hồi để tránh mua lặp. Luồng kiểm thử gồm kho đã có mã, mua phần thiếu, nhận mã, đưa vào lưu thông và tiếp tục yêu cầu đang dở.

Kiểm thử dùng dữ liệu tổng hợp, SQLite và API giả lập qua các planner/adapter thật của ứng dụng, gồm lô 10.000 GTIN WB/Ozon. Kiểm tra bộ cài Windows và kết quả toàn bộ kiểm thử được ghi trong `build-info.json` và gói verification của bản phát hành. Việc chạy kiểm thử không xác nhận đã mua KIZ có phí, ký bằng chứng thư CryptoPro hay ghi thẻ/GTIN vào tài khoản production của seller.

Khi ứng dụng đóng hoàn toàn hoặc mất mạng, yêu cầu được giữ để tiếp tục khi mở app/có mạng; máy không thể gửi API trong lúc không chạy. Bản nâng cấp giữ định danh cài đặt và dữ liệu hiện có. Bộ cài chưa có chữ ký Authenticode.
