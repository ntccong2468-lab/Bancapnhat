# Hàng đợi đăng ký không tự dừng sau năm lỗi đọc

Ảnh seller gửi ngày 24.10.2026 cho thấy lô 117 thẻ có 14 thẻ xác nhận trên Catalog, 25 feed được nhận, 11 đang chờ đối soát, 103 chưa xác nhận và không có thẻ bị từ chối. App ghi «Đã tạm dừng hàng đợi sau 5 lần thử kiểm tra lại chưa có kết quả». Mã cũ có đúng nhánh chuyển PAUSED khi đọc lại thất bại đủ năm lần; thao tác tiếp tục thủ công là điều kiện để chạy tiếp.

## Sửa nghiệp vụ

- WB và Ozon giữ hàng đợi ở trạng thái đợi cho lỗi mạng, giới hạn yêu cầu và lỗi đọc tạm thời. Tự tiếp tục theo thời điểm lưu trong database. Lùi thời gian thử tới tối đa năm phút cho backoff của app; `Retry-After` dài hơn vẫn được giữ. Không giới hạn tổng số lượt đọc bằng một lần tạm dừng tự động.
- Thẻ được Catalog nhận vẫn giữ GTIN/feed ID. Lần thử tiếp theo đối soát yêu cầu gốc; không tạo thẻ hoặc xin GTIN khác vì đã thử nhiều lần. Lỗi dữ liệu, owner/cấu hình/chữ ký/license cần xử lý vẫn theo các guard hiện có.
- WB xử lý các dòng chưa gửi trước các dòng WAITING_REMOTE cũ. Endpoint trạng thái chậm không chiếm toàn bộ lượt chạy trước những thẻ còn chưa được gửi. Thứ tự gửi trong nhóm chưa gửi vẫn giữ, mỗi thẻ một POST.
- Nâng cấp tự khôi phục riêng PAUSED với mã cũ `ZNACK_BATCH_RETRY_LIMIT_REACHED` hoặc `REGISTRATION_BATCH_RETRY_LIMIT: ...`, giữ scope và toàn bộ danh tính. Chờ 30 giây trước lượt khôi phục đầu; deadline phía dịch vụ vẫn được transport/worker kiểm tra. Seller đã Tạm dừng/Hủy thì không tự mở lại.
- Timer WB được khử trùng theo profile/shop/lô. Khôi phục hàng đợi có deadline tương lai sẽ đặt timer, kể cả sau khi mở app. Khi timer chạy, kiểm tra lại deadline hiện hành để timer cũ không vượt qua khoảng chờ mới. Tạm dừng/Hủy vô hiệu hóa ticket đang đợi.

## Kiểm tra và bàn giao

190 ca mục tiêu đạt: đọc thất bại hơn 5/12 lần vẫn chờ; phục hồi cùng GTIN/feed, không allocation/POST trùng; ưu tiên thẻ chưa gửi sau cooldown; giữ deadline một giờ của Ozon; khôi phục pause tự động qua repository mới và giữ manual Pause/Cancel. Có hồi quy controller WB, pane Ozon, báo cáo, HTTP transport và vòng đời nền.

Mã tổng hợp `3ede7581ab17425ff975b75f603df93c9166ce7c` đã đạt **1.240 ca hồi quy cuối, không lỗi/thất bại**, hash cây `4f205e14fd7707150466fbe12212930aa5bbf2dc02c2fac5a116457c1e75d9a5`. [Biên nhận](../validation/2026-10-24-catalog-registration-retry-continuation-verify.json).

Đây là sửa tiếp trên nhánh `perf/catalog-single-card-throughput`, đi cùng tối ưu [gửi từng thẻ](2026-10-24-catalog-single-card-throughput.md). Latest 1.5.14 đã phát hành chưa chứa phần này. Không đổi số phiên bản hoặc ghi đè tài sản bất biến trong lượt sửa; phát hành kế tiếp phải dựng từ mã đã kiểm tra, dùng phiên bản mới và chạy Windows native/upgrade. Không gửi feed production, ký seller hoặc mua KIZ có phí để thử.
