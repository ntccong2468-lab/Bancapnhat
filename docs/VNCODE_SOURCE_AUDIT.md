# Kiểm toán mã nguồn VN Code

Ngày: 21.10.2026 (Europe/Moscow). VN Code tại `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`, phiên bản nguồn1.5.7. Java25/JavaFX/Maven/SQLite, schema6. Không đổi license, không ghi dữ liệu production, không merge/phát hành.

Kiểm toán ban đầu đã hoàn thành trước khi sửa nguồn. Phân loại dưới đây dựa trên tìm kiếm toàn repository và chuỗi gọi, không coi tên tệp thiếu là thiếu chức năng. Bằng chứng kiểm thử cũ không được coi là nghiệm thu mã mới.

## Trạng thái nguồn sau triển khai

Các đoạn khảo sát chi tiết phía dưới ghi nhận **base `b467225` trước khi sửa**. Mọi số dòng trong những đoạn này trỏ về base đó; các câu “chưa sửa/chưa kiểm thử” là trạng thái của pha khảo sát, không phải kết luận về nguồn sau triển khai. Kết quả cuối: 393 lớp/2.788 ca, 2.786 đạt, 0 thất bại/lỗi, 2 ca chỉ dành Windows chưa chạy trên Linux; Node 103/103, build JAR và kiểm tra mở cửa sổ chính đạt. Bảng sau ghi các thay đổi hiện có trên nhánh nâng cấp. Các lớp kiểm thử là bằng chứng hồi quy đã thêm/điều chỉnh; kết quả chạy, lượt thất bại và build được chốt riêng trong [báo cáo kiểm thử](VNCODE_ZNACK_TEST_REPORT.md), không suy ra từ việc tệp test tồn tại.

Đường dẫn Java dưới đây tính từ `src/main/java/com/vncode/app/`; tên test nằm trong package tương ứng ở `src/test/java/com/vncode/app/`.

| ID thống nhất | Thay đổi đã triển khai | Tệp và kiểm thử liên quan | Trạng thái bàn giao |
| --- | --- | --- | --- |
| KIZ-01 | Giữ receipt/order/mã và trạng thái đã gửi hoặc chưa rõ khi chuẩn hóa GTIN/migration; chỉ xếp hàng lại ý định chứng minh chưa gửi. | `integration/znack/ZnackSchemaSupport.java`, `ZnackPurchaseCoordinator.java`; `ZnackPurchaseMigrationSafetyTest`, `ZnackGtinWorkflowTest`. | Đã sửa nguồn; xem kết quả hồi quy trong báo cáo kiểm thử. |
| KIZ-02 | Kiểm tra cấu trúc danh sách đối soát SUZ và lỗi nghiệp vụ; phản hồi thiếu/hỏng không còn thành xác nhận không có đơn. Thông báo `SUZ_ORDER_RECONCILIATION_INVALID` giữ mã và nguyên nhân kỹ thuật. | `integration/znack/ZnackKizOrderService.java`, `ZnackPurchaseCoordinator.java`, `shared/FriendlyErrorService.java` và bốn bundle; `ZnackOrderReconciliationSafetyTest`, `FriendlyErrorCodeTest`. | Đã sửa nguồn; không xác nhận mua production. |
| KIZ-03 | Bằng chứng cho phép mua KIZ kiểm tra owner/source hiện tại, danh tính/trạng thái Catalog, tuổi snapshot và article/màu/hệ size; không dùng checksum hoặc barcode đơn lẻ làm quyền mua. | `features/fbo/FboVerifiedGtinRepository.java`, `features/kiz/KizPurchaseMappingProof.java`; `FboVerifiedGtinRepositoryTest`, `KizPurchaseMappingProofTest`, các fixture FBO/WB/Ozon liên quan. | Đã sửa nguồn; giữ đường nhập KIZ của seller. |
| KIZ-04 | Caller FBO truyền UUID chuẩn riêng cho từng ý định mua; không nới validator request key. | `features/fbo/FboPurchaseService.java`; `FboPurchaseServiceTest`, `FboKizPrintPlannerTest`. | Đã sửa nguồn. |
| SIG-01 | Deadline bao phủ vòng đời subprocess và thu kết quả; cleanup khi timeout/hủy, giữ interrupt và giới hạn đầu ra. | `integration/znack/signature/CryptoProCommandRunner.java`; `CryptoProCommandRunnerTest`, `CryptoProSignatureTest`. | Đã sửa nguồn; process tree/CryptoPro thật trên Windows chưa nghiệm thu. |
| SIG-02 | Probe CAdESCOM giữ nguyên mã TIMEOUT/CANCELLED thay vì đổi thành thiếu component; giữ phân loại component thực sự thiếu. | `integration/znack/signature/WindowsCadesSignatureProvider.java`; `CryptoProSignatureTest.windowsCadesAvailabilityPreservesCancellationAndDoesNotTryAnotherHost`, `windowsCadesAvailabilityPreservesTimeoutAndDoesNotTryAnotherHost`, `windowsCadesAvailabilityStillReportsAnActuallyMissingComComponent`. | Đã sửa nguồn. |
| WB-01 | Tách hạn mức error/list đúng hợp đồng, không gây cooldown chéo với đọc/update/FBS. | `integration/wb/WbContentApiRateLimiter.java`; `WbContentApiRateLimiterTest`. | Đã sửa nguồn. |
| WB-02 | Chặn trang WB có số dòng khác `cursor.total` trước khi lưu hoặc báo nguồn hoàn chỉnh; giữ cache/checkpoint cũ khi đọc lỗi. | `integration/wb/WbProductSyncService.java`; `WbRegistrationSnapshotTest`. | Đã sửa nguồn. |
| PRINT-01 | Lưu snapshot đúng nhãn/KIZ trong lịch sử in cùng transaction consume, giữ khả năng tiếp tục xuất khi publish file thất bại. | `features/print/OrderExportWorkflow.java`, `ZnackKizExportService.java`, `features/print/history/PrintHistoryRepository.java`, `PrintHistoryService.java`, `PrintHistoryJobSummary.java`, `ui/history/PrintHistoryController.java`, `integration/znack/ZnackGtinInventoryService.java`; `PrintPublishRecoveryTest`, `ZnackGtinWorkflowTest`. | Đã kiểm thử cục bộ, gồm 9 ca khôi phục/xuất đồng thời và đúng cặp phục hồi khi khởi động; chưa nghiệm thu máy in/sàn thật. |
| CIS-01 | Đối soát từng CIS/GTIN duy nhất, không xác nhận lưu thông bằng số lượng node trạng thái. | `integration/znack/ZnackCisReadback.java`, `ZnackIntroductionService.java`, `ZnackIntroductionReadinessService.java`; `ZnackCisReadbackTest`, `ZnackIntroductionIdentityTest`, `ZnackModuleTest`. | Đã sửa nguồn; không gửi tài liệu lưu thông thật. |
| CIS-02 | Tương thích phản hồi CIS có `errorCode` bằng số 0 hoặc chuỗi "0": giá trị này không tự coi là lỗi; dữ liệu vẫn phải đạt kiểm tra CIS/GTIN/chủ thể/trạng thái. Giá trị hỏng, khác 0 hoặc có thông điệp lỗi vẫn chặn. | `integration/znack/ZnackErrorMessages.java`, `ZnackCisReadback.java`, `features/kiz/KizWarehouseService.java`; `KizWarehouseTest`, `ZnackCisReadbackTest`, `ZnackIntroductionIdentityTest`. | Đã sửa nguồn; bằng chứng tương thích từ tham chiếu thứ cấp, chưa xác minh lại schema TrueAPI chính thức cho `errorCode`. |
| CAT-01 (HS-01) | Đọc lỗi feed `result.result` theo hợp đồng; liên kết lỗi đúng entry/GTIN nếu có bằng chứng, nếu không ghi rõ lỗi thuộc gói đăng ký. | `integration/znack/registration/ZnackNationalCatalogService.java`, `ui/znackregistration/RegistrationDiagnostics.java`; `ZnackNationalCatalogServiceTest`. | Đã sửa nguồn; không coi receipt/HTTP200 là công bố. |
| UI-01 | Hiện Size gốc và Size RU riêng; thiếu RU giữ chưa biết. Tách đăng ký, kiểm duyệt Catalog và đồng bộ sàn, kèm trạng thái gốc/thời điểm đọc; giữ chưa xác minh khi lần đọc mới lỗi. Dịch header bảng xác nhận ký bằng bốn bundle hiện có. | `ui/znackregistration/RegistrationTableText.java`, `ZnackCardRegistrationController.java`, `OzonRegistrationPane.java`, `RegistrationSigningReviewDialog.java`, FXML tương ứng; `RegistrationTableTextTest`, `ZnackCardRegistrationControllerTest`, `OzonRegistrationPaneTest`, `RegistrationSigningReviewDialogTest`. | Đã sửa nguồn; renderer dùng snapshot/index đã tải, không thêm DB/API theo từng cell. |
| CAT-02 (HS-02) | Mở kiểm tra đầy đủ cho đúng Draft hiện hữu, kể cả native PROCESSING chưa có feed. Sau khi seller duyệt, dùng luồng feed cập nhật hiện có với cùng `good_id`/GTIN và `moderation=1`; không cấp lại GTIN, tự ký hoặc tự ghi sàn. Đối soát thụ động giữ chỉ đọc; lần gửi chưa rõ không phát lại. | `features/sync/RegistrationCardIndex.java`, `integration/znack/registration/ZnackCardRegistrationModels.java`, hai workflow/batch WB/Ozon và `ZnackNationalCatalogService.java`; UI `ZnackRegistrationQueryRepository.java`, `RegistrationBatchPreflight.java`, hai controller. Test `ExistingDraftReviewTest`, `ZnackRegistrationQueryRepositoryTest`, hai FX controller test và `OzonCardRegistrationWorkflowTest`. | Đã kiểm thử cục bộ trên WB/Ozon, gồm HTTP giả lập, restart/timeout và hủy review; chưa gửi production. |

Các giới hạn giữ nguyên: chưa nghiệm thu API ghi production, cấp GTIN/mua KIZ thật, Windows CryptoPro/USB/PIN, máy in thật hoặc hiệu năng 10.000 GTIN trên shop thật. Không đổi license, không xóa database và không phát hành bản cập nhật trong nhiệm vụ kiểm toán này.

## Phát hiện tại base trước khi sửa

| ID | Mức | Bằng chứng hiện tại | Sửa dự kiến | Kiểm chứng cần có |
|---|---|---|---|---|
| KIZ-01 | P0 | ZnackSchemaSupport228–234,446–455: reset các giao dịch đã gửi về QUEUED khi chuẩn hóa/trùng GTIN; chuỗi worker có thể gọi buy lại | Giữ receipt và trạng thái đã gửi/chưa rõ; chỉ xếp lại giao dịch chứng minh chưa gửi | Database cũ, GTIN tương đương, init2lần; không POST mua lại |
| KIZ-02 | P0 | ZnackKizOrderService84–114: orderInfos thiếu/sai kiểu hoặc dòng sai có thể thành NOT_FOUND | Phản hồi không đầy đủ là UNKNOWN; chỉ vắng mặt sau danh sách hợp lệ | {},null,sai schema/lỗi nghiệp vụ/dòng thiếu; pending không mở mua lại |
| KIZ-03 | P0 | FboVerifiedGtinRepository/KizPurchaseMappingProof: proof mua thiếu ID/trạng thái gốc/source/thời điểm/facts biến thể | Tái sử dụng proof/định danh hiện có, không dùng checksum làm quyền mua | GTIN khác size/màu/chủ thể, cache cũ, proof thiếu hoặc lỗi; không mua |
| KIZ-04 | P1 | FboPurchaseService23 tạo khóa fbo:<uuid>:<gtin>, coordinator chỉ nhận UUID chuẩn | UUID chuẩn cho từng intent, giữ scope/dedup hiện có | Hàng đợi thật SQLite, nhiềuGTIN, không gọi production |
| SIG-01 | P1 | CryptoProCommandRunner33–46: hủy không dừng process; join đầu ra không có deadline | Deadline cả vòng đời và dọn process/future khi hủy/timeout | Process Java thử, kế thừa pipe, hủy, timeout, giữ interrupt; không cần token |
| SIG-02 | P1 | WindowsCadesSignatureProvider305–325: probe đổi TIMEOUT/CANCELLED thành CADESCOM_MISSING | Giữ nguyên mã và cause, không thử thêm host sau hủy/timeout | Runner giả lập cho timeout/hủy/component thiếu |
| WB-01 | P1 | WbContentApiRateLimiter18–20: error/list dùng100/phút thay vì10/phút | Bucket độc lập đúng endpoint, giữ FBS/reads/update độc lập | Clock giả lập, Retry-After, burst/6s, cách ly quota |
| WB-02 | P1 | WbProductSyncService chấp nhận20cards/cursor.total80 rồi báo hoàn tất | Kiểm tra total đúng số dòng trước lưu/hoàn tất | Giữ cache tốt, trang20/20 hợp lệ,20/80 không READY, phân trang/429 |
| PRINT-01 | P1 | ZnackKizExportService/OrderExportWorkflow consume trước publish; lỗi rename mất PDF và thiếu snapshot KIZ để khôi phục | Mở rộng checkpoint lịch sử in đang có, lưu đúng nhãn nguyên tử với consume | Lỗi render/publish, publish một phần, restart/reprint giữ nguyên KIZ và không mua lại |
| CIS-01 | P1 | ZnackIntroductionService xác nhận bằng số lượng trạng thái, không danh tính CIS; Readiness cùng lỗ hổng | Đối chiếu đủ đúng CIS/GTIN, không trùng/ngoại lai | Đảo thứ tự hợp lệ; CIS trùng/khác/thiếu/error/GTIN sai không INTRODUCED |
| CAT-01 | P2 | Feed parser thiếu result.result được5.68 mô tả ở verbose=false; VN dùng true | Bổ sung chẩn đoán dạng hợp đồng cũ, không thay lifecycle/cấp mã | Rejected nhiều dòng, lỗi thân thiện, che bí mật; không coi đây là nguyên nhân mọi thẻ lỗi |
| UI-01 | P2 | Bảng dùng size nhà sản xuất và một cột trạng thái; nhãn ký còn tiếng Anh | Hiện Size RU và ba trạng thái từ dữ liệu sẵn có, dịch nhãn; giữ layout/style | JavaFX, lọc/chọn tất cả, unknown/rawstate/time, không hiển thị thành công giả |

## Kiến trúc, giao diện và kiểm thử hiện có

### Chi tiết khảo sát kiến trúc, UI và QA tại base `b467225`

**Phạm vi:** chỉ đọc VNCode và viết tài liệu tạm; không đổi source/test, không Maven, không API production, không build/publish. Đã đọc MASTER mới `/workspace/attachments/0a881cdc-6ae3-4f9a-b4b4-c7a682871b92/Văn bản đã dán.txt`.

**Repository:** `/workspace/Bancapnhat`; nhánh `upgrade/znack-source-audit-20261021`; base `b4672258090b` (nguồn 1.5.7). Working tree ban đầu chỉ `tools/__pycache__/` chưa theo dõi. Không thấy AGENTS.md trong workspace. Các citation sau là file:dòng trên base này, không phải tên tệp giả định từ repository tham khảo.

## 1. Kiến trúc và dữ liệu đã xác nhận

- Java 25 / JavaFX 25.0.2 / Maven, JUnit 5.12.1 + MockWebServer; `pom.xml:9`, `:14`, `:125`, `:149`, `:161`, `:209`. Không phải Electron/.NET/web SPA. Gson/OkHttp/SQLite JDBC, PDF/QR có dependency hiện hữu; không cần đổi stack.
- SQLite **schema version 6** (`config/Database.java:26`), init toàn bộ WB/Znack/Ozon/GTIN/sản phẩm/GS1/đồng bộ trong cùng DB (`:215–235`), additive `ensureColumnExists` (`:248`). Không dựng DB Catalog/queue song song.
- Dữ liệu nâng cấp có khóa thư mục, snapshot và phục hồi trước khi migrate: `shared/LocalDataMigrationGate.java:36`, `:58–60`, `:92–104`; giao dịch ghi ngắn xếp thứ tự, không giữ HTTP/ký/in trong transaction: `config/SqliteWriteTransaction.java:12–35`.
- Durable jobs/mapping đã có: `features/gtinsync/GtinSyncSchema.java:30–62` (`dedup_key UNIQUE`, native product+variant, preview/status/remote task/checkpoint/events); `features/sync/SyncSchema.java:24–45` (run/item/event, UNIQUE(run,product,variant)); batch WB/Ozon bổ sung retry count/next_retry_at, không thiếu hẳn hàng đợi.
- Tác vụ FX chạy worker/Task; lane Catalog có deadline 3 phút, queue hữu hạn và cancellation (`shared/AppTaskExecutor.java:22–32`); nhánh FBS riêng trong `features/sync/SyncRuntime.java:48–62`. Không suy ra app không có background queue chỉ vì class session guard ngắn.
- Hệ tích hợp thật: `integration/znack/ZnackApiClient.java:145–220` là National Catalog/TrueAPI; `:222–228` là SUZ, OMS/signature/header riêng. GS1 RUS đã có `integration/gs1/NationalCatalogGs1Client.java:11`, `features/gs1/Gs1AutomaticSyncService.java:19`, cùng account/prefix/mail/reminder/repository. KIZ có `ZnackPurchaseCoordinator`, `ZnackKizOrderService`, `ZnackKizCodeService`, `ZnackIntroductionService`, warehouse/KN3/print; không phải chỉ có đăng ký thẻ.

## 2. Toàn bộ 13 tên class trong MASTER đều tồn tại

Đã tìm toàn bộ `src/main/java`, không có tên nào trong danh sách này cần gán một “class thay thế” do bị thiếu. Trạng thái ĐÃ CÓ không có nghĩa đã nghiệm thu production.

| Class yêu cầu | Phân loại | Citation | Vai trò / cách tái sử dụng |
| --- | --- | --- | --- |
| `ZnackCardRegistrationWorkflow` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/registration/ZnackCardRegistrationWorkflow.java:37` | Luồng WB: claim → kiểm tra hiện hành → tra thẻ → cấp/giữ GTIN → gửi feed → đối soát → ký/công bố; không thay bằng workflow mới. |
| `ZnackNationalCatalogService` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/registration/ZnackNationalCatalogService.java:33` | Hợp đồng schema/thuộc tính/quốc gia/giấy tờ, cấp mã, feed, đọc thẻ, XML và gửi ký. |
| `ZnackWbAttributeMapper` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/registration/ZnackWbAttributeMapper.java:18` | Ánh xạ thuộc tính thực WB sang schema Catalog, không phải service pháp lý độc lập. |
| `ZnackCardRegistrationRepository` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/registration/ZnackCardRegistrationRepository.java:24` | Native checkpoint WB, scope, payload, feed/good_id, lịch sử và trạng thái theo size. |
| `ZnackCardRegistrationSchema` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/registration/ZnackCardRegistrationSchema.java:7` | Bảng native đăng ký, scope, signature, replacement, history và batch; additive init. |
| `ZnackCardRegistrationController` | ĐÃ CÓ | `src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java:60` | UI WB, host riêng Ozon, chọn toàn bộ bộ lọc, preflight, lô, ký/GTIN và báo cáo. |
| `ZnackApiClient` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/ZnackApiClient.java:13` | HTTP Catalog/TrueAPI/SUZ; route và xác thực khác nhau; mutation quan trọng không replay. |
| `ZnackGtinAutoSync` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/ZnackGtinAutoSync.java:11` | Chỉ guard tự đồng bộ một lần/shop/session, KHÔNG phải durable GTIN writer; durable orchestration nằm ở features/sync và GtinSyncCoordinator. |
| `ZnackKizOrderService` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/ZnackKizOrderService.java:17` | SUZ buy/status/reconcile thấp tầng; durable purchase nằm ở ZnackPurchaseCoordinator + ZnackRepository/pipeline. |
| `WindowsCadesSignatureProvider` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/signature/WindowsCadesSignatureProvider.java:18` | Fallback Windows CAdESCOM/XML đã có, package-private; CryptoProSignatureProvider lựa chọn fallback. |
| `CryptoProSignatureProvider` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/znack/signature/CryptoProSignatureProvider.java:20` | Bộ ký hiện có cryptcp/csptest/CAdES fallback, kiểm tra CMS, không xuất private key. |
| `WbApiClient` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/wb/WbApiClient.java:23` | Client WB hiện hữu cho đọc thẻ/đơn/API; ghi GTIN Content nằm ở WbGtinAdapter, không thêm client song song. |
| `WbProductSyncService` | ĐÃ CÓ | `src/main/java/com/vncode/app/integration/wb/WbProductSyncService.java:16` | Đọc phân trang thẻ/size/ảnh và cập nhật cache sản phẩm WB; không phải endpoint ghi GTIN. |

Các module còn lại cần ghi trong inventory, không tạo bản trùng: `features/sync/{RegistrationSources,ZnackCatalogSyncService,ZnackAutoSyncService,ZnackGtinBackgroundService}`; `features/gtinsync/{GtinPreviewService,GtinSyncCoordinator,GtinSyncRepository,RegisteredGtinProof}`; `features/tnved`; `integration/wb/{WbGtinAdapter,WbProductSnapshotReader}`; `integration/ozon/{OzonGtinAdapter,OzonProductSnapshotReader}`; `integration/znack/registration/OzonCardRegistrationWorkflow`; `ui/znackregistration` (19 class). Số lượng tham khảo trên base: integration/znack 96 class, gtinsync 23, features/gs1 23 + integration/gs1 4, WB 57, Ozon 65. Không dùng số lượng này để chứng minh độ hoàn thiện.

### Yandex Market: khoảng trống về phạm vi, không đồng nhất với Yandex Translate

Tìm toàn bộ Java/resources cho `Yandex|yandex|яндекс` chỉ thấy dịch tin tức: `integration/translation/YandexNewsTranslator.java:11–16`, endpoint `translate.api.cloud.yandex.net`; `ui/news/NewsPane.java:74`, `ui/workspace/HomeController.java:1793–1797`. `integration/marketplace/Marketplace.java:6–8` chỉ có WILDBERRIES/OZON; factories/RegistrationSources chỉ có hai sàn (`features/sync/RegistrationSources.java:100–116`). **Không có bằng chứng app đã tích hợp Yandex Market seller API.** Đây là chênh lệch với câu giới thiệu MASTER, không phải bằng chứng nên xây sàn thứ ba trong nâng cấp Znack/WB này. Nếu cần làm thật, phải có phạm vi/API/credential/quy trình riêng; không dùng API dịch như Market API.

## 3. UI: đã có hành động nào, không cần thêm nút trùng

| Yêu cầu IX | Hiện có / citation | Kết luận |
| --- | --- | --- |
| Đồng bộ sản phẩm | WB nút Đồng bộ Znack gọi `sources.refresh` (`ui/znackregistration/ZnackCardRegistrationController.java:364–378`); service đọc Marketplace rồi Catalog (`features/sync/RegistrationSources.java:67–91`, `:100–116`). Product tab còn có ProductRefreshService. | ĐÃ CÓ; nhãn gộp nguồn, không thiếu đồng bộ chỉ vì không có nút tên riêng. |
| Kiểm tra GTIN | Preflight/dynamic schema/RegistrationExistingCards + GtinReview/Preview trước đăng ký/ghi; xem lỗi từng dòng/báo cáo. | ĐÃ CÓ dưới luồng kiểm tra, chưa có nút kiểm tra GTIN độc lập ở header; không tạo engine kiểm tra thứ hai. |
| Chọn tất cả/lô | FXML `znack-card-registration-view.fxml:58–60`; controller `:270–303` dùng allSelectable ngoài FX rows; bộ lọc lỗi mở repair report. | ĐÃ CÓ, có test 1.003 dòng/chọn cả filter. |
| Đăng ký hàng loạt | controller `:381+`, durable batch WB/Ozon; filter khác vẫn xem được sau dispatch. | ĐÃ CÓ, không cần workflow mới. |
| Kiểm tra trạng thái | Nút đồng bộ, polling, row context read status (`controller:1119–1123`), report/tooltip có raw state + read time. | ĐÃ CÓ; không coi reload cache là server đã xác nhận. |
| Ký/công bố | FXML `:16`, controller fresh review `:327–333`, shared XML review dialog. | ĐÃ CÓ, mock tested; CryptoPro thật chưa nghiệm thu. |
| Đồng bộ GTIN WB | FXML `:17`, controller `:358–361`, GtinBackgroundStatus + adapter/bulk transport/readback. | ĐÃ CÓ, hợp đồng ADD-only và duyệt scope đã có; không hạ batch về 50 theo ví dụ khi official max3.000. |
| Lỗi chi tiết | Status click, row tooltip/context (`controller:1060–1063`, `:1116–1123`), report link FXML `:63`; report search/filter/export. | ĐÃ CÓ. |
| Tiếp tục gián đoạn | Row `.canResume()` action (`controller:1107–1111`); pause/resume/cancel menu `:170–171`, durable native checkpoints và batch. | ĐÃ CÓ; action menu không phải chức năng thiếu. |

Bảng WB đã có ảnh (giữ tỉ lệ `controller:1015–1018`), tên/article/màu, barcode riêng và GTIN đã xác minh (`:1048–1056`), TN VED dưới tên (`:1190–1193`), status+action. Ozon có tương ứng tại `OzonRegistrationPane.java:168–179`. Không có cột giả lập production; GTIN/proof rỗng được phân biệt bằng read state. Báo cáo hiện có raw Catalog/state/size/identity/read time/next action (`RegistrationSyncReportDialog.java:43–47`, `RegistrationReportData.java:15–36`).

## 4. Phát hiện cụ thể cần xử lý, không phóng đại thành thiếu toàn bộ luồng

Tất cả issue dưới đây thuộc VNCode `/workspace/Bancapnhat`, base `b4672258090b`; chưa sửa trong lượt chỉ đọc này.

### P1 — Hủy process CryptoPro chưa dừng subprocess

- File/class/function/dòng: `integration/znack/signature/CryptoProCommandRunner.java:31–46`, `run`.
- Hiện tại: `process` local trong try; timeout gọi `destroyForcibly` ở37 nhưng InterruptedException ở44 chỉ phục hồi interrupt rồi ném CANCELLED. Process/futures đọc stdout/stderr có thể tiếp tục sau khi worker bị hủy.
- Mong muốn: seller hủy/timeout phải dừng subprocess và cleanup đọc stream/tệp; vẫn giữ kết quả gửi remote chưa rõ để đối soát, không đổi CMS hoặc license.
- Bằng chứng: đọc mã trực tiếp; `CryptoProCommandRunnerTest` hiện chỉ kiểm tra tìm executable (line12/19/31), `CryptoProSignatureTest:175` kiểm tra mapping timeout/mock output; grep toàn bộ signature tests không có test interrupt process đang chờ. Cancel hộp review (`RegistrationSigningReviewDialogTest:28`) chỉ chứng minh chưa ký, không chứng minh kill process.
- Sửa tối thiểu: quản lý Process bên ngoài try/finally, destroy trên interruption/timeout và dọn bounded stream reader; test subprocess giả lập, không USB token.
- Rủi ro: Windows CAdES PowerShell/csptest/cryptcp dùng runner chung; cần test đủ fallback và cancellation, không thay bộ ký.

### P2 — Bảng Znack chưa hiển thị rõ Size RU đã có

- File/function/dòng: WB `ZnackCardRegistrationController.java:1053`, `configureTable`: dùng `Sku.size`; model có riêng `wbSize` (`ZnackCardRegistrationModels.java:31–38`). Ozon `OzonRegistrationPane.java:176` dùng `ProductRow::size`, dù model có russianSize.
- Hiện tại/mong muốn: đang thấy size nhà sản xuất (ví dụ L) ở cột Size; MASTER yêu cầu Size RU (ví dụ52). Backend đã dùng đúng RU khi đối chiếu, không phải bằng chứng mapping GTIN hiện sai.
- Sửa tối thiểu: hiển thị RU riêng có nhãn rõ, giữ size gốc khi cần; thiếu RU hiển thị thiếu, không quy đổi L→52. Giữ layout/style/cột barcode/GTIN và selection hiện có.
- Bằng chứng/test cần: UI fixture khác nhau L/RU52 cho cả WB/Ozon, thiếu RU và update sau reload; existing adapter test `WbGtinAdapterTest:53` bảo vệ RU backend nhưng UI chưa assert cột RU.
- Rủi ro: chiều rộng bảng/layout/4locale; không đổi dữ liệu persisted để sửa riêng hiển thị.

### P2 — Một status tổng hợp thay vì ba trạng thái độc lập trên bảng

- File/function/dòng: FXML `znack-card-registration-view.fxml:50–52`; controller `:1060`, `registrationStatus:1207–1210`; Ozon `OzonRegistrationPane.java:178`.
- Hiện tại: một status tổng hợp; thẻ published chưa có GTIN trên sàn được đổi nhãn thành chờ cập nhật. Registration/moderation/WB-sync không hiện đồng thời như bảng MASTER yêu cầu. Backend/report đã tách và có raw Catalog/time (`RegistrationDiagnostics.observation:26–36`), nên không thêm server state giả.
- Mong muốn/sửa tối thiểu: biểu diễn đồng thời ba nhóm bằng renderer/cột hiện có hoặc column group, dùng actual observation + local state + readback job; UNKNOWN giữ UNKNOWN, không đồng nhất feed với moderation.
- Test cần: published + sync pending; local feed accepted chưa đọc được card; moderation rejected; hạ tầng unknown còn last-good snapshot; không báo cả ba thành công từ một flag.
- Rủi ro: layout và seller hiểu nhầm raw status; reuse report/state helpers tránh thêm enum server trùng.

### P2 — Header tiếng Anh hardcode trong màn hình mới

- File/function/dòng: `ui/znackregistration/RegistrationSigningReviewDialog.java:23` (`Product ID`, `Variant ID / offer_id`, `Revision`, `ID Catalog`); Ozon `OzonRegistrationPane.java:175` (`Product ID / offer_id`).
- Hiện tại/mong muốn: đổi sang nhãn tiếng Việt từ I18n khi locale VI, giữ token kỹ thuật như GTIN/SKU/SHA-256. Không dịch hay sửa raw XML/remote status gốc.
- Sửa tối thiểu/test: thêm keys vào bundle hiện có; test header VI/không mất exact XML/hash/signer và các locale đang có. Không ép đổi ngôn ngữ đã lưu của seller: hiện AppLanguage mặc định RU (`shared/AppLanguage.java:31–40`), đó là hành vi cũ riêng, không nên đổi toàn app trong patch header.
- Rủi ro: test review dialog đang assert data/confirmation, không assert header; thêm test đúng nhãn thay vì đổi API payload.

## 5. QA baseline hiện hữu và nhóm A–F của MASTER

Nguồn bằng chứng là `docs/validation/2026-10-21-gtin-znack-G-tests.json`: **383 lớp / 2.641 ca, 2.639 đạt, 0 thất bại/lỗi, 2 Windows-only bỏ qua**; Node103 đạt. Đây là tổng kết cuối theo test case từ F+G+runs ảnh hưởng, không phải một lượt clean verify tất cả đều đạt ngay. Không chạy lại trong audit mới chỉ để lập inventory; thay đổi mới sau đây phải có targeted regression và kết quả riêng.

| Nhóm MASTER | Các chứng cứ đã có (test file:dòng, hành vi) | Còn cần phân biệt/bổ sung |
| --- | --- | --- |
| A — GTIN | `src/test/java/com/vncode/app/features/gtinsync/RegisteredGtinValidatorTest.java:7` lengths/checksum/zero đầu; `src/test/java/com/vncode/app/integration/znack/registration/RegistrationExistingCardsTest.java:49,59,65,113` existing/duplicate/ambiguous/RU; `src/test/java/com/vncode/app/integration/znack/registration/ZnackCardRegistrationWorkflowTest.java:212` lost allocation cannot blind retry; `src/test/java/com/vncode/app/features/gtinsync/GtinPreviewServiceTest.java:50,55` stale/existing equivalentcode. | Quyền/quota/unknown cần giữ block, không cấp lần2; test mock không chứng minh quyền GS1 production. |
| B — Catalog | `src/test/java/com/vncode/app/integration/znack/registration/RegistrationDynamicPreflightTest.java:35,79,98` missing/ambiguous/conditional; `src/test/java/com/vncode/app/integration/znack/registration/ZnackNationalCatalogServiceTest.java:62,105,109` remote state+failure/foreignGTIN; `src/test/java/com/vncode/app/integration/znack/registration/ZnackRegistrationBatchHttpIntegrationTest.java:111,132` lost feed ID/accepted without actual card; `src/test/java/com/vncode/app/integration/znack/registration/ZnackCurrentSchemaSubmissionTest.java:25,44` guard before marker. | Official range10000/deadline3min/25GTIN và INN-owner proof còn giới hạn, không bịa API fallback. |
| C — CryptoPro | `src/test/java/com/vncode/app/integration/znack/signature/CryptoProSignatureTest.java:25,149,175` certificate parsing/no cert/invalidoutput+timeout; `src/test/java/com/vncode/app/integration/znack/registration/ZnackRegistrationScopeTest.java:44` expired/wrongowner; `src/test/java/com/vncode/app/integration/znack/signature/CryptoProSignedCertificateReaderTest.java:22,31,37,43` actual signer/CMS; `src/test/java/com/vncode/app/integration/znack/registration/ZnackCardBatchSigningTest.java:87,103,172` unknown-after-send no repost; `src/test/java/com/vncode/app/ui/znackregistration/RegistrationSigningReviewDialogTest.java:28` cancel before signing. | Thiếu test interrupt/kill subprocess nêu P1; USB/PIN/CAdES thực phải Windows được phép, không đồng nhất với test CMS giả. |
| D — WB | `src/test/java/com/vncode/app/integration/wb/WbGtinAdapterTest.java:35,53,79,104,145` exactvariant/RU/preserve/fullRMW/changedfacts; `src/test/java/com/vncode/app/features/sync/GtinBulkPipelineTest.java:51,85` sibling sizes,429 vs503; `src/test/java/com/vncode/app/integration/listing/ListingBeforeSendGuardTest.java:30,73` credentialchanged/explicit400 redaction; coordinator tests unknown outcome and dispatch recovery. | Giữ official max3000/10MB, không mặc định50. Cần test403/no permission trên đường sửa mới nếu có; không kết luận thiếu transport chỉ vì không có class tên ListingHttpClientTest. |
| E — KIZ | `src/test/java/com/vncode/app/integration/znack/ZnackModuleTest.java:613,691,729,748,777,811` signedorder/reconciliation/download remainder/recovery/idempotency; `src/test/java/com/vncode/app/integration/znack/ZnackKizPurchaseReadbackTest.java:43,56,69,82,95` đúng GTIN buffer/partialunknown; `src/test/java/com/vncode/app/features/print/KizPrintHistoryRecoveryTest.java:40,48` giữ mã đã in/ownedcheckpoint. | Cần liên kết case print failure→không mua lại khi sửa purchase/print; không đọc lại KIZ thật hoặc mua thật để audit. |
| F — DB | `src/test/java/com/vncode/app/config/DatabaseMigrationCompatibilityTest.java:40,50,55,60` future schema failclosed/legacydata preserved; `src/test/java/com/vncode/app/shared/LocalDataSnapshotServiceTest.java:29,95,183,280,330` integrity/restore/crashboundaries; `src/test/java/com/vncode/app/features/gtinsync/GtinSyncRepositoryTest.java:31,65,107,129` migrate/dedup/crossjob/checkpoint; batch WB/Ozon in workflow/UItests cover reopen/control. | Không cần hệ job thứhai; nếu thêm cột phải idempotent+legacy/reopen tests và bảo toàn GTIN/KIZ. |

Hai skip thật: `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`, `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows`. Không coi chúng là đạt trên Linux. JAR+74 runtime libs Linux của base1.5.7 có biên nhận `docs/validation/2026-10-21-gtin-znack-build.json` và startup profile rỗng riêng; đó không phải EXE hoặc nghiệm thu seller production.

## 6. Điều chưa xác minh và phạm vi kiểm tra tiếp

- Chưa có nghiệm thu mới cho viết Catalog/WB/Ozon production, mua KIZ, ký CryptoPro thật, quyền license production, Windows updater/native/DPAPI/junction, hoặc máy in thật. Không dùng bảng mock pass để khẳng định đã đăng ký thẻ thật.
- Bằng chứng nguồn mở và giấy phép nằm trong [báo cáo tham khảo](VNCODE_REFERENCE_EVIDENCE.md); phần này mô tả VNCode tại base, không coi test-wcode là production và không sao chép mã chưa rõ license.
- Ưu tiên sửa P1 process cancellation và P2 UI nhỏ sau khi hợp nhất bằng chứng/thiết kế; không mở rộng Yandex Market, không đổi license hoặc kiến trúc. Test mới chỉ thêm cho hành vi thay đổi; không chạy lại 2.641 ca chỉ vì đọc inventory. Sau khi sửa, chạy nhóm chịu ảnh hưởng và quyết định full regression một lượt theo thay đổi thực.

## Bằng chứng KIZ và luồng in

### Chi tiết khảo sát KIZ tại base `b467225`

- Repository: `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b467225`.
- Phạm vi: mua KIZ, checkpoint/khôi phục, mã nhận được, mapping phục vụ mua, lưu thông, lỗi xuất nhãn. Đã tìm toàn bộ `src/main/java` và `src/test/java` trước khi kết luận.
- Trạng thái: **CHỈ ĐỌC MÃ; CHƯA SỬA; CHƯA CHẠY MAVEN/KIỂM THỬ**. Không dùng API hoặc dữ liệu seller thật.

## Những cơ chế đã có, phải giữ

| Nhóm | Bằng chứng hiện có | Kết luận |
|---|---|---|
| Ý định mua bền vững | `ZnackPurchaseCoordinator.enqueue:140–164`; `ZnackRepository.enqueuePipeline:570–590`; unique `(shop_id,request_key)` trong `ZnackSchemaSupport:245–268` | ĐÃ CÓ. Ghi pipeline trước POST; phát lại cùng UUID đối chiếu GTIN/số lượng. Không cần hệ hàng đợi mới. |
| Phản hồi mua bị mất | `ZnackKizOrderService.buy:29–57`; `ZnackPurchaseCoordinator.createOrder/reconcileOrder:524–577` | ĐÃ CÓ đối chiếu theo GTIN/số lượng/thời gian, không gửi lại chính pipeline đang mơ hồ. Vẫn có lỗ hổng đọc danh sách và migration bên dưới. |
| Tải mã một phần/khối cũ | `ZnackKizCodeService.download:15–36,recoverIssuedBlocks:43–60`; `ZnackPurchaseCoordinator.downloadCodes/recoverClosedOrder:592–650` | ĐÃ CÓ. Chỉ xin phần thiếu, lưu trước bước tiếp, phục hồi các block đã cấp, không tạo order mới chỉ vì buffer đóng. |
| Mã và trạng thái kho | `ZnackRepository.insertCodes:546–562`; `ZnackGtinInventoryService`; `ZnackGtinWorkflowTest.inventoryReservationConsumptionAndReleaseNeverDuplicate, staleReservationCannotConsumeCodeAfterItWasReleasedAndReservedAgain, reservationCannotBeConsumedThroughAnotherShop` | ĐÃ CÓ transaction/reservation token/phạm vi shop và loại trừ mã chưa lưu thông. Không thay đổi cơ chế nhập KIZ do seller đã yêu cầu. |
| Giấy tờ theo GTIN | `ZnackIntroductionService:28–47` gọi registry đúng `product.gtin()` và bỏ mặc định shop; `ZnackModuleTest.inactiveGtinDocumentsBlockSubmissionClearStaleSnapshotAndIgnoreLegacyDefault` | ĐÃ CÓ truy vấn giấy tờ đúng GTIN, chặn danh sách không hiệu lực/rỗng, giữ snapshot khi đọc tạm lỗi. Chưa đủ chứng cứ khẳng định kiểm tra độc lập toàn bộ thời hạn/chủ thể/phạm vi vì `GoodsDocument` chỉ giữ type/number/date. |
| Nối lại, không mua lại khi ký/lưu thông lỗi | `ZnackPurchaseCoordinator.resume/resumeAsync:270–300,retryIntroduction:216–235,submitIntroduction:702–748`; `ZnackRepository.pipelineSettings` | ĐÃ CÓ lưu mã đã mua và kết nối gốc, tách chờ giấy tờ/chữ ký. Mất phản hồi tài liệu chặn gửi lại khi chưa biết document ID. |
| PDF sinh thất bại | `ZnackKizExportService:40–55`; `ZnackKizExportServiceTest.generationFailureReleasesReservationAndLeavesNoPublishedFile` | ĐÃ CÓ hoàn trả reservation khi lỗi xảy ra trước consume; còn thiếu khôi phục khi xuất bản file lỗi sau consume. |

## Phát hiện cần sửa

### KIZ-01 — P0: migration biến order đã gửi thành một yêu cầu mua mới

- Tệp/class: `src/main/java/com/vncode/app/integration/znack/ZnackSchemaSupport.java`, `createTables:224–240`, `normalizeGtins:440–455`.
- Hiện tại: khi có nhiều pipeline active cho cùng shop/GTIN (kể cả hai cách viết GTIN ngắn/đủ 14 số), tất cả trừ dòng nhỏ nhất bị chuyển `QUEUED`, xóa lỗi. Các trạng thái bị chuyển bao gồm `CREATING_ORDER`, `RECONCILING_ORDER`, `POLLING_ORDER`, `DOWNLOADING_CODES` — đều có thể đã tạo order/mua thật.
- Đường gây mua lại: `ZnackRepository.activateNextQueuedPipeline:650–655` đổi thành `VALIDATING`; `ZnackPurchaseCoordinator.advance:419–422` gọi `createOrder`, hàm `524–530` gọi `orders.buy` không dùng order cũ.
- Bằng chứng kiểm thử hiện có: `DatabaseMigrationCompatibilityTest.mergesEquivalentShortAndPaddedGtinsWithoutLosingReferences:349–386` thậm chí đang kỳ vọng một `POLLING_ORDER` thành `QUEUED`; fixture không gắn remote order nên chưa bảo vệ trường hợp đã trả tiền.
- Mong muốn: không bao giờ đưa dấu vết đã gửi/mơ hồ về trạng thái có quyền mua. Giữ order ID/request key/codes và tiếp tục đọc đúng order hoặc dừng đối chiếu an toàn.
- Cách sửa tối thiểu đề nghị: chỉ chuyển về hàng chờ nếu có chứng cứ chưa gửi. Những dòng có receipt/order/đang mơ hồ phải giữ checkpoint riêng, không bị unique mutation index ép mua lại; mở rộng chính cơ chế trạng thái/index hiện hữu sau khi thống nhất thiết kế.
- Regression cần thêm: database legacy hai GTIN tương đương, hai remote order khác nhau, một có mã tải dở; khởi tạo hai lần và resume phải giữ IDs/số mã, tổng POST mua mới bằng 0.
- Rủi ro: migration/index và FIFO hiện có; không sửa bằng xóa dữ liệu hoặc đánh dấu completed giả.

### KIZ-02 — P0: danh sách order sai cấu trúc bị coi là đã xác nhận không mua

- Tệp: `ZnackKizOrderService.java`, `reconcile:84–86`; `ZnackPurchaseCoordinator.java`, `reconcileOrder:566–571`.
- Hiện tại: JSON `{}` hoặc `orderInfos` sai kiểu bị chuyển thành mảng rỗng rồi `NOT_FOUND`. Sau 5 phút coordinator ghi `FAILED/REMOTE_ORDER_NOT_FOUND`, giải phóng pipeline kế tiếp mua mới. Không có chứng cứ danh sách đọc được đầy đủ.
- Mong muốn: lỗi cấu trúc/không biết phạm vi kết quả là lỗi đọc, giữ `RECONCILING_ORDER` và receipt; không xác nhận không có order.
- Cách sửa tối thiểu: kiểm tra bắt buộc envelope/mảng/hàng nhận dạng, chặn dữ liệu sai; chỉ `NOT_FOUND` sau phản hồi đọc hợp lệ theo hợp đồng. Đối chiếu tài liệu chính thức về completeness/cửa sổ trước khi coi không tìm thấy là chắc chắn chưa mua.
- Regression cần thêm: POST đã nhận/mất phản hồi; đẩy clock order qua 5 phút; `/order/list` trả `{}`, mảng sai kiểu, lỗi nghiệp vụ trong HTTP200; giữ pipeline mơ hồ, không kích hoạt POST mua sau.
- Test hiện có `ZnackGtinWorkflowTest.confirmedMissingRemoteOrderReleasesNextQueuedPurchase:557–605` mock trực tiếp `NOT_FOUND`, chưa kiểm tra parser lỗi. `ZnackModuleTest.ambiguousOrderReconciliationUsesRecoveryListAndExactPurchaseFingerprint` có trường hợp hợp lệ.
- Rủi ro: giữ yêu cầu lâu hơn nhưng tránh phát sinh phí không xác định.

### KIZ-04 — P1: mua KIZ FBO luôn bị lỗi khóa yêu cầu

- Tệp: `features/fbo/FboPurchaseService.java`, `enqueue:25–29`.
- Hiện tại: truyền `fbo:<UUID>:<GTIN>` cho `ZnackPurchaseCoordinator.enqueue`. `requireRequestKey:197–201` chỉ chấp nhận UUID chuẩn, nên lỗi `Purchase request key is invalid.` trước khi tạo pipeline/POST.
- Mong muốn: UUID chuẩn khác nhau cho từng yêu cầu trả tiền, giữ lại trong pipeline để replay.
- Cách sửa tối thiểu: sinh UUID chuẩn cho mỗi dòng GTIN, giữ tiền tố/ngữ cảnh ở dữ liệu báo cáo nếu thực sự cần. Không nới bộ kiểm tra UUID hoặc đổi license.
- Kiểm thử cần thêm: gọi qua `FboPurchaseService` với preflight đã duyệt có thiếu kho, kiểm tra key đi qua coordinator validator, chặn GTIN/số lượng thay đổi trước lần ghi đầu; cả WB/Ozon dùng cùng service.
- Đã tìm toàn repo: các đường FBS `SupplyDetailController`, `OzonDashboardController`, `KizStockReplenisher` đã dùng UUID chuẩn. Chưa tìm test trực tiếp `FboPurchaseService`.
- Rủi ro thấp, riêng caller FBO; không đổi SUZ request body.

### KIZ-03 — P0: barcode cũ/sai biến thể có thể cấp quyền mua KIZ mới

- Tệp: `features/kiz/KizPurchaseMappingProof.java:11–21`, `features/fbo/FboVerifiedGtinRepository.java:33–56`.
- Hiện tại: bằng chứng mua chỉ join barcode của biến thể với `gtin_sync_catalog` có owner/published/tradeUnit. Không kiểm tra actual Catalog ID/raw status, source cấu hình hiện tại, tuổi snapshot hoặc article/màu/size. `KizPurchaseMappingProof:16` còn dựng reference với toàn bộ facts này rỗng.
- Mong muốn: tái sử dụng chứng cứ exact Catalog/marketplace hiện có và phạm vi seller, không coi barcode đúng checksum là thẻ đúng biến thể. Nếu chỉ có barcode hoặc dữ liệu cũ, yêu cầu đồng bộ/xác nhận thay vì mua.
- Cách sửa đề nghị: đối chiếu `ListingIdentity.verifiedGtinEvidence`/`RegisteredGtinProof` hiện hữu với facts thực của sản phẩm, không dựng một module mapping mới. Giữ việc cùng physical variant được dùng chung GTIN khi chứng cứ duy nhất, và ngoại lệ Ozon SKU hiện hữu có chứng cứ.
- Test cần thêm: barcode thuộc thẻ Catalog khác article/màu/size, raw published thiếu, source khác, quá 24h; không tạo pipeline. Dữ liệu đúng đủ thì vẫn mua được.
- Rủi ro: đường FBO và checkbox mua FBS dùng chung proof; giữ lựa chọn KIZ do seller nhập riêng biệt.

### PRINT-01 — P1: xuất file thất bại sau trừ kho làm mất khả năng in lại đúng mã

- Tệp: `features/print/ZnackKizExportService.java:47–55`; `features/print/OrderExportWorkflow.java:112–137`.
- Hiện tại: consume stock trước `AtomicFilePublisher.publish`; khi file đích bị khóa/đổi thành thư mục/rename lỗi, mã vẫn CONSUMED, staging bị xóa. Export KIZ riêng không có history. WB chỉ ghi failed job với số lượng, không snapshot exact KIZ/order (`PrintHistoryService.recordFailedJob:82–95`), history thành công chỉ ghi sau cả hai file đã xuất.
- Phân biệt: mã thô vẫn còn trong database — không mất bản ghi KIZ; nhưng không còn có thể tiếp tục xuất đúng bộ nhãn qua luồng bình thường. Lỗi renderer trước consume đã hoàn trả đúng và không được làm hỏng.
- Mong muốn: giữ exact snapshot mã/layout/đơn đã chọn và khả năng xuất lại, không mua thêm hoặc tự hoàn kho khi có thể file đã được in.
- Cách sửa đề nghị: dùng checkpoint print history có sẵn (đang có `recordPreparedFboJob`, `pdf_ready` và recovery), mở rộng đúng đường thiếu; ghi snapshot trước consume, giữ trạng thái có thể phục hồi khi publish lỗi. Không đổi thành publish→release mù vì có thể tái dùng mã đã lộ/in.
- Test cần thêm: exporter tạo file staged hợp lệ rồi file đích bị khóa/không publish được; nguyên mã được tìm lại trong history, không POST mua, re-export dùng chính bộ mã, không cấp lại cho đơn khác. Mất app tại consume→publish phải phục hồi tương tự.
- Rủi ro: FBS đang hoạt động, cần regression nguyên export+attach pipeline; hạn chế thay đổi, tái sử dụng history.

### CIS-01 — P1: xác nhận lưu thông dùng số lượng trạng thái thay cho exact mã

- Tệp: `ZnackIntroductionService.java:90–99,136–137`; `ZnackIntroductionReadinessService.java:121–180`.
- Hiện tại: `confirm.allIntroduced` chỉ đếm node `status=INTRODUCED` (>= số mã yêu cầu). Không đối chiếu CIS/GTIN/duplicate/error từng mã. Một response có N mã khác hoặc lặp cùng một CIS có thể làm toàn order `IN_CIRCULATION`. Readiness có kiểm tra GTIN nếu trường đó có mặt, nhưng vẫn chỉ đếm rows, không exact CIS, GTIN trống cũng không chặn.
- Mong muốn: từng CIS đã tải phải được máy chủ xác nhận rõ ràng đúng mã, duy nhất, không lỗi; unknown/thiếu/mã khác giữ trạng thái chờ. Không suy `IN_CIRCULATION` từ tổng row.
- Test hiện có `ZnackModuleTest.introductionConfirmationDoesNotDoubleCountParentAndChildStatuses:983–1007` chỉ bảo vệ parent/child trùng node; fixture thành công nhiều nơi chỉ có status (không CIS), cần sửa fixture theo actual documented response chứ nới proof.
- Cách sửa đề nghị: tái sử dụng normalization CIS hiện hữu, map theo mã; xác minh đủ tập yêu cầu và state từng mã; tách raw document accepted với actual code circulation. Không POST lưu thông lại khi unknown.
- Regression cần: duplicate CIS, foreign CIS cùng GTIN, foreign GTIN, thiếu row/row lỗi, thứ tự đảo, response hợp lệ đủ mã; chỉ trường hợp cuối được markCodes toàn lô.
- Rủi ro: fixture cũ và nhiều dạng payload; cần chứng cứ hợp đồng API hiện hành, không đoán tên field mới.

## Tham chiếu mã mở (không chạy/sao chép)

- `akdengi/sklad-cz`, commit `c6a72c642ade6066dcc9b42dc480a1f59ecb9760`, bản đọc `/workspace/references/vncode-source-audit/sklad-cz`.
- `LICENSE:1–21`: MIT, có yêu cầu giữ notice nếu sao chép. Đợt kiểm toán này không sao chép mã nguồn và không chạy script của repository.
- `app/cz_api.py:get_cis_info:324–338`: giới hạn 1000 mã/request và timeout30s; `check_cz_status:375–390` batch900. VN đã có batch<=1000 trong `ZnackIntroductionReadinessService` và `ZnackIntroductionService`; **không thiếu**.
- `app/cz_api.py:create_introduction_document:890–918` dùng từng unit/SKU cho TN VED/giấy tờ. VN đã có truy vấn registry đúng GTIN, chặt hơn fallback manual/reference; không thay thế bằng việc sao chép.
- Reference `_build_certificate_data:1069–1084` có tự lấy ngày hiện tại nếu thiếu date, và production TN VED fallback `0000000000` tại906. **KHÔNG ÁP DỤNG** vì trái yêu cầu không suy đoán thuộc tính pháp lý.
- Repository tham khảo không chứng minh quy tắc remote/SUZ production; mọi sửa parser/states phải dựa hợp đồng chính thức hoặc fixture response đã xác minh.

## Giới hạn kết luận

Các test nêu trên là vị trí chứng cứ hiện có đọc từ source, chưa chạy trong lượt kiểm toán này. Không tuyên bố mua/tải/lưu thông production thành công. Không đọc token/khóa/PIN và không gửi yêu cầu mạng thật. Các sửa P0 migration/lưu thông phải dùng đúng trạng thái/index và hợp đồng phản hồi hiện có. Phần khảo sát KIZ này không đề nghị thay license hoặc mở rộng UI; UI-01 được ghi riêng trong bảng triển khai đầu tài liệu.

## Bằng chứng hàng đợi và migration

### Chi tiết khảo sát National_cat_CHZ và đối chiếu tại base `b467225`

Thời điểm nghiên cứu: 21/10/2026. Phạm vi hiện tại chỉ đọc mã và tái hiện SQL trên SQLite trong bộ nhớ; chưa sửa mã VN Code, chưa chạy Maven/JUnit, không gọi API seller và không chạy mã Python từ kho tham khảo.

## 1. Nguồn và giới hạn sử dụng

- VN Code: `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`.
- Kho tham khảo: https://github.com/Chenuss/National_cat_CHZ ; bản đọc cục bộ `/workspace/references/vncode-source-audit/National_cat_CHZ`.
- Commit tham khảo: `1c237dd2e25517016b2105990fba0dd8c2dcf93e`, ngày 28/05/2026; checkout detached, không sửa kho này.
- Giấy phép: [readme.md:146–148](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/readme.md#L146-L148) ghi “MIT”. Toàn bộ danh sách tệp tracked không có LICENSE/COPYING/NOTICE hoặc toàn văn giấy phép. Trạng thái quyền sao chép: **CHƯA XÁC MINH**; chỉ nghiên cứu hành vi, không sao chép mã. Các sửa VN Code phải triển khai độc lập.
- Kho còn chứa `.env`, `catalog.db`, bytecode `__pycache__`; đã thấy tên tệp qua Git nhưng không đọc `.env`/database và không thực thi bytecode/script. Không sử dụng dữ liệu hay thông tin đăng nhập trong kho.

## 2. Tệp thực tế và bằng chứng

Ba tên trong yêu cầu đều tồn tại; không giả định từ tài liệu:

| Nội dung | Bằng chứng tại commit đã ghim | Nhận xét kỹ thuật |
|---|---|---|
| Client cũ / luồng CLI | [catalog_client.py:52–120](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L52-L120); [readme.md:25–43](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/readme.md#L25-L43) | CLI hướng dẫn chạy client cũ. Chỉ có một GET danh sách, không vòng offset và không truyền timeout ở dòng84. Không thể dùng làm bằng chứng đã đồng bộ hết cửa hàng. |
| Client mới | [nk_client.py:130–161](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L130-L161) | Có lớp NKCatalogClient mới; không thấy CLI cũ hoặc loader nhập lớp này trong toàn bộ tệp Python tracked. Không đồng nhất tính năng của lớp mới với luồng chạy cũ. |
| GTIN / thẻ đủ trạng thái | [nk_client.py:575–617](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L575-L617) | GET feed-product cho good_ids/gtins, tối đa25, nối `;`; chủ động tắt ETag. Đây là đọc thẻ, không cấp GTIN, không gửi kiểm duyệt hay ký. |
| Danh sách và phân trang | [nk_client.py:653–689](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L653-L689) | Wrapper nhận limit/offset, clamp limit1000; ghi chú giới hạn10.000/khung ngày. Không có thuật toán quét hết/dò ngày an toàn được tích hợp. Không suy ra tự động chia khoảng ngày là đầy đủ ở biên thời gian. |
| Mô hình thuộc tính | [nk_client.py:758–788](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L758-L788) | GET attributes theo cat_id hoặc TN VED; không ETag. |
| Lưu thuộc tính | [load_attributes.py:153–228](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L153-L228); [251–268](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L251-L268) | Cập nhật cờ thẻ, DELETE rồi INSERT lại thuộc tính/định danh, commit cuối batch. Loader không dùng client mới. Lược đồ SQL có UNIQUE(good_id,attr_id): [db_schema.sql:171–200](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/db_schema.sql#L171-L200). Không lấy mẫu này để thay hệ thống multivalue hiện có của VN Code. |
| Lưu trạng thái / GTIN | [catalog_client.py:141–163](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L141-L163); [200–230](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L200-L230); [models.py:62–111](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/models.py#L62-L111) | good_id làm khóa, GTIN lưu chuỗi; UPSERT. Model mới thêm ETag. Có model SyncState nhưng chưa thấy luồng CLI lưu/khôi phục nó: [models.py:507–524](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/models.py#L507-L524). |
| ETag | [nk_client.py:148–149](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L148-L149); [386–389](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L386-L389); [431–461](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L431-L461); [842–851](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L842-L851) | Gửi If-None-Match, phân biệt304, có wrapper etagslist. Nhưng danh sách ETAG_SUPPORTED_METHODS chỉ được khai báo, không được request() kiểm tra. get_countries dòng756 vẫn bật ETag cho endpoint ngoài danh sách: không sao chép nguyên chính sách này. |
| HTTP429 | [nk_client.py:546–562](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L546-L562) | Đọc Retry-After dạng giây, fallback300s, ném lỗi cho caller; không đọc HTTP-date. Loader cũ đệ quy vô hạn khi429: [load_attributes.py:70–82](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L70-L82). |
| Retry / timeout | [nk_client.py:398–497](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L398-L497) | Timeout thật được truyền ở412; retry kết nối/timeout/5xx với backoff cap60s. Hàm request generic không phân biệt mutation. Không đem retry này vào cấp GTIN/mua KIZ/ký. Chỉ gán session.timeout tại257 không thay thế timeout mỗi request. |
| Migration tham khảo | [migrate_db.py:29–56](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/migrate_db.py#L29-L56); [122–145](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/migrate_db.py#L122-L145) | ALTER thêm cột, tạo bảng, commit/rollback. Không có bằng chứng backup/khóa app/khôi phục job như VN Code. Không thay migration VN Code bằng script này. |

Không có bộ test pytest/unittest/tệp test tracked trong kho tại commit trên. `audit_report.md` chứa cả nhận xét lỗi cũ và mã đề xuất, thậm chí mô tả etagslist khác code hiện tại; chỉ được dùng như ghi chú, không coi là hành vi đã triển khai.

## 3. Đối chiếu với VN Code hiện tại

Các dòng VN Code dưới đây thuộc commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`.

| Thành phần VN Code | Trạng thái | Bằng chứng / kết luận |
|---|---|---|
| NationalCatalogGtinSource | ĐÃ CÓ | `integration/znack/registration/NationalCatalogGtinSource.java:24–49,109–178`: danh sách1000/trang với cửa sổ10.000 khi dùng Catalog key; batch25; kiểm tra total/dupe/truncated; checkpoint khi429/timeout; không biến lỗi thành kết quả không tìm thấy. Source registration cần total đầy đủ, không dùng cursor cũ làm bằng chứng vắng mặt. |
| CatalogScanCheckpoint | ĐÃ CÓ | `.../CatalogScanCheckpoint.java:25–37`: khóa(shop_id,source), lưu tiến độ/quan sát, TTL2h; loader kiểm tra tương thích pageSize ở source110–112. Giữ cache đã biết cho đến quét thành công. |
| Thẻ/thuộc tính/ID | ĐÃ CÓ | `.../CatalogProductDataParser.java:21–70`: raw good_id/status, giá trị list, thuộc tính và giấy tờ. `.../ZnackNationalCatalogService.java:160–213`: đọc schema bắt buộc/đầy đủ và dependencies; không cần database thứ hai. |
| GTIN tồn tại / matching biến thể | ĐÃ CÓ | `.../RegistrationExistingCards.java:19–95`: một snapshot đầy đủ trong mỗi thao tác, exact barcode/article/color/typed size, đọc lại thẻ thật; lỗi/scope không rõ không cấp mới. Test `RegistrationExistingCardsTest`, `NationalCatalogListWindowTest` hiện diện; chưa chạy lại trong đợt nghiên cứu này. |
| Chống cấp GTIN lặp | ĐÃ CÓ | `.../ZnackCardRegistrationWorkflow.java:573–615`: tra tồn tại, checkpoint ALLOCATING_GTIN, lưu GTIN + READY_TO_SUBMIT trước POST. `.../ZnackApiClient.java:160–164` chặn retry endpoint GET có tác dụng cấp mã. `.../ZnackCardRegistrationRepository.java:436–449,472–505` giữ danh tính/không ghi GTIN khác vào thẻ hiện có. |
| Khôi phục đăng ký | ĐÃ CÓ | `.../ZnackRegistrationBatchStore.java:18–57,100–126`: hàng đợi bền vững, retry_at/count và điều khiển giữa các thẻ; chỉ CHECKING chưa allocation được khôi phục sang preflight. `ZnackCardRegistrationWorkflowTest:212–220,269+` có kiểm thử mất phản hồi cấp mã và dùng lại GTIN/feed. |
| HTTP retry mutation | ĐÃ CÓ | `ZnackApiClient.java:97–109,160–164,232–238,289–320`: client không replay mutation, GET cấp mã/nhận block mã dùng đường không lặp; retry429 chỉ yêu cầu idempotent. VN Code an toàn hơn generic retry của kho tham khảo ở điểm này. |
| Khóa request mua KIZ | ĐÃ CÓ | `ZnackSchemaSupport.java:245–267` unique(shop_id,request_key); `ZnackPurchaseCoordinator.java:113–125` replay theo khóa; `ZnackGtinWorkflowTest:459–495` có ca request lặp. Chưa xác nhận toàn bộ luồng đúng vì hai lỗi P0 bên dưới. |
| App lock, snapshot, migration | ĐÃ CÓ | `shared/LocalDataMigrationGate.java:49–59,75–100`: lấy quyền sở hữu, recover, chặn schema mới hơn, tạo/reuse snapshot trước init. Tuy nhiên migration purchase có reset nguy hiểm bên dưới. |
| HTTP ETag Catalog | CẦN BỔ SUNG nếu có lợi hiệu năng / P2 | Chưa có cơ chế HTTP conditional `If-None-Match`/`etagslist`; body `_etag` của country dictionary đã được lưu trong bằng chứng dictionary và không phải HTTP ETag. Không phải lỗi ghi sai; không áp dụng cho feed-product/product-list. `docs/validation/2026-10-21-catalog-country-contract.md:84–92` đã phân biệt body _etag với HTTP ETag và ghi country dictionary no-store; không thêm conditional cache cho country dựa trên body _etag. |
| Hơn10.000 thẻ/cửa sổ và timeout | CHƯA XÁC MINH cho mở rộng | Hiện trả CATALOG_LIST_RANGE_UNSUPPORTED và không xác nhận thiếu thẻ khi vượt phạm vi. Deadline quét là3phút; selected đọc không giữ resume để tránh âm tính cũ. Không tự chuyển sang thuật toán cắt ngày của kho tham khảo khi chưa có bảo đảm biên ngày/order/cursor từ đặc tả. |

“ĐÃ CÓ” ở bảng trên là đã xác nhận bằng mã và tệp test, không phải tuyên bố test vừa chạy hoặc API production đã thành công.

## 4. Lỗi P0 cần sửa trước: migration có thể mua KIZ lại

- Repository: VN Code; file `src/main/java/com/vncode/app/integration/znack/ZnackSchemaSupport.java`.
- Hàm: `createTables` dòng227–240 và `normalizeGtins` dòng440–455.
- Hiện tại: mọi pipeline active thứ hai cùng GTIN hoặc cùng GTIN sau chuẩn hóa bị đưa về `QUEUED`, xóa error; áp dụng cả `CREATING_ORDER`, `RECONCILING_ORDER`, `POLLING_ORDER`, `DOWNLOADING_CODES`. `order_id` vẫn giữ nguyên.
- Đường hậu quả: `ZnackPurchaseCoordinator.java:413–435` cho QUEUED kích hoạt lại VALIDATING; `createOrder:524–530` gọi `orders.buy` không xét order_id cũ. Với dữ liệu cũ/collision13–14 số, một đơn đã mua hoặc chưa rõ kết quả có thể bị mua lần nữa.
- Bằng chứng tái hiện: [JSON tái hiện migration tại base](validation/2026-10-21-source-audit-migration-risk.json). Chạy đúng hai câu UPDATE lấy từ source trên SQLite in-memory độc lập, không chạy Java/kho tham khảo. Cả ca duplicate cùng GTIN và ca13/14 chữ số đều biến row `RECONCILING_ORDER`, order_id102 thành QUEUED và xóa uncertainty. Đây là bằng chứng SQL; chưa chạy hồi quy Java.
- Mong muốn: chỉ tái xếp hàng công việc chứng minh chưa gửi; giữ mọi remote order ID, receipt, mã đã tải và trạng thái uncertain/sent. Migration collision không được tự tạo lại mua hàng.
- Phương án sửa hẹp: phân loại durable evidence trước chuẩn hóa, giữ/reconcile hoặc giữ chặn những hàng đã gửi/không rõ; điều chỉnh index khi cần theo hướng không cho tạo mới trong khi còn giao dịch chặn. Không reset trạng thái đã gửi chỉ để tạo unique index. Giữ mọi dữ liệu gốc và migration idempotent.
- Kiểm thử cần thêm: duplicate legacy có hai order_id thật; canonical collision13/14; CREATING_ORDER không có phản hồi; POLLING/DOWNLOADING có mã đã lưu; init hai lần; tiếp tục worker không thêm POST mua.
- Rủi ro: queue FIFO mua KIZ, khôi phục DB cũ và normalization; không mở rộng sửa FBS/in nhãn/license.
- Phân loại: **CẦN SỬA — P0 — ĐÃ TÁI HIỆN SQL / CHƯA KIỂM THỬ JAVA**.

## 5. Lỗi P0 cần sửa trước: đối soát SUZ coi UNKNOWN là không có đơn

- Repository: VN Code; file `src/main/java/com/vncode/app/integration/znack/ZnackKizOrderService.java`.
- Hàm: `reconcile`, dòng82–116; API wrapper `ZnackApiClient.orderList:227–229` chỉ ép JsonObject.
- Hiện tại: thiếu orderInfos, null hoặc sai kiểu trở thành mảng rỗng tại85–86. Các row/createdTimestamp/buffer không đọc được bị skip. Mảng matches rỗng trả NOT_FOUND tại114.
- Đường hậu quả: `ZnackPurchaseCoordinator.reconcileOrder:566–572` sau5phút đổi đơn đang uncertain thành FAILED và giải phóng hàng chờ. Một HTTP200 `{}`, JSON báo lỗi nghiệp vụ hay danh sách hỏng có thể vì thế cho mua tiếp dù đơn cũ đã được SUZ tạo.
- Mong muốn: dữ liệu sai/thiếu/không đầy đủ là UNKNOWN và giữ RECONCILING; chỉ NOT_FOUND khi response list hợp lệ/đầy đủ chứng minh không có đúng đơn. Không suy luận từ dữ liệu không parse được; không chọn nhầm đơn GTIN khác chỉ vì quantity giống nhau.
- Bằng chứng: chuỗi nhánh source nêu trên. `ZnackModuleTest` khoảng698 kiểm tra payload hợp lệ; `ZnackGtinWorkflowTest:496–605` kiểm tra enum kết quả mô phỏng, có ca confirmed-missing giải phóng queue. Tìm toàn bộ orderInfos/reconcile không thấy test malformed list dẫn đến NOT_FOUND. Chưa chạy Java trong pha nghiên cứu.
- Phương án sửa hẹp: validate wrapper/business errors/OMS scope và từng row liên quan; sai shape/identity/time/quantity/buffer phải ném lỗi an toàn để coordinator giữ đối soát. Không dùng fallback[] cho missing. Giữ exact one-match và conflict; không gọi mua lại để “thử”. Giới hạn phạm vi absence theo hợp đồng list chính thức, không tự giả định phân trang.
- Kiểm thử cần thêm: `{}`, orderInfos:null/object, business error HTTP200, row thiếu orderId/time, buffer malformed/GTIN invalid/quantity fractional, OMS mismatch, valid empty và multiple candidates. Với local attempt đã quá5phút, malformed vẫn chặn đơn sau và POST count không tăng.
- Rủi ro: hiện thông báo cần đối soát lâu hơn khi API lỗi; đây là hành vi an toàn cần thiết để tránh tính phí lặp.
- Phân loại: **CẦN SỬA — P0 — BẰNG CHỨNG LUỒNG MÃ / CHƯA KIỂM THỬ JAVA**.

## 6. Hướng triển khai và báo cáo kiểm thử trung thực

1. Sửa hai lỗi P0 trong service/schema đang có; không tạo database/module thứ hai.
2. Bổ sung hồi quy migration và SUZ malformed-response trước khi đụng hiệu năng/ETag.
3. Chạy Maven/JUnit phù hợp và regression FBS/KIZ/migration trên dữ liệu tổng hợp; không thử mua/đăng ký/ký/cập nhật seller thật.
4. Chỉ sau kết quả test mới ghi ĐÃ SỬA/ĐÃ KIỂM THỬ. Đợt nghiên cứu này không có build/test Maven và không xác nhận Windows/CryptoPro/API production.
