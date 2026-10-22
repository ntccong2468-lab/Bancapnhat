# Bộ nhớ AI của VNCode

Cài trên mã nguồn mới nhất sau khi công bố 1.5.11; không sửa nguồn ứng dụng,
tag hoặc gói phát hành 1.5.11. File bộ nhớ đi cùng Git; clone/pull nhánh có thay
đổi này để phiên Codex sau đọc được. Đây không phải bộ nhớ toàn cục ChatGPT.

## Trạng thái ba hệ thống

| Hệ thống | Cài đặt trong repo | Trạng thái Codex Web |
| --- | --- | --- |
| [Codex Smart Project Memory](https://github.com/richenyu/codex-smart-project-memory) | Skill gốc `.agents/skills/codex-smart-project-memory/`, scripts/templates và workspace `.codex-memory/` | Dùng qua AGENTS và đọc/ghi file |
| [Deja Vu](https://github.com/focaxisdev/deja-vu) | Protocol v0.4 trong AGENTS; `memory/`; CLI offline `tools/memory/deja-vu/` | Dùng qua cue, record và writeback; không cần npm/daemon |
| [Codex-Mem](https://github.com/KeystoneScience/codex-mem) | Đã tải, đọc nguồn, pin revision và ghi hướng dẫn tương thích | **Chưa kích hoạt**, chưa xác minh Web hỗ trợ MCP/capture/persistence |

Revision và digest file vendor ở [sources.lock.json](../tools/memory/sources.lock.json).
Hai phần được copy giữ nguyên upstream cùng MIT LICENSE. Không gộp runtime
Codex-Mem vào ứng dụng VNCode; upstream runtime là dự án riêng, license AGPL-3.0.

## Khôi phục phiên và dùng bộ nhớ

Codex làm việc từ root repo đọc [AGENTS.md](../AGENTS.md). Đọc context pack trước,
quét cue, rồi chỉ đọc 1–3 record liên quan; không tải toàn bộ hai cây bộ nhớ.
Nếu client không tự nạp AGENTS/không có discovery skill, yêu cầu “Đọc AGENTS.md
ở gốc repo và tiếp tục từ context pack”. File skill vẫn có thể được đọc trực tiếp;
không khẳng định đã đăng ký skill toàn cục trong tài khoản Codex Web.

- Context/status/snapshot: `.codex-memory/P01_C02_vncode/`.
- Thông tin ổn định: `memory/summary.md`.
- Quyết định/lỗi đã sửa/kinh nghiệm: `memory/decisions/`.
- Việc chưa giải quyết: `memory/open-loops/`.
- Cue: `memory/impressions.jsonl`; phản hồi recall: `memory/recall-feedback.jsonl`.

Quyết định chỉ lưu ở Deja Vu; Smart `02_decisions.md` liên kết tới record chuẩn.
Sau công việc có ý nghĩa, cập nhật status/context pack và thêm snapshot ngắn:

```sh
python3 -B .agents/skills/codex-smart-project-memory/scripts/session_snapshot.py --project .codex-memory/P01_C02_vncode --title "Tên công việc" --summary "Kết quả đã kiểm chứng; giới hạn" --next "Việc tiếp theo"
```

Đây là writeback theo hướng dẫn agent, không phải hook tự chép mọi hội thoại.
Trước khi kết thúc: kiểm tra diff, bỏ bí mật/dữ liệu seller, commit/push file đã
được phép lưu. Nếu môi trường chỉ đọc, báo chưa lưu; không báo nhớ vĩnh viễn.
Nếu ghi chú khác code/remote, xác minh hiện tại và cập nhật ghi chú có bằng chứng.
Không chạy initializer `--overwrite`/`--force` lên bộ nhớ đã điền.

## Kiểm tra offline

Từ root repo, cần Python 3.9+ và Node 20+ (toolchain VNCode dùng Node 22):

```sh
python3 -B .agents/skills/codex-smart-project-memory/scripts/validate_memory_workspace.py --root .codex-memory
node tools/memory/deja-vu/scripts/deja-vu.mjs doctor --json
node tools/memory/deja-vu/scripts/dejavu-scan-memory.mjs "GTIN KIZ pending"
node tools/memory/deja-vu/scripts/dejavu-feedback-report.mjs --memory-root memory
```

Windows thay `python3` bằng `python`/`py -3` theo môi trường. Các công cụ này không
cần tải package. Validator Smart kiểm tra cấu trúc; doctor Deja Vu kiểm tra cue,
route/metadata và một số dấu hiệu nội dung nhạy cảm, không thay review thủ công.
Workflow [Check AI memory](../.github/workflows/check-ai-memory.yml) chạy hai
validator khi có thay đổi bộ nhớ. Không cần dựng ứng dụng để sửa tài liệu bộ nhớ.

## Codex-Mem: tương thích và kích hoạt sau

Nguồn đã kiểm tra: revision `56c7710b245e1d7f93ebce27b3841f70d58dfa29`,
package `codex-mem-cli` 11.0.0. Upstream dùng Codex CLI `notify`, `exec --json`
hoặc `app-server` để capture; Bun worker/SQLite và MCP stdio để tìm kiếm;
`MEMORY.md` là projection tùy chọn, không tự làm Web có capture.

Kiểm tra phiên cài: có binary `codex` và `uv`, không có Bun trên PATH; tool của
phiên Web không có `codex_mem`/`bootstrap_memory`; chưa có bằng chứng worker,
capture, provider xử lý và lưu trữ qua phiên. Vì vậy **không chạy setup, không
sửa config Codex toàn cục và không báo Codex-Mem đang hoạt động**.

Khi dùng Codex CLI trên máy/môi trường có lưu trữ bền vững, upstream hỗ trợ:

```sh
npx codex-mem-cli@11.0.0 codex setup
npx codex-mem-cli@11.0.0 codex doctor
```

Version npm được pin cho khả năng tái lập; chưa đối chiếu digest gói npm với
revision Git trong lần cài Web này. Muốn dùng chính revision Git đã đọc, checkout
revision trong sources.lock rồi chạy `node bin/codex-mem.js codex setup` từ bản
clone đó. Setup có thể cài Bun/uv/dependencies, khởi động worker và sửa config
CLI; phải bảo toàn cấu hình/hook có sẵn. Không chạy setup trong Web để giả lập
việc Web đã tích hợp MCP. Xem [hướng dẫn upstream tại revision đã đọc](https://github.com/KeystoneScience/codex-mem/blob/56c7710b245e1d7f93ebce27b3841f70d58dfa29/README.md).

Điều kiện báo kích hoạt thành công: worker health đạt, client thật gọi được
`bootstrap_memory`/search đúng repo, capture được dữ kiện thử đã loại bí mật,
phiên mới khôi phục đúng dữ kiện đó từ nơi lưu bền vững. Nếu Web không có đường
MCP/capture được hỗ trợ, tiếp tục dùng hai lớp file trong repo.

## Những dữ kiện ban đầu và giới hạn

Đã ghi từ tài liệu/bằng chứng trong repo: 1.5.11 công khai Latest; MSI updater;
luồng đăng ký và bộ lọc/đề xuất; GTIN readback; KIZ pending theo danh tính và
trạng thái. Test lô 10.000 dùng API mô phỏng. Số thực tế Mpp fashion và nghiệm
thu mua/ký production chưa xác nhận; được giữ là open loop, không chuyển thành
“đã xong” khi tóm tắt. Không lưu API token, chứng thư, mã KIZ hay dữ liệu đơn thật.
