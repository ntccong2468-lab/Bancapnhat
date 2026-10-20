# VNCode 1.5.6

- Sửa lỗi `CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE` ngăn bắt đầu đăng ký thẻ Znack trên WB/Ozon. Ứng dụng đọc đúng danh sách quốc gia trong `result._list` của Catalog, thay vì coi phản hồi hợp lệ là danh sách rỗng.
- Giữ mặc định nước sản xuất «Россия», gửi mã `RU` đúng danh mục; giữ nước khác đã khai rõ trên sản phẩm. Các kiểm tra giấy tờ, TN VED, danh tính, GTIN và chữ ký giữ nguyên.

Bản sửa tập trung từ 1.5.5; giữ dữ liệu seller và schema 6. Đã đọc API Catalog thật để xác định cấu trúc từ điển. Việc này không phải kết quả tạo/ký thẻ production mới.
