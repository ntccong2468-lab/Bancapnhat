# VN code 1.5.18

- Sửa lỗi `Invalid identity or value` khi áp dụng thay đổi cho nhiều sản phẩm. Thương hiệu, chất liệu, giới tính, TN VED và giấy tờ dùng đúng danh tính bài đăng WB/Ozon, giữ size, barcode và dữ liệu ngoài phạm vi sửa.
- Sửa WB chặn nhầm lần sửa tiếp theo khi sàn chỉ thay đổi thời điểm cập nhật. Vẫn kiểm tra thay đổi nội dung thật trước khi gửi.
- Sửa đối soát thuộc tính Ozon: nhận đúng văn bản khi sàn trả mã từ điển 0; tra TN VED theo mã chính xác trong nhãn có mô tả; kiểm tra đúng ID từ điển khi đọc lại.
- Sửa gắn giấy tờ Ozon đã tải lên: giữ ID giấy tờ, kiểm tra thông tin và gắn đúng SKU; không yêu cầu tải lại tệp cho giấy tờ có sẵn. Kết quả chỉ được báo hoàn tất sau khi sàn xác nhận bằng dữ liệu đọc lại.
- Thu gọn chữ, ảnh và các nút sửa trên trang Sản phẩm. Thanh thao tác tự tăng chiều cao khi nút xuống hàng, hộp sửa vừa cửa sổ, thông báo tiến trình nằm trong khung danh sách. Giữ danh sách rộng và cao theo vùng làm việc.
- Tăng tốc xuất nhãn PDF WB bằng QR vector; giữ nguyên nội dung mã, năm vị trí QR, kích thước nhãn và thứ tự trang đã chọn.
- Giảm thời gian xử lý và kích thước PDF nhãn Ozon khi in nhiều đơn; dùng chung font trong mỗi tệp và ghép trực tiếp trang vận đơn đã xác định đúng đơn từ PDF chung. Nhãn chưa xác định được đơn vẫn được xử lý riêng để tránh ghép nhầm KIZ; giữ nhãn vận đơn và nhãn kết hợp KIZ/barcode sản phẩm.

Kế thừa dữ liệu và chức năng của 1.5.17; giữ định danh cài đặt và thư mục dữ liệu hiện có.
