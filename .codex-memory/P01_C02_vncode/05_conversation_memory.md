# Bàn giao phiên

Snapshot chỉ ghi kết quả ngắn, hạn chế, bằng chứng và việc tiếp theo; không lưu
hội thoại thô. Snapshot mới nhất ở cuối file. Dùng `session_snapshot.py` trong
skill để bổ sung sau công việc có ý nghĩa.

## 2026-10-22 - Cài bộ nhớ AI cho VNCode 1.5.11

### Summary
Đã cài skill Smart Project Memory gốc, Deja Vu file protocol/công cụ offline, AGENTS và CI. Validator/doctor đạt; cue và phiên khôi phục độc lập đúng; 59 file vendor khớp nguồn pin; Node 22 đạt 117/117. Codex-Mem chưa kích hoạt Web; số GTIN shop thật và mua/ký production chưa xác minh.

### Decisions
Context pack giữ trạng thái phiên; Deja Vu giữ quyết định và open loops; không tạo release ứng dụng cho thay đổi bộ nhớ.

### Files
AGENTS.md;docs/ai-memory.md;tools/memory/sources.lock.json;docs/validation/2026-10-22-ai-memory-installation.md

### Next
Kiểm tra commit/remote và CI bộ nhớ; theo yêu cầu mới của chủ repo. Chỉ kích hoạt Codex-Mem khi client thực có MCP/capture và persistence đã kiểm chứng.
