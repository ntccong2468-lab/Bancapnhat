# VN code 1.6.0

## License và khởi động

- Khi mở ứng dụng, license đã được xác minh chữ ký, thuộc đúng thiết bị và còn hiệu lực có thể được sử dụng ngay trong phạm vi thời hạn offline. Việc kiểm tra lại với server chạy ở nền; nếu server trả về quyết định từ chối hoặc thu hồi, ứng dụng cập nhật và áp dụng quyết định đó.
- Mỗi yêu cầu license có giới hạn thời gian; một lượt kiểm tra có thể gồm nhiều yêu cầu và thử tuyến kết nối. Trạng thái ngắn trên thanh điều hướng ghi «License hợp lệ» khi quyền còn hiệu lực; trang license vẫn cho biết trạng thái offline, thời hạn và lỗi kết nối khi có.
- Chẩn đoán mạng phân biệt kết nối đã hoàn tất bắt tay TLS với kết nối còn đang chờ. Thay đổi thời gian chờ và chẩn đoán không bảo đảm khắc phục được mọi lỗi mạng trên máy seller.

## Ozon FBS và PDF

- Giữ lựa chọn đơn khi chuyển trang; xử lý và báo kết quả riêng cho từng đơn trong lô. Ứng dụng giữ kết quả chưa rõ để đối soát, tránh gửi lặp và không tự mua KIZ khi thiếu bằng chứng.
- Hỗ trợ đề xuất bổ sung KIZ theo các đơn đã chọn và chờ seller xác nhận; đối chiếu dữ liệu trước khi gán và nhận đơn.
- Lấy nhãn Ozon theo lô tối đa 1.000 đơn, tối ưu xử lý PDF và hiển thị thời gian các công đoạn. Trang nhãn phải được xác định đúng đơn trước khi ghép; trường hợp mơ hồ được báo để xử lý.
- Trong khi nhận đơn hoặc xuất nhãn, có thể xem và chuyển tab; các thao tác làm thay đổi lô đang xử lý được khóa để bảo vệ dữ liệu.

## GTIN và thẻ Znack

- Đồng bộ Ozon có điểm tiếp tục đã lưu cho danh mục lớn và chỉ xác nhận mapping sau khi đọc thấy barcode trên Ozon. Nếu GTIN đã thuộc sản phẩm khác trong dữ liệu được xác minh, ứng dụng báo lý do và hỗ trợ chọn sản phẩm đăng ký thẻ mới.
- Với các sản phẩm WB đã chọn, ứng dụng kiểm tra giá theo size, tính giá trung bình khi cần và xác nhận cập nhật giá trước khi gửi GTIN. Giá từng size có thể tăng hoặc giảm. Ứng dụng xử lý giới hạn API và báo khi token không đủ điều kiện kiểm tra giá an toàn.
- Trạng thái Znack phân biệt thẻ nháp bị từ chối và thẻ chưa ký, giữ chi tiết lỗi để seller xem và xử lý. Khi không đọc được lịch sử lỗi, ứng dụng báo lỗi thay vì hiển thị đang tải vô thời hạn.

## Phạm vi xác minh

- Các luồng GTIN, cập nhật giá và Catalog được kiểm thử bằng dữ liệu và API mô phỏng. Các bài đo PDF cũng dùng Ozon mô phỏng, không bao gồm thời gian mạng Ozon, ảnh hoặc KIZ thật; không phải cam kết thời gian xử lý trên máy seller.
- Chưa xác nhận ghi giá hoặc GTIN thật, đăng ký thẻ Catalog thật, mua KIZ có phí, gán KIZ hoặc nhận đơn thật trong lượt kiểm tra này. Các kết quả không rõ, giới hạn API và trường hợp thiếu dữ liệu vẫn cần được đối soát trước khi xử lý tiếp.
- License còn hiệu lực offline có thể được dùng trong lúc kiểm tra nền; quyết định thu hồi từ server chỉ có hiệu lực trên app sau khi app nhận được quyết định đó hoặc quyền đã ký hết hạn.
