# Kiểm chứng client license VNCode 1.4.0

Nhánh local `update/vn-code-1.4.0`, nguồn nền `854b65fc3d0a9853718a3219001e4d5f3b5486e6`.
Đây là bằng chứng Linux/fixture của mã đang phát triển, không phải nghiệm thu
website hoặc bộ cài Windows. Không deploy, push hoặc tạo release trong đợt này.

## Đã thực hiện

- Java 25.0.4.1 (Oracle archive/checksum đã đối chiếu), Maven 3.8.5 đúng wrapper.
  Môi trường cloud dùng proxy settings + truststore hệ thống, giữ xác minh TLS.
- Maven `clean verify`: **921 tests, 0 failures, 0 errors, 1 skipped**.
  920 tests đạt. Kiểm thử skipped duy nhất là DPAPI thật, yêu cầu Windows.
- Node `node --test tools/*.test.mjs`: **42 tests, tất cả đạt**, không skipped.
- Sau sửa wrap label sidebar, chạy lại LicensePaneTest và sidebar FXML smoke:
  **5 tests đạt**, package JAR thành công. Không đổi domain logic sau full verify.
- JAR chứa LicenseService/LicensePane, không chứa test fixture authority/JUnit
  bootstrap. Public trust production đúng `{}` và chưa mở activation production.
- Chạy Launcher từ JAR trên Xorg dummy với appdata mới `/tmp/vncode-14-launch-smoke`:
  cửa sổ `VN code v1.4.0` hiển thị Dashboard/sidebar, không lỗi khởi động. Đã dừng
  process thử sau kiểm tra; dữ liệu thử không dùng shop/account/database thật.
- `git diff --check`: sạch.

## Tình huống đã kiểm tra

- Gmail/OTP, approved metadata không tự cấp quyền, public pin thiếu không redeem.
- Raw Ed25519 public/proof/hash tương thích Node, chữ ký byte payload, duplicate
  JSON/sai kiểu/phạm vi/millis, account/device/revision binding, expired/offline0.
- Denial/protocol/signing refusal không mở offline sau restart; cache quyền đã
  ký chỉ dùng cho lỗi mạng/429/5xx hợp lệ, không kéo dài ngày.
- Highwater lưu khi dùng offline, restart với đồng hồ lùi bị từ chối.
- Startup kiểm tra server trước tác vụ mới; refresh đồng gộp, response cũ sau
  logout không khôi phục quyền; queue đầy không bỏ logout và future cũ hoàn tất.
- State codec fixture giữ khóa thiết bị sau logout; corruption/failed-protection
  không ghi plaintext. DPAPI API/purpose/limit được kiểm tra; native roundtrip chưa chạy.
- Guards trực tiếp tại service: zero new HTTP khi denied, revoke trong batch,
  known-unsent claim release, submitted checkpoint và accepted recovery giữ nguyên.
- Shop quota cạnh tranh và database cũ vượt quota; vẫn đọc cache/local/history.
- UI bốn ngôn ngữ, subscribe/dispose không rò listener, callback theo generation.
- Publisher từ chối empty/test trust map và thiếu/stale Windows/backend evidence.

## Chưa thực hiện / cổng còn thiếu

- Deploy Portal 0.3.0/migration/email thật trên vncode.id.vn.
- Public signing pin production; private signing key chỉ do chủ phần mềm giữ trên server.
- OTP/key thật, device concurrency trên D1 remote, lifecycle admin approve/renew/revoke.
- Windows DPAPI, Windows user khác, native build/upgrade/coinstall/print/low-end probes.
- Marketplace/CryptoPro/GS1/printer thật. Không dùng fixture để ghi nhận các mục này đạt.

Xem hợp đồng hiện tại và roadmap đợt nghiệp vụ tiếp theo trong docs/research và
`docs/superpowers/plans/2026-10-13-vn-code-140-progress.md`. `complete:false`,
`productionReady:false` và acceptance `result:blocked` được giữ trong status.
