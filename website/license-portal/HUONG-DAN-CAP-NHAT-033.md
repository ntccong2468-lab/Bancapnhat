# Cập nhật Portal 0.3.3 trên Windows

Bản này thêm xóa license đã hủy, xóa Gmail và đăng ký lại. Website thật hiện đang
ở 0.3.2; các thay đổi chỉ xuất hiện sau khi deploy cả Worker và public assets.

Gmail admin được hỗ trợ license desktop: sau cập nhật, trong mục Người dùng bấm
**Cấp license VNCode** ở tài khoản admin, đặt ngày/máy/cửa hàng và cấp khóa. Trong
VNCode đăng ký đúng Gmail admin hoặc nhập trực tiếp khóa vừa cấp. Quyền quản trị
website và mật khẩu được giữ nguyên; desktop vẫn cần khóa hợp lệ và còn thời hạn.

1. Giải nén ZIP Portal 0.3.3 vào thư mục mới. Mở PowerShell tại thư mục có
   `package.json` và `wrangler.jsonc`. Dùng Node.js 22.13+ hoặc 24 LTS.
2. Đối chiếu tên Worker `vncode-portal`, database `vncode-accounts` và database ID
   trong wrangler.jsonc với project đang phục vụ vncode.id.vn.
3. Chạy lần lượt:

```powershell
npm.cmd ci
npx.cmd wrangler login
npx.cmd wrangler d1 export vncode-accounts --remote --output ..\vncode-before-033.sql
npm.cmd run check
npm.cmd test
npx.cmd wrangler deploy --dry-run
npm.cmd run deploy
```

Từ 0.3.2 không có migration mới. Giữ database đang dùng, LICENSE_SIGNING_KEY,
RESEND_API_KEY và key ID vncode-1. Không tạo lại cặp khóa ký, không nhập SQL backup
cũ đè dữ liệu thật. File backup xuất ở trên chỉ để khôi phục nếu cần.

4. Mở https://vncode.id.vn/api/config: `portal_version` phải là `0.3.3`.
   Mở /admin, nhấn Ctrl+F5. License ở trạng thái Đã hủy có nút Xóa license đã hủy.
   Người dùng có nút Xóa Gmail; tài khoản admin không có nút xóa.
5. Để xóa Gmail, nhập lại đúng Gmail theo hộp thoại xác nhận. Thao tác xóa cả
   license, thiết bị, các phiên và đăng ký liên quan; nhật ký quản trị được giữ.
   Quyền online cũ ngừng hoạt động. Quyền offline đã ký vẫn đến hạn đã cấp.
6. Trên app, nhấn Kiểm tra trạng thái để nhận rằng phiên đăng ký cũ hết hạn,
   hoặc dùng nút Xóa đăng ký trên máy này để bỏ phiên cũ. Đăng ký lại cùng Gmail tạo khách mới
   Chờ duyệt. Bạn cần duyệt và cấp khóa mới; mã cũ không được sử dụng lại.

## Kiểm tra liên kết đã thực hiện

Ngày 14/10/2026, API đăng ký thật trả 201 / pending và API trạng thái trả 200 cho
Gmail thử nghiệm `ntccong2468+vncode-test-c1e5059f@gmail.com`. Chủ website đã xác
nhận thấy Gmail này trong mục đăng ký desktop trên admin. Chưa cấp license,
thiết bị hoặc gửi email cho tài khoản thử. Có thể dùng nút Xóa Gmail mới để dọn
chính tài khoản thử này sau khi cập nhật và kiểm tra đăng ký lại.

## Lỗi kích hoạt trên desktop

VNCode 1.4.2 là bản kiểm thử hiển thị mã lỗi transport, gồm DNS/HTTPS/timeout.
Việc website nhận đăng ký không chứng minh khóa ký server khớp public key hoặc
kích hoạt license thành công. Nếu vẫn thất bại, gửi mã lỗi hiển thị trong app;
không gửi khóa license, token, private key hoặc mật khẩu qua chat.
