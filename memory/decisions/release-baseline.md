---
id: release-baseline
title: Immutable 1.5.11 baseline and publisher lessons
status: active
scope: project:vncode
updated: 2026-10-22
---

# Bản nền 1.5.11 và kinh nghiệm công bố

## Decision
Ngày kiểm chứng: 2026-10-22. 1.5.11 công khai Latest, tag bất biến `v1.5.11`,
nguồn `2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`. Commit hoàn tất công bố
`ef5bd2c6de024a0f29eb0e0ec703b2df0330ae66`. Đã gỡ 30 release cũ, giữ history/tag.
Không chạy lại cleanup từ ghi chú này; kiểm tra yêu cầu và dữ liệu hiện tại.

Windows: 2.879 ca (2.877 đạt, 2 bỏ qua), Node 117/117; nâng cấp 1.5.10 → 1.5.11
và updater thực đã xác minh. Bộ cập nhật Windows chọn MSI trước EXE. Đã từng có
helper kiểm chứng kỳ vọng nhầm EXE: sửa helper theo hành vi thật, không đổi updater.

Bài học công bố:
- Draft có thể không tìm được qua get-by-tag; kiểm tra bằng release ID/danh sách.
- GitHub khóa release sau publish: không gắn thêm asset vào release bất biến.
  Lưu proof bổ sung trong Git/Actions artifact.
- Source ZIP có thể CRLF do git archive Windows; so sánh văn bản chuẩn hóa khi
  phù hợp, còn Git bundle giữ blob gốc. Không báo thiếu nguồn vì khác line ending.
- Cloud từng bị chặn upload asset/log redirect; kiểm tra phản hồi thực. Save
  network draft không có nghĩa runtime đã áp dụng. Publisher Actions là đường đã dùng.

Lý do: nguồn, gói công khai và proof phải cùng mốc; tránh ghi đè release đã chốt.
Bằng chứng từ gốc repo: `docs/releases/BASELINE.md`,
`docs/validation/2026-10-22-vncode-1511-publication.md`,
`docs/validation/evidence/1511/public-verification.json`,
`docs/validation/evidence/1511/updater-verification.json`.
Liên quan: [tóm tắt](../summary.md).

## Rationale
Các ràng buộc và bằng chứng nêu trên bảo toàn hành vi đã chấp nhận và tránh lặp sai sót.

## Consequences
Mỗi bản mới cần nguồn và bằng chứng phù hợp; không thay tag/asset bất biến. Tài liệu bộ nhớ không tự tạo phiên bản ứng dụng.
