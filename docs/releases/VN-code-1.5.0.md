# VNCode 1.5.0

- **Liquid Glass:** nút biểu tượng tròn, nút thao tác và điều hướng bo mềm, viền sáng và bóng kính xanh theo mẫu mới. Áp dụng chung cho các trang, hộp thoại, thanh sửa hàng loạt và bảng tin. Tin tức giữ khung riêng, xuống dòng và cuộn được.
- **Độ trong:** đầu trong nhất của thanh chỉnh chỉ còn 0,5% màu nền kính. Chữ và biểu tượng không bị giảm opacity theo nền; giữ màu tương phản trong cả giao diện sáng/tối. Mức đục nhất giữ nền kính kín. Không đặt độ mờ lên toàn cửa sổ.
- **Đăng ký Znack WB:** lô đã chọn đợi mỗi tác vụ ghi trạng thái thực tế rồi mới xử lý thẻ tiếp theo. Thẻ chờ kiểm duyệt, cần ký hoặc bị từ chối được báo đúng trạng thái; việc xếp hàng hoặc đọc nguồn không được tính là đăng ký thành công. Giữ checkpoint và đối chiếu quyền sở hữu trước mỗi thao tác.
- **GTIN WB/Ozon:** giữ xử lý lần lượt từng biến thể và đọc lại kết quả từ sàn trước khi tính thành công. Bỏ thời hạn ba phút của tác vụ đọc danh mục khỏi tác vụ ghi GTIN đã nhận; giữ thời hạn từng yêu cầu API và CryptoPro, ưu tiên dành tài nguyên cho FBS. Phân biệt lỗi chuẩn bị nguồn, hủy tác vụ và lỗi CryptoPro; xem được chi tiết lỗi từ thông báo viên thuốc.
- **Kho KIZ seller:** sửa in/xuất nhãn WB/Ozon cho đơn đã giữ mã trước khi xóa danh mục. Giữ đúng mã với đúng đơn; mã trả lại danh mục đã xóa được lưu trữ thay vì xuất hiện lại thành tồn khả dụng. WB giữ riêng từng lượt mã seller tải lên, kể cả nội dung trùng, khi lập nhãn cho nhiều đơn.
- **Báo cáo nhập KIZ:** tách rõ mã đọc được theo cấu trúc và trạng thái chấp nhận trên WB/Ozon. Nhập tệp không được báo “0 mã hỏng” như thể đã xác minh trên sàn. Trạng thái KIZ đỏ trên sàn chưa được xác minh hàng loạt lúc nhập tệp; phản hồi gán mã theo từng đơn vẫn quyết định kết quả cuối cùng.
- **Cập nhật:** giữ định danh ứng dụng, schema 6 và dữ liệu hiện có; bổ sung kiểm tra nâng cấp từ bộ cài Latest 1.4.19.

**Phạm vi xác minh:** đã đọc thật 388 sản phẩm WB/1.982 biến thể, 175 sản phẩm Ozon và 1.277 thẻ Catalog trong phiên chuẩn bị 1.5.0. Kết quả đọc không thay thế nghiệm thu ghi qua nút trên ứng dụng Windows có license và chứng thư CryptoPro thật. Chưa xác nhận đăng ký/ký thẻ mới hoặc ghi GTIN bằng phiên seller Windows của 1.5.0. Xem [báo cáo yêu cầu](VN-code-1.5.0-request-report.md).

**Cài đặt:** đóng VNCode rồi chạy `VN-code-1.5.0-Windows-x64.exe` khi bộ cài đã có trong bản phát hành. Java được đóng gói sẵn. Bộ cài chưa ký Authenticode.
