# Bàn giao GTIN / Znack theo PROMPT MASTER

Ngày cập nhật: **21.10.2026 UTC**. Nguồn đang kiểm tra là **VNCode 1.5.7**, phát triển tiếp từ HEAD bắt đầu `5621a4f` / 1.5.6. Không ghi đè danh tính các bản đã công bố. Đã hoàn tất các phần mã và kiểm thử độc lập C–G; còn các bước nghiệm thu bên ngoài nêu ở mục 4, nên **chưa xác nhận hoàn tất toàn bộ trên production**. Không tạo EXE, phát hành hay đổi Latest trong đợt này.

## 1. Nguyên nhân đã chứng minh

- **Lỗi chặn quốc gia:** API thật `/v3/dictionary/isocountry` trả `apiversion=3`, `result._etag/_list`, trong khi mã 1.5.5 đọc `result` như mảng. Đã thấy HTTP 200, 254 mục; Nga là `country_iso=RU`, tên hiển thị `Россия`. Thuộc tính Catalog 2630 trỏ đúng từ điển. [Bằng chứng chỉ đọc](2026-10-21-catalog-country-envelope.json), [hợp đồng 5.68](2026-10-21-catalog-country-contract.md).
- **Nhầm barcode với GTIN đã xác minh:** reader WB/Ozon từng đặt barcode đầu tiên vào `ProductVariant.gtin` dù chưa có bằng chứng Catalog. Mapping và đề xuất mặc định có thể bỏ lọt khác màu hoặc dùng giá trị phổ biến của lô mâu thuẫn.
- **Trạng thái tạo/ký không đủ căn cứ:** feed được nhận, hàng đợi cục bộ và thẻ đã thực sự tồn tại/được công bố chưa được tách rõ. Có đường tiếp tục dùng schema cũ, chưa tra đầy đủ thẻ hiện có trước xin mã, consent chỉ gắn owner+GTIN và retry chưa có giới hạn bền vững.
- **Ghi sàn và số liệu:** cần đọc lại sau khi chờ hạn mức, bảo vệ dữ liệu nguyên thẻ WB, kiểm tra archive Ozon, phân biệt đã thử gửi với đã xác minh; bộ đếm/báo cáo từng trộn phạm vi toàn shop và lô. Lô giả lập 10.000 Ozon còn bị lặp projection/đọc SQLite ở preview; đã tối ưu đường đọc tạm thời nhưng giữ kiểm tra nguồn và biến thể.
- **Hồi quy phát hiện lỗi publication Ozon:** khi thẻ được công bố, repository ghi đè bằng chứng Catalog bằng cờ cục bộ và mất ID/trạng thái/thuộc tính gốc. Kiểm tra hoàn tất GTIN vì thế chặn đúng, nhưng luồng không thể tiếp tục. Đã sửa để lưu bản đọc thẻ thực và ràng buộc ID Catalog với thẻ đang đăng ký; thông báo cục bộ cũ không được xóa bằng chứng tốt. Giữ trạng thái công bố thật nhưng không xóa việc kiểm tra giấy tờ đang chưa rõ. WB cũng được siết đối soát và kích hoạt theo ID thẻ cùng sản phẩm/màu/size thật.

## 2. Chức năng đã sửa và nhóm file chính

| Nhóm | Thay đổi và lý do | File tiêu biểu |
| --- | --- | --- |
| C — quốc gia, lỗi API | Chỉ nhận envelope có hợp đồng; giữ nguyên cause và phân biệt auth/429/DNS/TLS/timeout/HTML/JSON/rỗng. Dùng snapshot đúng thao tác theo `no-store`, không dùng cache tốt để vượt lỗi quyền. Tách nước sản xuất khỏi xuất xứ/pháp nhân. Giá trị mặc định `Россия` đã được chủ dự án chỉ định vẫn phải ánh xạ qua từ điển thật. Schema hiện hành được kiểm tra lại trước cấp mã/gửi. | `ZnackNationalCatalogService`, `ZnackApiClient`, `CatalogReadFailure`, `RegistrationVerifiedDefaults`, `RegistrationDynamicPreflight`, `RegistrationBatchPreflight` |
| D — định danh, đề xuất | Barcode sàn tách khỏi GTIN/provenance/thời điểm xác minh; giữ số 0 đầu và đọc tương thích snapshot cũ. Đối chiếu đúng tài khoản/article/màu/size/TN VED, không tự quy đổi L/XL thành cỡ Nga. Đề xuất có nguồn, lý do và revision; thiếu/mâu thuẫn phải bổ sung, không chọn giá trị phổ biến nhất. | `ProductVariant`, reader WB/Ozon, `ProductRepository`, `GtinVariantIdentity`, `RegisteredGtinProof`, mapping WB/Ozon, `ProductSuggestionService`, `ProductBulkEditService`, `ProductBulkEditPane`, `RegistrationReviewedValues` |
| E — đăng ký và hàng đợi | Đọc đầy đủ thẻ hiện có trong phạm vi đã xác minh rồi đọc exact ứng viên trước tạo. UNKNOWN không cho xin mã mới. Giữ intent/checkpoint/feed/good_id thật; kết quả mất sau gửi được đối soát, không phát lại mù. Tạm dừng/hủy phần chưa gửi/tiếp tục, giới hạn retry 5 lần. Đổi nhóm TN VED cần thao tác xin mã mới có xác nhận riêng. | `RegistrationExistingCards`, workflow/repository/batch WB và Ozon, `ZnackRegistrationMonitor`, `CatalogProductDataParser`, `NationalCatalogGtinSource`, `CatalogScanCheckpoint` |
| E — ký và công bố | Consent gắn payload hash, tài khoản, chứng thư, môi trường và XML đã duyệt. Consent cũ không tự cho phép ký. Hộp duyệt hiển thị lô, chủ thể, dữ liệu/XML/SHA-256; thay nội dung phải xem lại. Ký lô tối đa 10 tài liệu, kết quả tiếp nhận chữ ký vẫn cần đọc trạng thái công bố. | `RegistrationSigningConsent`, `RegistrationSigningReview`, `RegistrationSigningReviewDialog`, `ZnackNationalCatalogService`, workflow WB/Ozon |
| F — cập nhật GTIN | WB ADD-only vào đúng size, giữ barcode/thuộc tính/giấy tờ cũ; Ozon dùng API thêm barcode theo SKU thật. Quét đủ active/archive, kiểm tra cursor/tổng/định danh, đọc mới sau chờ hạn mức và đọc lại đúng biến thể trước báo thành công. Không giữ suất HTTP FBS trong lúc chờ. | `WbGtinAdapter`, `OzonGtinAdapter`, reader GTIN WB/Ozon, `GtinBatchTransport`, `ListingHttpClient`, `ZnackGtinReviewService`, `ZnackGtinBackgroundService`, `GtinSyncRepository/Schema`, `GtinRunStatusSnapshot` |
| F — báo cáo/giao diện | Tách trạng thái nội bộ/Catalog/hàng đợi/sàn; giữ trạng thái gốc và thời điểm. Lọc toàn run, báo cáo từng dòng và CSV che bí mật, bảo toàn tệp đích nếu dữ liệu đổi khi xuất. Bộ đếm riêng toàn shop/bộ lọc/lô; “Đã thử gửi” không đồng nghĩa server chấp nhận. Viên thuốc có kết quả và báo cáo, không khóa việc xem bộ lọc. | `RegistrationReportData`, `RegistrationSyncReportDialog`, `RegistrationDiagnostics`, `GtinBackgroundStatus`, controller WB/Ozon, bốn `messages_*.properties` |
| Kiểm chứng/bản dựng | Thêm hồi quy hành vi, giữ các kiểm tra đang có; nguồn đổi 1.5.7 để không dùng lại phiên bản đã công bố. | `src/test/java/...`, tài liệu C–F, `pom.xml` |

Giữ stack Java25/JavaFX/Maven/SQLite, schema version 6, dữ liệu cấu hình/sản phẩm/barcode/KIZ/lịch sử và luồng FBS hiện có. Bảng journal và cột consent bổ sung qua cơ chế khởi tạo idempotent; không viết lại bảng cũ hoặc nâng DB thật của seller trong lượt thử. Chi tiết đầy đủ nằm trong `git diff` và [nhật ký tiến độ](../GTIN_ZNACK_PROGRESS.md).

## 3. Kiểm thử/build đã thực sự chạy

| Phạm vi | Kết quả cuối đã ghi nhận | Bằng chứng |
| --- | --- | --- |
| C | 341 ca / 25 lớp, 0 thất bại/lỗi/bỏ qua | [C-tests.json](2026-10-21-gtin-znack-C-tests.json) |
| D | 327 ca / 31 lớp, 0 thất bại/lỗi/bỏ qua | [D-tests.json](2026-10-21-gtin-znack-D-tests.json) |
| E | 498 ca / 37 lớp, 0 thất bại/lỗi/bỏ qua | [E-tests.json](2026-10-21-gtin-znack-E-tests.json) |
| F | 386 ca / 42 lớp, 0 thất bại/lỗi/bỏ qua | [F-tests.json](2026-10-21-gtin-znack-F-tests.json) |
| Node | `node --test tools/*.test.mjs`: 103/103 đạt, không bỏ qua | `/tmp/vncode-master-G-node.log` |
| G — tổng hợp F + 341 lớp còn lại + chạy lại phần ảnh hưởng | **2.639 đạt / 2.641 ca, 383 lớp; 0 thất bại/lỗi, 2 ca Windows chưa chạy trên Linux.** Maven `verify` và JAR đạt. Đây là kết quả cuối từng ca qua các lượt, không phải một lượt toàn repo đạt ngay lần đầu. | [G-tests.json](2026-10-21-gtin-znack-G-tests.json), [build/SHA-256](2026-10-21-gtin-znack-build.json) |

C–F là kết quả cuối theo lớp/test case sau các lượt chạy lại phần ảnh hưởng, **không phải bốn lượt toàn repo đều đạt ngay lần đầu**. Các phạm vi có trùng ca nên không cộng thành một tổng duy nhất. Bằng chứng giữ các lần thất bại: E ban đầu 39 thất bại/8 lỗi; F có lượt bị dừng vì CPU và lượt 10 thất bại/1 lỗi, sau đó đã sửa và kiểm tra lại phần ảnh hưởng. Không bỏ test hay nới guard để báo đạt.

Các lệnh thực tế dùng Maven offline/JDK25/JavaFX/Xvfb. Dưới đây là một số lượt chạy lại đã được ghi trong nhật ký; danh sách toàn bộ lớp/kết quả có trong JSON liên kết phía trên:

```sh
DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -Dtest=CatalogReadFailureTest,ZnackCardRegistrationWorkflowTest,ZnackCardRegistrationControllerTest,RegistrationPreflightCancellationTest test
DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -Dtest=RegistrationPreflightCancellationTest test
DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -Dtest=RegistrationCardIndexTest,ZnackCardRegistrationControllerTest,ProductBulkDialogTest test
DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -Dtest=RegistrationExistingCardsTest test
node --test tools/*.test.mjs
```

G đầu chạy 341 lớp/2.245 ca: 2 thất bại, 8 lỗi, 2 ca Windows bỏ qua. Sau sửa publication/kích hoạt và fixture, chạy lại 22 lớp/403 ca còn một fixture âm thiếu thao tác xóa `identified_by` vốn đã có trong payload. Đã sửa đúng dữ liệu giả lập để mô phỏng trường bị thiếu và chạy lại đủ 11 ca của lớp này đạt; không sửa production hay nới guard ở lượt cuối. `verify` sau đó tạo JAR + dependency thật. Hai ca Windows là `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows` và `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`; không coi chúng là đạt.

Dạng ghi gọn tương đương argv thực tế dưới đây dùng đúng danh sách lớp đã lưu; không có cờ bỏ kiểm thử. G JSON giữ danh sách 341 lớp và kết quả từng ca để tái lập ngoài môi trường này:

```sh
DISPLAY=:99 XDG_CACHE_HOME=/tmp/vncode-ui-cache /workspace/toolchains/mvn-vncode -o "-Dtest=$(cat /tmp/vncode-G-remaining-selectors.txt)" verify
DISPLAY=:99 XDG_CACHE_HOME=/tmp/vncode-ui-cache /workspace/toolchains/mvn-vncode -o "-Dtest=$(cat /tmp/vncode-G-final-selectors.txt)" verify
DISPLAY=:99 XDG_CACHE_HOME=/tmp/vncode-ui-cache /workspace/toolchains/mvn-vncode -o -Dtest=ZnackCatalogObservationGuardTest verify
```

## 4. Phần nào đã xác minh, phần nào còn thiếu

| Môi trường/hành vi | Trạng thái |
| --- | --- |
| Localhost/mock, JavaFX thật và SQLite thật trên Linux | Đã kiểm tra C–G, gồm đăng ký/FBS/in/migration/khởi động; các ca Windows chưa chạy được ghi riêng. Mock không chứng minh đăng ký trên server thật. |
| API production **chỉ đọc** | Đã đọc dictionary/schema và một thẻ xanh thật: ID dương, `published`, `good_signed=true`, `identified_by` khớp GTIN ở cấp `trade-unit`. Danh sách 100 dòng chỉ là mẫu, không phải kết quả đồng bộ đầy đủ. Không gọi API ghi trong thử nghiệm mới. [Cấu trúc thẻ thật đã che dữ liệu](2026-10-21-catalog-published-shape.json), [hợp đồng sàn](2026-10-21-gtin-marketplace-contract.md). |
| Lô 10.000 GTIN WB/Ozon | Planner/adapter/SQLite thật + API giả lập: WB 4 lần ghi/214 đọc; Ozon 100 lần ghi/272 đọc, xác nhận đủ và giữ barcode cũ. Không phải đo tốc độ/quota trên shop thật. |
| Tạo thẻ/xin GTIN/ký/cập nhật sàn production | **Chưa nghiệm thu ghi thật** cho nguồn 1.5.7 này; không lấy kết quả của bản cũ làm chứng cứ mới. |
| Windows, EXE và nâng cấp bộ cài | **Chưa kiểm thử/đóng gói** cho nguồn mới. JAR+lib đã build và mở cửa sổ chính trên Linux/Xvfb, dữ liệu riêng rỗng, SQLite integrity/foreign-key đạt; không đổi tên thành EXE. |
| CryptoPro, chứng thư/vai trò thật | Có kiểm thử mô phỏng CMS/scope/hash; **chưa ký thật**. Danh sách thẻ của chủ API key chưa chứng minh độc lập INN chủ key trùng chứng thư cấu hình. |
| License production, máy in thật | Guard và luồng được kiểm thử bằng fixture/hồi quy; **chưa nghiệm thu license hoặc in trên thiết bị thật** của seller. |

Giới hạn hiện hành phải giữ rõ: đọc thẻ Catalog được chọn có deadline **3 phút**, chia **25 GTIN/lượt**; chưa đo cohort 10.000 qua server thật. `/v4/product-list` chỉ hỗ trợ 1.000 dòng/trang và cửa sổ `offset+limit <= 10000`; vượt cửa sổ/thiếu trang là UNKNOWN và chặn xin mã, không tự chia ngày khi chưa có hợp đồng đủ để chứng minh không sót. Dictionary quốc gia không có total/phân trang được công bố, nên không thể khẳng định phát hiện mọi trường hợp server trả một danh sách bị cắt nhưng vẫn đúng schema.

API WB/Ozon không có CAS được xác minh cho luồng này. Đọc mới ở ranh giới gửi giảm rủi ro ghi đè; không tuyên bố khóa nguyên tử thay đổi từ Seller Portal. Quyền dùng GTIN nhà cung cấp hoặc thuộc tính đã khóa chỉ được xử lý khi có bằng chứng/quy trình chính thức; ứng dụng không tự cấp quyền hoặc tạo mã mới để né lỗi.

Để nghiệm thu phần bên ngoài còn lại cần phiên Windows có CryptoPro/chứng thư và quyền tài khoản đúng phạm vi thử được seller duyệt; máy in chỉ cần cho nghiệm thu in thực tế. Các bước đó không được thay bằng kết quả giả lập.

## 5. Cách thao tác và bản dựng

1. Chọn đúng shop, đồng bộ sản phẩm và Catalog. Xem riêng barcode sàn/GTIN đã xác minh cùng thời điểm đọc; lỗi kết nối giữ dấu “Chưa xác minh”.
2. Chọn bộ lọc/thẻ hoặc cả lô, bấm Đăng ký. Kiểm tra bảng lỗi/đề xuất có nguồn, bổ sung dữ liệu cần thiết rồi Áp dụng/xác nhận phạm vi. App xử lý tiếp trong lô đã duyệt; xem tiến độ/báo cáo và menu tạm dừng, tiếp tục hoặc hủy phần chưa gửi.
3. Ký thẻ đã duyệt mở bản đọc mới và hộp xem XML/hash/chủ thể ký. Chỉ xác nhận khi đúng lô; thay dữ liệu phải xem lại. Theo dõi đến trạng thái Catalog thực, không coi feed receipt là đã công bố.
4. Cập nhật GTIN WB/Ozon: xem lô/đề xuất, xác nhận; viên thuốc phân biệt đã thử gửi, chờ đọc lại, đã xác minh và lỗi. Mở báo cáo lọc toàn lô hoặc xuất CSV để xem nguyên nhân/hành động từng biến thể.

**Bản dựng kiểm tra Linux 1.5.7 đã tạo:**

- JAR: `target/VNcode-1.5.7.jar`, 6.206.985 byte, SHA-256 `baf6dd64212d1bebed7ff7baa2120ad427024694b1412501f5ebaf0e0fa4d241`.
- Gói JAR + 74 thư viện runtime + hướng dẫn: [VNcode-1.5.7-linux-verification.zip](../../target/verification/VNcode-1.5.7-linux-verification.zip), 84.408.579 byte. Mọi member ZIP đã kiểm CRC, thư viện đúng danh sách manifest có hash riêng trong [biên nhận build](2026-10-21-gtin-znack-build.json).
- Đã mở cửa sổ `VN code v1.5.7` trên Linux/Xvfb bằng thư mục dữ liệu mới riêng, 0 shop, SQLite integrity `ok`, không vi phạm foreign key hoặc log lỗi khởi động. [Ảnh cửa sổ](../../target/verification/startup-linux-1.5.7.png). Chỉ dừng tiến trình kiểm tra riêng sau khi ghi bằng chứng; không chạm dữ liệu seller.

Giải nén gói, giữ `lib/` cạnh JAR; dùng JDK25 trong phiên đồ họa Linux:

```sh
java --enable-native-access=ALL-UNNAMED -jar VNcode-1.5.7.jar
```

Đây là bản kiểm tra Linux. License và các kiểm tra quyền production vẫn bật; mở được cửa sổ không chứng minh license, ký thật hoặc ghi sàn thành công. Artifact này không thay bộ cài Windows để gửi seller. **Chưa tạo EXE, chưa phát hành hoặc đổi Latest.**
