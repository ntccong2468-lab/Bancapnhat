---
id: memory-boundaries
title: Roles and limits of three memory systems
status: active
scope: project:vncode
updated: 2026-10-22
---

# Phân vai bộ nhớ

## Decision
Ngày: 2026-10-22. Người dùng yêu cầu cài Smart Project Memory, Deja Vu và kiểm
tra khả năng tương thích Codex-Mem với Web trước khi kích hoạt.

- Smart Project Memory: skill gốc trong `.agents/skills/`; `.codex-memory/` giữ
  context pack/trạng thái/snapshot/chỉ mục file của VNCode.
- Deja Vu: protocol v0.4, cue trong `memory/impressions.jsonl`; `summary.md` giữ
  thông tin ổn định, `decisions/` giữ quyết định/lỗi, `open-loops/` giữ việc chưa xong.
  Không cần daemon, database hoặc npm install.
- Codex-Mem: nguồn đã kiểm tra nhưng Web chưa có MCP/capture được xác minh;
  chưa bật runtime, notify hoặc đọc hội thoại để ingest. Có `codex` trong container
  không chứng minh client Web sử dụng cấu hình CLI.

Root AGENTS ưu tiên context pack cho khôi phục phiên rồi cue Deja Vu cho nhiệm vụ;
không mặc định nạp toàn cây theo cả hai bộ hướng dẫn. Record quyết định có một
nơi chuẩn; Smart `02_decisions.md` chỉ liên kết.

Bộ nhớ bền vững nhờ file được commit/push và phiên sau đọc AGENTS; không phải
trí nhớ toàn cục ChatGPT hoặc đồng bộ ẩn. Nếu chỉ đọc, báo không lưu được thay vì
khẳng định đã ghi. Không sửa global config hoặc ghi đè MEMORY.md của người dùng.

Liên quan: [Codex-Mem](../open-loops/codex-mem-web.md), [tóm tắt](../summary.md).
Hướng dẫn từ gốc repo: `docs/ai-memory.md`.

## Rationale
Các ràng buộc và bằng chứng nêu trên bảo toàn hành vi đã chấp nhận và tránh lặp sai sót.

## Consequences
Giữ block Deja Vu ở AGENTS theo hợp đồng gốc: doctor nhận dạng các dòng recall bằng tiếng Anh. Nội dung record có thể viết tiếng Việt; giữ các heading metadata chuẩn để lint được.
