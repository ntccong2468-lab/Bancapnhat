# VN Code 1.6.7

Bản này kế thừa đầy đủ 1.6.6 và gộp các chỉnh sửa KIZ mapping, giấy tờ, đăng ký Znack, GTIN WB và giao diện đã được duyệt sau bản đó.

- Mua KIZ trên WB: giữ đủ màu của sản phẩm, nhận diện nhất quán các cách ghi màu tương đương; phân biệt thiếu màu và sai màu để hiển thị đúng phần cần xử lý.
- Giấy tờ: phân biệt chưa có, hết hạn, đình chỉ và đang chờ xác minh. Giữ giấy tờ đã đọc khi registry đang xử lý; lấy đúng ngày hết hạn của giấy tờ được xác nhận.
- Đăng ký Znack: tự chuẩn hóa chữ Latin/Cyrillic trông giống nhau trong phần số đăng ký có cấu trúc xác định. Giữ nguyên mã quốc gia, số thứ tự và ngày; báo rõ giấy tờ bị registry từ chối thay vì lỗi đọc dữ liệu chung.
- Giữ ID giấy tờ WB và liên kết giấy tờ Ozon đã có khi cùng số giấy tờ chỉ khác cách gõ bảng chữ; tránh đính kèm trùng hoặc yêu cầu tải lại file.
- Ghép GTIN WB: sửa trường hợp sai article, bỏ sót barcode và báo mơ hồ không đúng. Đọc lại bài WB trước khi quyết định ghép, cập nhật hoặc xác nhận GTIN đã có.
- Theo dõi đăng ký Catalog đúng mã yêu cầu, tránh lấy kết quả của yêu cầu khác. Giữ cơ chế tiếp tục hàng đợi và chống gửi trùng sau mất mạng hoặc mở lại app.
- Khi đổi API Catalog, kết quả cũ không ghi đè dữ liệu của kết nối mới; giữ lịch sử, kho KIZ và các yêu cầu đang theo dõi.
- Giao diện mới đã duyệt áp dụng vào app: nền sáng/tối/hệ thống, danh sách rộng, thanh điều hướng gọn, chuyển động có tùy chọn giảm và bảng tìm nhanh Ctrl+K.
- Nút **Thêm cửa hàng** nằm trong **Quản lý** cạnh ô chọn cửa hàng. Sửa nút thao tác bị ép chiều rộng và màu menu sau khi đổi nền.

Giữ toàn bộ chức năng đã có: nhận FBS Ozon theo lô, tự thay KIZ seller nhập theo danh mục, vận đơn Ozon gốc và nhãn KIZ kết hợp, phiếu nhặt hàng cùng quy tắc WB, Đơn nợ WB, năm nút sửa sản phẩm, lưu dữ liệu đồng bộ và cập nhật ứng dụng.

Sau khi cập nhật, bấm **Đồng bộ Znack** một lần để làm mới dữ liệu Catalog đã lưu từ bản cũ. App vẫn yêu cầu đúng sản phẩm/biến thể và đọc xác nhận kết quả từ sàn; giấy tờ không hợp lệ hoặc chưa có KIZ phù hợp sẽ hiển thị lý do để xử lý.
