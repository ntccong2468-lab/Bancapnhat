# Sửa đăng ký Znack theo lô và báo cáo GTIN — 19/10/2026

## Lỗi được sửa

- Worker theo dõi trước đây chỉ lấy thẻ đã có `feed_id`. Thẻ ở checkpoint `READY_TO_SUBMIT` chưa gửi yêu cầu không được tự tiếp tục.
- Trong khoảng thẻ vừa được claim và còn nằm trong hàng đợi worker, truy vấn trạng thái có thể không thấy việc chờ. Luồng đồng bộ vì vậy có thể quay về chu kỳ nhàn rỗi 15 phút. Hiện app tính cả worker đang chạy/xếp hàng và đánh thức đồng bộ cho checkpoint chưa gửi.
- Việc chuẩn hóa, đọc quyền sở hữu thẻ và kiểm tra giấy tờ xảy ra trước dấu mốc gửi HTTP. Lỗi đọc tạm thời trong các bước đó không còn bị gộp vào trạng thái chưa biết kết quả POST.
- Khi bị giới hạn API hoặc lỗi kết nối trong bước chuẩn bị chỉ đọc, app lưu lý do và hẹn tự thử tiếp; không yêu cầu seller bấm Đăng ký lần nữa.
- Chọn toàn bộ chuyển phần xét điều kiện lô lớn sang worker, gom thay đổi selection của JavaFX, lưu cờ thao tác và tập ID để không quét lại cả danh sách mỗi khi vẽ checkbox/nút.
- Khi đối chiếu cả lô, báo cáo ghi theo giao dịch tối đa 250 dòng và dùng lại câu lệnh SQL, giữ nhật ký từng thẻ; không tạo một kết nối/giao dịch mới cho từng dòng.
- Báo cáo lưu toàn bộ lựa chọn trước khi kiểm tra thẻ đầu tiên. Hủy xem/bổ sung dữ liệu được ghi nhận là chưa gửi. Làm mới báo cáo đối chiếu cả lô, kể cả thẻ ngoài trang đang xem.
- Viên thuốc hoàn tất tự ẩn sau 8 giây; lỗi GTIN tự ẩn sau 15 giây. Kết quả và lý do vẫn mở được từ Báo cáo. Bộ hẹn giờ cũ không xóa viên thuốc của tác vụ mới.
- Kết quả GTIN hiển thị số đã xác nhận trên sàn, số chưa cập nhật, số chưa có/ghép được GTIN, số chờ sàn xác nhận và số bị chặn. Lỗi giữa lô giữ lại ID báo cáo và kết quả đã xác nhận trước đó.
- Báo cáo GTIN nhận bằng chứng đọc lại trên sàn riêng với bước kích hoạt mapping/GTIN nội bộ, tránh hiện “chờ” cho GTIN đã thực sự có trên sàn.

## Điều kiện xác nhận

“Catalog đã nhận yêu cầu” có `feed_id` khác với “Đã xuất bản”. App chỉ xác nhận xuất bản sau khi đọc lại đúng GTIN/ID thẻ và trạng thái xuất bản. HTTP 404 tạm thời không được coi là một phản hồi thành công xác nhận không có thẻ. Một phản hồi thành công rỗng sau lượt xử lý đã kết thúc hoặc sau thời gian chờ được báo rõ để người dùng xử lý.

Không tự gửi lại POST chưa rõ kết quả, không tự xin thêm GTIN cho checkpoint cấp mã chưa rõ kết quả và không tự đổi danh tính/TN VED của thẻ cũ. Các checkpoint `GTIN_GENERATED` cũ không có bằng chứng chưa gửi vẫn giữ yêu cầu đối soát; chỉ `READY_TO_SUBMIT` đủ điều kiện tiếp tục gửi tự động.

## Phạm vi kiểm chứng

- Kiểm thử JavaFX dùng nút chọn toàn bộ thật trên danh sách hơn 1.000 thẻ, có lọc “Chưa tạo”; bảng vẫn tải dần, không đưa toàn bộ dòng vào giao diện.
- Kiểm thử workflow với API mô phỏng: tự tiếp tục checkpoint chưa gửi, đánh thức luồng nền, giữ poll ngắn khi worker chưa bắt đầu, lỗi đọc tạm thời, 404 tạm thời và kết quả gửi không xác định.
- Kiểm thử báo cáo: lưu đủ 1.001 thẻ trước thẻ đầu tiên, cập nhật kết quả của 650 thẻ ngoài một trang, giữ kết quả GTIN đã xác nhận khi lượt sau lỗi, thống kê thẻ không có GTIN và không bỏ qua lỗi thẻ riêng lẻ.
- API thật WB, Ozon và Catalog đã đọc thử thành công HTTP 200, mỗi dịch vụ một dòng. Chi tiết an toàn ở `evidence/2026-10-19-znack-batch-hotfix/live-read-access.json`. Các lượt này không tạo/ký thẻ và không ghi GTIN lên sàn.
- Kết quả cuối cùng của bộ kiểm thử và bản build nằm trong `evidence/2026-10-19-znack-batch-hotfix/verification.json` sau khi hoàn tất.

Bản cài Windows 1.5.1 đã phát hành trước đó không được sửa nội dung hoặc ghi đè bằng bản dựng kiểm chứng này. Việc tạo/ký thẻ bằng CryptoPro và ghi GTIN thật trên máy seller cần được phân biệt với kiểm thử workflow/API mô phỏng nêu trên.
