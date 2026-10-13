# VNCode 1.4.0: public key từ chủ website

Ngày 14/10/2026 chủ website cung cấp key ID `vncode-1` và public key Ed25519:

`Gg7F92gqXnCLGdMbI130qHcBJ2hiI6KseZBgWkNCTeI`

Đã kiểm tra Base64URL canonical, 32 byte và pin vào resource trusted-keys.json.
Không tạo khóa thay thế và không nhận/lưu private key. Chủ website báo đã triển
khai Portal và đăng nhập admin được.

HTTP read-only với User-Agent của client: /api/health trả 200/ok, /api/config
trả mail_ready=true, /api/client/registration/status không có credential trả
401/AUTH_REQUIRED. [Bằng chứng](evidence/2026-10-14-license-public-endpoints.json).
mail_ready chỉ phản ánh cấu hình, chưa chứng minh OTP được gửi/nhận. Python UA
bị Cloudflare 403/1010; UA okhttp/4.12.0 dùng trong app nhận được JSON đúng.

Chưa xác minh server ký bằng private key tương ứng; đăng ký Gmail/OTP, phê duyệt,
kích hoạt, quota thiết bị, retry, thu hồi và offline production còn chờ. Các cờ
nghiệm thu vẫn giữ false; resource có public key không tự mở quyền sử dụng.
Bộ cài Windows có pin cần được tạo để thử luồng thực tế trước phát hành.

Ảnh admin do chủ website cung cấp hiển thị Gửi email: Đã cấu hình và Ký license: Chưa cấu hình. Worker cần secret LICENSE_SIGNING_KEY là private key cùng cặp với public key đã pin; giữ LICENSE_KEY_ID=vncode-1. Không tạo lại cặp khóa hoặc đưa private key vào repo.

Kiểm chứng bản có pin: Maven clean verify 1.175 kiểm thử, 1.174 đạt, 0 failure, 0 error, 1 DPAPI chỉ chạy Windows bỏ qua trên Linux; 42/42 Node đạt. JAR chứa chính xác public key do chủ website cung cấp, không chứa fixture authority. Khởi động JAR trên Linux/Xorg với appdata riêng thành công, integrity_check=ok và không lỗi khóa ngoại.

SHA-256 JAR có pin: `dcf5c97a8b409db8577bbd5f5233eedaba57b1fcdfa31f0cf9161b87dd6d6974`. [Runtime evidence](evidence/2026-10-14-vncode-140-pinned-runtime.json). Kết quả cloud không chứng minh activation production hoặc native Windows.
