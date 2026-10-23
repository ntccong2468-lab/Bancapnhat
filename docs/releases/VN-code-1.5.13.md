# VNCode 1.5.13

- Đăng ký Znack theo lô và tạo từng thẻ WB/Ozon cùng sử dụng hàng đợi gửi API Catalog. Bổ sung lượt đọc lại cuối lô để nhận các thẻ xuất hiện trễ, không yêu cầu bấm tạo thêm lần nữa. Giữ GTIN, biên nhận và yêu cầu đang chờ để tiếp tục đối soát, tránh gửi lặp.
- Bộ lọc Đang xử lý chỉ nhận thẻ đã được đọc thấy trên Catalog. Biên nhận gửi yêu cầu chưa đủ để báo đã có thẻ. Các yêu cầu đang chờ xác nhận vẫn hiển thị rõ trạng thái; chọn tất cả không gửi lại những yêu cầu này. Bộ lọc Lỗi tiếp tục chỉ nhận thẻ bị Catalog từ chối.
- Tạo từng thẻ Ozon dùng cùng bước kiểm tra dữ liệu và gửi theo lô; chỉ gửi những dòng đã qua kiểm tra. Giữ khả năng khôi phục và sửa có điều kiện của lô Ozon đã lưu.
- Nhận tên nước «Китай» từ WB khi danh mục quốc gia thật của Catalog xác nhận CN với tên đầy đủ tương ứng. Không suy đoán mã nước khi không có dữ liệu xác nhận.
- Thu gọn hàng Sản phẩm còn 104 px, hàng Đăng ký Znack còn 76 px để xem nhiều sản phẩm hơn; ảnh giữ đúng tỉ lệ, thông tin và thao tác hiện có được giữ.

Trước đóng gói, toàn bộ kiểm thử Linux đạt 2.927/2.929 ca, 2 ca bỏ qua theo hệ điều hành, không lỗi/thất bại. Lượt thử được chủ dự án xác nhận đã xin đúng một GTIN và gửi đúng một yêu cầu lên API Catalog production; đọc lại thấy đúng thẻ, trạng thái nháp/chờ duyệt. Đây là bằng chứng gửi API thật, chưa phải nghiệm thu hai nút trên thiết bị Windows của seller hoặc ký/xuất bản thẻ xanh. Không thực hiện mua KIZ có phí hoặc ghi GTIN lên WB/Ozon trong lượt thử này.

Kết quả kiểm tra Windows, mở ứng dụng và nâng cấp giữ dữ liệu được kèm trong build-info và gói verification của bản phát hành. Giữ nguyên dữ liệu, định danh cài đặt và các yêu cầu đang dở khi nâng cấp. Khi ứng dụng đóng hoàn toàn hoặc mất mạng, yêu cầu đã lưu tiếp tục khi mở lại/có mạng. Bộ cài chưa có chữ ký Authenticode.
