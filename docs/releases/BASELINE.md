# VNCode consolidated baseline

Bản nền hiện hành đã công bố: **VNCode 1.5.14**, tag bất biến `v1.5.14`, mã ứng dụng `09718c80133528808047b01399e004ef9329ff64`.

Nguồn kế thừa 1.5.13 và giữ luồng Catalog đã sửa. Bổ sung cập nhật khi có tác vụ nền với checkpoint an toàn, lịch sử xác nhận GTIN WB/Ozon, gán KIZ trước đóng gói Ozon, PDF 1.000 đơn, vùng hiển thị toàn app, proof mua cho thẻ đã xuất bản và Áp dụng mua phần KIZ thiếu → chờ lưu thông → tự xuất nhãn. Ozon FBS tách Chờ nhận / Đơn mới chưa in / Đang giao hàng đã in / Đã bàn giao; In tất cả ở Đơn mới chỉ lấy đơn chưa in. [Biên nhận Latest](../validation/2026-10-24-vncode-1514-published-latest.json), [bàn giao](../validation/2026-10-24-kiz-print-purchase-handoff.md), [nhóm đơn Ozon](../fixes/2026-10-24-ozon-new-unprinted-orders.md).

Gói đầy đủ gồm:

- `VN-code-1.5.14-Windows-x64.exe` và `.msi`: bộ cài giữ định danh nâng cấp.
- `VN-code-1.5.14-Windows-x64.zip`: bộ chạy độc lập kèm Java và thư viện.
- `VN-code-1.5.14-source.zip`: toàn bộ file được Git quản lý tại commit phát hành.
- `VN-code-1.5.14-history.bundle`: lịch sử Git và các ref tại lúc đóng gói. Bundle có đúng SHA nguồn nhưng có thể chưa chứa tên tag được tạo sau lượt checkout.
- `VN-code-1.5.14-verification.zip`, `build-info.json`, native/upgrade smoke, `asset-manifest.json` và `SHA256SUMS`.

Windows và Linux `clean verify`: mỗi bên 2.986 ca, 2.984 đạt, 2 bỏ qua theo OS, không lỗi/thất bại. Mở app, DPAPI, cài/gỡ và nâng cấp từ Latest 1.5.13 giữ dữ liệu đều đạt. 136 ca mục tiêu và 117 ca Node đạt. Tài sản bắt buộc được tải công khai và kiểm tra digest/kích thước/checksum; bộ cập nhật trong JAR 1.5.13 phát hành đọc đúng Latest 1.5.14, chọn MSI và khớp hash/kích thước. Runtime công khai chứa các lớp mới và FXML/bốn ngôn ngữ; 1.147 file source/test/pom trong source ZIP khớp SHA chốt sau chuẩn hóa CRLF. Chưa quan sát thông báo trên máy seller.

Một thẻ đã được gửi và đọc lại bằng API Catalog production ở phiên 1.5.13; đây là bằng chứng riêng, không phải giao dịch của CI hoặc nghiệm thu bản 1.5.14 trên tài khoản seller. Thẻ lúc thử là nháp/chờ duyệt, chưa ký xanh. Không mua KIZ có phí, gán đơn hoặc ghi GTIN WB/Ozon production trong phiên phát hành 1.5.14. Xem [biên nhận API trước](../validation/2026-10-23-catalog-one-card-api-live.json).

Release được GitHub khóa bất biến sau công bố. Biên nhận tải công khai giữ trong repo và Actions; không thêm hoặc ghi đè tài sản sau Publish. Không tự động xóa Release cũ khi đưa bản mới lên Latest. Nếu được yêu cầu xóa riêng, phải xác minh bản mới đầy đủ trước và giữ Git history/tag; không xóa dữ liệu seller.

## Sửa và dựng bản tiếp theo

Clone repository hoặc Git bundle; dùng SHA nguồn trong build-info làm mốc. Giữ Java 25, schema 6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và phạm vi tài khoản, đối soát yêu cầu chưa rõ, mua KIZ đúng một lần và xác nhận GTIN bằng readback.

Trên Windows: chạy `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Lưu bộ cài/bằng chứng trước khi chạy `build.bat app-image`, vì script sẽ dọn `out`.

Workflow **Build complete VNCode baseline** dựng toàn bộ từ một SHA, kiểm tra Windows và nâng cấp từ Latest được pin tại đầu lượt chạy. Các bản dựng thường không phụ thuộc ma trận bộ cài lịch sử. Dựng lại đúng phiên bản Latest không được coi là thử nâng cấp hoặc công bố lại cùng tag. Chỉ đổi Latest sau khi tài sản cuối và native checks đạt; giữ tag/SHA/binary đã kiểm tra bất biến.

Xem [ghi chú 1.5.14](VN-code-1.5.14.md), [bộ nhớ dự án](../PROJECT_MEMORY.md), [quy tắc Catalog và hàng thu gọn](../fixes/2026-10-23-catalog-readback-compact-rows.md). Các ghi chú cũ trong nguồn là tài liệu lịch sử.
