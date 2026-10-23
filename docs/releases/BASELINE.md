# VNCode consolidated baseline

Bản nền hiện hành đã công bố: **VNCode 1.5.13**, tag bất biến `v1.5.13`, mã ứng dụng `7e88dc0e5e4e491025874f560c3ac069b6718644`.

Nguồn kế thừa 1.5.12 và bổ sung bản sửa `674eada`: đọc lại cuối lô Catalog, xác nhận thẻ trước khi đưa vào Đang xử lý, dùng chung luồng tạo từng thẻ/theo lô Ozon, ánh xạ tên nước có bằng chứng và hàng sản phẩm thu gọn. Đã đối chiếu mã sửa trong JAR/runtime và toàn bộ nguồn sản xuất trong gói source. [Biên nhận Latest](../validation/2026-10-23-vncode-1513-published-latest.json), [bàn giao](../validation/2026-10-23-vncode-1513-handoff.md).

Gói đầy đủ gồm:

- `VN-code-1.5.13-Windows-x64.exe` và `.msi`: bộ cài giữ định danh nâng cấp.
- `VN-code-1.5.13-Windows-x64.zip`: bộ chạy độc lập kèm Java và thư viện.
- `VN-code-1.5.13-source.zip`: toàn bộ file được Git quản lý tại commit phát hành.
- `VN-code-1.5.13-history.bundle`: lịch sử Git và các ref tại lúc đóng gói. Bundle có đúng SHA nguồn nhưng có thể chưa chứa tên tag được tạo sau lượt checkout.
- `VN-code-1.5.13-verification.zip`, `build-info.json`, native/upgrade smoke, `asset-manifest.json` và `SHA256SUMS`.

Windows `clean verify`: 2.929 ca, 2.927 đạt, 2 bỏ qua theo OS, không lỗi/thất bại. Mở app, DPAPI, cài/gỡ và nâng cấp từ Latest 1.5.12 giữ dữ liệu đều đạt. 117 ca Node đạt. Tài sản bắt buộc được tải công khai và kiểm tra digest/kích thước/checksum; bộ cập nhật trong JAR phát hành đọc đúng Latest 1.5.13 và chọn MSI. Chưa quan sát thông báo trên máy seller.

Một thẻ đã được gửi và đọc lại bằng API Catalog production theo phê duyệt cụ thể của chủ dự án trong phiên sửa; đây là bằng chứng riêng, không phải giao dịch của CI. Thẻ đang nháp/chờ duyệt, chưa ký xanh. Không mua KIZ có phí hoặc ghi GTIN WB/Ozon trong lượt này. Xem [biên nhận API](../validation/2026-10-23-catalog-one-card-api-live.json).

Release được GitHub khóa bất biến sau công bố. Biên nhận tải công khai giữ trong repo và Actions; không thêm hoặc ghi đè tài sản sau Publish. Không tự động xóa Release cũ khi đưa bản mới lên Latest. Nếu được yêu cầu xóa riêng, phải xác minh bản mới đầy đủ trước và giữ Git history/tag; không xóa dữ liệu seller.

## Sửa và dựng bản tiếp theo

Clone repository hoặc Git bundle; dùng SHA nguồn trong build-info làm mốc. Giữ Java 25, schema 6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và phạm vi tài khoản, đối soát yêu cầu chưa rõ, mua KIZ đúng một lần và xác nhận GTIN bằng readback.

Trên Windows: chạy `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Lưu bộ cài/bằng chứng trước khi chạy `build.bat app-image`, vì script sẽ dọn `out`.

Workflow **Build complete VNCode baseline** dựng toàn bộ từ một SHA, kiểm tra Windows và nâng cấp từ Latest được pin tại đầu lượt chạy. Các bản dựng thường không phụ thuộc ma trận bộ cài lịch sử. Dựng lại đúng phiên bản Latest không được coi là thử nâng cấp hoặc công bố lại cùng tag. Chỉ đổi Latest sau khi tài sản cuối và native checks đạt; giữ tag/SHA/binary đã kiểm tra bất biến.

Xem [ghi chú 1.5.13](VN-code-1.5.13.md), [bộ nhớ dự án](../PROJECT_MEMORY.md), [quy tắc Catalog và hàng thu gọn](../fixes/2026-10-23-catalog-readback-compact-rows.md). Các ghi chú cũ trong nguồn là tài liệu lịch sử.
