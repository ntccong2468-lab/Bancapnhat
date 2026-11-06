# Điểm tiếp tục cho AI — VN Code

Cập nhật: **06.11.2026, 22:25 MSK (UTC+03:00)**. Đối chiếu Git/PR/Latest trước khi tiếp tục. [Chỉ mục và cách dùng bộ nhớ](README.md).

## Mã và bản đã phát hành

- Repository: `ntccong2468-lab/Bancapnhat`; nhánh đang làm **`audit/code-doctor-20261106`**, nền `e386aee` của `fix/znack-state-wb-gtin-20261105`.
- **Latest đã là v1.6.4**, release `435647198`, công bố **06.11.2026 00:13:28 MSK** (`2026-11-05T21:13:28Z`). Nguồn/tag khóa `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`.
- Bản này chứa cả sửa Znack `33f3d6c5c7f380d7980cea6fb1e312328902be58` và sửa PDF Ozon, kế thừa đầy đủ 1.6.3. Mã nguồn Znack đầy đủ được ghi chính xác trong [đối chiếu nguồn](../validation/2026-11-05-vncode-164-source-inclusion.json).
- Publisher `23e604df98e389f8c3f72f87781e04a52a931677`; các commit sau nguồn ứng dụng ghi công cụ phát hành/bằng chứng, không phải một bộ cài khác.
- [PR #20](https://github.com/ntccong2468-lab/Bancapnhat/pull/20) OPEN/draft, base `feature/vncode-163-approved-ui`; không tự merge. Không chọn default branch cũ `update/vn-code-1.3.0` làm nền.
- [Bàn giao phát hành 1.6.4](../validation/2026-11-05-vncode-164-handoff.md), [Latest/receipt](../validation/2026-11-05-vncode-164-published-latest.json). Bộ cài và 16 tài sản 1.6.3 được giữ nguyên.

## Code Doctor và sửa mới chưa phát hành

- [PR #21](https://github.com/ntccong2468-lab/Bancapnhat/pull/21) OPEN/draft, base `fix/znack-state-wb-gtin-20261105`; chưa merge.
- Người dùng gửi ZIP, yêu cầu cài skill và dùng để soi mã/chạy VN Code. Đã cài nguyên văn `.agents/skills/code-doctor/SKILL.md`, thêm hướng dẫn trong AGENTS; không có script cài đặt. [Cách dùng](../engineering/code-doctor.md).
- Commit cài skill `338d46e`; nguồn sửa cuối: **`c2e9dcfc56b4287f7ece05bc058e310fa5bd9942`**, [báo cáo và lệnh thực tế](../validation/2026-11-06-code-doctor.md). Các sửa này **chưa nằm trong Latest 1.6.4**, không ghi đè tag/bộ cài đã phát hành.
- Xác nhận/sửa cùng lỗi ở các đường đọc chi tiết/trạng thái/hàng đợi Ozon: một đơn 404 hoặc thiếu dữ liệu không chặn các đơn/trang hợp lệ phía sau. Giữ dữ liệu cũ của đơn lỗi và cảnh báo tới cuối lượt/checkpoint; 401/429, mạng và sai danh tính vẫn thoát. Luồng nền đọc trang tiếp theo theo nhịp 1 giây, thử lại lỗi sau lượt.
- Baseline 197 ca/7 lớp; bộ báo cáo hồi quy cuối **507 ca/25 lớp**, 0 lỗi/thất bại/skip. Nhóm rộng đạt trước chỉnh nhịp trang/nhánh hàng đợi; ba lớp đồng bộ 72 ca được chạy lại cùng package với nguồn cuối. Node **150/150**, Maven package đạt. [Phạm vi và hash từng giai đoạn](../validation/2026-11-06-code-doctor-tests.json).
- Đã chạy entry point production và JAR vừa build trên Linux/Xvfb bằng hồ sơ mới 0 shop: workspace 1300×700, JAR mở sau 5.915 ms, đóng/nhả khóa bình thường, không kích hoạt license. [Ảnh](../validation/assets/code-doctor-20261106/startup.png), [biên nhận](../validation/2026-11-06-code-doctor-startup.json).
- Chưa full Java/Windows installer cho sửa mới, chưa ghi/nhận/mua/ký API seller thật. Không dùng kết quả Windows của 1.6.4 bên dưới làm bằng chứng cho commit audit. Không đổi schema, UI, version, portal hoặc prototype credential migration.

## Kiểm API thật và sửa Znack/GTIN mới nhất

- Người dùng chọn tiếp tục WB/Catalog trước khi Ozon đang bị khóa; sau đó yêu cầu rút bộ lọc còn bốn trạng thái và **bỏ kiểm tra trùng TN VED riêng khi append GTIN WB**. Nguồn sửa mới **`5710392b9caaade838a94f33b6e4bdfe8d64cc3a`**, cùng PR #21; kế thừa sửa Ozon `c2e9dcf`, chưa phát hành.
- WB/Catalog đọc API thật qua SDK/parser/repository production: **388 bài / 1.982 biến thể**, **1.380 thẻ** = 1.260 published, 117 notsigned, 2 draft, 1 errors. JVM mới đọc cache giữ đủ số lượng/trạng thái, không gọi lại API. [JSON đọc thật](../validation/2026-11-06-code-doctor-live.json).
- UI WB/Ozon còn Chưa tạo / Đang xử lý / Hoàn tất / Lỗi và lựa chọn Tất cả; các việc ghép cần xem lại nằm trong Đang xử lý. Lỗi chỉ là Catalog từ chối; không xóa trạng thái receipt/checkpoint nội bộ. Thẻ khớp hiện tại không bị GTIN cũ che; hiển thị công bố tách khỏi mapping, không gán lỗi cũ vào thẻ đã công bố.
- Bỏ TN VED ở matcher, các gate CatalogSync và WbGtinAdapter cho **GTIN WB**. Giữ chính xác article/màu/size/chủ sở hữu/độ mới/xung đột, barcode và thuộc tính/giấy tờ cũ. TN VED khi tạo thẻ Catalog, adapter Ozon và mapping kho KIZ mua không đổi. Nút GTIN dùng snapshot lưu và worker refresh khi hết hạn trước ghi; mở/lọc màn hình không khởi động đồng bộ mạng.
- Dữ liệu thật: **229 ứng viên duy nhất đã công bố**, gồm **204 khác nhóm TN VED** không còn bị TN VED chặn. Adapter đọc mới/validate 5 biến thể WB mẫu đạt. **Không đồng nghĩa đã ghi 229 GTIN: không gọi submit, không ghi GTIN/Catalog/KIZ hoặc kích hoạt license**. SDK harness dùng local test-license fixture ngoài JAR, chưa xác minh full chứng thư/chủ doanh nghiệp.
- Trường hợp bài trong ảnh: 5/5 size/article có thẻ tương ứng nhưng màu WB có sắc độ khác cách ghi màu Catalog; chưa khớp theo quy tắc màu. Không tự gộp các sắc độ/màu khác nhau. Đã có nút đọc xác minh/cập nhật và hiển thị đúng trạng thái thẻ, giữ chặn ghi khi màu thực sự chưa khớp. Không dùng 5 API mẫu khác làm bằng chứng đã sửa/ghi được bài trong ảnh.
- **330 ca / 14 lớp**, 0 lỗi/thất bại/skip; gồm UI JavaFX/Xvfb, ghi/giữ TN VED, các điều kiện đúng biến thể, nguồn/chủ sở hữu và Ozon/KIZ không bị đổi. Package đạt; JAR hash `0dc4010b4150ab6ca24532859cb2697c1f8b4a9659d7be7c6490220ed8e5eaa7`, 9 class sửa khớp class đã kiểm; không chứa fixture/probe. Chưa Windows installer/full suite cho nguồn mới. [Bàn giao/lệnh](../validation/2026-11-06-znack-four-states-wb-gtin.md), [330 ca](../validation/2026-11-06-znack-four-states-tests.json), [build](../validation/2026-11-06-znack-four-states-build.json).
- Ozon thật trả **403/code 7, API key deactivated** ở roles, sản phẩm và hàng đợi. Không thử nhận/gán/in thật tiếp bằng credential này; chờ cập nhật Secrets khi tiếp tục Ozon. Không hỏi/gửi lại khóa trong chat.

## Đã sửa trong 1.6.4

- Znack: trạng thái Catalog mới có danh tính phù hợp không bị checkpoint cũ che; chờ ký tách khỏi lỗi; đọc bổ sung thẻ đã biết `good_id`. Giữ yêu cầu chưa rõ kết quả để đối soát, không gửi trùng.
- GTIN: chung quy tắc màu/size khi ghép và ghi, giữ màu WB dạng chuỗi; không bỏ kiểm tra đúng biến thể/chủ sở hữu/TN VED. WB/Ozon có một vùng tiến độ và báo cáo gọn, giữ lịch sử/CSV/chức năng chi tiết.
- PDF Ozon: preflight cũ không bỏ KIZ đã dành cho đơn; KN3 từ chối vẫn in đúng mã đã gắn posting/product/exemplar có chứng cứ, giữ riêng kết quả API và không trả mã về kho. Giữ kho seller nhập theo danh mục và kho mua theo mapping.
- Tải vận đơn theo lô, giữ nguyên vận đơn gốc → KIZ đúng đơn; trang chưa rõ danh tính không làm mất KIZ của các đơn đã nhận diện. Căn DataMatrix theo điểm in 203 dpi; in lại giữ mã. Không đổi schema/UpgradeCode.

## Kiểm chứng và giới hạn

- Windows build `42095550861` SUCCESS: **3.864 ca, 0 thất bại/lỗi, 2 bỏ qua theo OS, 522 lớp**; native/migration/snapshot/DPAPI/cài-gỡ và nâng cấp thụ động 1.6.3 → 1.6.4 giữ dữ liệu đạt. [Bằng chứng](../validation/2026-11-05-vncode-164-windows.json).
- Publisher `42110439977` SUCCESS, đủ 16 tài sản công khai đúng hash/size; 2.792 tệp source ZIP đối chiếu đúng nguồn. Updater từ chính binary 1.6.3 đọc Latest 1.6.4 thành AVAILABLE và chọn đúng MSI/hash/size. Chưa quan sát popup trên máy seller.
- Trước build: Znack 500 test/26 lớp; 10.000 GTIN WB mô phỏng; [PDF Ozon 191 test/18 lớp](../validation/2026-11-05-ozon-combined-handoff.md), 100 đơn có 200 trang/lượt, in lại giữ mã, đọc đủ 203/300 dpi.
- API thật trong lượt sửa Znack chỉ đọc. **Không có nghiệm thu ghi/ký Catalog, GTIN hay nhận đơn/KIZ thật mới trong lượt 1.6.4.** Bằng chứng ghi thật trước đó có thời điểm và bàn giao riêng; không dùng mock để khẳng định giao dịch thật.
- CodeQL Java/JavaScript, lint/audit, scanner và 150 test công cụ đạt ở publisher. Dependency review không khả dụng vì Dependency graph của repo; không có thay đổi dependency. [Phạm vi CI](../validation/2026-11-05-vncode-164-tool-checks.json).
- Prototype credential migration vẫn chưa đưa vào runtime; không triển khai portal. Bộ nhớ nằm trong Git; chưa cài/kết nối MCP memory.

## Tiếp tục

Cài skill, sửa/kiểm Code Doctor, đọc API thật và yêu cầu bốn bộ lọc/GTIN WB đã hoàn tất trên nhánh audit; tiếp tục từ nguồn `5710392` và bàn giao mới ở trên. Không tự gộp màu hoặc ghi 229 GTIN chỉ vì có ứng viên matching. Việc phát hành gộp 1.6.4 trước đó đã hoàn tất và giữ nguyên. Không tự merge hoặc phát hành sửa audit khi chưa được yêu cầu. Nếu điều tra dữ liệu seller, đọc bàn giao và journal trước; không chạy lại giao dịch chỉ vì tệp trong `/tmp` bị mất. Giữ riêng việc in KIZ cục bộ và Ozon chấp nhận mã; chỉ ghép đúng đơn/nguồn/danh mục. Đọc [so sánh và sơ đồ Znack](../research/2026-11-05-wbcode-1318-vncode-znack.md) khi cần tiếp tục luồng Catalog/GTIN.
