# VNCode 1.4.0 — Gmail/OTP và khóa license từ Portal 0.3.0

Luồng hiện tại thay thế phần email/mật khẩu trong thiết kế ngày 12/10.
Nguồn backend đã nhận và chuẩn bị: VNCode License Center 0.2.0, sau đó Portal
0.3.0 với `src/desktop.js` và migration `0002_desktop_license.sql`. Bộ website
chưa triển khai; unit test không chứng minh vncode.id.vn chạy hợp đồng này.

## Client và server

1. Trong License của VNCode, nhập Gmail và gửi đăng ký.
2. `/api/client/registration/start` gửi OTP; client giữ registration_token bằng DPAPI.
3. `/verify` nhận OTP; `/status` đọc trạng thái qua token, không tra cứu chỉ bằng Gmail.
4. Khách chờ chủ phần mềm duyệt trên Admin và cấp khóa VNC gắn đúng account.
5. Khách nhập khóa trong VNCode; `/license/challenge` tạo proof action activate.
6. Client Ed25519 ký exact UTF-8 message `vncode-device-v1\nactivate\n{id}\n{nonce}`.
7. `/license/activate` kiểm tra OTP identity, approval, code và quota; trả access_token,
   account và envelope quyền đã ký. Thời hạn chỉ cộng một lần khi redeem khóa.
8. `/license/renew` dùng access_token thiết bị và proof action renew, không mật khẩu.

Origin production cố định `https://vncode.id.vn`, không redirect hoặc tự retry
credential. Response <=65536 byte UTF-8; signature/payload Base64URL canonical;
JSON từ chối duplicate trường, sai kiểu, timestamp milliseconds và phạm vi bất hợp lệ.

Public key thiết bị là raw Ed25519 32 byte Base64URL. Hash trong lease là SHA256
của CHUỖI Base64URL đó, khớp backend 0.3.0; không dùng SPKI hash của kế hoạch cũ.
Client ký message xác nhận domain/action/id/nonce; private key giữ DPAPI current-user.

Registration token và access token backend 0.3.0 có hạn 30 ngày. Hết phiên cần
xác minh Gmail lại và kích hoạt cùng khóa/device; server không cộng ngày lần hai.
Logout giữ device pair, xóa session/account/lease; không giải phóng slot server.

## Quyền sử dụng

Chữ ký Ed25519 được kiểm tra với public pin trong resource trusted-keys.json
trước khi parse quyền. Metadata active=true hoặc trạng thái approved không tự
cấp quyền. Lease phải khớp account/device/hash/revision, product và thời hạn.

Offline có hạn từ server và không vượt hạn tài khoản. offlineHours=0 chỉ dùng
quyền online tối đa 300 giây sau lần kiểm tra; không phục hồi offline qua restart.
Khi khởi động với session đã lưu, kiểm tra server trước tác vụ mới. Chỉ lỗi mạng,
429/5xx hợp lệ được dùng cache đã ký còn hạn; 401/403, protocol hoặc cấu hình ký
không hợp lệ không mở offline. Denial lưu trong DPAPI, có marker không chứa
secret khi việc ghi DPAPI thất bại để ngăn hồi phục cache cũ.

Một worker, queue8 và một timer chung cho tất cả shop. Refresh đồng gộp; logout/
đổi đăng ký ưu tiên, hoàn tất các future bị hủy, không mất khi queue đầy. Generation
chặn response cũ. Wall/highwater/monotonic chống lùi giờ; highwater lưu khi startup,
offline fallback và tick định kỳ. Đối chiếu native Windows/disk fault vẫn cần.

## Service boundaries và dữ liệu

- Tác vụ đọc seller mới, shop, listing/bulk, GTIN, Znack, KIZ, đóng hàng/in mới và
  GS1 kiểm tra quyền tại service, sau queue/lock và trước request mới.
- Card đã gửi giữ checkpoint; denial trước gửi giải phóng claim theo owner/hash
  và trả trạng thái đã biết chưa gửi. Không biến license denial thành timeout.
- Accepted order/feed/ship được đối soát theo journal gốc; không có skipLicense
  chung hoặc thay shop/owner khi recovery. Tác vụ mới của recovery vẫn cần quyền.
- Xem dữ liệu cục bộ, lịch sử và sao lưu không cần quyền seller mới.
- Số shop được quan sát từ SQL cục bộ, thêm shop kiểm tra quota trong lock.
  Dữ liệu cũ vượt maxShops chặn tác vụ mới cho đến khi giảm shop hoặc tăng quota;
  cho phép quản lý shop khi quyền SHOP_MANAGE hợp lệ, không tự xóa shop.

## Cổng phát hành

Resource production hiện là `{}`: chưa nhận public pin của chủ phần mềm và
không chèn khóa test. Vì vậy binary hiện không thể kích hoạt production.
`tools/license-release-gate.mjs` chặn publish 1.4.0 khi thiếu pin, source-matched
Windows/DPAPI/second-user proof, backend Gmail/quota/retry/offline/revoke acceptance.
Private signing key chỉ trên website; không đưa vào repo hoặc app.

Chưa thực hiện: deploy Worker/D1, nhận OTP thật, pin thật, hoạt động Windows
DPAPI/đổi user, account/shop/printer thật, phát hành EXE hoặc tự cập nhật.


## Bổ sung Portal 0.3.1 và mô hình nhiều khách

Hợp đồng route/proof ở trên được giữ. Source bổ sung tại
`website/license-portal` có cache import khóa ký theo secret/key ID, checkpoint
quyền sau ký và migration `0003_multi_customer_scaling.sql`/cleanup có giới hạn.
Mỗi khách dùng desktop và dữ liệu seller riêng; website chỉ dùng chung đăng ký/
license. Admin cần cấp maxShops ít nhất 10 cho tài khoản quản lý 10 cửa hàng.

Client renew khi cadence 150–180 giây đến hạn, tick mỗi 60 giây; thời điểm thực tế
làm tròn theo timer. Có jitter khởi đầu và backoff lỗi, thao tác thủ công vẫn kiểm
tra ngay. Kiểm thử nhiều khách cục bộ không chứng minh năng lực D1 production.
[Kiến trúc](../architecture/vncode-140-multi-client.md) và
[kiểm chứng](../validation/2026-10-14-vn-code-140-scaling.md).
