# VNCode consolidated baseline

Bản đã công bố trên Latest hiện là **VN Code 1.6.4**, tag bất biến `v1.6.4`, nguồn `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`; xem [bàn giao và kiểm chứng 1.6.4](../validation/2026-11-05-vncode-164-handoff.md). **1.6.5 chỉ đang được chuẩn bị, chưa phát hành**; xem [phạm vi bản gộp](VN-code-1.6.5.md).

Phần dưới giữ hồ sơ nền **1.6.3**, tag `v1.6.3`, nguồn `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2`, phát hành 2026-11-04T23:11:51Z. Không dùng các số liệu này làm kết quả kiểm thử 1.6.5.

Kế thừa toàn bộ 1.6.2. Áp dụng giao diện native đã duyệt, ưu tiên diện tích danh sách và quản lý cửa hàng cạnh selector. Nhận Ozon đối soát sau cả lô; lỗi KIZ tùy chọn chỉ cho tiếp tục khi API xác nhận được nhận. PDF giữ vận đơn Ozon gốc, đặt KIZ kết hợp đúng đơn ngay sau, sắp xếp cùng WB trên toàn lô; bỏ barcode sản phẩm đứng riêng. Phiếu nhặt hàng giữ đúng số vận đơn. In KIZ và chấp nhận KIZ trên API là hai kết quả riêng.

[Tải bộ cài EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.3/VN-code-1.6.3-Windows-x64.exe), [ghi chú 1.6.3](VN-code-1.6.3.md), [bàn giao](../validation/2026-11-04-vncode-163-handoff.md), [biên nhận Latest](../validation/2026-11-04-vncode-163-published-latest.json). Lịch sử tính năng nền ở [ghi chú 1.6.2](VN-code-1.6.2.md).

## Gói nguồn và kiểm chứng

Release giữ đủ 16 tệp: EXE, MSI, ZIP chạy độc lập kèm Java, source ZIP, Git history bundle, verification ZIP, tệp EXE `.sha256`, `build-info.json`, `release-notes.md`, `native-smoke.json`, `gs1-smoke.json`, `side-by-side-smoke.json`, `upgrade-baseline-smoke.json`, `previous-baseline.json`, `asset-manifest.json`, `SHA256SUMS`. Các tệp có tiền tố phiên bản dùng `VN-code-1.6.3-`.

Windows build `41918387266` trên đúng nguồn phát hành: **3.844 ca, 0 thất bại/lỗi, 2 bỏ qua theo OS**, 521 lớp. Preflight, native launcher, migration/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp bằng đúng EXE từ Latest 1.6.2 giữ dữ liệu đều đạt. Node 144/144 trên nguồn ứng dụng và 145/145 ở publisher. Không chạy lại full Linux; 296 ca mục tiêu riêng biệt đã đạt trước đóng gói.

Publisher `41931634446` đạt. Cả 16 tệp đã tải công khai và đối chiếu SHA-256/kích thước. Source ZIP gồm 2.769 tệp được đối chiếu với nguồn: 2.466 tệp văn bản chỉ khác CRLF do Windows; nhị phân trùng byte. Runtime JAR đúng 1.6.3, chứa giao diện đã duyệt, sửa nhận đơn và ghép nhãn. Bộ đọc updater từ binary 1.6.2 nhận đúng Latest 1.6.3, MSI/hash/kích thước; chưa quan sát popup hay cài trên máy seller.

Bộ cài chưa ký Authenticode. [Rà soát bảo mật nền 1.6.1](../validation/2026-11-03-release-security-review.md) vẫn có cảnh báo định danh chứng thư và các mục medium; không tuyên bố mọi gate bảo mật đạt. Prototype migration mã hóa credential nằm ngoài runtime. Portal không được triển khai.

## Phạm vi API thật

Trước đóng gói đã nhận 22 đơn và 2 đơn mới qua API Ozon, đọc lại xác nhận. PDF thật 25 vận đơn + 13 nhãn KIZ gilet = 38 trang, 2 trang nhặt hàng, 2 task theo lô/0 tải nhãn riêng; 6,999 giây xuất/9,401 giây đầu-cuối. Đối chiếu độc lập vận đơn gốc không đổi, 13 mã đúng reservation và thứ tự cùng WB. Không gửi 13 mã này lên API trong lượt xuất PDF; không coi in KIZ là Ozon đã chấp nhận mã. 12 đơn khác danh mục chỉ in vận đơn.

Lô 1.000 dùng mô phỏng, chưa có benchmark 1.000 đơn thật. Các giới hạn nghiệm thu sản phẩm/Catalog của 1.6.1 tiếp tục áp dụng; nguồn báo cáo phạt Ozon vẫn chưa hỗ trợ khi hợp đồng API chưa được xác minh. Lượt công bố không nhận thêm đơn, mua KIZ, kích hoạt license hay gửi lại Catalog; dữ liệu thật giữ ngoài repository.

## Tiếp tục phát triển

Lấy SHA nguồn trong `build-info.json` làm mốc. Giữ Java 25, schema 6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và phạm vi cửa hàng. Đọc receipt/journal trước giao dịch chưa rõ để tránh gửi hoặc mua trùng.

Trên Windows: `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Giữ bộ cài và bằng chứng trước `build.bat app-image` vì script dọn `out`. Workflow Build complete VNCode baseline dựng từ một SHA cố định, pin Latest trước khi kiểm tra nâng cấp; lượt full Windows 1.6.2 mất hơn 73 phút. Không dùng kết quả SHA khác để khẳng định binary mới đã được kiểm chứng.

Chỉ công bố sau kiểm thử và kiểm tra gói cuối. Giữ tag/binary/checksum bất biến, không thêm hoặc ghi đè asset sau Publish. Giữ các bản cũ; không dùng `prune` trong kiểm chứng. Xem [bộ nhớ dự án](../PROJECT_MEMORY.md) và bàn giao trước thao tác dữ liệu thật.
