# Ozon: nhãn ghép KIZ và barcode sản phẩm, 58×40 mm

Chủ dự án đã duyệt bố cục và yêu cầu áp dụng cho Ozon ngày 20/10/2026: DataMatrix KIZ bên trái, tên/article/màu/size bên phải, barcode sản phẩm và chữ đọc được ở phía dưới. Đây là nhãn hàng hóa; các trang nhãn vận chuyển chính thức vẫn được sao chép nguyên trạng.

## Dữ liệu thật đã đọc

Lúc 10:35 ngày 20/10/2026, giờ Moscow, đọc đủ 175 sản phẩm Ozon qua hai trang `POST /v3/product/list` và hai lượt `POST /v3/product/info/list`. Cả bốn yêu cầu đọc trả HTTP 200. Không thay đổi dữ liệu trên Ozon.

- 174 sản phẩm có một barcode OZN.
- Một sản phẩm có barcode OZN và một barcode số 13 chữ số.
- Không có sản phẩm thiếu barcode, nhiều OZN hoặc mơ hồ trong lần đọc này.
- Tất cả 175 barcode OZN có dạng `OZN` cộng 10 chữ số. Không lưu giá trị barcode hoặc định danh sản phẩm thật trong báo cáo.

Quy tắc ưu tiên một barcode OZN duy nhất xác định được barcode cho 175/175 sản phẩm đã đọc. Không tạo barcode từ SKU, offer ID hoặc mã KIZ.

## Ràng buộc triển khai

- Đối chiếu đúng SKU, offer và định danh sản phẩm của đơn; chấp nhận trường hợp API đơn dùng SKU làm `product_id`.
- DataMatrix GS1 có FNC1 và giữ dấu phân cách GS; cả DataMatrix và Code128 được vẽ vector. Code128 giữ vùng trắng hai bên, kiểm tra kích thước module tối thiểu tương đương hai chấm ở 203 dpi.
- Một nhãn ghép cho mỗi exemplar KIZ; giữ nguyên các trang vận chuyển, số lượng hàng và quy tắc trừ KIZ.
- Ozon FBO: mỗi đơn vị cần KIZ chuyển từ `BARCODE, BARCODE, KIZ` sang một trang ghép; đơn vị không cần KIZ giữ hai bản barcode. Chỉ thay lộ trình Ozon; bộ lập kế hoạch WB tiếp tục xuất các loại trang hiện có.
- Lịch sử FBO lưu cả barcode, KIZ và định danh gốc trong dữ liệu đóng băng với dấu phiên bản bộ dựng ẩn. In lại dùng đúng dữ liệu đó, không đọc Catalog, không lấy KIZ mới hoặc yêu cầu quyền in mới. Lịch sử cũ không có dấu phiên bản tiếp tục dùng bộ dựng cũ.
- Tra cứu riêng cho in cho phép lấy barcode đã lưu của sản phẩm lưu trữ, giữ khả năng in lại đơn cũ. Các luồng tra cứu sản phẩm khác vẫn chỉ đọc sản phẩm đang hoạt động.
- Thiếu/mơ hồ barcode, nhầm sản phẩm hoặc mã quá dày để in rõ: báo lý do và SKU bằng tiếng Việt; không xuất đè PDF đích hoặc tiêu thụ KIZ khi xuất thất bại.

## Kiểm tra

47 kiểm thử trọng tâm đã đạt, không lỗi hoặc bỏ qua: chọn barcode OZN/GTIN, đọc lại cả hai mã ở 203 và 300 dpi, trang vận chuyển và số lượng nhãn, lỗi xuất nguyên tử, in lại sản phẩm lưu trữ, bàn giao bộ cài. Log: `/tmp/vncode-153-ozon-label-focused.log`. Toàn bộ 85 kiểm thử công cụ Node đạt; log `/tmp/vncode-153-node-label.log`.

Ảnh/PDF mẫu được tạo bằng đúng `OzonKizLabelAppender` của app, dùng dữ liệu DEMO và barcode tổng hợp `OZN0000000000`. Cả hai mã đọc đúng ở 203 và 300 dpi; DataMatrix có định danh GS1 `]d2`. Không gọi API hoặc sử dụng KIZ thật trong lần tạo mẫu.

Đóng gói JAR cục bộ thành công; bộ cài Windows và kiểm tra nâng cấp EXE thụ động từ 1.5.2 vẫn cần được thực thi trên đúng source cuối trước khi phát hành.

Sau khi bổ sung FBO, 98 kiểm thử trọng tâm đạt, không lỗi hoặc bỏ qua; log `/tmp/vncode-153-ozon-fbo-focused.log`. Năm kiểm thử tích hợp FBO gồm hai đơn vị cần KIZ xuất hai trang ghép, giải mã 203/300 dpi, lỗi trước khi giữ mã, lỗi xuất không làm mất tệp/kho, và phục hồi lịch sử sau khi xóa Catalog dưới quyền in mới bị từ chối. Bộ dựng xuất FBO thật cũng tạo mẫu DEMO đúng một trang 58×40 mm, cả hai mã giải mã đúng ở 203/300 dpi.

Chưa in bằng máy in tem vật lý hoặc nghiệm thu quét trên thiết bị của seller.
