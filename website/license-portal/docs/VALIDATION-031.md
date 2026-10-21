# Portal 0.3.1 — kiểm thử cục bộ ngày 13/10/2026

`npm run check` đạt. `npm test`: **28/28 đạt**, không bỏ qua kiểm thử.

Đã kiểm tra:

- 100 và 1.000 khách độc lập gửi yêu cầu gia hạn đồng thời. Mỗi quyền Ed25519
  xác minh được, đúng account ID, device ID, public-key hash và revision;
  chính sách test cho phép 10 cửa hàng/1 máy. Không có seller API/đơn/KIZ thật.
- 1.000 tác vụ import khóa đồng thời dùng một promise; thay key ID hoặc secret
  lấy khóa mới; import cũ hoàn tất muộn không thay cache mới; lỗi import hồi phục.
- Proof của khách khác, thiết bị thu hồi và license hết hạn không nhận quyền.
  Thu hồi hoặc đổi revision trong khi ký chặn trả quyền bằng checkpoint cuối.
- Dọn hạn mỗi lần có giới hạn, giữ token còn hạn và dữ liệu tài khoản/thiết bị/
  license/audit; migration index chạy hai lần giữ dữ liệu và khóa ngoại sạch.
- Luồng Gmail/OTP, Admin duyệt/cấp khóa, kích hoạt một lần, giới hạn thiết bị,
  email lỗi, trang quản trị và hash mật khẩu cũ vẫn đạt bộ kiểm thử trước.

SQLite trong RAM, WebCrypto Node và D1 adapter giả dùng để kiểm tra tính đúng.
Thời gian của bộ test không được dùng làm số đo năng lực Cloudflare/D1 thật.
Chưa deploy Worker, chưa chạy migration vào D1 thật, chưa thay secret/khóa ký,
chưa gửi email thật hoặc nghiệm thu các máy Windows khách hàng.
