# VN Code 1.6.8

Kế thừa đầy đủ chức năng và dữ liệu của 1.6.7, bổ sung sửa đăng ký Znack theo lô và phục hồi mã KIZ đã mua.

- Đăng ký Znack từ WB: đọc thẻ theo trang, đối chiếu đúng sản phẩm và biến thể; khắc phục tìm kiếm trả rỗng dù thẻ vẫn tồn tại trên WB.
- Báo rõ thẻ nào thiếu dữ liệu trong bước chuẩn bị; cho phép xác nhận tiếp tục với tập hợp lệ, giữ báo cáo cho toàn bộ lựa chọn.
- Chuẩn bị lựa chọn tối đa 10.000 biến thể; gửi và lưu kết quả từng thẻ, tiếp tục hàng đợi sau gián đoạn hoặc mở lại app. Thẻ đã có mã yêu cầu vẫn được đối soát trước khi gửi tiếp để tránh đăng ký trùng.
- Giảm chi phí ghi và tính trạng thái báo cáo cho lô lớn. Giữ kết quả từng yêu cầu ngay khi nhận được; không đánh dấu thẻ hoàn tất chỉ vì HTTP 200 hoặc đã cấp GTIN.
- KIZ: xử lý bộ đệm SUZ `EXHAUSTED` bằng cách đối soát và lấy lại các gói mã đã cấp của đúng đơn mua, thay vì lặp yêu cầu cấp mã thông thường hoặc tự mua lại.
- Phục hồi phần gói mã chưa lưu đủ, giữ tiến trình qua khởi động lại; kiểm tra đúng đơn/GTIN/khối và giữ nguyên trạng thái mã đã dùng hoặc giữ chỗ.

Giữ toàn bộ sửa trước: nhận và in FBS Ozon theo lô, nhãn vận đơn gốc và KIZ kết hợp, phiếu nhặt hàng, Đơn nợ WB, sửa thông tin sản phẩm, GTIN/mapping, chữ ký điện tử, giao diện và cập nhật ứng dụng.

Sau cập nhật, bấm **Đồng bộ Znack** để đối chiếu dữ liệu mới. Giữ nguyên hồ sơ, lịch sử mua KIZ và các yêu cầu đang theo dõi; không tạo đơn mua hoặc thẻ mới để thay yêu cầu chưa rõ kết quả. Dịch vụ Catalog/SUZ vẫn kiểm tra quyền, hồ sơ và trạng thái nghiệp vụ; đơn đã đóng hoặc gói không còn được SUZ cho phép lấy lại sẽ cần đối soát.
