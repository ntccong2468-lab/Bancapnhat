# VNCode consolidated baseline

Bản nền hiện hành đã công bố: **VN Code 1.6.1**, tag bất biến `v1.6.1`, mã ứng dụng `1786c2c30e38c975523f9b678c4e77d203b5a7cb`.

Bản này gộp giao diện UI UX Pro Max toàn ứng dụng, nền tối/sáng, sửa sản phẩm và Catalog, FBS/KIZ, PDF Ozon theo lô, cache ảnh phiếu nhặt hàng và các sửa audit sau 1.6.0. [Tải bộ cài](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.1/VN-code-1.6.1-Windows-x64.exe), [ghi chú 1.6.1](VN-code-1.6.1.md), [bàn giao](../validation/2026-11-03-vncode-161-handoff.md), [biên nhận Latest](../validation/2026-11-04-vncode-161-published-latest.json).

## Gói nguồn và kiểm chứng

Release giữ đủ 16 tệp:

- `VN-code-1.6.1-Windows-x64.exe` và `.msi`: bộ cài giữ định danh nâng cấp.
- `VN-code-1.6.1-Windows-x64.zip`: bộ chạy độc lập kèm Java và thư viện.
- `VN-code-1.6.1-source.zip`: toàn bộ tệp Git tại mã phát hành.
- `VN-code-1.6.1-history.bundle`: lịch sử Git và ref tại lúc đóng gói. Bundle có SHA nguồn; tag được tạo sau bước đóng gói có thể chưa có trong bundle.
- `VN-code-1.6.1-verification.zip`, tệp `.sha256`, `build-info.json`, `release-notes.md`, `native-smoke.json`, `gs1-smoke.json`, `side-by-side-smoke.json`, `upgrade-baseline-smoke.json`, `previous-baseline.json`, `asset-manifest.json`, `SHA256SUMS`.

Windows trên đúng mã phát hành: 3.813 ca, 0 thất bại/lỗi, 2 bỏ qua theo OS; preflight 69/69. Linux full trên `24f9761`: 3.813 ca, 0 thất bại/lỗi, 2 OS skip; runtime, POM, wrapper và build script không thay đổi ở nguồn cuối. Native launcher, migration/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp từ Latest 1.6.0 bằng đúng EXE đều đạt, giữ dữ liệu. Công cụ phát hành Node 138/138 đạt.

Cả 16 tệp đã tải công khai và đối chiếu SHA-256/kích thước. Đối chiếu source ZIP gồm 2.626 tệp: 2.401 tệp văn bản chỉ khác CRLF do Windows, tệp nhị phân trùng byte. Runtime JAR đúng 1.6.1 và chứa CSS/lớp mới. Bộ cập nhật lấy từ binary 1.6.0 nhận đúng Latest 1.6.1, chọn MSI và khớp hash/kích thước; chưa quan sát popup trên máy seller.

Bộ cài chưa ký Authenticode. [Rà soát bảo mật](../validation/2026-11-03-release-security-review.md) giữ lại cảnh báo CodeQL Java về định danh chứng thư và các mục medium cần đánh giá tiếp; không tuyên bố mọi gate bảo mật đạt. Prototype migration mã hóa credential vẫn nằm ngoài runtime. Lượt này không triển khai portal.

## Phạm vi API thật

Mười lượt gửi lại giá trị hiện có của năm trường WB/Ozon đã được đọc lại xác nhận. Một GTIN/feed Catalog mới được đọc thấy đúng thẻ ở draft/moderation; chưa ký/công bố hoặc đẩy GTIN mới lên sàn. PDF Ozon thật đo trên một đơn/hai trang, 6,111 giây khi tạo tác vụ và 4,989 giây khi in lại; lô 100/1.000 dùng HTTP/PDF mô phỏng. Nguồn sàn quyết định khả năng nhận đơn/cấp nhãn; app giữ kết quả đúng và lý do còn thiếu, không giả báo đã nhận/in.

## Tiếp tục phát triển

Clone repository hoặc Git bundle; lấy SHA nguồn trong `build-info.json` làm mốc. Giữ Java 25, schema 6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và phạm vi cửa hàng. Đọc receipt/journal trước khi tiếp tục giao dịch chưa rõ, tránh tạo lại GTIN/feed hoặc mua KIZ trùng.

Trên Windows: `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Lưu bộ cài và bằng chứng trước `build.bat app-image`, vì script dọn `out`. Workflow Build complete VNCode baseline dựng từ một SHA cố định và dùng Latest được pin đầu lượt chạy làm mốc nâng cấp. Không dùng kết quả của SHA khác để khẳng định build đã được kiểm chứng.

Chỉ công bố sau khi kiểm thử và gói cuối đạt. Giữ tag, binary và checksum bất biến; không thêm/ghi đè tài sản sau Publish. Các bản cũ vẫn được giữ; lệnh `prune` không thuộc kiểm chứng thông thường. Xem [bộ nhớ dự án](../PROJECT_MEMORY.md) và bàn giao trước giao dịch thật hoặc thay đổi dữ liệu.
