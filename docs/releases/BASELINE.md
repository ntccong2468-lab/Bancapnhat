# VNCode consolidated baseline

Bản nền hiện hành đã công bố: **VNCode 1.5.12**, tag bất biến `v1.5.12`.
Nguồn dựa trên `v1.5.11`, luồng đăng ký/đồng bộ trong PR #12 và giao diện nền đen `b27d799`. Đã xác minh API Latest, toàn bộ tệp công khai và checksum; [biên nhận](../validation/2026-10-22-vncode-1512-published-latest.json). Release được GitHub khóa bất biến sau công bố. Biên nhận tải công khai được giữ trong repo và artifact Actions vì không thể thêm tài sản sau khi khóa.

Gói đầy đủ gồm:

- `VN-code-1.5.12-Windows-x64.exe` và `.msi`: bộ cài giữ định danh nâng cấp.
- `VN-code-1.5.12-Windows-x64.zip`: bộ chạy độc lập kèm Java và các thư viện.
- `VN-code-1.5.12-source.zip`: toàn bộ file được Git quản lý tại commit phát hành, gồm ứng dụng, website/Portal, công cụ build, kiểm thử và tài liệu.
- `VN-code-1.5.12-history.bundle`: lịch sử Git và các ref để tiếp tục phát triển/khôi phục mã.
- `VN-code-1.5.12-verification.zip`, `build-info.json`, native/upgrade smoke, `asset-manifest.json` và `SHA256SUMS`.

Không tự động xóa Release cũ khi công bố Latest. Nếu được yêu cầu xóa riêng, các bản cũ chỉ được xoá sau khi bộ cài, mã nguồn, runtime, lịch sử và kiểm thử của bản mới đã được tải công khai và đối chiếu digest/kích thước. Lưu danh sách release cũ trong `previous-release-inventory.json`; giữ Git history và tag như chứng cứ nguồn. Không xóa dữ liệu cài đặt của seller.

## Sửa và dựng bản tiếp theo

Clone repository hoặc Git bundle; dùng commit `2f9f194d26a9d840e9ab1097f51d91811abc3bbf` trong build-info làm mốc, tạo nhánh sửa mới. Tag `v1.5.12` trên GitHub trỏ đúng commit này; bundle được đóng trước khi tạo tag nên có thể chưa chứa tên tag mới. Giữ Java 25, schema 6, UUID nâng cấp, các khóa phạm vi tài khoản, đối soát yêu cầu chưa rõ, mua KIZ đúng một lần và xác nhận GTIN bằng readback.

Trên Windows: chạy `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Để có toàn bộ runtime dùng `build.bat app-image` sau khi lưu bộ cài và bằng chứng: script build sẽ dọn `out` mỗi lần.

Workflow **Build complete VNCode baseline** dựng toàn bộ từ một SHA, kiểm tra Windows và nâng cấp từ bản Latest được pin tại đầu lượt chạy. Workflow **Build & Test JavaFX Desktop App** chuyển đường mặc định sang workflow bản nền này; ma trận lịch sử cũ chỉ là chế độ tuỳ chọn và đòi hỏi các fixture đã lưu riêng. Các bản dựng thường không phụ thuộc bộ cài release cũ đã xoá. Dựng lại đúng phiên bản Latest được phép để kiểm tra, nhưng không được báo là đã kiểm tra nâng cấp hay công bố lại cùng tag.

Xem [ghi chú 1.5.12](VN-code-1.5.12.md), [WB 10.000](../validation/2026-10-22-wb-10000-audit.md) và [luồng đăng ký](../validation/2026-10-22-znack-registration-filters-proposals.md). Các file ghi chú cũ trong mã nguồn là tài liệu lịch sử, không phải danh sách bản còn tải được.
