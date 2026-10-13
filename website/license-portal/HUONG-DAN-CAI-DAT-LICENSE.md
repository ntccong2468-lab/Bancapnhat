# Cập nhật vncode.id.vn lên Portal 0.3.1

Đây là hướng dẫn cho chủ Cloudflare. Không có lệnh nào trong tài liệu đã được
chạy lên website/D1 thật trong quá trình chuẩn bị bộ mã.

## 1. Chuẩn bị và sao lưu

Giữ bản Worker hiện đang chạy để có thể rollback. Export D1 hiện tại trước:

```sh
npx wrangler d1 export vncode-accounts --remote --output ../vncode-before-031.sql
```

Kiểm tra tên Worker/binding/database_id trong wrangler.jsonc khớp tài khoản của
bạn. Bộ mã giữ cấu hình D1 từ ZIP đã cung cấp. Không tạo lại database, không nhập
file backup cũ đè lên database hiện tại, không chạy lệnh cấp admin hàng loạt.

## 2. Cài và kiểm tra

```sh
npm ci
npm run check
npm test
```

## 3. Migration giữ dữ liệu

Áp dụng theo thứ tự cho database cũ (cả ba chạy lại được):

```sh
npx wrangler d1 execute vncode-accounts --remote --file migrations/0001_license_codes.sql
npx wrangler d1 execute vncode-accounts --remote --file migrations/0002_desktop_license.sql
npx wrangler d1 execute vncode-accounts --remote --file migrations/0003_multi_customer_scaling.sql
```

Không dùng schema.sql như bản thay thế dữ liệu production. Các tài khoản,
hash mật khẩu, license và lịch sử cũ giữ nguyên.

## 4. Email cho OTP và khóa license

Chuẩn bị tài khoản Resend, xác minh domain gửi vncode.id.vn/DNS theo hướng dẫn
nhà cung cấp; EMAIL_FROM phải là địa chỉ thuộc domain đã xác minh.
Thiết lập secret trong Cloudflare bằng terminal của bạn:

```sh
npx wrangler secret put RESEND_API_KEY
```

Không nhập secret vào chat, wrangler.jsonc hoặc Git. Cấu hình vars.EMAIL_FROM
và PUBLIC_BASE_URL đã có. Không cần thêm binding EMAIL khi dùng Resend.
Nếu dùng một dịch vụ email khác, thay adapter và chạy lại test trước triển khai;
không dùng email routing chỉ gửi được tới các địa chỉ đã xác minh cho khách bất kỳ.

## 5. Khóa ký quyền Windows

Trên máy tin cậy, tạo một thư mục MỚI bên ngoài project:

```sh
node tools/create-signing-key.mjs /absolute/path/outside-checkout/vncode-signing-private
npx wrangler secret put LICENSE_SIGNING_KEY
```

Trên Windows PowerShell, dùng thư mục nằm ngoài checkout:

```powershell
$keyDirectory = Join-Path $env:USERPROFILE "VNCode-License-Keys-20261014"
node tools/create-signing-key.mjs $keyDirectory
npx.cmd wrangler secret put LICENSE_SIGNING_KEY
```

Không chạy cả hai ví dụ tạo khóa; giữ cặp khóa đã tạo. Ở prompt secret, nhập
nội dung file LICENSE_SIGNING_KEY.txt qua terminal riêng.
Private key là PKCS8 DER Base64URL; không phải license VNC gửi cho khách.
Đặt LICENSE_KEY_ID trong vars (mặc định vncode-1). Giữ backup khóa ký an toàn.
Chỉ public key raw 32 byte trong LICENSE_PUBLIC_KEY.txt được pin vào build
VNCode Windows. Không gửi hoặc đưa thư mục private vào ZIP/Git. Công cụ từ chối
thư mục đã tồn tại để tránh ghi đè khóa. Windows cần tích hợp theo docs/DESKTOP-API.md.

## 6. Deploy và nghiệm thu

```sh
npx wrangler deploy --dry-run
npm run deploy
```

Mở vncode.id.vn/admin bằng tài khoản admin hiện có. Kiểm tra khối quản trị mới.
Dùng Gmail test của bạn để nghiệm thu qua client test/ứng dụng đã tích hợp:
nhận OTP → chờ duyệt → duyệt/cấp khóa → nhận email → nhập khóa → kiểm tra quyền.
Kiểm tra sai OTP, khóa sai/Gmail khác, máy thứ hai, gia hạn, khóa tài khoản,
thu hồi máy, mất mạng và hết quyền offline. Sau mất phản hồi kích hoạt, dùng
challenge mới và cùng khóa/device để đối soát/thử lại; thời hạn không cộng lại.

Nếu gửi khóa thất bại, khóa vẫn được cấp và hiển thị một lần để bạn gửi thủ công.
Mất khóa chưa dùng: hủy khóa đó và cấp khóa mới, không truy xuất khóa đầy đủ từ DB.
Mặc định danh sách khách lọc Chờ duyệt; sau duyệt chuyển sang Đã duyệt để xem.

Sau deploy, kiểm tra Cron Trigger mỗi phút trong Cloudflare. Worker chỉ dọn
credential đã hết hạn, tối đa 500 dòng/bảng/lần; không xóa tài khoản hoặc lịch sử.
Quan sát CPU, lỗi/rate limit, D1 rows read/written và độ trễ gia hạn bằng các máy
test được cấp phép trước khi mở rộng số khách. Mô phỏng 1.000 khách trong test
không thay được nghiệm thu tải trên D1 thật.

Khi đổi khóa ký, dùng một LICENSE_KEY_ID mới và pin public key tương ứng trong
client trước khi chuyển server. Bản 0.3.1 không đổi private key hoặc secret của
bạn; cache tự tách cấu hình mới khỏi các yêu cầu đang dùng cấu hình cũ.

Bản này chưa được nghiệm thu trên Cloudflare/email/Windows thật. Dry-run và
kiểm thử SQLite không thay bằng chứng production. Không công bố offline hay
khóa license hoạt động trên app cũ trước khi client được tích hợp và kiểm tra.
