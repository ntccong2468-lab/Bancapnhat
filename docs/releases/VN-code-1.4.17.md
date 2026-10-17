# VNCode 1.4.17

- Đăng ký Znack chỉ xác nhận đã xuất bản khi đọc lại được đúng GTIN, mã thẻ và trạng thái `published` trên Catalog. Nhận feed, gửi chữ ký và đọc nguồn có trạng thái riêng.
- Mẫu đăng ký tự động mặc định dùng cấu trúc một thẻ Catalog đã xuất bản và schema hiện tại; dữ liệu luôn lấy từ đúng sản phẩm/biến thể được chọn. Tự bỏ khoảng trắng ở hai đầu, giữ thông tin bên trong.
- Giữ danh sách đã chọn khi xử lý lô, dùng thuộc tính mới sau sửa hàng loạt và không hiện lặp yêu cầu xác nhận cùng giấy tờ. Kiểm tra thương hiệu, chất liệu, giới tính và gợi ý TN VED cho cả lô trước khi cấp GTIN.
- Feed đã được nhận vẫn tiếp tục đọc lại sau timeout/quota API; không cấp thêm GTIN hoặc tự gửi lại feed. Thẻ đã duyệt nhưng phản hồi feed thiếu mã thẻ được tra lại bằng đúng GTIN để ký tiếp. Thẻ chờ duyệt, tài liệu ký tạm thời chưa có, feed kết thúc nhưng chưa tạo thẻ và yêu cầu bị gián đoạn được báo rõ. Không tự gửi lại giao dịch hoặc chữ ký chưa rõ kết quả. WB/Ozon cho thử lại bằng GTIN đang có hoặc chọn GTIN mới sau khi Catalog xác nhận feed cũ bị từ chối, giữ lịch sử yêu cầu cũ.
- Sau khi thẻ mới được xác minh xuất bản, GTIN được đưa vào hàng đợi cho đúng biến thể đã đăng ký; Ozon kiểm tra thêm SKU, tên, màu và size. Chỉ đổi thẻ đang dùng sau khi đọc lại sàn thành công. Danh sách hiển thị ngay barcode/GTIN vừa xác minh và tách trạng thái chờ cập nhật lên sàn khỏi thẻ đã hoàn tất.
- Màn đăng ký Znack có bốn nút chính trên cùng hàng theo ảnh đã duyệt. Nút Đăng ký và chọn tất cả nằm dưới bảng; cập nhật GTIN chạy nền với viên thuốc trạng thái ở giữa phía dưới bảng.
- Sửa hàng loạt sản phẩm chạy nền sau Áp dụng, hiện tiến độ và kết quả đọc lại. Sao chép TN VED kiểm tra clipboard và hiện xác nhận.
- Kho KIZ hết mã vẫn nhận lô mới trong cùng danh mục, kể cả file trùng tên; số lượng cập nhật ngay, không khôi phục lại mã đã gán.
- Bổ sung bộ lọc chất liệu TN VED, thanh gợi ý cuộn ngang, placeholder tải nhẹ; giữ phần ngân sách HTTP cho luồng FBS liên tục. Hướng dẫn có khung ảnh/video ổn định, âm lượng và mở bài trong trình duyệt.

Bản đang được chuẩn bị. Kiểm thử tự động và đọc dữ liệu thật được ghi riêng với kiểm thử ghi/ký thật trên máy seller; không coi chúng là kết quả nghiệm thu CryptoPro hoặc license production.
