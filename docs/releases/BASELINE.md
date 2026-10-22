# VNCode consolidated baseline

Bản nền đã công bố: **VNCode 1.5.11 — Latest**, tag bất biến `v1.5.11`.
Nguồn gom từ `v1.5.10`, sửa luồng đăng ký `3b71216` và các sửa/kiểm tra WB–KIZ trong phiên này. Nguồn ứng dụng: `2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`; [lượt Windows đầy đủ](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39760334344) đạt 2.879 kiểm thử, 0 lỗi/thất bại, 2 ca bỏ qua. Đã xác minh nâng cấp 1.5.10 → 1.5.11, đủ 1.839 tệp nguồn và các tệp tải công khai. Bộ cập nhật Windows ưu tiên MSI và đã được kiểm tra bằng JAR trong gói phát hành.

Gói đầy đủ gồm:

- `VN-code-1.5.11-Windows-x64.exe` và `.msi`: bộ cài giữ định danh nâng cấp.
- `VN-code-1.5.11-Windows-x64.zip`: bộ chạy độc lập kèm Java và các thư viện.
- `VN-code-1.5.11-source.zip`: toàn bộ file được Git quản lý tại commit phát hành, gồm ứng dụng, website/Portal, công cụ build, kiểm thử và tài liệu.
- `VN-code-1.5.11-history.bundle`: lịch sử Git và các ref để tiếp tục phát triển/khôi phục mã.
- `VN-code-1.5.11-verification.zip`, `build-info.json`, native/upgrade smoke, `asset-manifest.json` và `SHA256SUMS`.

Các bản phát hành cũ chỉ được xoá sau khi bộ cài, mã nguồn, runtime, lịch sử và kiểm thử của bản mới đã được tải công khai và đối chiếu digest/kích thước. Release 1.5.11 bị GitHub khóa bất biến sau công bố. Các biên bản bổ sung được lưu trong [evidence/1511](../validation/evidence/1511/), gồm danh sách release cũ `previous-release-inventory.json` được commit và xác minh công khai trước khi xoá. Giữ Git history và tag như chứng cứ nguồn. Không xóa dữ liệu cài đặt của seller.

## Sửa và dựng bản tiếp theo

Clone repository hoặc Git bundle; dùng tag `v1.5.11` làm mốc, tạo nhánh sửa mới. Giữ Java 25, schema 6, UUID nâng cấp, các khóa phạm vi tài khoản, đối soát yêu cầu chưa rõ, mua KIZ đúng một lần và xác nhận GTIN bằng readback.

Trên Windows: chạy `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe`. Để có toàn bộ runtime dùng `build.bat app-image` sau khi lưu bộ cài và bằng chứng: script build sẽ dọn `out` mỗi lần.

Workflow **Build complete VNCode baseline** dựng toàn bộ từ một SHA, kiểm tra Windows và nâng cấp từ bản Latest được pin tại đầu lượt chạy. Workflow **Build & Test JavaFX Desktop App** chuyển đường mặc định sang workflow bản nền này; ma trận lịch sử cũ chỉ là chế độ tuỳ chọn và đòi hỏi các fixture đã lưu riêng. Các bản dựng thường không phụ thuộc bộ cài release cũ đã xoá. Dựng lại đúng phiên bản Latest được phép để kiểm tra, nhưng không được báo là đã kiểm tra nâng cấp hay công bố lại cùng tag.

Xem [ghi chú 1.5.11](VN-code-1.5.11.md), [WB 10.000](../validation/2026-10-22-wb-10000-audit.md) và [luồng đăng ký](../validation/2026-10-22-znack-registration-filters-proposals.md). Các file ghi chú cũ trong mã nguồn là tài liệu lịch sử, không phải danh sách bản còn tải được.

## Biên bản và dọn bản cũ

Các tệp cốt lõi trong Release giữ nguyên checksum đã kiểm thử. [Bằng chứng tải công khai](../validation/evidence/1511/public-verification.json) và [bộ cập nhật thật](../validation/evidence/1511/updater-verification.json) nằm trong repository vì không thể gắn thêm asset vào Release bất biến. Công cụ `tools/prune-immutable-baseline.mjs` chỉ dọn sau khi kiểm tra commit lưu trữ, bộ cập nhật, nguồn tag, đủ asset và Latest trước từng lệnh DELETE; không xóa Git ref hoặc dữ liệu seller. Biên bản hoàn tất sẽ được lưu tại `evidence/1511/baseline-cleanup.json`.
