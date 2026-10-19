# VNCode 1.5.1 — sửa thẻ Catalog lỗi

Thêm luồng **Lỗi → Chọn tất cả → báo cáo → Chỉnh sửa/Hủy → bảng đề xuất → Áp dụng** cho WB và Ozon. Báo cáo đọc toàn bộ snapshot Catalog thuộc shop/tài khoản hiện hành, giữ cả thẻ chưa ghép được sản phẩm; lỗi native cũng xuất hiện. Thẻ không có GTIN/thẻ từ xa cần quay lại luồng đăng ký, không giả tạo feed_id để sửa.

Đề xuất dùng dữ liệu mới từ sàn và schema Catalog: article, màu, size/hệ size, TN VED, thương hiệu, chất liệu, giới tính và giấy tờ. Khi size cũ sai và nhiều biến thể có thể khớp, seller chọn biến thể trong báo cáo. Bảng đề xuất có giá trị trước/sau và các trường bắt buộc; giấy tờ có thể chọn hoặc nhập loại/số/ngày, được registry kiểm tra lại trước ghi. Không hardcode giấy tờ của tài khoản mẫu. TN VED đổi nhóm bốn số phải dùng luồng đăng ký thẻ mới đã có, giữ thẻ cũ.

Áp dụng đóng bảng và chạy nền với viên thuốc dưới giữa bảng nguồn. Ghi danh sách đã chọn vào báo cáo trước khi xử lý, xử lý từng thẻ qua state machine đăng ký native hiện có. Chỉ nhập checkpoint cho thẻ được API xác nhận draft/errors; giữ GTIN, good_id, scope/owner, lịch sử và feed_id. Bảo vệ published/moderation, yêu cầu gửi/chữ ký chưa rõ kết quả, GTIN đang thuộc biến thể khác và các checkpoint đang chạy. Không cấp GTIN hoặc tra quota GS1 cho thao tác sửa cùng thẻ. Đọc lại hai nguồn trước mỗi lần gửi và không tự gửi lại khi timeout.

Báo cáo phân biệt đang xử lý/chờ duyệt/chờ ký/cần xử lý/đã xuất bản. Chỉ native workflow xác minh xuất bản mới được ghi SUCCEEDED; feed receipt không được coi là thẻ đã duyệt. Lý do nguyên văn chỉ có khi Catalog/feed thực sự trả về; đề xuất kiểm tra giấy tờ/size không được trình bày thành kết luận kiểm duyệt.

## Kiểm tra trong máy Linux

- 178 ca qua trong 11 lớp liên quan: service sửa WB/Ozon, các nút báo cáo/Áp dụng/Hủy, controller đăng ký thật, truy vấn và báo cáo, workflow WB/Ozon, registry và sửa sản phẩm theo lô.
- 73 kiểm tra công cụ release qua.
- 16 ca mới: 12 service, 3 hộp thoại, 1 thao tác nút Chọn tất cả trên màn hình WB. Dùng dữ liệu tổng hợp, SQLite thật, mapper/payload và workflow thật với API giả lập.
- Kiểm tra các trường hợp: đúng size 52 thay size 1; giấy tờ đúng nhóm; giấy tờ nhập sai không tạo checkpoint; không cấp GTIN; giữ GTIN/feed, chờ moderation; bảo vệ published/sai good_id; chặn thay đổi sau khi xem đề xuất; không gửi trùng; báo cáo thẻ lịch sử chưa có native checkpoint.
- Bổ sung kiểm tra nâng cấp từ bộ cài Published 1.5.0 với checksum đã lưu; kết quả Windows được ghi riêng sau CI.

Đây là kiểm thử tự động bằng API giả lập. Không tạo/sửa/ký thẻ thật của seller trong đợt này. CryptoPro doanh nghiệp, license production và nghiệm thu thao tác ghi từ desktop lên sàn chưa được xác nhận. Giữ các cờ này false khi phát hành theo yêu cầu của chủ repo.
