# VNCode 1.4.1 / Portal 0.3.2: Gmail trực tiếp và khóa dùng nhiều máy

Yêu cầu: đăng ký bằng Gmail trong app, xuất hiện ngay trên admin để duyệt/cấp
khóa; mục nhập khóa luôn dùng được, máy tiếp theo dùng cùng khóa mà không phải
đăng ký lại Gmail.

Nguyên nhân: Portal 0.3.1 desktop bắt buộc OTP và gửi email, còn biến
REQUIRE_EMAIL_VERIFICATION=0 chỉ áp dụng luồng tài khoản website. UI cũng khóa
mục kích hoạt khi chưa xác minh Gmail. Tài khoản chưa hoàn tất OTP chưa được
tạo trong users nên admin chưa nhìn thấy.

Sửa cả client và server:

- API /registration/register lưu khách pending ngay, không gửi mail/OTP.
- API /registration/license xác định tài khoản từ khóa VNC được admin cấp.
  Không cấp thiết bị, session hoặc quyền ở bước này.
- Admin có thể duyệt/cấp khóa khi email_verified=0. Giữ email_verified thực,
  không tuyên bố nhập Gmail là xác minh hộp thư.
- Kích hoạt vẫn yêu cầu approved, khóa đúng tài khoản, proof Ed25519 thiết bị,
  quota nguyên tử; mỗi máy có session riêng. Token đăng ký không renew/login/admin.
- Giữ tài khoản, mật khẩu, xác minh email, license đã cấp, hạn, thiết bị và quyền
  hiện có khi một người đăng ký lại Gmail. Mã đã dùng không cộng ngày lần nữa.
- Mục 2 mở cả trên máy chưa đăng ký; tạm khóa khi đang gửi yêu cầu để tránh gửi
  trùng. Giữ DPAPI, public key vncode-1, offline có chữ ký và chặn thu hồi.
- Route OTP cũ được giữ để tương thích app cũ. App mới báo cập nhật Portal nếu
  website trả AUTH_REQUIRED/NOT_FOUND ở các route đăng ký mới.

Kiểm thử tại cloud:

- Targeted LicenseServiceTest/WebLicenseApiTest/LicensePaneTest/FxmlSmokeTest:
  89 đạt, không lỗi/bỏ qua.
- Maven clean verify với Java 25, DISPLAY=:99: 1.182 test, 1.180 đạt, không
  failure/error, 2 trường hợp riêng nền tảng bỏ qua trên Linux; FXML smoke đạt.
- Portal npm run check và npm test: 33 đạt, không lỗi/bỏ qua. Bao gồm pending
  xuất hiện trên admin, thiếu mail/signing vẫn đăng ký được, không có OTP;
  duyệt/kích hoạt/retry/renew/thu hồi; cùng khóa trên 2 máy và chặn máy thứ 3;
  đăng ký lại không thay tài khoản hay có quyền dùng/admin; không đổi mật khẩu.
- Node release/packaging contracts: 48 đạt, không lỗi/bỏ qua.
- Wrangler 4.149.0 deploy --dry-run: bundle và 4 assets hợp lệ; không deploy.

GET /api/config thật bằng User-Agent native OkHttp trả 200, mail_ready=true,
email_verification_required=false, chưa có desktop_registration_mode. Website
chưa có bản sửa. Không POST đăng ký/cấp mã/kích hoạt khách thật trong cloud.
Cloud chưa có CLOUDFLARE_API_TOKEN/account credential nên chủ website cần deploy
theo website/license-portal/HUONG-DAN-CAP-NHAT-032.md. Giữ secret/khóa ký hiện có.

Bộ cài mới tăng version lên 1.4.1, giữ định danh app/data. CI Windows phải kiểm
tra bộ cài, DPAPI, DPI/workspace và nâng cấp dữ liệu, thêm baseline draft 1.4.0
đã kiểm tra SHA-256. Không coi test giả lập là nghiệm thu license production;
license acceptance và public release vẫn chờ deploy/kích hoạt thật.
