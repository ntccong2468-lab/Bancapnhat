# Portal 0.3.5 / VNCode 1.4.4 — chuyển sang khóa vncode-2

Chủ website đã xác nhận Portal 0.3.4 báo Secret cũ không hợp lệ và Database sẵn
sàng. Tệp khóa cũ đã mất. Cặp mới được tạo trên máy của chủ website; mã nguồn
chỉ nhận public key:

```text
Key ID: vncode-2
Public key: SPtRxTUJ78fqt5YMJ4DJ_PZwB2cMYegs-J7nY62ADMQ
```

VNCode 1.4.4 giữ pin vncode-1 và thêm vncode-2. Quyền offline cũ vẫn phải đúng
chữ ký, thiết bị và thời hạn; thêm pin mới không tăng quyền hoặc số ngày. Các
mã VNC, Gmail, thiết bị, quota và dữ liệu cửa hàng không bị xóa khi xoay khóa.

## 1. Cài app mới trước

Đóng VNCode, cài bộ cài Windows 1.4.4 đã cung cấp, mở app và kiểm tra phiên bản
1.4.4. VNCode 1.4.3 trở về trước chỉ tin vncode-1, không nhận được quyền mới
được ký dưới vncode-2. Chưa cần nhập lại mã trong bước này.

## 2. Chuẩn bị Portal và kiểm tra cặp khóa trên máy

Giải nén ZIP Portal **0.3.5** vào thư mục mới; mở PowerShell tại thư mục chứa
`package.json`, `wrangler.jsonc` và `tools`. Dùng Node.js 22.13+ hoặc 24 LTS.
Chạy từng dòng, chờ dòng trước kết thúc:

```powershell
node -p "require('./package.json').version"
npm.cmd ci
node .\tools\check-signing-key.mjs "$env:USERPROFILE\VNCode-License-Keys-vncode-2\LICENSE_SIGNING_KEY.txt"
```

Phiên bản phải là **0.3.5**. Lệnh kiểm tra phải hiện **Khóa riêng khớp public key
vncode-2**. Nếu lệnh báo lỗi, dừng trước bước deploy/đổi Secret; kiểm tra đúng
tệp khóa đã tạo. Công cụ chạy tại máy, không gửi khóa hoặc in nội dung khóa.

Nếu tệp được lưu ở thư mục khác, thay đường dẫn bằng đường dẫn tệp của bạn.
Không tạo lại cặp mới vào thư mục hiện có; sao lưu cả hai tệp an toàn ngoài repo.

## 3. Deploy Portal với Key ID mới

Đối chiếu trong `wrangler.jsonc`: Worker `vncode-portal`, D1 `vncode-accounts`
và database ID đúng project hiện đang phục vụ vncode.id.vn. Bản này đã đặt
`LICENSE_KEY_ID` là **vncode-2**; không có migration mới.

```powershell
npx.cmd wrangler whoami
npx.cmd wrangler d1 export vncode-accounts --remote --output ..\vncode-before-vncode-2.sql
npm.cmd run check
npm.cmd test
npm.cmd run deploy
```

Nếu chưa đăng nhập Cloudflare, chạy `npx.cmd wrangler login`, mở link trong
trình duyệt để cấp quyền, rồi tiếp tục. Không nối link đăng nhập vào lệnh deploy.

Deploy cập nhật Worker/assets và Key ID mới. Secret cũ chưa hợp lệ nên kích
hoạt vẫn chờ bước 4. Không import SQL backup cũ đè D1 đang chạy.

## 4. Nhập khóa riêng trực tiếp từ tệp vào Secret Cloudflare

Sau khi đã cài app mới, kiểm tra cặp khóa thành công và deploy Portal 0.3.5,
chạy lệnh này tại cùng thư mục Portal:

```powershell
Get-Content -Raw "$env:USERPROFILE\VNCode-License-Keys-vncode-2\LICENSE_SIGNING_KEY.txt" | npx.cmd wrangler secret put LICENSE_SIGNING_KEY --name vncode-portal
```

Lệnh đọc tệp trên máy và gửi trực tiếp tới Secret của Worker; không cần dán
private key vào chat. Đợi Wrangler báo tạo/cập nhật Secret thành công. Nếu
Wrangler yêu cầu xác nhận tạo Worker mới, dừng và đối chiếu lại tên Worker/tài
khoản Cloudflare: Worker hiện có phải được cập nhật.

Giữ `RESEND_API_KEY` và các cấu hình khác hiện có. Public key không được nhập
vào `LICENSE_SIGNING_KEY`. Dùng thư mục Portal 0.3.5 cho những lần deploy tiếp
theo; config của bản cũ đặt lại ID vncode-1.

## 5. Kiểm tra và kích hoạt thật

1. Mở https://vncode.id.vn/api/config: `portal_version` phải là **0.3.5**.
2. Đăng nhập admin trên vncode.id.vn, nhấn Ctrl+F5, bấm **Kiểm tra cấu hình
   license** ngay trong trang. Cookie SameSite=Strict có thể không gửi phiên
   khi bấm link API từ một website khác; dùng nút trong admin.
3. Kết quả cần `key_id` / `client_key_id` là `vncode-2`,
   `signing_key_valid:true`, `signature_matches_client:true`,
   `key_id_matches:true`, `database_ready:true`, `activation_configuration_ready:true`.
4. Trong VNCode 1.4.4, nhập mã VNC đã cấp cho đúng Gmail đã duyệt rồi kích hoạt.
   Mã chưa dùng vẫn được giữ trong D1; không cần cấp lại chỉ vì đổi khóa ký.
5. Khi cần nhiều máy, đặt Số máy ở admin lớn hơn 1 trước khi nhập cùng khóa
   trên máy tiếp theo. Giới hạn này không tự thay đổi trong bản cập nhật.

Chẩn đoán cấu hình sẵn sàng chưa chứng minh seller kích hoạt thành công. Sau
đó cần kiểm tra máy thứ hai trong quota, máy vượt quota bị từ chối, gia hạn,
thu hồi và offline. Chỉ gửi kết quả/ảnh thông báo; không gửi mã VNC, token,
private key hay mật khẩu.

## Nếu cần hoàn tác

Nếu deploy lỗi, dữ liệu D1 đã sao lưu vẫn còn; không xóa khách hoặc mã license
để thử sửa khóa. Vì khóa riêng vncode-1 đã mất/Secret cũ không dùng được,
không thể khôi phục kích hoạt bằng cách chỉ đổi Key ID về vncode-1. Giữ cặp mới
đã sao lưu, sửa cấu hình đúng Worker và đối chiếu public pin trước khi thử lại.
