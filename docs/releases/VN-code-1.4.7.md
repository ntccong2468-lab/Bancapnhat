# VNCode 1.4.7

Màn hình đóng đơn FBS của Ozon có bố cục nhất quán với WB.

- Bảng đơn có cột đơn hàng, ngày giao dự kiến, ảnh, sản phẩm và giá riêng. Giữ offset múi giờ của thời gian Ozon trả về.
- Dòng sản phẩm gọn hơn; vẫn hiện số lượng, article, màu/size, GTIN, SKU/barcode và yêu cầu KIZ. Đơn nhiều sản phẩm giữ từng ảnh và giá đúng hàng.
- Thanh tìm kiếm có nút xóa; phân trang đặt dưới bảng. Các tab đơn mới, đóng gói và đang giao giữ bộ đếm riêng.
- Chọn toàn bộ kết quả lọc, chuyển đóng gói, sắp xếp và in đặt trong thanh thao tác gọn, tự xuống dòng khi cửa sổ hẹp.
- Giữ phân trang 100 đơn/lần, chọn lô trên nhiều trang và xử lý nền. Các bước kiểm tra, gán KIZ và in vẫn dùng đúng API và quy tắc của Ozon.
- Giữ thông báo cập nhật từ Published/Latest trên GitHub, kèm nội dung thay đổi, tải bộ cài và tải/cập nhật có kiểm tra SHA-256.

Bộ cài Windows x64 có Java đi kèm. Website Portal 0.3.5 và license key ID vncode-2 không thay đổi; không cần triển khai website lại.

Kiểm thử dữ liệu mẫu không thay thế nghiệm thu tài khoản Ozon/Catalog thực hoặc license production nhiều máy, offline và thu hồi. Những mục chưa chạy được giữ nguyên trong hồ sơ kiểm thử.
