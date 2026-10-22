---
id: summary
title: VNCode project summary
status: active
scope: project:vncode
updated: 2026-10-22
---

# VNCode

Ứng dụng Windows Java/JavaFX cho seller WB/Ozon; JDK 25, Node 22, schema 6.
Mục tiêu hiện tại: bộ nhớ dự án/phiên và kinh nghiệm có thể tiếp tục qua Git.
Context pack phiên: `.codex-memory/P01_C02_vncode/04_context_pack.md` từ gốc repo.

Ràng buộc: UI/nút Znack giữ nguyên; GTIN xác nhận bằng readback; mua KIZ đúng
phạm vi/danh tính, không mua lặp, chỉ gán mã đủ điều kiện. 1.5.11 là mốc phát hành
đã kiểm chứng ngày 2026-10-22; kiểm tra GitHub lại khi cần công bố bản tiếp theo.

Quyết định:
- [Znack và UI](decisions/znack-registration.md)
- [WB GTIN và KIZ pending](decisions/wb-gtin-and-kiz.md)
- [Release và bài học công bố](decisions/release-baseline.md)
- [Ranh giới ba lớp bộ nhớ](decisions/memory-boundaries.md)

Việc còn mở:
- [Nghiệm thu shop thực tế](open-loops/production-reconciliation.md)
- [Kích hoạt Codex-Mem ở client phù hợp](open-loops/codex-mem-web.md)

Không lưu bí mật, dữ liệu seller, hội thoại hoặc log thô trong bộ nhớ công khai.
