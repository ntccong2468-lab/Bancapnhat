# Báo cáo kiểm thử nâng cấp Znack/GTIN/KIZ

Ngày 21.10.2026, Europe/Moscow. Repository `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`, nguồn 1.5.7. Các thay đổi được kiểm chứng bằng Java 25, JavaFX/Xvfb, Maven/JUnit, API giả lập và SQLite riêng.

## Kết quả cuối

| Hạng mục | Kết quả thực tế |
| --- | --- |
| Java, lấy kết quả cuối từng lớp | **393 lớp, 2.788 ca: 2.786 đạt, 0 thất bại, 0 lỗi, 2 chưa chạy vì chỉ dành cho Windows.** |
| Công cụ | `node --test tools/*.test.mjs`: **103/103 đạt**, không bỏ qua. |
| Build | Maven `verify` thành công, JAR 1.5.7 và 74 thư viện runtime. Không phải bộ cài Windows. |
| Khởi động | Đã nhìn thấy Dashboard, thanh điều hướng và các thẻ chức năng trên Linux/Xvfb bằng thư mục dữ liệu mới riêng biệt. Không chỉ dựa vào tiêu đề cửa sổ. |
| Database khởi động thử | `integrity_check=ok`, 0 vi phạm khóa ngoại, 0 cửa hàng, không có nhật ký lỗi khởi động. |
| Production | Không tạo thẻ, cấp GTIN, mua KIZ, ký pháp lý, lưu thông hoặc ghi marketplace production trong nhiệm vụ này. |

[Bằng chứng từng lớp, từng ca và từng lượt chạy](validation/2026-10-21-source-audit-tests.json). [Biên nhận build, SHA-256, thư viện, tệp thay đổi và kiểm tra khởi động](validation/2026-10-21-source-audit-build.json).

Phương pháp đếm: chạy P0 → P1 → P2, các lớp còn lại, rồi compile/kiểm thử lại những lớp chịu ảnh hưởng của sửa bổ sung. Mỗi lớp chỉ lấy XML cuối cùng. Các lượt chạy lại thay kết quả trước, không cộng trùng. Bộ hồi quy phần còn lại dùng compiled snapshot P2; toàn bộ lớp chịu ảnh hưởng bởi sửa Draft/CIS/xuất đồng thời sau đó đã chạy lại trên nguồn mới. Đã đối chiếu với danh sách lớp kiểm thử trong repository. Không lấy 2.641 ca của commit nền làm kết quả nguồn mới.

## Các lượt chạy, bao gồm lần thất bại

Các lệnh dùng `/workspace/toolchains/mvn-vncode`, `DISPLAY=:99`, `XDG_CACHE_HOME=/tmp/vncode-ui-cache`. Danh sách selector đầy đủ, log và checksum nằm trong JSON bằng chứng; bản log cục bộ lưu tại `target/source-audit/test-logs/`.

| Lượt | Kết quả | Xử lý |
| --- | --- | --- |
| P0 lần đầu | Compile main đạt; test compile lỗi, chưa chạy test. | Bổ sung `junit-jupiter-params` đúng phiên bản JUnit hiện có, chỉ scope test; sửa import `Order` bị nhập nhằng trong test mới. Không thêm thư viện production. |
| P0 chạy lại | 176 ca, 2 thất bại. | Một test migration kỳ vọng reset đơn đã gửi về QUEUED: sửa kiểm tra để bảo vệ cả hai receipt/order đã được nhận qua hai lần init. Một lỗi thật làm mất trạng thái EMITTED trong lý do chờ: giữ mã trạng thái hợp lệ đã biết, không đưa raw CIS vào thông báo. |
| P0 kiểm chứng phần sửa và CIS | 108 ca, tất cả đạt. | Kết quả này thay các lớp tương ứng của lượt trước; các lớp P0 khác đã đạt được giữ bằng chứng riêng. |
| P1 | 110 ca, tất cả đạt. | Ký giả lập/process Java, WB, in/khôi phục và inventory. |
| P2 | 109 ca, tất cả đạt. | Feed parser, JavaFX, Size RU, ba trạng thái và dịch lỗi. |
| Hồi quy phần còn lại | 2.307 ca: 2.305 đạt, 2 Windows-only chưa chạy. | Bao gồm hai lô 10.000 GTIN, FBS/FBO, sửa sản phẩm, giá/sao chép, GS1, database, cập nhật/khởi động và giao diện. Không có thất bại/lỗi. |
| Verify sau bổ sung Draft/CIS/xuất đồng thời | 715 ca, 0 thất bại, 1 lỗi fixture. | Test mới đếm lần tải Catalog chưa giả lập đầy đủ xác thực, thử GET nonce đến hostname True API mặc định và bị từ chối kết nối. Không gửi yêu cầu ghi hoặc dùng tài khoản seller thật. Sửa fixture xác thực; giữ kiểm tra 25 thẻ, một lần tải Catalog và không ghi dữ liệu. |
| Verify sau sửa fixture | 12 ca `RegistrationDynamicPreflightTest` đạt; build thành công. | Chỉ test-source thay đổi, không sửa guard production để làm test qua. |

Không vô hiệu hóa test, không bật skip cho lỗi, không gọi Windows-only là đã đạt trên Linux. Hai ca chưa chạy chính xác:

- `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows`: cần Windows/DPAPI.
- `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`: cần Windows/NTFS junction.

## Đối chiếu các nhóm A–F

| Nhóm | Hành vi đã kiểm thử cục bộ | Bằng chứng kiểm thử tiêu biểu |
| --- | --- | --- |
| A — GTIN | Đã có/chưa có, checksum, nhiều size, trùng/không đủ quota, mất phản hồi cấp mã không cấp lại; Draft giữ mã cũ. | `RegistrationExistingCardsTest`, `RegisteredGtinValidatorTest`, `ZnackCardRegistrationWorkflowTest`, `OzonCardRegistrationWorkflowTest`, `GtinBulkPipelineTest`. |
| B — Catalog | Thiếu thuộc tính, TN VED/giấy tờ không khớp, nhiều danh mục, từ chối/chờ duyệt, mất phản hồi feed, mẫu quốc gia và Draft sau review. | `RegistrationDynamicPreflightTest`, `ZnackReviewedDraftSubmissionTest`, `ZnackCatalogSubmissionReadbackTest`, `ZnackNationalCatalogServiceTest`, `OzonAutomaticDraftSchemaTest`, `ExistingDraftReviewTest`. |
| C — CryptoPro | Chứng thư/định dạng lỗi, timeout, hủy, giữ nguyên nguyên nhân, process con/pipe, stdout lớn, phản hồi ký chưa rõ không ký lại. | `CryptoProCommandRunnerTest`, `CryptoProSignatureTest`, `WindowsXmlSignatureTest`, `ZnackSigningSessionTest`, các test signing/registration. Windows provider dùng mock, không chứng thư thật. |
| D — WB/Ozon | Đúng biến thể, bảo toàn barcode/size khác, thành công một phần, 403/429, quyền ghi, dữ liệu thay đổi khi chờ, readback và checkpoint. | `WbGtinAdapterTest`, `OzonGtinAdapterTest`, `WbRegistrationSnapshotTest`, `WbContentApiRateLimiterTest`, `GtinBatchTransportTest`, `GtinSyncCoordinatorTest`, `GtinBulkPipelineTest`. |
| E — KIZ | Đặt mua/mất phản hồi/không mua lại, proof trả phí đúng biến thể, render/publish lỗi, khôi phục nguyên mã, chống xuất đồng thời, exact CIS và phản hồi code 0. | `ZnackOrderReconciliationSafetyTest`, `FboVerifiedGtinRepositoryTest`, `KizPurchaseMappingProofTest`, `PrintPublishRecoveryTest`, `ZnackCisReadbackTest`, `ZnackIntroductionIdentityTest`, `KizWarehouseTest`. |
| F — Database | Database cũ, GTIN 13/14 số tương đương, giữ order/request/error/time/KIZ, init lặp, transaction rollback, restart và release đúng reservation chưa tiêu thụ. | `ZnackPurchaseMigrationSafetyTest`, `DatabaseMigrationCompatibilityTest`, `PrintPublishRecoveryTest`, các test scope/repository/queue. |

`PrintPublishRecoveryTest` có 9 ca, gồm lỗi xuất PDF sau tiêu thụ, xuất hai PDF chỉ thành công một phần, khôi phục đúng mã/thông tin cũ, chặn job/shop/raw-code giả, hai lượt xuất đồng thời và đúng cặp phục hồi khi app khởi động. Kiểm thử in lại dùng trigger SQLite chặn thêm/cập nhật inventory và mua mới; vẫn khôi phục được nhãn từ lịch sử. Bản in khôi phục không tự gửi lại metadata đơn lên sàn.

Draft được kiểm thử với cả thẻ nguồn chưa có hàng native và thẻ đã liên kết: review đầy đủ bắt buộc, hủy không gửi; cùng GTIN/good_id, không allocation/sign/marketplace grant; metadata cục bộ không lọt vào HTTP; hash/owner/scope/TN VED/status thay đổi bị chặn; READY tiếp tục payload đóng băng, mất phản hồi không POST lại. Lookup Draft hàng loạt tải/index Catalog một lần. Chỉ Draft được xác minh mới bỏ kiểm tra quota cấp mới; thẻ cần cấp mã mới vẫn giữ gate quota.

Hai lô 10.000 GTIN dùng planner, adapter, SQLite và HTTP giả lập. Kết quả không chứng minh tốc độ hay quota production. FBS latency/performance cũng là số đo mô phỏng Linux, không phải máy Windows của seller.

## Build và kiểm tra mở app

Lệnh build cuối:

```bash
DISPLAY=:99 XDG_CACHE_HOME=/tmp/vncode-ui-cache \
  /workspace/toolchains/mvn-vncode -o \
  -Dtest=RegistrationDynamicPreflightTest verify
```

JAR: `target/VNcode-1.5.7.jar`; bản sao phân biệt với nền tại `target/source-audit/VNcode-1.5.7-source-audit.jar`. SHA-256:

```text
e43d364d76a885dd78a25db24e116aba6dd98acb05381e7f1b5b2e2025d9e063
```

Đã kiểm CRC JAR và sự tồn tại của cả 74 thư viện trong manifest. Ảnh kiểm tra cửa sổ chính: `target/source-audit/startup-linux-source-audit-1.5.7.png`. Ảnh đầu tiên chụp ngay khi tiêu đề đổi vẫn còn màn chờ, nên không dùng nó làm bằng chứng mở app; lượt xác nhận sau đã đợi render và xem ảnh thật. Chỉ dừng tiến trình kiểm tra riêng sau khi thu bằng chứng, không tác động dữ liệu/cửa sổ seller.

## Giới hạn và bước tiếp theo

- **ĐÃ SỬA / ĐÃ KIỂM THỬ CỤC BỘ:** các thay đổi P0/P1/P2 được liệt kê trong kiểm toán và changelog, theo bằng chứng trên.
- **CẦN KIỂM THỬ WINDOWS:** bộ cài/nâng cấp, DPAPI/NTFS, CryptoPro/CAdESCOM/chứng thư và máy in. ProcessHandle dọn tiến trình đã quan sát; chưa bảo đảm bắt được tiến trình con tách trước khi được quan sát như Windows Job Object.
- **CẦN API THẬT VÀ SELLER DUYỆT:** nghiệm thu tạo/cấp GTIN, kiểm duyệt/ký/công bố, mua/lưu thông KIZ và ghi/đọc lại marketplace trong môi trường được phép. Nhiệm vụ này không cho phép các thao tác đó, nên không dùng mock thay bằng xác nhận thành công production.
- **CHƯA XÁC MINH:** schema True API chính thức hiện hành cho `errorCode: 0`; mẫu tương thích lấy từ nguồn tham khảo đã ghim, và mọi kiểm tra CIS/GTIN/chủ thể/trạng thái vẫn giữ nguyên. Quota khi hai token cùng một tài khoản seller, khả năng mở rộng Catalog ngoài cửa sổ 10.000/deadline hiện có và tốc độ production vẫn cần dữ liệu đúng hợp đồng.
- Hiện không cần seller cung cấp thêm thông tin để bàn giao mã cục bộ. Nghiệm thu bước ghi/ký thật cần môi trường Windows, chứng thư/quyền API đúng tài khoản và phê duyệt cụ thể cho dữ liệu kiểm chứng; không gửi khóa/PIN vào chat.
- Nhánh dành cho review. Giữ lệnh chưa tạo bộ cài, không push/merge, không phát hành hay thay Latest.
