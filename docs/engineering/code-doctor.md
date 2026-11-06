# Code Doctor trong repo VN Code

Skill được cài tại [SKILL.md](../../.agents/skills/code-doctor/SKILL.md), nguyên văn từ file `code-doctor-skill-for-codex-web.zip` do người dùng gửi ngày 06.11.2026. `AGENTS.md` dẫn tới skill khi điều tra lỗi hoặc độ ổn định. Đây là hướng dẫn cho AI làm việc với repository, không phải plugin chạy trong bộ cài VN Code.

- SHA-256 ZIP: `69b4a91d0909e14d46eda1c71b9b9548e9269897bd07395cc6bc217e12afd504`.
- SHA-256 `SKILL.md`: `8270891e2fe2927e452a05cb2b6cf8ec656890dfc9372e9872a427a597d46cac`.
- ZIP có hai tệp Markdown, không có script cài đặt. Chỉ lấy đúng đường dẫn skill, không ghi đè README gốc hay các skill có sẵn.

Mở task Codex tại nhánh chứa skill rồi yêu cầu, ví dụ:

```text
Dùng $code-doctor để kiểm tra VN Code. Đọc AGENTS.md và docs/memory/CURRENT.md,
đối chiếu mã đang sửa với Latest, tái hiện lỗi bằng dữ liệu thử, sửa đúng nguyên
nhân và chạy hồi quy. Ghi rõ lệnh, kết quả và phần chưa kiểm chứng.
```

Nếu danh sách skill của phiên chưa cập nhật, mở task mới/refresh ở đúng nhánh; AI vẫn có thể đọc trực tiếp file qua hướng dẫn trong `AGENTS.md`.

Quy trình: xác định baseline → tái hiện → theo dõi nguyên nhân → sửa nhỏ nhất → kiểm thử hồi quy → kiểm tra diff và báo cáo. Phạm vi kiểm thử theo [testing.md](testing.md); dữ liệu tạm theo [debugging.md](debugging.md). Quyền thao tác tiếp tục theo yêu cầu người dùng và hướng dẫn repo; skill không tự cấp quyền ghi seller API, mua, ký, merge hay phát hành.

Lượt sử dụng đầu tiên: [báo cáo Code Doctor 06.11.2026](../validation/2026-11-06-code-doctor.md).
