# VNCode 1.4.12

Bản nâng cấp tiếp nối 1.4.11 và 1.4.10, tập trung vào đồng bộ nền, GS1 và báo cáo tác vụ.

- **GS1 tự xác thực:** dùng chứng thư CryptoPro và thông tin trong Cấu hình Znack để đọc thành viên, GCP/GLN, hạn, số GTIN còn lại, email doanh nghiệp và số dư Честный знак. Không cần mở trang đăng nhập. Giữ một nút Đồng bộ GS1; phiên hết hạn được xác thực lại có giới hạn. Lỗi từng nguồn được báo riêng và giữ cache tốt gần nhất.
- **Đồng bộ nền theo cửa hàng:** tách hàng đợi FBS khỏi catalog, Znack, GS1, FBO và tài chính. Khi ứng dụng mở và API khả dụng, hàng đợi FBS cửa hàng đang chọn được kiểm tra định kỳ khoảng 10 giây, trạng thái khoảng 15 giây; cửa hàng khác có lịch riêng. Đây là polling có giới hạn API, không phải kết nối push với cam kết thời gian tuyệt đối.
- **FBS WB/Ozon:** đọc theo trang/lô, tiếp tục từ checkpoint, cập nhật cả đơn đã rời hàng đợi chưa đóng. WB làm mới nội dung và số đơn trong shipment, ưu tiên shipment đang xem. Dữ liệu thiếu hoặc sai định danh không được coi là lượt đồng bộ thành công.
- **Znack → GTIN tự động:** sau khi đọc thẻ published, ghép đúng sản phẩm/biến thể rồi dùng luồng gửi và đọc lại hiện có. Ozon giữ riêng product ID, offer ID và SKU; SKU đổi, sai size/màu hoặc ghép mơ hồ sẽ dừng đúng dòng. Chỉ công bố mapping cho luồng KIZ sau khi sàn xác nhận. TN VED, giấy tờ, chất liệu và thuộc tính tiếp tục có bước đối chiếu/xác nhận trong màn dữ liệu sản phẩm.
- **Trung tâm tác vụ:** hiển thị tác vụ cụ thể, bước đang chạy, trạng thái từng sản phẩm, lý do lỗi và lịch sử. Có xuất CSV/JSON và đối soát; không xuất API key, token hoặc mã KIZ đầy đủ. Đã sửa tác vụ hoàn tất bị hiển thị như đang chạy.
- **Gọn thao tác:** bỏ mục GTIN WB/Ozon riêng khỏi Công cụ; theo dõi và sửa liên kết trong luồng Znack và Trung tâm tác vụ. Lịch nền không đợi database trên luồng giao diện; thao tác ghi được giới hạn và xếp riêng theo cửa hàng.

Các thao tác gửi hồ sơ GS1, mua mã, đưa vào lưu thông và thanh toán vẫn giữ bước xác nhận riêng. Nếu hủy hộp thoại CryptoPro, kiểm tra lại chứng thư/chọn cửa hàng trước khi thử ký lại. Dữ liệu thiếu không được tự điền thành giấy tờ hay quyền sở hữu hợp lệ.

Kho KIZ vẫn riêng từng máy. Bộ kiểm thử dùng dữ liệu tổng hợp; chưa thực hiện gửi GTIN, gán KIZ hoặc mua mã thật trên tài khoản seller trong lần phát triển này. Xem [báo cáo triển khai](https://github.com/ntccong2468-lab/Bancapnhat/blob/v1.4.12/docs/validation/2026-10-15-vncode-1412-implementation.md) và [sơ đồ GS1](https://github.com/ntccong2468-lab/Bancapnhat/blob/v1.4.12/docs/architecture/2026-10-15-gs1-automatic-sync.md) để biết kết quả và giới hạn kiểm chứng.
