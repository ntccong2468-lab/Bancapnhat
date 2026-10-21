# Nghiệm thu trước khi seller nhận cập nhật 1.4.6

Build và các kiểm thử tự động được ghi riêng trong release status. Bước còn
thiếu là chạy bộ cài mới với license/CryptoPro của chủ app. Không coi kiểm thử
giả lập là kết quả production và không đổi cờ chưa kiểm tra thành `true`.

1. **Nâng cấp và dữ liệu:** đóng app, cài bản mới trên máy đang dùng; xác nhận
   đúng 1.4.6, license còn hoạt động, cửa hàng/lịch sử/dữ liệu cũ còn nguyên.
2. **Cùng key trên nhiều máy:** với tài khoản thử có giới hạn hai máy, kích
   hoạt cùng key trên hai máy Windows khác nhau mà không đăng ký Gmail lại.
   Máy vượt giới hạn phải bị từ chối; thử lại trên máy đã kích hoạt không tăng
   số máy hoặc cộng thêm ngày sử dụng.
3. **Offline:** khi license đang hợp lệ, ngắt mạng và mở lại app. Trạng thái
   offline và hạn offline phải đúng; dữ liệu cũ vẫn đọc được. Kết nối mạng lại
   để kiểm tra trước bước tiếp theo.
4. **Thu hồi/gia hạn:** trên tài khoản thử, thu hồi từ admin rồi kiểm tra
   license trong app đang online. Quyền phải bị chặn; dữ liệu vẫn đọc được.
   Cấp/gia hạn lại theo quyền admin và kiểm tra app nhận đúng hạn/giới hạn.
5. **Catalog:** chọn đúng cửa hàng/chứng thư, kiểm tra chữ ký, nhập API KEY
   trong Cài đặt và bấm kiểm tra/lưu. Thông báo phải xác minh đúng chủ tài
   khoản; đồng bộ phải đọc được thẻ. Nếu lỗi, ghi mã `CATALOG_OWNER_...` hoặc
   `CATALOG_KEY_...` hiển thị, không đánh dấu bước này thành công.
6. **Đối chiếu website:** Gmail gửi từ app xuất hiện đúng tài khoản trong admin,
   việc duyệt/cấp key/thiết bị khớp các bước trên. Admin và seller là quyền
   website riêng; license desktop không thay vai trò website.

Kết quả cần ghi gồm phiên bản, thời gian, số máy/hạn trước và sau, mã lỗi nếu
có và các bước đã thực hiện. Không cần gửi private key hoặc mã license.
Sau khi đủ bằng chứng, cập nhật file acceptance đúng SHA nguồn đã build và
chạy workflow Releases với chế độ `publish`.

Sau Published/Latest, dùng VNCode phiên bản trước mở app hoặc bấm kiểm tra
phiên bản: phải thấy 1.4.6, đúng notes, nút tải và cập nhật. Không thể kiểm tra
bước thông báo công khai bằng một draft chưa publish.
