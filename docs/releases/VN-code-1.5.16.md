# VNCode 1.5.16

- Sửa In tất cả Ozon bị dừng với lỗi chưa xác định được trang nhãn thuộc đơn nào. App đối chiếu số đơn, barcode vận chuyển trên/dưới và hỗ trợ dấu gạch nối, khoảng trắng, nhãn chỉ có barcode. Nhãn có định danh rõ vẫn dùng một tác vụ/tệp chung cho tối đa 1.000 đơn, kể cả khi Ozon đảo thứ tự trang.
- Nếu PDF chung chưa xác minh được đầy đủ các trang, app tự lấy nhãn chính thức riêng cho đúng từng đơn bằng luồng đã có. Không ghép theo thứ tự trang hoặc barcode sản phẩm. Lưu mã tác vụ để tiếp tục/in lại; yêu cầu chưa rõ kết quả không được tạo lặp.
- Giữ đúng hai nhãn chính cho hàng có KIZ: nhãn vận chuyển và nhãn kết hợp barcode sản phẩm/KIZ. Nếu một nhãn trong lô lỗi trước khi xuất, app giữ tệp đích cũ, chưa tiêu KIZ và chưa chuyển các đơn thành đã in.
- Màn FBS Ozon dùng bố cục bảng theo shipment WB: STT, mã đơn/hạn giao, ảnh giữ tỷ lệ, thông tin hàng, cột Barcode Ozon/GTIN Znack/SKU, KN3 và giá. In tất cả ở thanh thao tác của tab Đơn mới. Giữ các nhóm Chờ nhận, Đơn mới, Đang giao hàng, Đã bàn giao và Kho KIZ.
- Bổ sung hiển thị mã đơn/ngày và các mã hàng trên cả bốn tab, tiêu đề cột đầy đủ, phân trang/nút thao tác trong cửa sổ nhỏ, hỗ trợ bốn ngôn ngữ.

Kế thừa toàn bộ dữ liệu, chức năng, hàng đợi đăng ký Catalog và đồng bộ GTIN của 1.5.15. Giữ định danh cài đặt và dữ liệu khi nâng cấp. Không thay mã TN VED, giấy tờ hoặc KIZ của seller vì thay đổi giao diện/in nhãn.

Đã kiểm tra API Ozon thật ở bước đọc đơn/barcode. Kiểm thử xuất PDF, ghép KIZ và các lỗi tác vụ dùng dữ liệu tổng hợp/HTTP localhost; không đồng nghĩa đã in hoặc gán KIZ trên tài khoản production của từng seller. Kết quả kiểm thử toàn bộ, mở app Windows và nâng cấp giữ dữ liệu nằm trong build-info/gói verification. Bộ cài hiện chưa có chữ ký Authenticode.
