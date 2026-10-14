# Cập nhật Portal 0.3.4 và kiểm tra Secret license

Lỗi VNCode đang báo là `SIGNING_NOT_CONFIGURED`: Worker không import được khóa
Ed25519 riêng. Trạng thái `signing_ready:true` của Portal 0.3.2 chỉ kiểm tra
Secret có tồn tại; không chứng minh khóa đúng định dạng hoặc khớp với VNCode.

Tệp khóa gốc đã mất. Trước tiên giữ Secret đang có trên Cloudflare: 0.3.4 đọc thêm
PKCS8 Base64 chuẩn, Base64URL, PEM PRIVATE KEY, khoảng trắng và chuỗi JSON có dấu
nháy. Bản này không thay cặp khóa hoặc cấp license thử trên website thật.

1. Tải ZIP Portal 0.3.4, giải nén vào thư mục mới. Mở PowerShell ở thư mục có
   `package.json` và `wrangler.jsonc`. Cần Node.js 22.13+ hoặc 24 LTS.
2. Kiểm tra Worker `vncode-portal` và D1 `vncode-accounts` trong `wrangler.jsonc`
   đúng project đang phục vụ vncode.id.vn. Giữ database, Secret và key ID hiện có.
3. Chạy từng dòng:

```powershell
npm.cmd ci
npx.cmd wrangler login
npx.cmd wrangler d1 export vncode-accounts --remote --output ..\vncode-before-034.sql
npm.cmd run check
npm.cmd test
npx.cmd wrangler deploy --dry-run
npm.cmd run deploy
```

Từ 0.3.2 không cần migration mới. Không import SQL backup cũ đè dữ liệu thật.
Deploy cập nhật cả Worker và public assets; thêm Secret vào Worker khác sẽ không
sửa được API đang phục vụ tên miền này.

4. Mở https://vncode.id.vn/api/config: `portal_version` phải là `0.3.4`.
5. Đăng nhập `/admin`, nhấn Ctrl+F5, bấm **Kiểm tra cấu hình license** trong mục
   **Đăng ký từ VNCode & thiết bị**. Hoặc mở trên cùng trình duyệt:
   https://vncode.id.vn/api/admin/desktop/diagnostics
6. Đọc kết quả:

| Kết quả | Việc cần làm |
|---|---|
| `signing_key_valid:false` | Secret chưa chứa khóa riêng Ed25519 PKCS8 hợp lệ, hoặc `LICENSE_KEY_ID` sai định dạng. |
| Khóa hợp lệ nhưng `signature_matches_client:false` | Khóa đang lưu không thuộc public key được gắn trong app. |
| `key_id_matches:false` | Kiểm tra `LICENSE_KEY_ID`; app hiện tin `vncode-1`. Không đổi ID của cặp khóa khác để giả khớp. |
| `database_ready:false` | Xem tên bảng/cột thiếu trong `schema`, đối chiếu các migration đã có trước khi kích hoạt. |
| `activation_configuration_ready:true` | Cấu hình đã vượt kiểm tra; thử kích hoạt bằng khóa license của bạn trong VNCode. Đây chưa phải kết quả kích hoạt thật. |

Kết quả không chứa private key, token hay dữ liệu khách. Có thể gửi các cờ
chẩn đoán hoặc ảnh kết quả để đối chiếu. Không gửi nội dung Secret qua chat.

## Nếu Secret không thể dùng và không còn bản sao khóa riêng

Không thể tính lại private key từ public key
`Gg7F92gqXnCLGdMbI130qHcBJ2hiI6KseZBgWkNCTeI`. Cần xoay cặp khóa đồng bộ:

1. Trên máy tin cậy, chạy công cụ trong ZIP vào thư mục mới ngoài mã nguồn:

```powershell
node .\tools\create-signing-key.mjs "$env:USERPROFILE\VNCode-License-Keys-vncode-2"
```

2. Sao lưu an toàn thư mục này. `LICENSE_SIGNING_KEY.txt` là khóa riêng; chỉ
   sao chép vào Cloudflare Secret `LICENSE_SIGNING_KEY`, không tải lên GitHub.
3. Dùng key ID mới `vncode-2`. Gửi **chỉ key ID và nội dung
   `LICENSE_PUBLIC_KEY.txt`** để gắn public key mới vào VNCode và cập nhật pin
   chẩn đoán của Portal; giữ pin cũ để đọc quyền offline cũ đến hạn.
4. Chuẩn bị bản app/Portal có pin mới trước, rồi đổi Secret và ID của Worker,
   deploy, kiểm tra chẩn đoán và thử kích hoạt lại. VNCode 1.4.3 hiện chỉ chứa
   pin `vncode-1`, nên chưa thể nhận quyền ký bởi khóa mới.

Mã `VNC-…` đã cấp vẫn ở D1; xoay khóa ký không yêu cầu xóa khách hay cấp lại
mã chưa dùng. Với mã đã dùng, kiểm tra cấp quyền/gia hạn và giới hạn thiết bị
theo chính sách hiện có. Chữ ký offline cũ không được ký lại bằng public key.

## Sau khi sửa cấu hình

Gmail admin cũng được cấp license desktop: trong **Người dùng**, chọn **Cấp
license VNCode**, đặt số ngày/máy/cửa hàng, cấp khóa rồi nhập vào ứng dụng. Máy
mới nhập cùng khóa trực tiếp trong giới hạn số máy admin cấp. Quyền admin của
website không tự cấp quyền desktop.

Kiểm tra tối thiểu: đăng ký Gmail xuất hiện ở admin; duyệt/cấp khóa; kích hoạt
máy thứ nhất; máy thứ hai trong giới hạn; từ chối máy vượt giới hạn; thu hồi và
gia hạn. Website nhận đăng ký thành công chưa chứng minh bước kích hoạt đạt.
