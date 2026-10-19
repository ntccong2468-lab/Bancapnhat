# VNCode 1.5.1 — sửa thẻ Catalog lỗi

Luồng WB/Ozon: **Lỗi → Chọn tất cả → báo cáo → Chỉnh sửa/Hủy → đề xuất → Áp dụng**. Báo cáo giữ cả thẻ lỗi lịch sử chưa có checkpoint native, không giả tạo feed_id. Khi một thẻ khớp nhiều biến thể, seller chọn chính xác màu/size trước khi xem đề xuất.

Đề xuất đọc dữ liệu mới trên sàn, đúng GTIN/good_id từ Catalog và schema hiện tại của danh mục. Cho xem trước/sau, sửa trường thiếu, chọn hoặc nhập giấy tờ thật. Giấy tờ được tra lại registry; không hợp lệ bị chặn. Cảnh báo phạm vi TN VED được tách khỏi lý do kiểm duyệt, hiện rõ trước Áp dụng theo API 5.68. Xem [quy tắc công bố](2026-10-19-catalog-official-rules.md).

Áp dụng đóng bảng và chạy từng thẻ trong nền với viên thuốc ở dưới giữa bảng nguồn. Lưu danh sách đã chọn trước khi xử lý, giữ GTIN khi sửa cùng thẻ, gửi good_id đúng hợp đồng edit và không tiêu GTIN GS1 cho thao tác này. Tiếp tục checkpoint cũ chưa gửi cũng giữ good_id. Lịch sử cũ và feed_id được lưu; thay đổi nhóm bốn số dùng luồng thẻ mới đã xác nhận, giữ thẻ cũ.

Bảo vệ thẻ published/moderation, chữ ký hoặc POST chưa rõ kết quả, GTIN đang thuộc biến thể khác, thay đổi credential/owner, dữ liệu sàn hoặc revision Catalog sau khi xem đề xuất. Không gửi lại timeout. Không cho phép đổi good_id ngoài dự kiến rồi báo thành công. Chỉ xác nhận SUCCEEDED khi đúng thẻ được đọc lại published; các trạng thái còn lại phân biệt chờ duyệt/chờ ký/cần xử lý. Lý do nguyên văn chỉ có khi Catalog/feed thực sự trả về.

Kiểm tra Linux/Xvfb của quy tắc mới: 263 ca trong nhóm đăng ký và UI qua; thêm hai ca tiếp tục checkpoint cũ được kiểm tra riêng, 73 kiểm tra công cụ release qua. Các lượt 178/55 ca trước đó là kiểm thử phiên bản điều kiện chặn cũ, không được dùng để tuyên bố quy tắc mới đã nghiệm thu.

Run Windows 39255533688 trước đây có hai timeout trình điều khiển modal. Đã sửa driver; run 39268181948 tiếp theo qua đủ 1913 ca (1911 passed, 2 platform skips), nhưng hủy build/publication sau yêu cầu quy tắc công bố. Cần lượt Windows mới cho nguồn thay đổi hiện tại trước Latest.

API seller đã đọc lại thẻ, giấy tờ và schema; không ghi thẻ hoặc sàn thật trong đợt này. CryptoPro doanh nghiệp, license production và nghiệm thu ghi từ app vẫn chưa được xác nhận. Giữ các cờ nghiệm thu này false khi phát hành theo yêu cầu của chủ repo.
