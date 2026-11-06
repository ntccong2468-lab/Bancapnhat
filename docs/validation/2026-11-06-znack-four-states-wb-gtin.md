# Code Doctor — bốn bộ lọc Znack và cập nhật GTIN WB

Ngày: **06.11.2026, MSK (UTC+03:00)**. Nguồn ứng dụng: `5710392` trên `audit/code-doctor-20261106`, [PR #21](https://github.com/ntccong2468-lab/Bancapnhat/pull/21). **Chưa phát hành.** Latest tiếp tục là v1.6.4, nguồn `86d76d8`; không ghi đè bộ cài/tag cũ.

## Chẩn đoán và nguyên nhân

Theo yêu cầu Code Doctor, đã đọc API thật trước khi sửa. WB đọc đủ 388 bài / 1.982 biến thể; Catalog đọc 1.380 thẻ qua 2 trang danh sách và 56 lượt chi tiết, tối đa 25 GTIN/lượt. Bộ đọc/phân trang/parser/repository production của ứng dụng đã chạy với các API này và lưu đủ dữ liệu vào hồ sơ thử riêng. Khởi chạy JVM khác đọc lại 1.982 biến thể, 1.380 thẻ và cùng số lượng từng trạng thái mà không gọi API. Đối chiếu độc lập raw response khớp parser production: 1.260 published, 117 notsigned, 2 draft, 1 errors. Không coi 120 bản ghi có outer status draft đều là lỗi.

Bằng chứng: [JSON API thật và cache](2026-11-06-code-doctor-live.json). Không commit raw response, ID seller, mã GTIN/KIZ hoặc credential thật.

Ảnh người dùng thể hiện nguồn đọc đã hết 24 giờ. Giao diện khóa cả nút cập nhật GTIN mặc dù worker của nút đã có khả năng tự refresh nguồn hết hạn. Một GTIN/checkpoint cũ cũng được ưu tiên trước một thẻ khác khớp article, màu và size hiện tại, dẫn đến báo ghép không khớp. Giao diện dùng trạng thái ghép sản phẩm thay cho trạng thái công bố thực tế của thẻ.

TN VED bị kiểm tra ở bước matching, ở CatalogSync trước preview/dispatch/resume và trong WbGtinAdapter ngay trước ghi. Chỉ bỏ một tầng sẽ vẫn bị tầng sau chặn. Theo yêu cầu mới của người dùng, **TN VED không còn là điều kiện ghép/ghi GTIN lên WB**. Quy tắc này chỉ áp dụng việc append GTIN WB; khi tạo/sửa thẻ Catalog vẫn lấy TN VED sản phẩm trên sàn. Luồng Ozon và mapping kho KIZ mua giữ kiểm tra riêng của chúng.

Bốn ca hồi quy mới chạy trên mã trước sửa: 130 ca / 4 lớp, 2 failure + 2 error đúng các điều kiện này. Những lỗi compile/kỳ vọng fixture trong quá trình viết test đã được sửa trước khi dùng báo cáo cuối; không tính chúng là lỗi ứng dụng.

## Thay đổi

- `ZnackCardRegistrationController` / `OzonRegistrationPane`: chỉ còn **Chưa tạo, Đang xử lý, Hoàn tất, Lỗi**, cộng lựa chọn bỏ lọc **Tất cả**. Những việc cần xem lại ghép thẻ nằm trong Đang xử lý, không biến mất khi bỏ các bộ lọc phụ. Lỗi chỉ nhận trường hợp Catalog từ chối; các trạng thái nghiệp vụ/receipt cũ vẫn được lưu để tiếp tục an toàn.
- `RegistrationCardIndex` và `GtinVariantIdentity`: ưu tiên thẻ khớp đầy đủ danh tính hiện tại khi GTIN cũ không khớp; nếu vẫn có nhiều thẻ cùng khớp thì giữ mơ hồ, không tự chọn một GTIN. Không tự quy đổi size hoặc gộp các sắc độ màu khác nhau.
- `ZnackAutoSyncService`, `GtinTnvedIdentity`, `WbGtinAdapter`: bỏ chặn TN VED ở đầy đủ đường WB GTIN. Tiếp tục kiểm tra article, màu, đúng size/russian size, chủ sở hữu, nguồn hiện hành và xung đột GTIN ở sản phẩm khác. Append giữ barcode cũ, dữ liệu thẻ, TN VED và giấy tờ trên WB; thành công cần readback, không chỉ HTTP 200.
- UI giữ trạng thái công bố thực tế của một thẻ đã đọc, tách khỏi việc thẻ đã ghép vào biến thể WB hay chưa. Báo cáo dùng GTIN vừa đối chiếu, không ưu tiên GTIN/checkpoint cũ; lỗi lịch sử không được gắn thành lỗi hiện hành của thẻ đã công bố.
- Nút Cập nhật GTIN và thao tác cập nhật từng thẻ hoạt động với snapshot đã lưu. Khi nhấn, worker hiện có đọc xác minh lại nguồn hết hạn trước preview/ghi. Không kéo dài hiệu lực dữ liệu cũ hoặc bỏ gate nguồn/chủ sở hữu. Không tự gọi API chỉ vì mở màn hình/lọc dữ liệu. Khi TN VED đã đổi, cập nhật GTIN vẫn có thể dùng; tạo thẻ mới vẫn nằm trong menu và giữ xác nhận riêng.

## Kết quả thực tế sau sửa

Production matcher chạy trên 1.982 biến thể thật và cache chi tiết API thật: **229 ứng viên duy nhất đã công bố**, trong đó **204 cặp khác nhóm TN VED** đã không còn bị chặn bởi TN VED. Năm biến thể mẫu được đọc mới qua WbGtinAdapter và validateIdentity đạt **5/5**. Đây là đọc API/kiểm tra danh tính, **không phải nghiệm thu ghi GTIN**; không gọi submit.

Với bài người dùng đưa làm ví dụ, 5/5 biến thể có article và size phù hợp nhưng trường màu hiện có trên WB chi tiết hơn màu Catalog, nên không khớp chính xác theo quy tắc màu hiện tại. Đây là dữ liệu không khớp, không phải Catalog từ chối hay chưa có thẻ. Giao diện cho đọc xác minh/cập nhật; việc ghi vẫn yêu cầu đúng màu như người dùng đã chỉ định. Không tự thay màu hoặc ghép khác biến thể để tạo kết quả xanh.

## Kiểm thử

[330 ca / 14 lớp duy nhất](2026-11-06-znack-four-states-tests.json), 0 failure/error/skip. Hai lệnh cuối chạy trên cùng nguồn runtime cuối (các chỉnh tiếp theo là fixture/test và tài liệu):

```bash
DISPLAY=:167 /workspace/toolchains/mvn-vncode -q -Dtest=WbGtinAdapterTest,ZnackAutoSyncServiceTest,CatalogSyncServiceTest,RegistrationCardIndexTest,RegistrationDiagnosticsTest,ZnackRegistrationQueryRepositoryTest,ZnackCardRegistrationControllerTest,OzonRegistrationPaneTest test
DISPLAY=:167 /workspace/toolchains/mvn-vncode -q -Dtest=ZnackCardRegistrationControllerTest,ZnackRegistrationQueryRepositoryTest,GtinTnvedIdentityTest,ZnackGtinBackgroundServiceTest,GtinSyncCoordinatorTest,WbCatalogMappingServiceTest,OzonGtinAdapterTest,RegisteredGtinProofTest test
```

Lệnh đầu phát hiện hai kỳ vọng fixture cần cập nhật: đọc tất cả trang khi kiểm tra phân hoạch 1.230 hàng, và nút tạo thẻ khi TN VED đổi đã chuyển vào menu bên cạnh cập nhật WB. Hai lớp đó được chạy lại trong lệnh cuối và đạt; JSON sử dụng báo cáo cuối của từng lớp, không cộng lặp kết quả cũ.

Đã kiểm tra: append GTIN khác TN VED giữ nguyên giá trị TN VED/giấy tờ; sai article/màu/size không gửi; xung đột/chủ sở hữu/độ mới vẫn bị chặn; Ozon giữ TN VED; kho KIZ mua giữ quy tắc; phân hoạch đủ hàng vào đúng một trong bốn bộ lọc; snapshot hết hạn giữ danh sách và cho nút GTIN khởi động refresh; tạo thẻ thay thế vẫn cần xác nhận.

Build: `/workspace/toolchains/mvn-vncode -q -DskipTests package` ([PASS và hash JAR](2026-11-06-znack-four-states-build.json)). Không gọi skipTests là một lượt chạy test mới.

## Giới hạn và bàn giao

Ozon trả HTTP 403 / code 7 trên roles, sản phẩm và hàng đợi, thông báo API key bị vô hiệu hóa. Người dùng chọn tiếp tục WB/Catalog trước; không thử nhận/gán KIZ bằng khóa này nữa. Cần credential hợp lệ mới kiểm được Ozon thật.

API thật dùng Java production và hồ sơ SQLite riêng, kèm `LicenseDomainFixture` chỉ để cấp quyền chạy SDK ở harness. Fixture không nằm trong JAR/app, không kích hoạt license từ xa, không gửi Gmail/license key, không mua KIZ, không tạo/ký Catalog hay cập nhật GTIN seller. Chưa xác minh chứng thư/chủ doanh nghiệp trong full `forShop` production authentication, Windows installer hoặc thao tác ghi trên sàn trong lượt này. Phải giữ riêng đọc API, test mô phỏng và nghiệm thu ghi thật.

[Probe thủ công và hướng dẫn](code-doctor-20261106/README.md). Không merge/phát hành chỉ vì kiểm thử đạt; Latest và các release cũ được giữ nguyên.
