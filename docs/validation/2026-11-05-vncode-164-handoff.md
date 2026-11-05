# VN Code 1.6.4 — gộp Znack và PDF Ozon

## Nguồn đã khóa và quyền phát hành

Người dùng yêu cầu gộp cả sửa Znack trước đó và sửa PDF Ozon rồi phát hành xuống app. Nguồn ứng dụng: `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`, nhánh `fix/znack-state-wb-gtin-20261105`. Bao gồm Latest 1.6.3 `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2` và sửa Znack `33f3d6c5c7f380d7980cea6fb1e312328902be58`; [bằng chứng kế thừa và hash 26 đầu vào runtime](2026-11-05-vncode-164-source-inclusion.json).

- [Windows build 42095550861](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42095550861): đang chạy; preflight giao diện/nhãn/Catalog đã đạt, full Java đang chạy. Còn native, cài-gỡ và nâng cấp 1.6.3 → 1.6.4 trước công bố.
- Draft GitHub ID `435647198`, tag dự kiến `v1.6.4`, target đúng nguồn trên. **Chưa công bố; Latest vẫn 1.6.3 tại thời điểm ghi**. Chỉ trình phát hành có đủ bằng chứng và hash mới chuyển Latest.
- `tools/publish-vncode-164.mjs` khóa source/build/draft ID, kiểm tra đủ 16 tài sản và bằng chứng nâng cấp. Không xóa hay ghi đè tài sản phát hành cũ; không dùng `baseline-release.mjs prune`.
- PR #20 được cập nhật phạm vi thành Znack + PDF Ozon. Các build PR trùng nguồn đã hủy; chỉ dùng build Windows dispatch nguồn khóa trên làm bằng chứng phát hành.

## Kiểm chứng trước bộ cài

- [Znack](2026-11-05-znack-reconcile-handoff.md): 500 test/26 lớp; 10.000 GTIN mô phỏng; API thật chỉ đọc. Chưa chứng minh ghi/ký Catalog hoặc GTIN thật trong lượt sửa này.
- [PDF Ozon](2026-11-05-ozon-combined-handoff.md): 191 test/18 lớp; lô 100 đơn, 200 trang/lần xuất hoặc in lại, 400 lần giải mã ở 203/300 dpi; giữ mã đúng đơn và phản hồi từ chối riêng.
- 150 test công cụ phát hành đạt; actionlint 1.7.12 kiểm tra workflow đạt.
- Đã tải portable 1.6.3 công khai, kiểm tra SHA-256 theo manifest và chạy parser updater từ chính JAR đã phát hành: đọc Latest 1.6.3 thành CURRENT, chọn đúng MSI/hash/size. Chạy lại với metadata công khai sau khi phát hành 1.6.4 để xác nhận AVAILABLE; không kích hoạt license hoặc sửa dữ liệu seller.

## Kiểm tra CI bổ sung

CI cho nguồn ứng dụng `86d76d8`: CodeQL Java, lint/audit và scanner OpenQodex đạt. Dependency review không chạy được do repository chưa hỗ trợ/bật Dependency graph; không có thay đổi dependency trong bản sửa. CodeQL JavaScript báo ba lỗi kiểm tra rồi mở lại đường dẫn ở công cụ phát hành cũ 1.6.1–1.6.3 (7.7). Đã sửa trình phát hành hiện tại và ba script đó để kiểm tra/đọc cùng một file handle, từ chối symlink/thư mục, có test; không thay đổi bộ cài hoặc tài sản các bản cũ. Chờ CI xác nhận sửa công cụ. Không gọi toàn bộ PR checks là xanh khi dependency review vẫn chưa khả dụng.

## Việc phải chốt

Sau khi Windows hoàn tất: tải artifact, đối chiếu nguồn và hash, chạy publisher nguồn riêng với build khóa; đọc lại Latest/tag/assets, tải và kiểm hash công khai, kiểm updater 1.6.3. Lưu receipt, cập nhật trạng thái bộ nhớ và PR. Kiểm thử Windows dùng dữ liệu tổng hợp; không khẳng định đã quan sát popup trên máy seller.
