# VNCode 1.4.13

Bản sửa đồng bộ sản phẩm, Znack/GTIN và kho KIZ seller nhập, kế tiếp 1.4.12.

- UI và tác vụ nền cùng nhận kết quả của một lượt đọc Catalog đang chạy, tránh việc màn hình bị bỏ lại danh sách trống khi đồng bộ lúc khởi động.
- Dùng chung nguồn Catalog cho các màn hình; đọc theo tài khoản đã xác thực và phân trang, không chỉ danh sách nhóm `lp` hoặc cache đăng ký local.
- Máy mới có thể lấy API key Catalog qua chứng thư CryptoPro đã cấu hình; bản sao lưu chứa key DPAPI của Windows khác sẽ xác thực lại và lưu key riêng trên máy hiện tại. Không cần đăng nhập trình duyệt; mỗi máy vẫn cần chứng thư/khóa riêng hoạt động.
- Thẻ đã xuất bản không bị ẩn khỏi nguồn GTIN chỉ vì thiếu metadata giấy tờ. Kiểm tra giấy tờ cho mua/đưa mã vào lưu thông vẫn được giữ đúng bước.
- Ghép GTIN qua chỉ mục theo đúng biến thể; kiểm tra product ID, offer ID và SKU Ozon trước khi ghi, rồi đọc lại để xác nhận. Tác vụ GTIN riêng không đợi API giá hoặc từ điển thuộc tính không liên quan.
- Tách hàng đợi Znack/GS1 khỏi tác vụ nền khác, hiển thị bước/số lượng và xử lý timeout. Lô Catalog lỗi không bị gọi lại cho từng GTIN; dòng ghi/đọc lại lỗi nhiều lần chuyển sang Cần xử lý với lý do cụ thể.
- Kho KIZ nhập theo danh mục dùng ngay, không kiểm tra Znack/INN/chứng thư và không yêu cầu GTIN mã nhập trùng GTIN sản phẩm. Bỏ nút kiểm tra Znack trong kho; giữ FIFO, chống trùng, reservation và lịch sử đã dùng trên WB/Ozon/FBO.
- Khi kho category thiếu, chỉ lượng mua bù mới cần đúng GTIN đã xác minh của sản phẩm. Nhập file không tự tạo chứng cứ Catalog hoặc đánh dấu giả đã đưa mã vào lưu thông.
- Báo cáo tác vụ hiển thị từng GTIN/thẻ và sản phẩm, trạng thái/loại lỗi, kể cả nguồn đồng bộ mới.

Kho KIZ vẫn riêng từng máy; tránh cấp phát cùng tập mã trên hai kho độc lập. Các thay đổi được đối chiếu ghi chú WBCode Latest 1.3.9 và mã WCode công khai; không có mã nguồn nội bộ 1.3.9 để xác nhận tương đương toàn bộ. Nghiệm thu trên tài khoản seller/máy thứ hai thực tế và nghiệm thu license production đúng phiên bản vẫn chưa được thực hiện.
