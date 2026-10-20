# Tải VNCode 1.4.4 và Portal 0.3.5 cho khóa vncode-2

Ngày kiểm chứng: 15/10/2026 (Asia/Saigon). Source build:
`07c085aab3472e730669acb3ba97073d64155cf6`.

## Bộ cài Windows — cài trước

[Tải artifact VNCode 1.4.4](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38615048269/artifacts/11920585481).
Đăng nhập GitHub nếu cần, giải nén ZIP artifact, mở thư mục `out`, đóng VNCode
và chạy `VN-code-1.4.4-License-Diagnostics-Windows-x64.exe`.

SHA-256 của EXE đã được đối chiếu sau khi tải về:

`c73a6998e551859ebc66be06d5fd0d574087963363b2a986a027ffa47c5bc2bb`

VNCode 1.4.4 thêm public pin vncode-2 và giữ vncode-1. Quyền cũ vẫn yêu cầu đúng
chữ ký, thiết bị, hạn và quota. App cũ chỉ có vncode-1 không thể nhận quyền mới
được ký bởi vncode-2; cài bản mới trên các máy sẽ sử dụng license trước khi đổi
Secret.

## Portal — deploy sau khi cài app mới

[Tải artifact Portal 0.3.5](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38615048269/artifacts/11920515061).
Trong artifact có ZIP gốc `VNCode-Portal-0.3.5-Key-Rotation.zip` và checksum.

SHA-256 của ZIP đã được đối chiếu sau khi tải về:

`b47122a7c8563dfdcf86f0600e4cc0a4c76326784ccfb003e9383eae438b58e3`

ZIP chứa public pin, mã nguồn, assets, công cụ kiểm tra tệp riêng tại máy và
`HUONG-DAN-CAP-NHAT-035.md`; không chứa private key, mật khẩu hoặc node_modules.
Giải nén vào thư mục mới; mở PowerShell ở thư mục có `package.json` và
`wrangler.jsonc`, làm theo [hướng dẫn từng bước](../../website/license-portal/HUONG-DAN-CAP-NHAT-035.md).

Thứ tự: cài VNCode 1.4.4 → kiểm tra tệp private key khớp pin trên máy → deploy
Portal 0.3.5 với ID vncode-2 → nhập Secret trực tiếp từ tệp vào Worker → chẩn
đoán trên admin → kích hoạt thật bằng mã VNC đã cấp. Không nhập public key vào
LICENSE_SIGNING_KEY, không gửi private key qua chat hoặc GitHub.

## Kiểm thử và giới hạn

Build [38615048269](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38615048269)
đã đạt: 88 Java Windows, 56 Portal và 49 hợp đồng đóng gói; không lỗi hoặc skip.
Native launcher, migration 3→5, giữ lịch sử/snapshot và DPAPI đã đạt. XML/JSON
kết quả nằm trong artifact; bản ghi tổng hợp nằm ở
[validation evidence](../validation/evidence/2026-10-15-vncode-144-key-rotation-ci.json).

Đây là bộ cài kiểm thử có public pin thật, chưa phải Release chính thức; chưa
chạy lại toàn bộ workspace/installer-upgrade matrix. Không có private key của
chủ website trong môi trường này. Chưa deploy Portal 0.3.5, chưa nhập Secret
mới và chưa xác nhận kích hoạt production thành công. Các mã license/Gmail
hiện có được giữ, không cần cấp lại chỉ vì xoay khóa ký.
