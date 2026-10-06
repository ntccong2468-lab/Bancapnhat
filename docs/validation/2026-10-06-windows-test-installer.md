# Bộ cài thử Windows x64 — 06/10/2026

Đã tạo EXE trên runner Windows theo yêu cầu của người dùng. Bộ cài giữ tên
và installer identity WCode hiện có, đóng gói Java runtime kèm theo.

- Nhánh: `feat/windows-gtin-sync-20261006` tại `ntccong2468-lab/Vncode`.
- Commit build: `05ff94424cb1335bceadb9390589379148cde953`.
- [Lượt build thành công](https://github.com/ntccong2468-lab/Vncode/actions/runs/37519724842).
- [Tải artifact Windows x64](https://github.com/ntccong2468-lab/Vncode/actions/runs/37519724842/artifacts/11440685457).
- File: `WCode-1.1.32-Ozon-Test.exe`, 141.020.672 byte; kèm file `.sha256`.
- Artifact GitHub được giữ 14 ngày; có thể cần đăng nhập GitHub để tải.

SHA-256 của **EXE**, đã đối chiếu với checksum do runner Windows tạo:

```text
01b7d6dc635498e0c799bf1bada19ac15de72df633ed7951c13eedd539406d4a
```

| Kiểm tra | Kết quả |
| --- | --- |
| Java/FXML, `mvnw.cmd -B clean verify` | 518 tests; 0 failures, errors, skipped |
| Node contracts | 18 tests passed; 0 failed |
| Native packaging, `build.bat exe` | Thành công trên Windows |
| Artifact tải về | SHA-256 khớp; header PE x86-64 hợp lệ |
| Cài EXE và chạy launcher trên máy người dùng | Chờ nghiệm thu |

## Cài thử

Tải ZIP ở link artifact, giải nén, đóng WCode đang chạy rồi mở
`WCode-1.1.32-Ozon-Test.exe` trên Windows x64. Không cần cài Java riêng.
Đây là bộ cài **chưa ký Authenticode**, chưa được coi là bản phát hành chính thức.

Màn hình **GTIN WB / Ozon** có đọc, ghép, ánh xạ, xem trước và lịch sử.
Production ADD/REPLACE vẫn bị khóa tới khi hợp đồng API chính thức được xác minh.
Kiểm thử mutation dùng fixture; lượt tạo bộ cài không gửi dữ liệu lên seller.
Windows scaling 100%/125%, nâng cấp dữ liệu qua installer, CryptoPro/chứng thư,
DPAPI runtime và tích hợp seller thật vẫn chưa được nghiệm thu trong lượt này.
