# Kết quả kiểm tra bản Portal 0.3.0

- npm run check: đạt cho backend và giao diện JavaScript.
- npm test: 17 kiểm thử, tất cả đạt, không bỏ qua.
- Test nghiệp vụ: OTP, giới hạn thử/hết hạn, đăng ký chờ duyệt, bảo toàn tài khoản
  cũ, quyền admin, cấp/đổi/thu hồi khóa, chữ ký Ed25519, proof thiết bị, chống replay,
  giới hạn máy, hai kích hoạt đồng thời, retry không cộng ngày, expiry/suspension.
- Test giao diện bằng DOM mô phỏng: hiển thị Gmail, duyệt/cấp khóa, cảnh báo email
  gửi thất bại, thu hồi thiết bị và escape tên máy. Chưa test trình duyệt thật.
- Test migration: chạy lại được, giữ hash/tài khoản cũ, foreign_key_check sạch.
- Resend HTTP contract kiểm tra bằng mock; chưa gửi email thật.
- wrangler deploy --dry-run: bundle đạt; không deploy production.
- Schema chạy trên D1 LOCAL bằng Wrangler. Worker chạy trên workerd cục bộ.
- HTTP runtime: health, đăng ký/login tài khoản fixture, auth Admin, requests,
  settings, audit và static assets đạt; anonymous status bị từ chối. Chưa cấu
  hình email thì API đăng ký desktop trả 503 đúng dự kiến.
- Đã sửa Worker entrypoint: chỉ export default; export ITERATIONS của bộ cũ
  gây lỗi khởi động workerd. Test helpers vẫn nằm trong index.js cho unit test.

Giới hạn: test concurrency dùng SQLite với batch tuần tự mô phỏng D1; chưa
nghiệm thu cạnh tranh trên D1 remote. Email/provider, secret/public pin, Gmail
thật và VNCode Windows chưa được cấu hình/tích hợp/nghiệm thu. Không sửa website,
D1 remote, SQL backup thật hoặc repository VNCode Windows.
