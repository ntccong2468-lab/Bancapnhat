# Kết quả yêu cầu VNCode 1.5.2

## Đã sửa

- Znack không cần bấm Đăng ký lần hai đối với checkpoint chắc chắn chưa gửi; tự chờ và tiếp tục khi API đọc bị giới hạn. Không gửi lại POST/GTIN khi kết quả lần trước chưa xác định.
- Báo cáo ghi đủ lựa chọn trước khi xử lý, đối chiếu cả lô, có trạng thái và lý do từng thẻ. Chọn tất cả chạy ngoài luồng giao diện.
- Tiến độ GTIN phân biệt đã xác minh trên sàn, đang chờ đọc lại, chưa cập nhật và chưa có GTIN.
- Năm bảng sửa Sản phẩm WB/Ozon chọn sẵn đề xuất có dữ liệu; giữ quyền sửa lựa chọn. Áp dụng một lần, đóng bảng và chạy nền. Không đoán dữ liệu thương hiệu/giấy tờ/thành phần khi thiếu căn cứ.
- Viên thuốc báo tổng, còn lại, thành công, thất bại, chờ sàn; thành công chỉ sau đối soát sàn. Hoàn tất tự ẩn sau tám giây, chi tiết được lưu lại.
- Thay KIZ đỏ không giới hạn số lần: dùng mã sẵn có từ các kho đang chọn, không buộc danh mục/GTIN của đơn, không tự mua thêm; mỗi mã đỏ bị loại khỏi mọi bản sao trong kho và bị chặn nhập lại. Đọc lại đúng mã mới được sàn chấp nhận mới hoàn tất.
- Bỏ nút thay ở từng đơn, giữ một nút cạnh Kho KIZ trong shipment WB/Ozon; bảng xác nhận đóng trước khi chạy nền, viên thuốc hoàn tất tự ẩn sau tám giây.
- Chuyển giao hàng WB tự chọn điểm giao gần nhất từ lịch sử của đúng tài khoản; bỏ biểu mẫu quốc gia/cách giao/ngày/thành phố, không có ô biển số xe hay nơi sản xuất. Nút chuyển giao chạy trực tiếp, vẫn giữ kiểm tra nhãn và KIZ trước khi gọi sàn. Trường hợp không có lịch sử, WB kiểm tra cấu hình lô hiện có; app không tự bịa địa chỉ.
- Kết nối KIZ đang xử lý giữ cấu hình gốc; bổ sung tiến độ Mapping, lỗi chữ ký/mua mã và tiếp tục quét Catalog.

## Kiểm chứng

- Hồi quy sạch toàn bộ sau sửa Sản phẩm: 1.942 kiểm thử, 0 lỗi, 2 bỏ qua theo nền tảng.
- Hồi quy sạch trước thay đổi Sản phẩm: 1.937 kiểm thử, 0 lỗi, 2 bỏ qua theo nền tảng.
- Bài thử lô 650 thẻ đối chiếu toàn bộ báo cáo trong khoảng 40 ms.
- Kiểm thử nút Áp dụng cho WB và Ozon; kiểm thử payload năm trường WB, đọc lại xác nhận và phân biệt thẻ bị từ chối với thẻ đã sửa.
- Kết nối chỉ đọc WB, Ozon và Catalog đều HTTP 200. Không dùng kết quả chỉ đọc làm bằng chứng đăng ký/ghi thật.
- Bộ cài mới phải vượt qua bộ Java đầy đủ mới chạy trên Windows, kiểm tra giao diện, cài/gỡ và nâng cấp giữ dữ liệu, gồm bản 1.5.1.

## Chưa hoàn tất

Chưa nghiệm thu chữ ký CryptoPro doanh nghiệp, đăng ký/ký thẻ Catalog thật, ghi GTIN thật trên desktop có license production và toàn bộ giấy phép production trong lượt này. Các kiểm thử mô phỏng không được công bố là thành công trên tài khoản thật. Chủ ứng dụng yêu cầu đóng gói và phát hành Latest sau các sửa này; bản phát hành giữ công khai giới hạn trên.
