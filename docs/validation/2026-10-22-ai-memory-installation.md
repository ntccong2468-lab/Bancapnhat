# Kiểm chứng cài bộ nhớ AI cho VNCode

Ngày 2026-10-22. Base repo `ef5bd2c6de024a0f29eb0e0ec703b2df0330ae66` sau công bố
1.5.11. Nguồn ứng dụng/tag/release giữ nguyên. Phạm vi: skill và bộ nhớ repo,
công cụ offline, hướng dẫn khôi phục/writeback và CI kiểm tra bộ nhớ.

## Nguồn và cài đặt

Revision/digest: `tools/memory/sources.lock.json` từ root repo.

- richenyu/codex-smart-project-memory `a000480fb296f551d5d6578929db1bb581d12cd3`:
  copy nguyên skill/scripts/templates và MIT LICENSE, khởi tạo workspace một dự án.
- focaxisdev/deja-vu `cccdffbfd6869772621d21176c5dd5e423a9de25`: copy nguyên
  scripts/starter-kit/MIT LICENSE; AGENTS merge theo protocol v0.4, scope project:vncode.
- KeystoneScience/codex-mem `56c7710b245e1d7f93ebce27b3841f70d58dfa29`:
  đã clone/đọc README/setup/provider code. Không kích hoạt Web vì chưa có MCP,
  capture/provider, worker và lưu trữ qua phiên được chứng minh. Không đổi config global.

## Kiểm tra đã chạy

1. Trước cài: Smart workspace không tồn tại; Deja Vu doctor báo thiếu ba file bắt buộc.
2. Sau cài: Smart validator đạt, một project; doctor đạt, 0 lỗi/0 cảnh báo.
   Doctor nhận dạng chính xác block recall tiếng Anh; giữ hợp đồng gốc trong AGENTS,
   các record tiếng Việt dùng heading metadata chuẩn.
3. Cue `GTIN KIZ pending`, `Mpp fashion 10000 thực tế`, `codex-mem MCP` trả strong
   và dẫn đúng record. Cue `unrelated-orchard-zebra` trả none, không nạp memory.
4. So sánh SHA-256 và byte gốc: cả 59 file vendor khớp revision nguồn; không có
   sửa code upstream. Link tài liệu mới giải quyết được; đường bộ nhớ không phụ
   thuộc /workspace; không có thay đổi src/pom/build hay publisher ứng dụng.
5. Phiên agent mới chỉ đọc AGENTS/context/status/cue và 3 detail records đã trả lời
   đúng mốc 1.5.11, 10.000 mô phỏng, số shop thật chưa xác nhận, KIZ còn ràng buộc
   và Codex-Mem Web chưa kích hoạt. Đối chiếu audit nguồn không thấy mâu thuẫn.
6. `node --test tools/*.test.mjs` bằng Node 22.23.3: 117/117 đạt. Lượt trong
   sandbox chỉ đọc ban đầu báo thất bại ở 4 file test
   (preview-source-guard, recover-windows-fixture-java, release-version,
   update-manifest); chạy lại ngoài sandbox bằng toolchain dự án đạt toàn bộ.
7. Snapshot cài đặt được tạo bằng script upstream; đọc lại thấy đúng kết quả,
   giới hạn và việc tiếp theo. Không chép hội thoại/log hoặc dữ liệu seller.

Không dựng lại Java/Windows vì không sửa nguồn ứng dụng. Những con số test app
1.5.11 trong context pack là bằng chứng phát hành trước đó, không phải chạy lại
trong lượt cài bộ nhớ. Workflow Check AI memory chỉ kiểm tra bộ nhớ; kết quả GitHub
phải kiểm tra theo SHA đã push.

## Giới hạn còn giữ

AGENTS là hướng dẫn file cho agent; skill repo không đồng nghĩa đã đăng ký toàn
cục trong tài khoản Web. File chỉ đi qua phiên khi commit/push và client đọc lại.
Codex-Mem chưa chạy capture/search/persistence; hướng dẫn CLI pin version đã ghi,
chưa đối chiếu gói npm với source Git. Production Mpp fashion và mua/ký KIZ thật
vẫn chưa được nghiệm thu. Xem `docs/ai-memory.md` và `memory/open-loops/`.
