# Điểm tiếp tục cho AI — VN Code

Cập nhật: **09.11.2026 16:46 MSK (UTC+03:00)**. Đối chiếu Git/Latest trước thao tác tiếp theo. [Chỉ mục](README.md) · [Lịch sử checkpoint giữ nguyên](CURRENT-history-through-2026-11-09.md).

## Latest1.6.7 — đã phát hành và xác minh

- **Release immutable438471378 /tag `v1.6.7`**, nguồn app **`d0231cd6913ce3a1764067bad0901b32c535de41`**, nhánh `release/vn-code-1.6.7`; công bố **09.11.2026 16:38:02 MSK /13:38:02 UTC**. [EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.7/VN-code-1.6.7-Windows-x64.exe), [bàn giao đầy đủ](../validation/2026-11-09-vncode-167-release-handoff.md), [receipt cuối](../validation/2026-11-09-vncode-167-final-release.json).
- Đủ mọi sửa1.6.6 và bốn phần trước: WB KIZ màu/giấy tờ, native UI đã duyệt, KIZ/Znack/GTIN/read revision, Latin/Cyrillic permit. Thêm discovery/token/DER/preroute, hủy đọc và **xác nhận trước ký nếu đổi người ký**; Cancel không ký/API/ghi. Policy reset/owner/consent giữ nguyên; **chưa có migration renewal cùngINN giữ kho/mapping**.
- Windows **42611294387 SUCCESS:4.188 ca/547 lớp,4.186 đạt,0 failure/error,2 OS skip đúng danh tính**; chứng thư12/38/14, ba stress10.000, native/migration3→6/snapshot/DPAPI/cài-gỡ/coexistence/passive EXE upgrade từ1.6.6 đạt. [Build](../validation/2026-11-09-vncode-167-build-run.json), [package](../validation/2026-11-09-vncode-167-package.json), [review](../validation/2026-11-09-vncode-167-artifact-review.json).
- Publisher **42627047589 SUCCESS**, tooling `b1e5e7774e1ab01d0c1c57ee934900c6927638f7`, nhánh `release/vn-code-1.6.7-publication`. Đã tải/kiểm **16 public files**; source ZIP2.993 tệp/JAR1.987 class/production launcher/không fixture. Giữ **16 release/258 asset/45 tag cũ**, không prune. [Công bố](../validation/2026-11-09-vncode-167-publication.json), [public preservation](../validation/2026-11-09-vncode-167-public-preservation.json).
- **Binary1.6.6 thật nhận AVAILABLE1.6.7**, đúng MSI/hash/148710661 bytes. [Updater](../validation/2026-11-09-vncode-167-updater.json); Cloud proxy/CA, chưa quan sát popup/cài trên máy seller. [Review độc lập sau công bố](../validation/2026-11-09-vncode-167-post-publication-review.json) PASS.
- [Inclusion](../validation/2026-11-08-vncode-167-source-inclusion.json):115 runtime=90 Java+25 resource, thêm POM;105/109 hash ban đầu giữ nguyên,4 locale chỉ thêm3 key/file. [Fresh security42611297755](../validation/2026-11-09-vncode-167-certificate-guard-security.json): cùng8 finding Java cũ/0 mới, **Java gate FAIL** với1 high store thumbprint; JS0/lint PASS,2 dispatch jobs skip, GS1 extraction warning cũ. Không all-security-green hoặc nghiệm thu CryptoPro seller thật.
- Giữ lịch sử:0f5 full PASS nhưng không dùng chứng nhận mã mới; e774 preflight392-byte Progress FAIL;2a5 full CANCELLED; final d023 dùng fresh run. Không cộng200/14 Linux,192+12 hoặc tooling166/11 controls thành full app. [Công cụ phiên có thể khôi phục](../validation/release-167-tools/README.md).

## Tiếp tục

Sau nâng cấp chạy **Đồng bộ Znack đầy đủ một lần**, giữ profile/feed/jobs/journal. Nguồn/tag/assets1.6.7 immutable; tài liệu sau công bố không thay binary. Công việc mới cần nguồn/phiên bản/kiểm thử riêng, không phát hành lại1.6.7 hoặc dùng memory để tự mở quyền API.

Không seller API write/mua KIZ/ký thật/license activation/Gmail/Portal deploy trong lượt phát hành. Còn giới hạn CryptoPro/PKI seller và mua/ký-công bố đầu-cuối, benchmark1000 đơn thật, nguồn phạt Ozon, PDF chờ mua sau restart, DPI/Narrator và TLS máy seller. Trạng thái Git của commit/push tài liệu cuối phải được kiểm trực tiếp; các checkpoint “chưa phát hành” cũ đã chuyển sang [lịch sử](CURRENT-history-through-2026-11-09.md), không phải việc còn dở hiện tại.
