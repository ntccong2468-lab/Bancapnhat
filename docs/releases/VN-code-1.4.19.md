# VNCode 1.4.19

Bản cập nhật tiếp nối bản nền 1.4.18, giữ giao diện Liquid Glass, chỉnh sửa hàng loạt và ưu tiên đồng bộ FBS.

- **Sản phẩm:** bấm Đồng bộ sẽ quét lại toàn bộ danh mục và đọc chi tiết mọi sản phẩm, bao gồm sản phẩm có/chưa có GTIN; không phụ thuộc trang, tìm kiếm hoặc bộ lọc đang mở. Giữ TN VED, giấy tờ, chất liệu, giới tính, thương hiệu và barcode sàn. Hiện tiến độ đọc chi tiết.
- **GTIN WB/Ozon:** xử lý từng biến thể, cập nhật tiến độ sau mỗi kết quả thay vì chờ hết lô 100 biến thể. Một biến thể không hợp lệ không chặn các biến thể còn lại. Báo đúng mã lỗi trong báo cáo; chỉ tính thành công khi đọc lại xác nhận. Giữ kiểm tra thẻ đã xuất bản, định danh sản phẩm/biến thể, SKU Ozon và hàng đợi ghi hiện có.
- **Nhóm TN VED khi gán GTIN:** nếu nhóm 4 số đầu đã khác thẻ Znack cũ, chặn gán và báo cần đăng ký thẻ mới/GTIN mới. Kiểm tra cả lúc ghép, xem trước và trước khi xử lý ghi; không tự cấp GTIN.
- **Kho KIZ seller trong FBS WB/Ozon:** thêm Xóa danh mục; danh mục đã xóa không tự xuất hiện lại khi làm mới. Mã chưa dùng của danh mục được lưu trữ; mã đang giữ và lịch sử đơn được bảo toàn.
- **Nhập KIZ seller:** nhận từng lần tải thành lô mới, kể cả tệp cùng tên, cùng nội dung, mã trùng và mã từng dùng. Không kiểm tra Znack, GTIN sản phẩm hoặc lịch sử mã. Lọc mã sai định dạng, báo số mã đọc được/giữ lại/bị loại; Tự nhập mã dùng được khi chọn tệp, cập nhật số còn ngay tại danh mục rồi hiện báo cáo. Số còn trừ theo lượt giữ/gán mã. WB/Ozon vẫn quyết định chấp nhận mã khi gán.
- **Định dạng tệp:** nhận dạng theo nội dung, đọc các sheet/cột chứa mã, giữ các dòng đúng khi tệp có dòng hỏng. Hỗ trợ văn bản, CSV/TSV, JSON/XML, Excel, DOCX, RTF/HTML, ZIP, PDF/ảnh DataMatrix; không bảo đảm giải mã mọi tệp nhị phân hoặc ảnh không đọc được.
- **Dữ liệu:** nâng schema 5 lên 6, lưu riêng từng lượt mã seller nhập; giữ định danh, liên kết đơn và quy tắc duy nhất cho mã mua. Bản cũ sẽ từ chối mở schema mới để tránh xử lý sai kho mới.

Luồng mua KIZ và ưu tiên đồng bộ FBS giữ nguyên. Đã [ghi một GTIN thật lên WB và xác nhận lưu trên sàn](https://github.com/ntccong2468-lab/Bancapnhat/blob/v1.4.19/docs/validation/2026-10-18-vncode-1419-wb-live-gtin.md) qua API với payload production. Chưa nghiệm thu thao tác ghi qua ứng dụng Windows có license production.

**Cài đặt:** đóng VNCode rồi chạy `VN-code-1.4.19-Windows-x64.exe`; Java được đóng gói sẵn. Bộ cài chưa ký Authenticode. Ứng dụng tạo bản sao dữ liệu trước khi nâng schema; bản cũ không mở được dữ liệu schema 6.

**Giới hạn xác minh:** không coi kiểm thử tự động hoặc ghi trực tiếp qua API là nghiệm thu nút trên phiên seller Windows. Nút cập nhật GTIN, ký CryptoPro thật và license production chưa được nghiệm thu đầy đủ trong phiên này. Kết quả gán KIZ cuối cùng phụ thuộc phản hồi WB/Ozon, kể cả khi seller chọn nhập lại mã đã dùng.
