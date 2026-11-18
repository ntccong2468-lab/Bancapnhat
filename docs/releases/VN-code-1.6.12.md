# VN Code 1.6.12

- Mở app và nâng cấp ổn định hơn. VN Code dùng lại bản sao lưu của trình cập nhật khi phiên bản đích, cơ sở dữ liệu, WAL và mã kiểm tra khớp. App tránh khởi tạo cơ sở dữ liệu hai lần trong cùng lượt mở, hiện bước đang chạy, thời gian chờ và tùy chọn sao chép chẩn đoán khi chờ lâu.
- Gia nhập GS1 nhanh hơn. VN Code giữ dữ liệu từ National Catalog, điền thêm tên pháp nhân và OGRN/OGRNIP đã xác minh từ chứng thư cùng INN. Hồ sơ đủ dữ liệu mở thẳng màn duyệt với nút **Áp dụng**. Hồ sơ thiếu mở tại mục cần bổ sung. Seller duyệt và xác nhận ký điện tử trước khi gửi.
- Thư xin tiền tố **01.04.01** đưa OGRN/OGRNIP và GLN vào phần thông tin doanh nghiệp khi Catalog cung cấp. Tiêu đề ghi INN để thuận tiện đối chiếu thư. Seller có thể xem trước nội dung và PDF trước khi gửi.
- Thẻ GS1 dùng một nút chính theo trạng thái cửa hàng. **Đăng ký GS1** mở hồ sơ gia nhập. **Xin tiền tố GS1 bổ sung** dành cho thành viên đã xác minh. Hồ sơ đã nộp hiện trạng thái chờ duyệt.
- Sáu mục **Tài khoản GS1 & Честный знак** có nhãn màu và chi tiết theo nguồn, gồm thời điểm đọc, số dư, hạn thành viên, số GTIN hoặc mã lỗi. VN Code dùng cùng địa chỉ Честный знак mặc định ở đồng bộ và màn GS1, đồng thời giữ lượt kiểm tra thỏa thuận khi hồ sơ cửa hàng đang tải.
