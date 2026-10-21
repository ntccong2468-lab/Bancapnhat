# Hợp đồng desktop Portal 0.3.3

Origin cố định https://vncode.id.vn. JSON UTF-8, tối đa 12000 ký tự/request;
TLS bắt buộc, không redirect mật khẩu/token. Thời gian Unix giây UTC.
Lỗi JSON `{error:"CODE"}` ở API mới. Không gửi giá trị email/khóa/token vào URL.
Không cần mật khẩu website. Mọi token/private key phía Windows lưu bằng DPAPI.

## Đăng ký

1. POST `/api/client/registration/register`, `{email:"customer@gmail.com"}`.
   Trả 201 `{registration_id,registration_token,email,status,expires_in:2592000}`.
   Khách xuất hiện ngay trong admin, mặc định pending. Không cần OTP, email service,
   mật khẩu hoặc tài khoản website trước. Gmail lowercase/trim; giữ dấu chấm/dấu +.
   Đăng ký lại giữ tài khoản, mật khẩu, email_verified, trạng thái duyệt và hạn cũ.
   Nhập Gmail KHÔNG chứng minh quyền sở hữu hộp thư và không tự cấp quyền.
2. Máy đã có khóa có thể POST `/api/client/registration/license`, `{code:"VNC-..."}`
   mà không nhập Gmail. Server xác định tài khoản từ khóa đã được admin cấp,
   trả cùng registration response với status approved. Khóa sai/void/tài khoản
   suspended/khóa đã dùng và hết hạn trả INVALID_LICENSE; không cấp device/lease.
   Cùng mã có thể kích hoạt nhiều máy, giới hạn bởi max_devices do admin đặt.
3. GET `/api/client/registration/status`, Bearer registration_token. Trả email/status/expires_at.
   Không cấp quyền chỉ vì approved: phải kích hoạt license và kiểm tra thiết bị.

Registration token chỉ được xem trạng thái và kích hoạt bằng mã được cấp; không
đăng nhập website, quản trị hoặc renew. Token hết hạn thì đăng ký lại cùng Gmail
hoặc nhập khóa để tạo credential mới. Cột desktop_registrations.verified=1 biểu
thị credential được phép dùng; users.email_verified chỉ thay đổi khi có xác minh
hộp thư thật. Không cần migration mới từ 0.3.1.

`/registration/start` và `/registration/verify` giữ lại cho client OTP cũ. App mới
chỉ dùng `/register` hoặc `/license`; Portal cũ cần cập nhật thay vì fallback OTP.

## Chứng minh thiết bị

Client sinh cặp Ed25519; giữ private key DPAPI, public_key raw 32 byte Base64URL
không padding. Không dùng fingerprint phần cứng do khách nhập làm bằng chứng.

POST `/api/client/license/challenge`, Bearer registration_token (activate) hoặc
access_token thiết bị (renew), `{action:"activate"|"renew",public_key}`.
Trả `{challenge_id,message,expires_at}`. Client ký **đúng UTF-8 message trả về**,
không JSON.stringify message, không thay newline. Signature 64 byte Base64URL.
Challenge chỉ dùng một lần trong 120 giây, ràng buộc credential, key và action.

## Kích hoạt

POST `/api/client/license/activate`, Bearer registration_token:
`{challenge_id,public_key,signature,code:"VNC-...",device_name:"Máy đóng hàng"}`.

Server yêu cầu user approved, khóa đúng account và giới hạn máy, không bắt OTP.
DB batch cấp slot máy và redeem/extend nguyên tử. Khóa chưa dùng cộng days;
khóa đã redeem đúng account không cộng thời gian lần nữa. Retry cần challenge mới.
Máy đã thu hồi không được hồi sinh bằng khóa cũ. Máy khác chỉ nhận slot khi có chỗ.

Trả `{access_token,expires_in:2592000,user,device_id,lease,online_valid_until}`.
Access token thiết bị 30 ngày; hết hạn cần đăng ký lại hoặc nhập khóa và kích hoạt lại cùng
khóa/device. Token đăng ký không dùng để renew. Khóa không được lưu/log plaintext.

## Kiểm tra lại

POST `/api/client/license/renew`, Bearer access_token thiết bị,
`{challenge_id,public_key,signature}` lấy từ challenge action renew.
Trả user/device_id/lease/online_valid_until (không tạo token mới).

Server giới hạn kích hoạt/gia hạn **30 lần/giờ/mỗi token**, challenge 60 lần/giờ.
Client tự gia hạn mỗi 150–180 giây, có jitter giữa các máy và backoff khi lỗi;
tránh timer 60 giây vì sẽ vượt hạn ngay khi nhiều khách dùng lâu.
429 yêu cầu lùi thời điểm thử lại, không gửi lại dồn dập. Không dùng cùng một
token/thiết bị cho nhiều khách; mỗi máy dùng credential và private key của mình.

Lease `{key_id,payload,signature}`: payload Base64URL bytes UTF-8 JSON,
server Ed25519 ký chính xác bytes này. Public key pin trong build; KHÔNG lấy key
mới từ response để tự tin. Client xác minh signature rồi mới parse strict JSON,
đối chiếu product/account/devicePublicKeyHash, formatVersion, revision và hạn.

Payload gồm formatVersion=1, product=vncode, accountId, deviceId,
devicePublicKeyHash (SHA256 của chuỗi public_key Base64URL), licenseRevision,
issuedAt,notBefore,expiresAt,subscriptionExpiresAt,maxDevices,maxShops,offlineHours,
permissions. expiresAt=min(subscriptionExpiresAt,issuedAt+offlineHours*3600).
Clock rollback phải phát hiện; không kéo dài hạn. offlineHours=0 cho quyền offline
hết hạn ngay, chỉ dùng xác nhận online tối đa đến online_valid_until (5 phút).

401 yêu cầu xác thực lại; 403 ngừng quyền, không fallback offline; 409 kiểm tra
quota/challenge rồi xử lý theo code. 503 SIGNING_NOT_CONFIGURED là lỗi cấu hình,
không phải mất mạng để mở quyền. Timeout/5xx có thể dùng lease cache hợp lệ còn
hạn khi chưa nhận từ chối đã biết. Response malformed/chữ ký sai phải fail closed.
403 LICENSE_CHANGED nghĩa là quyền đã thay đổi trong khi ký; không sử dụng
snapshot cũ hoặc coi đó là lỗi mạng. Lần kiểm tra mới cần challenge mới.

## Admin (cookie/Bearer tài khoản admin cũ)

- GET `/api/admin/desktop/requests?status=pending&search=...&page=0`.
- GET `/api/admin/desktop/settings`: metadata cấu hình, không có secret.
- POST `/api/admin/desktop/users/:id/approve-issue`:
  `{days:30,max_devices:1,max_shops:1,offline_hours:72,send_email:true}`.
  Duyệt user và tạo key trong batch; trả key chỉ một lần và email_sent. Email là
  tùy chọn để gửi khóa, không phải điều kiện duyệt hoặc kích hoạt.
  Ngày license bắt đầu lúc khách redeem. Nếu đã có hạn, cộng từ max(now,hạn cũ).
  Tài khoản có role=admin cũng được cấp license desktop. Đăng ký, cấp mã, đổi mã
  và gia hạn không đổi role/password hoặc biến registration/device token thành
  quyền quản trị website. Desktop vẫn yêu cầu mã đúng tài khoản, hạn còn hiệu lực,
  proof thiết bị và quota; role=admin không miễn các kiểm tra này.
- POST `/api/admin/desktop/users/:id/policy`: cùng giới hạn, không cần days.
- GET `/api/admin/desktop/users/:id/devices`.
- POST `/api/admin/desktop/devices/:deviceId/revoke`.
- POST `/api/admin/license-codes/:id/delete`, `{confirm:true}`: chỉ xóa mã
  state=void chưa từng đổi license; giữ audit, không đổi quyền của khách khác.
- POST `/api/admin/users/:id/delete`, `{confirm_email:"customer@gmail.com"}`:
  chỉ admin xóa user thường. Trong một D1 batch, xóa challenge/credential/thiết
  bị/policy/license/redemption liên quan, các phiên website, action token và tài
  khoản. Giữ audit theo UUID cũ; không ghi lại Gmail vào audit xóa. Trả
  `{ok:true,registration_reset:true,last_offline_expires_at}`. Đăng ký tiếp theo
  tạo account ID mới / pending, không khôi phục quyền hoặc khóa cũ. Chỉ đặt lại
  quota của tài khoản/email/token liên quan; giữ quota chung theo IP.
- API cũ `/api/admin/users/:id/grant`, `/status`, `/license/revoke` vẫn dùng;
  khóa tài khoản xóa phiên desktop, thu hồi license thu hồi tất cả thiết bị.

Admin xem được hạn offline cuối. Quyền ký đã cấp không thu hồi tức thì khi
máy offline. Hạ max_devices cần thu hồi máy trước; policy không hồi tố lease cache.
Giới hạn cửa hàng do app thực thi với maxShops trong quyền đã ký.
