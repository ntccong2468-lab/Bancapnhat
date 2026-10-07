# Bàn giao mã nguồn Windows và trạng thái nghiệm thu GTIN

Ngày kiểm tra lại: 06/10/2026. Nhánh bàn giao: `feat/windows-gtin-sync-20261006`
tại `ntccong2468-lab/Vncode`. Mã nguồn đã kiểm thử trên Linux và Windows.
**Đã có EXE thử nghiệm Windows x64 chưa ký**, build native và checksum qua;
cài đặt và chạy thử trên máy người dùng đang chờ nghiệm thu.
Chức năng thêm/thay GTIN trên seller WB/Ozon **chưa sẵn sàng dùng thật**.

## Đã triển khai và xác minh

| Hạng mục | Kết quả |
| --- | --- |
| Checksum GTIN 8/12/13/14 số, giữ số 0 đầu, phân biệt trade-unit/KIZ | Unit tests qua; unknown packaging/publication bị loại khỏi ứng viên |
| Ghép article/màu/size và chọn GTIN đăng ký thủ công | Không đoán khi thiếu thuộc tính/khớp nhiều; source/matching/preview tests qua |
| Catalog, mapping, jobs/items/events SQLite | Migration additive schema 3; shop/marketplace scope và CAS được kiểm tra |
| Upgrade schema 2→3 | Fixture không có bảng GTIN; shop cũ giữ nguyên; integrity/foreign-key sạch; snapshot trước nâng giữ schema 2 và dữ liệu shop |
| Preview thêm/thay, giữ barcode khác | Xem trước dùng lựa chọn hiện tại; mapping nội bộ không gửi lên sàn |
| Queue và reconciliation | Fixture có xác nhận, chống trùng, revalidation, tạm dừng, hủy dòng chưa gửi; timeout/restart không tự gửi lại |
| Retry có chủ ý sau kết quả an toàn | Job toàn CANCELLED/FAILED được xác nhận lại thành attempt mới; job active/ambiguous/succeeded giữ chống trùng |
| UI và async | Load token chặn kết quả shop cũ; history cập nhật khi chạy; pause/cancel không bị khóa suốt queue; mutation giữ shop gốc |
| RU/EN/VI/ZH, CSV/XLSX, FXML, navigation | Tests qua; XLSX ô chuỗi; CSV theo quy ước Excel có dấu nháy đơn |
| Toàn bộ Java | JDK 25.0.4.1, Maven 3.8.5, Linux/Xorg: **518 tests, 0 failures/errors/skipped**, `clean verify` exit 0 |
| Toàn bộ Java/FXML trên Windows | Runner Windows, JDK 25: **518 tests, 0 failures/errors/skipped**, `clean verify` exit 0 |
| Packaging contracts | Node: **18 tests passed**, exit 0 trên Linux và Windows; thêm tùy chọn workflow chỉ build Windows |
| EXE Windows x64 | `build.bat exe` thành công; tải và xác minh SHA-256, PE x86-64; xem [báo cáo bộ cài thử](2026-10-06-windows-test-installer.md) |
| Chạy màn hình GTIN với fixture | Render JavaFX thực 1440×900, tiếng Việt, trên Linux; preview thay mã hiển thị và nút gửi bị khóa; có ảnh PNG đi kèm bàn giao |

Nguồn production đọc National Catalog và sản phẩm sàn qua các client WCode
hiện có. Chưa có payload seller thật để kiểm chứng độ phủ thuộc tính. Reader
không lấy đủ màu/size hoặc gặp cấu trúc National Catalog chưa biết sẽ không
được dùng để tự ghép chắc chắn. Ánh xạ thủ công vẫn phải chọn GTIN đã xác minh
đăng ký; không tự tạo GTIN. Preview mutation cần catalog không quá 24 giờ và
không ở tương lai quá 5 phút.

## Trở ngại đang còn

1. `dev.wildberries.ru` và `docs.ozon.ru` trả HTTP 403 qua network policy của
   môi trường. Hai miền đã được lưu vào **bản nháp** cấu hình cloud; chưa áp
   dụng runtime. Cần review/lưu/publish bản nháp để tải được tài liệu chính
   thức, rồi xác minh endpoint, schema, quyền token, giữ payload sản phẩm,
   giới hạn xóa/thay barcode và cách đối soát trước khi bật capability.
   Hiện `WbGtinAdapter` và `OzonGtinAdapter` khóa ADD/REPLACE; xem trước có thể
   hiển thị, xác nhận gửi bị khóa. HTTP 403 không chứng minh API không hỗ trợ.
2. Đã build EXE trên runner Windows và chạy full Java/FXML suite. Chưa cài
   EXE hoặc chạy native launcher của package trên máy người dùng; hiển thị
   Windows 100%/125%, MSI/app-image, upgrade MSI legacy và DPAPI runtime
   vẫn chưa được nghiệm thu trong lượt này.
3. Chưa có CryptoPro/chứng thư thử và fixture seller được phê duyệt để kiểm
   chứng tích hợp thật. Không dùng API key/chứng thư/database seller trong tests.

Người dùng đã yêu cầu đẩy mã lên nhánh mới trong `ntccong2468-lab/Vncode`,
sau đó yêu cầu tạo bộ cài Windows để kiểm tra. Đã dispatch workflow build
trên nhánh đó và bàn giao artifact thử nghiệm; không phát hành release hoặc
gửi mutation seller.

## Build và nghiệm thu trên Windows x64

Workflow đã chạy `mvnw.cmd -B clean verify`, các Node contracts và `build.bat exe`
trên Windows thành công. Các lệnh bên dưới dùng để tự build thêm các loại gói;
MSI và app-image chưa build trong lượt này. Cài JDK 25 x64 và Node 22 theo
runbook hiện có; EXE/MSI cần
WiX theo yêu cầu `jpackage`. Mở terminal ở thư mục mã nguồn, kiểm tra
`java -version` trỏ đúng JDK 25:

```bat
mvnw.cmd -B clean verify
node --test tools/*.test.mjs
build.bat app-image
build.bat exe
build.bat msi
```

Các gói local chưa được coi là signed release. Giữ cấu hình installer identity,
update signature và bảo vệ app-data trong [runbook](../javafx-release-runbook.md).
README có link riêng tới bộ cài thử GTIN; các link release WCode cũ không chứa
module mới.

Smoke-test launcher với app-data tạm riêng, không dùng database seller. Kiểm
tra toàn bộ route trong [bảng bảo toàn chức năng](vn-code-feature-parity.md),
tiếng Việt và nhãn dài ở 100%/125%; shop switch khi đọc nền; chọn/thay preview;
mapping chỉ nội bộ; confirm bị khóa khi capability chưa xác minh. Sau khi có
adapter đã xác minh, chạy add/replace/reconcile với fixture giả, timeout sau
acceptance và restart; hủy unsent không được hủy dòng đã gửi. Chạy upgrade
fixture và kiểm tra `PRAGMA integrity_check`, `foreign_key_check`, snapshot,
shop và lịch sử GTIN. Ghi SHA-256 và đường dẫn từng artifact thực tế.

## Rà soát cuối và quyết định triển khai

Một lượt review độc lập không có Critical; bốn Important được tái hiện bằng
kiểm thử rồi sửa: editor preview, pause tại send-claim, controls khi queue chạy,
retry sau cancellation/definitive failure. Kiểm thử UI dùng JavaFX thực với
adapter fixture và SQLite tạm. Observer hiển thị lỗi không quyết định kết quả
mutation. Sau sửa chạy full suite và Node contracts thành công.

Các quyết định còn lại: dùng checkout cloud cô lập sẵn, không thêm worktree;
ghi ledger thủ công vì script skill không có trong filesystem; giữ màn hình
nghiệp vụ cũ và chỉ tách navigation; dữ liệu đăng ký/đóng gói không chắc chắn
bị chặn; serialize các size cùng product khi đang xử lý; giữ giới hạn license
hiện có; catalog có hạn sử dụng 24 giờ; production mutation chặn tới khi có
tài liệu xác minh. Đổi lại, một số dòng chưa đủ metadata không tự ghép được
và ứng dụng chưa đáp ứng đầy đủ yêu cầu cập nhật trực tiếp lên sàn.

Lượt kiểm tra lại trước khi đẩy mã sửa thêm lỗi xóa shop làm cascade mất history
khi còn tác vụ GTIN chưa kết thúc. Shop chỉ được xóa sau khi các dòng chưa gửi
được hủy hoặc kết quả của mọi dòng đã gửi được đối soát. Hai kiểm thử hồi quy
mới dùng SQLite tạm; đầy đủ thông tin trong [báo cáo kiểm tra lại](2026-10-06-pre-push-check.md).
