# Giao diện nền đen theo ảnh tham chiếu

Đã hoàn tất phần luồng đăng ký trước khi chuyển sang giao diện. Nền mã nguồn: 1.5.11 và các sửa luồng trong PR #12. Không tạo bộ cài ở bước giao diện này.

- Dùng nền đen, bảng xám đậm, nút xám và thao tác chính màu tím. Logo VN chuyển sang nền đen, chữ trắng; xuất PNG, ICO và ICNS từ cùng ảnh gốc.
- Đăng ký WB/Ozon: giữ bốn nút chính phía trên, bộ lọc, bảng gộp Barcode/GTIN, trạng thái và thao tác cùng dòng. Các chứng cứ kiểm duyệt, nguồn GTIN, thời điểm đọc và đồng bộ sàn vẫn được giữ trong báo cáo; việc gộp cột không biến một barcode thành GTIN đã xác minh.
- Sản phẩm: giữ ảnh đúng tỉ lệ, thông tin giấy tờ/chất liệu/giới tính/TN VED và thanh thao tác sửa hàng loạt.
- Hộp TN VED: nhóm gợi ý, bộ lọc, biểu tượng sao chép hoạt động, ba nút gợi ý theo sản phẩm/Áp dụng/Hủy ở cuối. Hủy không gọi thao tác sửa.
- GS1: thẻ thành viên, sáu kiểm tra tài khoản, bảng GCP/GLN và thư ở cột bên phải. Trạng thái chưa xác minh vẫn giữ màu xám; số dư và số GTIN không được suy đoán từ ảnh mẫu.
- Cấu hình Znack có **5 tab cố định** theo yêu cầu bổ sung: Thông tin, Lịch sử mua KIZ, Đã xóa, Cài đặt, Kho KIZ. Kho KIZ sử dụng luồng nhập file hiện có, không tạo kho dữ liệu thứ hai. Nhật ký và sản phẩm kỹ thuật vẫn mở được từ menu ngữ cảnh tiêu đề.
- Cấu hình mặc định chỉ đọc; Chỉnh sửa cấu hình mở nhập liệu, Hủy khôi phục giá trị đã lưu. Khóa Catalog vẫn là PasswordField, không dùng chuỗi dấu chấm giả làm khóa thật. Hình thức sản xuất tại Nga phản ánh phương thức mua thực sự đang được hỗ trợ.
- Kèm bản Inter Bold tĩnh tạo từ font Inter OFL đã có để JavaFX thể hiện đúng chữ đậm. Ghi nguồn, cách tạo và SHA256 trong Inter-source.json.

Kiểm tra: **115 ca / 11 lớp đạt**, tổng hợp kết quả mới nhất ở [JSON](2026-10-22-dark-reference-ui-tests.json). Có kiểm thử nút sao chép bằng chuột thật trong giả lập, chọn tất cả sản phẩm, thanh sửa hàng loạt, sửa/hủy cấu hình và hộp TN VED. Các ảnh dưới đây chụp trực tiếp view JavaFX với dữ liệu tổng hợp; không phải kết quả đăng ký hay mua KIZ thật. Kích thước/chữ có thể khác theo DPI và khung cửa sổ của Windows; không khẳng định bằng ảnh từng pixel trên máy seller.

Ảnh kiểm tra: [WB](images/2026-10-22-dark-reference/wb-registration.png), [Ozon](images/2026-10-22-dark-reference/ozon-registration.png), [Sản phẩm](images/2026-10-22-dark-reference/products-selected.png), [TN VED](images/2026-10-22-dark-reference/tnved-picker.png), [GS1](images/2026-10-22-dark-reference/gs1-ready.png), [Cấu hình](images/2026-10-22-dark-reference/znack-settings.png), [Kho KIZ](images/2026-10-22-dark-reference/znack-kiz-warehouse.png).
