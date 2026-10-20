# VNCode 1.5.5

- Đăng ký Znack WB/Ozon: tự điền dữ liệu xác định được từ sản phẩm, danh mục Catalog hiện hành và giấy tờ đã xác minh. Mẫu mặc định đối chiếu cấu trúc 24 thẻ đã ký/xuất bản; nước sản xuất mặc định hiển thị «Россия» khi sản phẩm chưa khai nước khác.
- Kiểm tra đủ trường của toàn bộ sản phẩm đã chọn trước khi cấp GTIN; chỉ yêu cầu bổ sung dữ liệu còn thiếu hoặc mâu thuẫn. Giữ kiểm tra TN VED, phạm vi giấy tờ, size và trường phụ thuộc.
- Chỉ tăng số thẻ đã tạo khi đọc được đúng thẻ trên Catalog. Phân biệt đã nhận yêu cầu, chờ đọc thẻ, bản nháp, đang duyệt, chờ ký, đã xuất bản và lỗi.
- Bộ lọc Đang xử lý tự làm mới, giữ lựa chọn/vị trí cuộn; yêu cầu đã gửi được đối soát tiếp mà không phải bấm Đăng ký lần hai. Giữ hàng đợi khi mất mạng hoặc mở lại app, tránh cấp GTIN/gửi thẻ lặp khi kết quả chưa rõ.
- Ký tối đa 10 thẻ/lô, giữ kiểm tra và kết quả riêng từng thẻ; phân biệt lỗi CryptoPro cục bộ với lỗi mạng/xác thực CRPT.
- Hoàn thiện Đồng bộ & map GTIN Ozon: đọc sản phẩm, đối chiếu Catalog và barcode theo đúng SKU/biến thể; báo lý do từng sản phẩm chưa map được.
- Tiến trình báo số đã xác nhận, còn lại và thất bại. Viên thuốc sửa lỗi Catalog tự ẩn sau hoàn tất; báo cáo vẫn được lưu.
- Khởi động: yêu cầu khôi phục cửa sổ của tiến trình đang giữ dữ liệu; báo rõ lý do khi không khôi phục được. Bảo vệ database không đọc được khỏi bị thay thế khi phục hồi.
- In Ozon: khi nhãn kết hợp đã bao phủ đủ KIZ/barcode, bỏ nhãn sản phẩm trùng; đơn một sản phẩm giữ vận đơn và nhãn kết hợp. Giữ những trang vận chuyển hoặc bố cục chưa xác định.

Kế thừa dữ liệu, schema 6, ưu tiên FBS, kho KIZ, Liquid Glass và các sửa cập nhật/mua KIZ của bản nền 1.5.4. Giữ nguyên kiểm tra license, chữ ký, TLS, checksum và phục hồi dữ liệu.

Chưa nghiệm thu tạo/ký thẻ Catalog thật, ghi GTIN lên sàn qua Windows seller, mua KIZ trả phí hoặc license production trong phiên bản này. Kiểm thử tự động không thay thế nghiệm thu các thao tác này.
