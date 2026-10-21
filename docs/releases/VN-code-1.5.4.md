# VNCode 1.5.4

- Cập nhật: sửa bước «Chuẩn bị dữ liệu» khi đồng bộ nền liên tục ghi SQLite/WAL. Bản sao lưu giữ một thời điểm dữ liệu cố định để không phải sao chép lại từ đầu sau mỗi lần ghi; bổ sung tiến độ sao chép và kiểm tra dữ liệu. Giữ checksum, kiểm tra toàn vẹn, bản sao phục hồi và quyền hủy trước khi bàn giao bộ cài.
- Kho KIZ WB/Ozon: sửa định danh yêu cầu mua bổ sung để được hàng đợi mua chung chấp nhận. Mỗi GTIN có mã yêu cầu riêng; không làm mới danh tính của đơn mua đã gửi.
- Nhận KIZ: không tự gửi lại yêu cầu cấp một khối mã khi mất phản hồi. App dùng đường đối soát của đơn mua hiện có; nhận một phần vẫn chờ cho đến khi số mã đã lưu đủ số đã mua.
- Trạng thái mua KIZ: đối chiếu đúng buffer của GTIN đang mua. Khi thiếu buffer, trả nhiều buffer hoặc thiếu trạng thái, giữ checkpoint và báo rõ để tiếp tục kiểm tra đơn hiện có, tránh tạo đơn mua trùng.

Giữ dữ liệu, mẫu đăng ký Znack, quy tắc giấy tờ/TN VED, đồng bộ và giao diện của bản nền 1.5.3. Bộ cài mới phải qua toàn bộ kiểm tra Windows và nâng cấp giữ dữ liệu từ bộ cài 1.5.3 đã công bố, gồm bàn giao EXE `/passive /norestart`. Chưa nghiệm thu một giao dịch mua KIZ trả phí hoặc ký bằng CryptoPro thật trong phiên bản này.

Nếu bản đang cài còn kẹt ở bước chuẩn bị dữ liệu, thoát VNCode hoàn toàn rồi chạy bộ cài EXE mới để cài nâng cấp trực tiếp và giữ dữ liệu.
