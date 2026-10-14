# VNCode 1.4.6

Giao diện gọn hơn trên toàn bộ ứng dụng và chuẩn bị kênh cập nhật chính thức
tại GitHub Releases của Bancapnhat.

- Thanh bên gọn, biểu tượng có chú thích; tăng diện tích bảng và nội dung.
- Header, tiêu đề, ô nhập, bảng và thẻ thông tin có kích thước nhất quán;
  giao diện tối dùng màu nền trung tính, giao diện sáng giữ độ tương phản.
- Chuyển trang hiện ngay, bỏ hiệu ứng mờ khi điều hướng.
- Dải thao tác đăng ký thẻ tự xuống dòng; sửa bảng đơn FBO đẩy nút ra ngoài
  trên màn hình nhỏ. Giữ chiều cao linh hoạt cho đơn Ozon nhiều sản phẩm.
- Catalog và chữ ký CryptoPro đặt cạnh nhau khi đủ rộng, xếp dọc khi hẹp.
  API KEY được che, kiểm tra chủ tài khoản trước khi lưu, bảo vệ bằng DPAPI
  riêng theo cửa hàng/chứng thư/INN/môi trường.
- Giữ bản sửa license khi giờ Windows chậm vài giây và thông báo lỗi sở hữu
  Catalog cụ thể; không cần dùng múi giờ Nga.
- App kiểm tra bản phát hành khi mở và định kỳ; hiện nội dung thay đổi,
  nút tải riêng hoặc tải và cập nhật. Tải/cài kiểm tra size và SHA-256,
  đợi công việc kết thúc và sao lưu dữ liệu trước khi chạy bộ cài.

Bộ cài Windows có Java đi kèm. Đóng VNCode trước khi cài thủ công.
Website license giữ Portal 0.3.5 và key ID vncode-2, không cần deploy lại.

Chỉ bản **Published**, không đánh dấu **Pre-release**, có tag `vX.Y.Z`
và bộ cài Windows tương ứng mới xuất hiện trong kênh seller. Draft và
artifact Actions là bản chuẩn bị/kiểm tra và không tạo thông báo cập nhật.

Các kiểm thử fixture không xác nhận tài khoản Catalog thực hoặc toàn bộ
nghiệm thu license production nhiều máy/offline/thu hồi. Các kết quả kiểm tra
và trạng thái phát hành cụ thể được ghi trong `VN-code-1.4.6-status.json`.
