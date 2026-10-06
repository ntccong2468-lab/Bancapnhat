# Bảo mật mã nguồn & chống crack — WCode

Tóm tắt kết luận nghiên cứu (07/2026) và cách WCode được bảo vệ.

## Nguyên tắc cốt lõi: bảo vệ nằm ở server, không ở client

Bytecode Java **luôn** decompile được (Vineflower, CFR, JADX...). Obfuscation chỉ **nâng chi
phí** reverse-engineering, không chống được người quyết tâm. Ai đó có thể:
- Decompile app và đọc logic.
- Patch `LicenseFileVerifier.verify(...)` để luôn trả về hợp lệ, hoặc thay
  `DEFAULT_PUBLIC_KEY_B64` bằng khóa của họ để tự ký license.
- Bản cài `--win-per-user-install` nằm ở `%LOCALAPPDATA%` → jar ghi được, không cần quyền admin.

→ Vì vậy **tính năng đáng tiền (mua KIZ / gọi Znack) phải được gate ở server của mình**, kiểm tra
license + device fingerprint trên **mỗi** lời gọi. Một bản crack không mua được KIZ chỉ là bản demo.
Đây là lớp bảo vệ thật; các lớp dưới chỉ là phụ trợ.

Hiện trạng WCode: `LicenseService` xác thực với `license-server` (Ed25519, offline grace 14 ngày,
chống lùi đồng hồ) và gate hai nút mua KIZ. Bước củng cố tiếp theo (khuyến nghị): cho pipeline
mua KIZ đi qua server của mình thay vì gọi Znack trực tiếp từ client.

## Obfuscation

Maven/JavaFX là production build. Profile `-Pobfuscate` dùng cấu hình bảo thủ: không shrink hoặc
optimize và giữ controller FXML, DTO Gson, record/enum cùng model được lưu trong SQLite. Profile này
không được đưa vào release cho tới khi ProGuard được smoke-test đầy đủ với Java 25 và toàn bộ flow
FXML, WB, Ozon, Znack, license, PDF.

Obfuscation không thay thế ký artifact, kiểm tra marketplace boundary hoặc server-side license gate.
- Muốn có string encryption + control-flow obfuscation (ProGuard free không có): cần công cụ trả phí
  **Zelix KlassMaster** hoặc **Allatori** (~vài trăm USD/dev). Chỉ đáng đầu tư nếu quan sát thấy bị crack.

## Việc nên làm khi phát hành

1. Thay `LicenseFileVerifier.DEFAULT_PUBLIC_KEY_B64` bằng public key sinh trên server production.
2. Ký Authenticode cho EXE/MSI (chống cảnh báo SmartScreen + chống sửa installer).
3. Cân nhắc đưa call mua KIZ qua server (điểm 1 phần trên) — giá trị lớn nhất.
