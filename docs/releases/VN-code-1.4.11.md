# VNCode 1.4.11

Nâng cấp đồng bộ dữ liệu từ National Catalog lên WB/Ozon và kho KIZ dùng khi in, đóng đơn.

- **Đồng bộ dữ liệu sản phẩm:** xem trước GTIN, TN VED, chất liệu, giấy tờ, thương hiệu, tên, giới tính, màu, xuất xứ và thuộc tính có tên tương ứng. Có thể sửa giá trị đề xuất khi khác từ điển sàn. Gửi thuộc tính/giấy tờ, đọc lại, rồi thêm GTIN và kiểm tra; giữ barcode cũ.
- **GTIN và KIZ:** lựa chọn GTIN theo đúng biến thể chỉ được dùng cho đóng đơn sau khi sàn đã xác nhận. Giữ GTIN đang hoạt động trong thời gian thẻ mới còn chờ. Mở thêm màn hình không khởi phục nhầm công việc đang gửi.
- **Lỗi gán KIZ rõ hơn:** WB từ chối vì trạng thái đơn, quyền API, giới hạn tốc độ hoặc kết quả chưa rõ được báo riêng. Giữ checkpoint và đối soát khi mất phản hồi. Ozon tiếp tục giữ đúng số exemplar theo số lượng sản phẩm.
- **Kho KIZ:** mở **Cấu hình Znack → Kho KIZ**, nhập tệp của seller, xem trước theo GTIN, chống trùng CIS và kiểm tra từng mã qua True API trước khi dùng. Kho ưu tiên mã nhập đã xác minh; giữ mã bằng giao dịch SQLite để hai lượt gán không nhận cùng mã.
- **Nhiều định dạng:** TXT/CSV/TSV, JSON/XML, XLSX/XLS, DOCX/RTF, Markdown/HTML, PDF/ảnh DataMatrix và ZIP chứa định dạng hỗ trợ. Nhận văn bản Notepad UTF-8/BOM/UTF-16. Giữ dấu phẩy, dấu chấm phẩy, dấu nháy và ký tự trong serial; không nhầm chúng với cột CSV.
- **Tự bổ sung khi thiếu:** seller có thể bật tùy chọn mua phần thiếu có tính phí. Khi in/đóng đơn, ứng dụng kiểm tra kho và lần mua/đưa vào lưu thông đang dở trước khi tạo yêu cầu mới. Sau khi mã sẵn sàng, bấm in lại. Mua thêm vẫn cần GTIN và cấu hình/giấy tờ Znack đủ điều kiện; nhập file không tự đăng ký quyền sở hữu GTIN.

Hỗ trợ CIS/SGTIN ngắn 31 ký tự và DataMatrix đầy đủ. Không tự thêm phần mật mã còn thiếu hoặc sửa mã đã bị ứng dụng khác làm mất ký tự. PDF/ảnh phải có DataMatrix đọc được; định dạng ghi chú độc quyền cần xuất ra văn bản hoặc định dạng hỗ trợ.

Kho được lưu riêng trên từng máy: chia các dải mã riêng cho các máy để tránh dùng lại cùng KIZ. Việc đọc tệp thành công không đồng nghĩa mã đã được xác minh đúng chủ sở hữu và đủ trạng thái lưu thông trên Znack.

Xem [bảng đối chiếu WBCode/WCode và sơ đồ luồng](https://github.com/ntccong2468-lab/Bancapnhat/blob/v1.4.11/docs/research/2026-10-15-catalog-kiz-comparison.md). Chưa dùng mã thật của seller để gán đơn hoặc mua KIZ trong quá trình kiểm thử.

Nếu dùng VNCode 1.4.4–1.4.7, tải EXE, đóng ứng dụng và cài thủ công một lần để thay updater cũ. Các phiên bản mới hơn có thể cập nhật qua ứng dụng.
