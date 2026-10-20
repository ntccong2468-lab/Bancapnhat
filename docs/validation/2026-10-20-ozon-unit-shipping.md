# Ozon: nhận hàng loạt, mỗi sản phẩm một shipment

Yêu cầu ngày 20/10/2026: khi nhận tất cả đơn, đơn có hai sản phẩm tự tách thành hai shipment con trên Ozon, bao gồm hai SKU khác nhau hoặc hai đơn vị cùng SKU.

## Giao thức và dữ liệu đã đối chiếu

Luồng nhận/đóng gói hiện có dùng `POST /v4/posting/fbs/ship`. Giao thức này nhận nhiều `packages`; mỗi sản phẩm vật lý được gửi trong một package với `quantity: 1`, `product_id` là SKU Ozon. App dùng số shipment thật do `result[]` trả về, không tự ghép hậu tố số đơn. Tham khảo [ghi nhận nguồn giao thức](2026-10-20-ozon-per-unit-protocol.md).

Đọc Ozon thật lúc 11:02 ngày 20/10/2026, giờ Moscow: danh sách bảy ngày có năm đơn chờ đóng gói, đều một đơn vị. Lượt danh sách và đọc chi tiết trả HTTP 200. Chi tiết có `order_id`, `order_number`, `delivery_method.warehouse_id`, `products[].sku` và số lượng; không có `products[].product_id`. `related_postings` và `product_exemplars` là object. Không có đơn hai đơn vị trong mẫu đọc, không thực hiện lệnh tách/đóng gói production.

## Hành vi triển khai và kiểm tra

Thao tác nhận hàng loạt giữ toàn bộ lựa chọn; trước khi gọi API lưu ý định, SKU/số lượng và dấu kết nối vào SQLite. Sau khi Ozon nhận lệnh, chỉ xác nhận thành công sau khi đọc lại các shipment con, đúng đơn/kho/SKU, đủ số lượng, mỗi shipment một đơn vị và trạng thái đã đóng gói/giao hàng hợp lệ.

Mất phản hồi hoặc chưa đủ bằng chứng: giữ trạng thái cần đọc lại, không tự gửi lại lệnh tách. Đồng bộ nền tiếp tục đối soát các shipment đang chờ; giao diện phân biệt số đơn nguồn đã xử lý và tổng shipment con đã xác minh.

Đơn đã có KIZ gán trong app hoặc trên Ozon được bảo vệ trước khi tách; không sao chép mù mã KIZ, không lấy mã mới hoặc làm mất dấu giao dịch cũ. Giới hạn 100 đơn vị/đơn là giới hạn bảo vệ của VNCode, không được mô tả là giới hạn công bố của Ozon.

Source a684fcd đạt 105 kiểm thử trọng tâm, không lỗi hoặc bỏ qua; log `/tmp/vncode-153-ozon-split-focused-final.log`. Hai kiểm thử hồi quy bổ sung xác nhận tự nâng cấu trúc checkpoint cũ và tự đọc lại sau khi Ozon sửa dữ liệu shipment con chưa khớp; không cần bấm nhận lại. Toàn bộ Linux của source này đạt 2.045 kiểm thử với 2 bỏ qua theo nền tảng. Windows run 39393525107 thất bại tại focused UI `OzonBulkUiTest`, chưa chạy full Java hoặc tạo bộ cài. Fixture đã sửa để chạy lại; bản cập nhật còn thêm sửa Znack/GTIN nên phải có source/run mới, không dùng kết quả a684fcd làm nghiệm thu phạm vi cuối.
