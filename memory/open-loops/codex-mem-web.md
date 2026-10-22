---
id: codex-mem-web
title: Codex-Mem Web compatibility not established
status: active
scope: project:vncode
updated: 2026-10-22
---

# Codex-Mem — chưa kích hoạt Web

## Owner
người quản lý client Codex/môi trường

## Opened
2026-10-22

## Next trigger
Web cung cấp đường tích hợp MCP/capture và lưu trữ được hỗ trợ,
hoặc chuyển sang client Codex CLI tương thích.

Đã tải/đọc nguồn KeystoneScience/codex-mem tại revision trong sources.lock.json.
Upstream mô tả CLI notify/exec-json/app-server, worker Bun cổng 37777, SQLite và
MCP stdio; setup sửa config CLI và có thể tải thêm runtime/provider dependencies.
Ở phiên Web này không có tool codex_mem/bootstrap_memory, không tìm thấy Bun;
có binary codex/uv không đủ chứng minh capture hay persistence cho Web.

Trước khi bật: xác minh worker health, MCP bootstrap/search đúng phạm vi repo,
capture một dữ kiện thử đã loại bí mật, khôi phục ở phiên mới và chính sách lưu
trữ/provider. Chỉ có health xanh chưa chứng minh ghi nhớ được qua phiên.

Có hướng dẫn CLI được pin version trong `docs/ai-memory.md` từ gốc repo.
Không tự chạy setup CLI trong Web hoặc báo cả ba hệ thống đã hoạt động.
## Why it matters
tránh cấu hình sai client và lời hứa bộ nhớ không có bằng chứng.
Liên quan: [ranh giới bộ nhớ](../decisions/memory-boundaries.md), [tóm tắt](../summary.md).
