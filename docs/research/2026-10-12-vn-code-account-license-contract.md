# VN code — hợp đồng đề xuất cho tài khoản, thiết bị và quyền offline

> Cập nhật 13/10: phần mật khẩu và hợp đồng thiết bị trong tài liệu này là kế hoạch lịch sử. Luồng đã chốt chuyển sang Gmail/OTP trong ứng dụng và khóa VNC từ Portal 0.3.0. Đã có mã backend chuẩn bị và client; xem [hợp đồng hiện tại](2026-10-13-vn-code-desktop-license-contract.md) nếu đọc từ plans, hoặc `2026-10-13-vn-code-desktop-license-contract.md` trong research. Production vẫn chờ pin/deploy/nghiệm thu.

Ngày 12/10/2026. Thực hiện [thiết kế B đã được người sở hữu duyệt](../superpowers/specs/2026-10-12-vn-code-web-license-design.md). **Đây là hợp đồng đề xuất để phát triển client và kiểm thử bằng fixture, chưa phải API đã triển khai trên website.** Mã Worker, schema và test backend chưa được cung cấp. Kiểm tra lại repository ứng viên `ntccong2468-lab/vncodeidvn` bằng `git ls-remote` vẫn không trả ref; chỉ có README đã nhận.

## API đang được README mô tả

`POST https://vncode.id.vn/api/client/login`, JSON `{email,password}`, trả `access_token` và `user` có `active`, `status`, `expires_at`. `GET /api/client/me` dùng Bearer. Token có hiệu lực 7 ngày theo README; token không phải lease và không cho phép sử dụng offline 7 ngày. Chỉ GET health và GET client/me không xác thực đã được kiểm tra; chưa đăng nhập admin hoặc seller thật.

Client giữ nguyên mật khẩu, không trim, không lưu mật khẩu; email bỏ khoảng trắng đầu/cuối, giữ dấu `+` và dấu chấm. Không biến đăng nhập email website thành đăng nhập Google. Trường `active`/role trong JSON không thay thế chữ ký lease. Tên/kiểu ID và thời gian trong API cũ phải được đối chiếu từ backend trước adapter production.

## Quy ước chung cho API bổ sung

- Origin duy nhất `https://vncode.id.vn`, HTTPS và xác minh TLS; không redirect Bearer/mật khẩu, không nhận URL server do người dùng nhập.
- Bearer của tài khoản hiện tại cho mọi endpoint client; cookie/admin role không được dùng thay Bearer để tạo quyền desktop.
- JSON UTF-8, request/response tối đa **65.536 byte**; payload lease/proof giải mã tối đa **32.768 byte**. Base64URL không có padding, signature Ed25519 đúng 64 byte.
- Thời gian là số nguyên Unix **giây UTC**, không milliseconds, không float/string. ID tài khoản là chuỗi opaque 1–128 ký tự; backend chuyển ID thật sang chuỗi tại ranh giới API, không cast số lớn qua JavaScript Number.
- Public key thiết bị là Ed25519 **DER SubjectPublicKeyInfo (SPKI)**, Base64URL. `devicePublicKeyHash` là SHA-256 của DER này, 64 ký tự hex viết thường. Private key không gửi lên server.
- Lease dùng namespace `product="vncode"`, `formatVersion=1`. Quyền chuẩn phiên bản này: `seller_api`, `shop_manage`, `gtin`, `kiz`, `listing_write`, `packing`, `print_new`, `gs1`. Quyền lạ không tự mở chức năng; thiếu quyền tương ứng thì chặn tác vụ đó.

## Challenge và bằng chứng thiết bị

`POST /api/client/device/challenge`

```json
{"device_public_key":"<SPKI Base64URL>","purpose":"activate"}
```

`purpose` chỉ nhận `activate`, `renew`, `deactivate`. Server tính hash từ key, không tin hash người gọi khai. Challenge sống **120 giây**, một lần, gắn tài khoản/thiết bị/mục đích. Với renew/deactivate, thiết bị phải thuộc tài khoản và chưa bị thu hồi. Activate vẫn kiểm tra lại quota tại bước consume; challenge chưa giữ slot.

Response HTTP 200:

```json
{"challenge_id":"<opaque>","proof":"<Base64URL bytes JSON bên dưới>","server_time":1791763200,"expires_at":1791763320}
```

Byte proof UTF-8 chứa chính xác các trường:

```json
{"formatVersion":1,"product":"vncode","accountId":"<opaque>","devicePublicKeyHash":"<64 hex>","purpose":"activate","challengeId":"<opaque>","nonce":"<32 random bytes Base64URL>","issuedAt":1791763200,"expiresAt":1791763320}
```

Client kiểm tra binding, tuổi challenge và giới hạn trước ký. Chữ ký thiết bị ký **byte ASCII `vncode.device-proof.v1\n` nối byte proof đã giải mã**; không stringify lại JSON. Server lấy proof đã lưu của challenge, đối chiếu toàn bộ binding, chữ ký và consume nonce nguyên tử; không chỉ kiểm tra một nonce người gọi tự chọn.

`POST /api/client/device/activate`, `/api/client/license/renew`, `/api/client/device/deactivate` dùng cùng request:

```json
{"device_public_key":"<SPKI Base64URL>","challenge_id":"<opaque>","signature":"<64 bytes Base64URL>"}
```

Activate/renew thành công trả account metadata và lease. Deactivate trả `{deactivated:true,device_id:<hash>,server_time:<epoch>}` sau khi server thu hồi; app chỉ báo giải phóng slot khi nhận thành công. Đăng xuất cục bộ không tự gọi deactivate.

Mất response activate không được retry lại nonce đã dùng. Client dùng challenge mới cho cùng public key; server activate cùng thiết bị còn active phải idempotent và không tăng slot. Hai activate khác nhau cạnh tranh slot cuối phải kiểm tra/consume/đăng ký bằng thao tác nguyên tử D1; chọn SQL thực tế sau khi đọc schema và runtime backend.

## Lease được server ký

Response activate/renew:

```json
{
  "account":{"id":"<opaque>","email":"seller+1@gmail.com","status":"approved","expires_at":1792000000,"max_devices":1,"offline_hours":72,"device_count":1},
  "lease":{"keyId":"<production key ID>","payload":"<Base64URL UTF-8 JSON>","signature":"<64 bytes Base64URL>"}
}
```

Payload lease bắt buộc:

```json
{"formatVersion":1,"product":"vncode","accountId":"<opaque>","devicePublicKeyHash":"<64 hex>","licenseRevision":1,"issuedAt":1791763200,"notBefore":1791763200,"expiresAt":1792000000,"subscriptionExpiresAt":1792000000,"maxDevices":1,"offlineHours":72,"permissions":["seller_api","shop_manage","gtin","kiz","listing_write","packing","print_new","gs1"]}
```

Server ký **chính xác byte payload UTF-8** bằng khóa Ed25519 riêng của chủ VN code. Client xác minh chữ ký với public key pin tin cậy trong build trước parse JSON. Metadata dùng hiển thị; metadata không nâng cấp quyền/đổi hạn của payload ký.

Client từ chối payload có trường trùng, thiếu/sai kiểu, format/product/account/device/key ID không khớp, revision thấp hơn mốc đã biết, `maxDevices` ngoài 1–10, `offlineHours` ngoài 6–168, hoặc thời gian không thỏa:

```text
0 < notBefore <= issuedAt <= expiresAt <= subscriptionExpiresAt
expiresAt <= issuedAt + offlineHours * 3600
notBefore <= effectiveNow < expiresAt
```

`issuedAt` quá tương lai hơn dung sai 300 giây không được chấp nhận. Dung sai đồng hồ **không cộng 300 giây vào expiresAt**. Hạn tài khoản phải hữu hạn; role admin hoặc `expires_at=null` của website cũ không tạo lease vĩnh viễn hay đường tắt trong desktop. Backend phải cung cấp entitlement có thời hạn và chính sách cho chính tài khoản chủ trước nghiệm thu.

Build production có pin public key riêng; fixture kiểm thử chỉ được nạp qua constructor test. Không có public key tự tin từ response, runtime property đổi pin, master key hoặc fallback mở quyền. Xoay key qua bản cập nhật được kiểm tra hoặc danh sách key được root pin ký; bản đầu dùng pin tĩnh và chưa cần endpoint xoay key.

## Thiết bị và quản trị

`GET /api/client/devices` trả `{devices:[{device_id,created_at,last_seen_at,last_lease_expires_at,current,revoked}],max_devices:<1..10>}`. Danh sách chỉ thuộc Bearer hiện tại, tối đa 100 dòng; client không có chức năng thu hồi máy khác. Máy khác được chủ phần mềm quản lý tại website.

API admin đề xuất, giữ middleware/cookie/CSRF hiện có sau khi đối chiếu nguồn:

| Endpoint | Request / kết quả |
|---|---|
| `POST /api/admin/users/:id/license-policy` | `{max_devices:1..10,offline_hours:6..168}`; tăng revision, audit trước/sau |
| `GET /api/admin/users/:id/devices` | Thiết bị của đúng account, last seen, hạn lease offline cuối, tình trạng thu hồi |
| `POST /api/admin/users/:id/devices/:deviceId/revoke` | Thu hồi đúng thiết bị, vô hiệu challenge tương ứng, audit; không sửa tài khoản khác |

Duyệt/gia hạn/khóa tài khoản tăng revision và áp dụng theo quyền backend; tài khoản bị khóa không được activate/renew. Với active account, đổi mật khẩu/thu hồi phiên phải làm Bearer cũ hết hiệu lực theo cơ chế backend. Các đổi policy không thể thu hồi ngay lease đã cấp cho máy offline. UI admin phải hiển thị hạn offline cuối, kể cả sau khi đã giải phóng slot.

## Lỗi và cách client xử lý

API mới trả `{error:{code:"<mã>",message:"<thông báo công khai>"}}`; client chọn bản dịch theo code, không log/hiển thị body hoặc message upstream tùy ý.

| HTTP / code | Quyền desktop |
|---|---|
| 403 `ACCOUNT_PENDING`, `ACCOUNT_SUSPENDED`, `SUBSCRIPTION_EXPIRED`, `DEVICE_REVOKED` | Ghi trạng thái từ chối được bảo vệ; ngừng tác vụ mới, không dùng lease cache cũ |
| 409 `DEVICE_LIMIT` | Không cấp lease mới; hiển thị hạn máy và đường quản lý |
| 401 `SESSION_EXPIRED` hoặc 401 API cũ | Yêu cầu đăng nhập lại; không coi là lỗi mạng, không tự dùng lease cũ để mở tác vụ |
| 400/403 `INVALID_PROOF`, `CHALLENGE_EXPIRED`, `CHALLENGE_USED` | Không mở quyền; challenge expired/used chỉ được lấy challenge mới có giới hạn khi online, không gửi lại cùng chữ ký |
| 429, 5xx, timeout, lỗi mạng/TLS | Có thể dùng lease cache đã ký còn hạn, đúng tài khoản/máy và chưa có từ chối đã biết; không tự kéo dài lease |
| 404 endpoint mới, JSON/chữ ký/key ID không hợp lệ, response vượt giới hạn | Chưa xác minh được hợp đồng/quyền; không fallback active từ JSON hoặc cache để che lỗi protocol |

Đã nhận khóa/hết hạn rõ ràng thì một 5xx đến sau không khôi phục lease. Đăng xuất hoặc bắt đầu đăng nhập tài khoản khác tăng generation; response cũ không ghi token/lease/UI. Lần đầu luôn online, không có token/lease thử tích hợp sẵn.

## Tính tương thích và nghiệm thu

Hợp đồng này không thay users/sessions/mã VNC hiện có. Backend mới dùng migration additive và test DB cũ thực tế; không tạo database production mới hoặc làm mất audit/redeem. Không triển khai Worker/D1 từ README để chứng minh client.

Fixture test sinh bằng khóa test riêng: cùng exact byte payload, Node Web Crypto ký → Java xác minh; Java device proof ký → Node Web Crypto xác minh; test replay/concurrency thực thi trong runtime D1 khi có backend. Mocks không thay Windows DPAPI hoặc website production.

Trước phát hành: nhận/đọc mã backend, đối chiếu adapter và plan backend với middleware/schema thật; kiểm thử Workers/D1; chủ phần mềm thiết lập signing secret trực tiếp trên server, đối chiếu public pin; dùng account test nghiệm thu duyệt/gia hạn/khóa/giới hạn máy/offline. Giữ VN code 1.4.0 chưa phát hành khi thiếu bất kỳ cổng nào.
