# VN Code 1.6.4 — phát hành gộp Znack và PDF Ozon

**Đã công bố [1.6.4 lên Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.4) lúc 06.11.2026 00:13:28 MSK (`2026-11-05T21:13:28Z`)**, release `435647198`. Người dùng yêu cầu gộp cả sửa Znack trước đó và sửa PDF Ozon vừa hoàn tất.

Nguồn ứng dụng/tag: `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`, nhánh `fix/znack-state-wb-gtin-20261105`. Bao gồm Latest 1.6.3 `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2` và sửa Znack `33f3d6c5c7f380d7980cea6fb1e312328902be58`. [Đối chiếu nguồn và 26 đầu vào runtime](2026-11-05-vncode-164-source-inclusion.json). Các commit sau nguồn ứng dụng chỉ cập nhật công cụ phát hành/bằng chứng; PR #20 còn OPEN/draft, chưa merge.

## Những sửa đã có trong bộ cài

- Znack: trạng thái Catalog mới, có danh tính phù hợp, không bị checkpoint cũ che; thẻ chờ ký tách khỏi lỗi; đọc bổ sung thẻ có `good_id` chưa có trong trang danh sách. Giữ đối soát feed chưa rõ kết quả, không gửi lặp. Thống nhất màu/size giữa ghép GTIN và ghi WB, giữ màu WB dạng chuỗi và kiểm tra đúng biến thể/TN VED/chủ sở hữu.
- Đăng ký WB/Ozon có một vùng tiến độ; báo cáo mặc định tập trung vào thao tác seller. Giữ nút, lịch sử/CSV và dữ liệu kỹ thuật. [So sánh và bốn sơ đồ](../research/2026-11-05-wbcode-1318-vncode-znack.md), [bàn giao Znack](2026-11-05-znack-reconcile-handoff.md).
- PDF Ozon: preflight cũ không bỏ KIZ đã dành cho đơn; phản hồi KN3 từ chối không làm mất nhãn kết hợp của đúng posting/product/exemplar khi có binding và chứng cứ. Giữ nguyên phản hồi từ chối, không đổi thành API chấp nhận hoặc trả mã về kho.
- Tải vận đơn theo lô, giữ nguyên vận đơn gốc và ghép KIZ đúng đơn; trang chưa xác định được không làm mất KIZ của các đơn đã nhận diện. In lại giữ mã, seller nhập theo danh mục, kho mua theo mapping. Căn DataMatrix theo lưới 203 dpi. [Bàn giao PDF và test](2026-11-05-ozon-combined-handoff.md).
- Không đổi schema 6 hoặc UpgradeCode, không triển khai portal hay prototype credential migration. [Ghi chú cập nhật](../releases/VN-code-1.6.4.md).

## Kiểm chứng phát hành

- [Windows build 42095550861](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42095550861) SUCCESS: **3.864 ca / 522 lớp, 0 thất bại/lỗi, 2 bỏ qua theo hệ điều hành**. Full Java chạy khoảng 84 phút; preflight UI/nhãn/Catalog, native/migration/snapshot, DPAPI, cài-gỡ và nâng cấp thụ động đúng EXE từ 1.6.3 đều đạt; dữ liệu và đăng ký cài đặt được giữ. [Biên nhận Windows](2026-11-05-vncode-164-windows.json).
- [Publisher 42110439977](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42110439977) SUCCESS, nguồn publisher `23e604df98e389f8c3f72f87781e04a52a931677`. Xác minh đủ 16 tài sản rồi mới công bố; draft được khóa bằng ID trả về. [Receipt](2026-11-05-vncode-164-publication-receipt.json), [Latest/tag](2026-11-05-vncode-164-published-latest.json).
- **16 tài sản được tải lại không dùng token**, đúng kích thước và SHA-256 của receipt. [Bằng chứng tải công khai](2026-11-05-vncode-164-public-assets.json). Nguồn và toàn bộ 16 tài sản 1.6.3 được đối chiếu còn nguyên, không xóa hay ghi đè release cũ.
- Source ZIP có **2.792 tệp**, đối chiếu từng tệp với commit; chỉ chuẩn hóa CRLF đối với văn bản. JAR đúng 1.6.4, có lớp sửa Znack/PDF. [Biên nhận gói](2026-11-05-vncode-164-package.json).
- [Updater từ chính binary 1.6.3](2026-11-05-vncode-164-updater.json) đọc metadata Latest công khai và báo **AVAILABLE 1.6.4**, chọn đúng MSI/hash/size. Không khẳng định đã quan sát popup hoặc cài trên máy seller.

EXE: `VN-code-1.6.4-Windows-x64.exe`, **149.207.552 byte**, SHA-256 `9d384ba29f1995ad0da4f51cb0b5faf7e2cde37a0e9455a856676689135b2d57`.

MSI cho cập nhật trong app: `VN-code-1.6.4-Windows-x64.msi`, **148.538.629 byte**, SHA-256 `487df1c2377c5614052093aebce8742ada1c129513115431f2e510054e22d641`.

## Phạm vi và kinh nghiệm

- Trước build: Znack 500 test/26 lớp và 10.000 GTIN mô phỏng; PDF 191 test/18 lớp, 100 đơn tổng hợp tạo 200 trang mỗi lượt xuất/in lại, đọc 400 mã ở 203/300 dpi. Không dùng các số này làm benchmark hoặc bằng chứng ghi API thật.
- API Znack/WB ở lượt sửa này chỉ đọc. Không ghi/ký Catalog, nhận đơn, gán KIZ, mua KIZ hoặc kích hoạt license thật mới để kiểm thử 1.6.4. Nghiệm thu thật các lượt trước có thời điểm và bàn giao riêng.
- 150 test công cụ, actionlint, CodeQL Java/JavaScript và scanner OpenQodex đạt ở publisher; không gọi scanner là review AI độc lập. Dependency review không khả dụng do repository chưa hỗ trợ/bật Dependency graph; không đổi dependency. [Biên nhận và giới hạn CI](2026-11-05-vncode-164-tool-checks.json).
- Đã sửa ba cảnh báo CodeQL ở script phát hành cũ 1.6.1–1.6.3 và dùng cùng helper cho 1.6.4: kiểm tra/đọc trên cùng file handle, từ chối symlink/thư mục. Không chạy lại các publisher cũ hoặc thay bộ cài cũ. Không tắt gate để có kết quả xanh.
- Build PR trùng đã hủy, giữ build nguồn khóa. Môi trường Codex kết nối lại lúc CI hoàn tất; đã đọc lại trạng thái run/commit thay vì khởi động build/phát hành lần nữa. Luôn lấy draft theo ID và đối chiếu receipt trước khi tiếp tục.

Yêu cầu gộp và phát hành đã hoàn tất. Khi điều tra seller, đọc journal và phân biệt reservation/in cục bộ với Ozon chấp nhận mã; không gửi lại hoặc cấp lại mã chỉ vì tệp tạm không còn.
