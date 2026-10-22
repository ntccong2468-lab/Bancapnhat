# VNCode — hướng dẫn cho Codex

## Khôi phục và tiếp tục

Repository: `ntccong2468-lab/Bancapnhat`. Giao tiếp với chủ repo bằng tiếng Việt.
Ở đầu phiên, đọc `.codex-memory/P01_C02_vncode/04_context_pack.md`, rồi kiểm tra
`git status --short --branch` và HEAD. Khi người dùng nói “tiếp tục”, đọc thêm
`01_current_status.md` cùng thư mục; chỉ đọc bàn giao gần nhất trong
`05_conversation_memory.md` nếu cần. Bộ nhớ có thể cũ: kiểm tra code, Git và bằng
chứng hiện tại trước khi sửa hoặc báo kết quả.

<!-- deja-vu:rules:start -->
<!-- deja-vu:rules:version=1 -->
## Deja Vu Project Memory Rules

- Protocol: Deja Vu Protocol v0.4
- Scope: `project:vncode`
- Memory root: `memory/`

Before substantial planning, coding, refactoring, debugging, or architecture discussion:

1. Inspect `memory/impressions.jsonl` for familiar cues.
2. If there is no familiarity, do not load memory by default.
3. If familiarity is weak, read `memory/summary.md`.
4. If familiarity is strong, read only the 1-3 linked detailed records needed for the task.
5. Do not load the whole memory tree unless the user explicitly asks.

### Durable Writeback Only

Write only accepted decisions, architecture intent, stable preferences, unresolved
follow-ups, milestone summaries and useful recall feedback. Never store secrets,
customer/user PII, full transcripts, raw logs or disposable exploration noise.
<!-- deja-vu:rules:end -->

Bộ nhớ phiên và cue có vai trò khác nhau: context pack khôi phục việc đang làm;
Deja Vu chỉ nạp kinh nghiệm đúng chủ đề. Không nạp toàn bộ hai hệ thống cùng lúc.

## Ghi nhớ sau công việc

- Cập nhật `01_current_status.md` và context pack: kết quả, bằng chứng, hạn chế,
  việc tiếp theo. Dùng skill `.agents/skills/codex-smart-project-memory/SKILL.md`
  khi tạo, bảo trì, thu gọn bộ nhớ hoặc ghi snapshot.
- Quyết định/lỗi đã sửa/kinh nghiệm có thể tái sử dụng: ghi một record ngắn trong
  `memory/decisions/`; việc chưa giải quyết: `memory/open-loops/`; thêm hoặc cập
  nhật cue trong `memory/impressions.jsonl`. Summary là bối cảnh bền vững;
  `02_decisions.md` trong bộ nhớ phiên chỉ liên kết, không sao chép quyết định.
- Chỉ ghi phản hồi recall có ích vào `memory/recall-feedback.jsonl`.
- Ghi trạng thái chưa rõ đúng là chưa rõ; bằng chứng mới thay thế ghi chú cũ.
  Đánh dấu record cũ `superseded` và cập nhật route khi thay quyết định.
- Không ghi token, chứng thư, khóa ký, dữ liệu khách hàng, mã KIZ thật,
  toàn bộ hội thoại, log thô hoặc kết quả phỏng đoán vào bộ nhớ công khai.

## Ràng buộc VNCode

Giữ giao diện/nút thao tác hiện hữu khi sửa luồng Znack. Đối soát Catalog và WB
bằng kết quả phía dịch vụ; HTTP ACK không đồng nghĩa thẻ/GTIN đã được xác nhận.
Mua/gán KIZ phải giữ phạm vi shop, INN, chứng thư, SKU/variant, trạng thái mã và
chống mua lặp. Xem record theo cue trước khi sửa các luồng này.
Không tự di chuyển tag phát hành, xóa dữ liệu seller, công bố phiên bản hoặc
thực hiện mua/ký thật chỉ vì ghi chú cũ nói đã có quyền. Làm theo yêu cầu hiện tại.

## Kiểm tra

Bộ nhớ (không cần npm install):

```sh
python3 -B .agents/skills/codex-smart-project-memory/scripts/validate_memory_workspace.py --root .codex-memory
node tools/memory/deja-vu/scripts/deja-vu.mjs doctor --json
```

Mã ứng dụng: JDK 25, Node 22; `./mvnw -B clean verify` (Linux cần display cho
JavaFX), `node --test tools/*.test.mjs`; Windows dùng `mvnw.cmd`, `build.bat`.
Chọn kiểm tra phù hợp thay đổi. Hướng dẫn: `docs/ai-memory.md`.

Codex-Mem chưa được kích hoạt trong Web. Chỉ dùng khi client thực sự có MCP,
worker khỏe, cơ chế capture và lưu trữ bền vững đã kiểm chứng. Không coi việc
copy config hoặc có lệnh `codex` trong container là đã tích hợp vào phiên Web.
