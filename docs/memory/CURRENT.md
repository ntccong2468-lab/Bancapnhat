# Điểm tiếp tục cho AI — VN Code

Cập nhật: **05.11.2026, 22:30 MSK (UTC+03:00)**. Đây là trạng thái đã đối chiếu ở thời điểm ghi; kiểm tra Git/PR/Latest lại khi tiếp tục. [Chỉ mục và cách dùng bộ nhớ](README.md).

## Mã đang làm và bản đã phát hành

- Repository: `ntccong2468-lab/Bancapnhat`.
- Nhánh đang làm: `fix/znack-state-wb-gtin-20261105`; mã sửa nghiệp vụ tại `33f3d6c5c7f380d7980cea6fb1e312328902be58`, nền `d9af0b9`.
- [PR #20](https://github.com/ntccong2468-lab/Bancapnhat/pull/20): **OPEN / draft, chưa merge**, base `feature/vncode-163-approved-ui`.
- **Latest là v1.6.3**, nguồn/tag `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2`, công bố 05.11.2026 02:11:51 MSK (`2026-11-04T23:11:51Z`). [Bàn giao phát hành](../validation/2026-11-04-vncode-163-handoff.md).
- **Sửa Znack và PDF Ozon trong PR #20 chưa có trong Latest.** Người dùng đã yêu cầu gộp và phát hành: mã hiện tại mang phiên bản **1.6.4**, chờ full Windows/build/nâng cấp trước khi công bố. Không chọn nhánh mặc định cũ `update/vn-code-1.3.0` làm nền công việc mới.

## Sửa gần nhất

- Đã sửa thêm PDF Ozon mất KIZ vì preflight cũ, trạng thái KN3 từ chối, và một trang chưa nhận diện ảnh hưởng cả lô. Căn DataMatrix theo điểm in 203 dpi. **191 test/18 lớp đạt**; 100 đơn tổng hợp có đủ 100 vận đơn + 100 KIZ, in lại giữ mã, đọc đủ ở 203/300 dpi. [Bàn giao PDF](../validation/2026-11-05-ozon-combined-handoff.md).
- Đối chiếu WBCode 1.3.18: trạng thái Catalog mới có danh tính phù hợp không bị checkpoint cũ che; chờ ký tách khỏi lỗi; đọc bổ sung thẻ có `good_id`. GTIN được cấp hoặc receipt không chứng minh thẻ đã công bố; không gửi lại feed chưa rõ kết quả.
- Chung quy tắc màu/size cho ghép và ghi GTIN; giữ màu WB dạng chuỗi. Giữ kiểm tra đúng biến thể, chủ sở hữu và TN VED.
- WB/Ozon có một vùng tiến độ; báo cáo mặc định bảy cột và thao tác seller. Lọc báo cáo theo owner/kind trước LIMIT; lịch sử, CSV, dữ liệu kỹ thuật và các nút cũ còn nguyên. Không đổi schema.
- [Báo cáo so sánh và bốn sơ đồ](../research/2026-11-05-wbcode-1318-vncode-znack.md); [bàn giao, quyết định và kinh nghiệm](../validation/2026-11-05-znack-reconcile-handoff.md).

## Đã kiểm chứng và còn thiếu

- **500 test / 26 báo cáo JUnit đạt**, Linux/JavaFX; không phải kiểm thử bộ cài Windows. [Bằng chứng](../validation/2026-11-05-znack-tests.json).
- **10.000 GTIN WB mô phỏng**: 4 lượt ghi, đọc lại đủ 10.000, mở lại không gửi trùng. Thời gian mô phỏng không phải tốc độ API thật. [Bằng chứng](../validation/2026-11-05-znack-wb-10000-mock.json).
- **API thật chỉ đọc**: 1.982 biến thể WB / 1.380 thẻ Catalog; khớp duy nhất 248 → 263. Trong 229 biến thể khớp thẻ công bố, 204 khác nhóm TN VED; 25 cùng nhóm vẫn cần kiểm tra đầy đủ trước khi ghi. [Số đếm](../validation/2026-11-05-znack-readonly-identity-counts.json).
- Đã xem ảnh JavaFX sáng/tối. **Chưa ghi/ký Catalog hoặc giá/GTIN thật cho sửa mới; chưa full Windows/build/upgrade/phát hành.** Không gọi số đo mock hoặc lần đọc API là nghiệm thu ghi thật.
- Prototype mã hóa credential có bàn giao riêng, chưa được đưa vào runtime; không gộp nhầm khi phát hành.

## Bước tiếp tục

Người dùng đã yêu cầu phát hành **cả sửa Znack và PDF Ozon**: chuẩn bị **1.6.4** từ nhánh trên, chạy full Windows/native/build/nâng cấp từ 1.6.3 rồi công bố Latest. Giữ nguyên tài sản 1.6.3; không đưa prototype credential migration vào runtime. Chưa cài/kết nối MCP memory. Đọc bàn giao/receipt trước giao dịch thật; dữ liệu riêng trong `/tmp` có thể mất khi đổi môi trường.
