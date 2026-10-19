# VNCode 1.5.1

- **Sửa thẻ Znack lỗi:** chọn **Lỗi → Chọn tất cả** để mở báo cáo. Bấm **Chỉnh sửa** để xem thông tin trước/sau, chọn đúng biến thể, giấy tờ và bổ sung trường thiếu, rồi **Áp dụng**. Bảng đóng và viên thuốc dưới giữa danh sách theo dõi từng thẻ.
- **Quy tắc đăng ký API 5.68:** dùng mẫu mặc định v3, nhận đúng nhóm trạng thái giấy tờ Green và trường bắt buộc phụ thuộc. Cảnh báo phạm vi giấy tờ cần seller xác nhận, được tách khỏi lý do từ chối của Catalog. Giữ mã TN VED hợp lệ đang có, không tự đổi theo gợi ý danh mục; dùng size và hệ size thật. Danh mục chỉ nhận size Nga sẽ lấy size Nga trên sàn, thay vì gắn nhầm 2XL vào hệ Nga. Sửa cùng thẻ gửi đúng good_id và giữ nguyên GTIN; thẻ đổi nhóm TN VED dùng luồng đăng ký mới có xác nhận.
- **Báo cáo rõ trạng thái:** phân biệt lỗi, đã gửi, đang duyệt, chờ ký và đã xuất bản. Có GTIN, mã thẻ, mã lượt gửi và lý do khi Catalog trả về. Bảo vệ yêu cầu chưa rõ kết quả và thẻ đã xuất bản.
- **GTIN WB/Ozon:** cập nhật theo lô, dùng chung chỉ mục sản phẩm và đọc lại kết quả; hỗ trợ lượt xử lý 10.000 GTIN. Giữ các barcode và dữ liệu khác của bài đăng.
- **Sửa sản phẩm hàng loạt:** tối ưu thương hiệu, chất liệu, giới tính, TN VED và giấy tờ. Ozon cập nhật riêng thuộc tính, giảm phụ thuộc đọc giá; nhận đúng thành phần chất liệu và không lấy chất liệu lớp lót.
- **KN3 trong shipment:** xanh khi sàn chấp nhận, đỏ khi sàn từ chối. Nút **Thêm lại KIZ** mở báo cáo và xác nhận thay; chạy nền và đọc lại trạng thái từ sàn. Giữ dữ liệu kho và nhãn, tiếp tục ưu tiên đồng bộ FBS.
- **Logo:** VN Liquid Glass nền xanh, chữ trắng, đồng bộ biểu tượng giao diện và bộ cài.

Bản phát hành được kiểm tra tự động và kiểm tra bộ cài Windows. Ký thật bằng CryptoPro doanh nghiệp và lượt sửa/ghi thật từ desktop của seller chưa được nghiệm thu trong đợt này; xem `request-report.md` và `release-acceptance.json` đính kèm để biết phạm vi kiểm chứng.
