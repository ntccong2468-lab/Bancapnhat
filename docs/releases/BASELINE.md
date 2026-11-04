# VNCode consolidated baseline

Bản nền hiện hành đã công bố: **VN Code 1.6.2**, tag bất biến `v1.6.2`, nguồn `3968f8f4bb2239ddf61b48e9f6245086bd4ad529`, phát hành 04.11.2026 08:22:03 UTC.

Kế thừa toàn bộ 1.6.1. Bản mới giữ nguyên nhãn vận đơn Ozon, bỏ trang barcode sản phẩm đứng riêng khi nhận diện được, giữ nhãn kết hợp và luồng lấy nhãn theo lô. Phiếu nhặt hàng dùng đúng số trên nhãn từng đơn với đối chiếu `scanit`. Báo cáo lý do phạt chuyển từ Tổng quan sang Công cụ → Báo cáo tài chính.

[Tải bộ cài EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.2/VN-code-1.6.2-Windows-x64.exe), [ghi chú 1.6.2](VN-code-1.6.2.md), [bàn giao](../validation/2026-11-04-vncode-162-handoff.md), [biên nhận Latest](../validation/2026-11-04-vncode-162-published-latest.json). Lịch sử tính năng nền ở [ghi chú 1.6.1](VN-code-1.6.1.md).

## Gói nguồn và kiểm chứng

Release giữ đủ 16 tệp: EXE, MSI, ZIP chạy độc lập kèm Java, source ZIP, Git history bundle, verification ZIP, tệp EXE `.sha256`, `build-info.json`, `release-notes.md`, `native-smoke.json`, `gs1-smoke.json`, `side-by-side-smoke.json`, `upgrade-baseline-smoke.json`, `previous-baseline.json`, `asset-manifest.json`, `SHA256SUMS`. Các tệp có tiền tố phiên bản dùng `VN-code-1.6.2-`.

Windows build `41789324429` trên đúng nguồn phát hành: **3.824 ca, 0 thất bại/lỗi, 2 bỏ qua theo OS**, 520 lớp; preflight 69/69. Native launcher, migration/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp bằng đúng EXE từ Latest 1.6.1 giữ dữ liệu đều đạt. Node 138/138 trên nguồn ứng dụng, 141/141 ở publisher. Không chạy lại full Linux cho bản này; 302 ca liên quan đã đạt trước đóng gói.

Publisher `41798807654` đạt. Cả 16 tệp đã tải công khai và đối chiếu SHA-256/kích thước. Source ZIP gồm 2.646 tệp: 2.417 tệp văn bản chỉ khác CRLF do Windows; nhị phân trùng byte. Runtime JAR đúng 1.6.2 và chứa các lớp sửa nhãn, scanit và Tài chính. Bộ đọc updater từ binary 1.6.1 nhận đúng Latest 1.6.2, MSI/hash/kích thước; chưa quan sát popup hay cài trên máy seller.

Bộ cài chưa ký Authenticode. [Rà soát bảo mật nền 1.6.1](../validation/2026-11-03-release-security-review.md) vẫn có cảnh báo định danh chứng thư và các mục medium; không tuyên bố mọi gate bảo mật đạt. Prototype migration mã hóa credential nằm ngoài runtime. Portal không được triển khai.

## Phạm vi API thật

Các kết quả ghi thật trước bản nền 1.6.1 vẫn có giới hạn được ghi trong bàn giao 1.6.1. Lượt sửa 1.6.2 đọc API Ozon và đối chiếu `scanit` với một PDF thật đã lưu, không tạo tác vụ nhãn mới. Lô 100/1.000 dùng HTTP/PDF mô phỏng, không phải tốc độ cửa hàng thật. Nguồn báo cáo phạt Ozon vẫn chưa hỗ trợ khi hợp đồng API chưa được xác minh. Lượt phát hành không nhận đơn khách, mua/tiêu KIZ, kích hoạt license hoặc gửi lại Catalog.

## Tiếp tục phát triển

Lấy SHA nguồn trong `build-info.json` làm mốc. Giữ Java 25, schema 6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và phạm vi cửa hàng. Đọc receipt/journal trước giao dịch chưa rõ để tránh gửi hoặc mua trùng.

Trên Windows: `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Giữ bộ cài và bằng chứng trước `build.bat app-image` vì script dọn `out`. Workflow Build complete VNCode baseline dựng từ một SHA cố định, pin Latest trước khi kiểm tra nâng cấp; lượt full Windows 1.6.2 mất hơn 73 phút. Không dùng kết quả SHA khác để khẳng định binary mới đã được kiểm chứng.

Chỉ công bố sau kiểm thử và kiểm tra gói cuối. Giữ tag/binary/checksum bất biến, không thêm hoặc ghi đè asset sau Publish. Giữ các bản cũ; không dùng `prune` trong kiểm chứng. Xem [bộ nhớ dự án](../PROJECT_MEMORY.md) và bàn giao trước thao tác dữ liệu thật.
