# VN code — Ozon FBS and Windows Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tối ưu FBS Ozon, đo với 5 shop và tạo bản nâng cấp Windows có bằng chứng.

**Architecture:** Thay loadLocal đồng bộ bằng page snapshot nền, tái dùng Ozon ship/exemplar/label services đúng semantics. Kiểm tra domain/UI/migration trước native Windows benchmark/installer; giữ cùng application identity.

**Tech Stack:** Java 25, JavaFX 25, SQLite/JDBC, Gson, OkHttp, JUnit 5 và MockWebServer; toolchain hiện có.

**Spec:** [Thiết kế đã duyệt](../specs/2026-10-11-vn-code-products-copy-design.md), commit `e8c900a76377685324cd3cc43bd30c098fb7e338`; người dùng duyệt ngày 11/10/2026.

## Global Constraints

- Cùng ứng dụng VN code Windows; Java 25/JavaFX, Maven; không thêm runtime desktop/web thứ hai.
- Giữ appdata, template, lịch sử và UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`; không sửa installer/tag đã phát hành.
- Logo VN trắng nền đen, tông tối/tím; TN VED chỉ trong tác vụ, không có mục sidebar; copy mới có đủ VI/RU/EN/ZH.
- Shop/marketplace/INN/môi trường được xác minh trước đọc/ghi; async result chỉ áp dụng đúng generation.
- Migration additive; snapshot nhất quán, integrity/foreign-key check; test luôn dùng appdata tạm.
- Mutation: đọc mới, preview, xác nhận, checkpoint, gửi một lần, đối soát; timeout không tự gửi lại. Không dùng credential seller thật trong test.
- Hợp đồng ngoài chưa xác minh phải hiện rõ chưa sẵn sàng; không suy API ghi từ screenshot/tài liệu cũ. Không gọi thao tác seller thật để kiểm thử.

Quy ước đường dẫn: các tên dưới `features/`, `integration/`, `shared/`, `ui/` đều thuộc `src/main/java/com/vncode/app/`; test tương ứng thuộc `src/test/java/com/vncode/app/`. Resource luôn ghi đầy đủ. Lệnh `MVN` bên dưới là `/workspace/wcode-setup/mvn-bancapnhat` trong cloud hiện tại, hoặc `./mvnw -B` trên máy đã có JDK 25. Mỗi task thực hiện đủ red → green trước commit; lỗi biên dịch vì class mới chưa tồn tại là red hợp lệ.

## Review Focus

1. 1.001 đơn, sort theo product/article/color/size: count đầy đủ và sort trước page — D1.
2. Exemplar/KIZ/status thay đổi sau chọn: refresh yêu cầu trước ship/print, không bỏ điều kiện — D1.
3. Chuyển shop lúc gửi/tải label: giữ owner, read generation cũ không vẽ vào shop mới — D1.
4. Schema 4 có dữ liệu và 20 vòng/5 shop: dữ liệu được giữ, queue/listener/cache không tăng vô hạn — D2.
5. Installer mới trên máy có VN code cũ và app khác: nâng cấp đúng identity, DPI/DPAPI/CryptoPro limitations có bằng chứng — D3.

---

### D1: Snapshot phân trang và FBS Ozon giống luồng WB

**Files:** Create `features/ozon/{OzonFbsQuery,OzonFbsPage,OzonFbsSnapshotService}.java`; modify `integration/ozon/{OzonPostingRepository,OzonCatalogRepository,OzonSyncWorkflow}.java`, `ui/ozon/OzonDashboardController.java`, `src/main/resources/com/vncode/app/ui/ozon/ozon-dashboard-view.fxml`, theme/i18n; tests `features/ozon/OzonFbsSnapshotServiceTest.java`, `integration/ozon/OzonPostingRepositoryTest.java`, `ui/ozon/OzonDashboardControllerTest.java`.

**Interfaces:** `OzonFbsQuery(int shopId,String status,String text,String sort,int offset,int limit)`; `OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku)`; `OzonFbsSnapshotService.load(OzonFbsQuery):OzonFbsPage`; `OzonPostingRepository.countFiltered(int,String,String):long`, `findSortedPage(OzonFbsQuery):List<OzonPostingDto>`; `OzonCatalogRepository.findBySkus(int,Collection<String>):Map<String,OzonProductDto>`. Read workers A2; existing `OzonShipService.ship(Shop,String,boolean)` remains owner/checkpoint/reconcile authority.

- [ ] Viết `moreThan500PostingsAreReachable`, `sortingPrecedesPaging`, `onlyPageSkusAreLoaded`, `oldShopResultCannotUpdateTable`, `latestKizRulesAreRequired`, `shipTimeoutIsNotRetried`; assert fixture 1001 count=1001, first page100, global sort boundaries correct, 0 findAll, no ship without confirm, lost-response only one request. Chạy `MVN -Dtest=OzonFbsSnapshotServiceTest,OzonPostingRepositoryTest,OzonDashboardControllerTest,OzonShipServiceTest test`; xác nhận red.
- [ ] Implement prepared/batch queries snapshot count+rows/sku enrich off-FX; avoid per-item barcode/metadata queries. Sort full filtered dataset in SQL or maintained sort projection, stable tie key; debounce 300 ms, page100, frozen posting selection, status counts true. Lightweight `syncCurrentPostings`, no full catalog fetch on tab/shop switch.
- [ ] Update responsive FXML three tabs: Đơn mới (`awaiting_packaging`) → Chuẩn bị/Chờ giao (`awaiting_deliver`) → Đang giao (`delivering`); `/ship` only confirmation đóng gói. Collapsible GTIN/KIZ panel; batch detail images/posting/time/article/color/size/GTIN/barcode/quantity/source price+currency/KIZ/label. Preview+confirm actual selected set before ship, refresh current requirements/label readiness; show success/error/pending per order, preserve context across shop changes.
- [ ] Chạy targeted command và `MVN -Dtest=OzonExemplarWorkflowTest,OzonPrintReadinessServiceTest,OzonLabelServiceTest,FxmlSmokeTest test`; 0 failures/errors, no lost guards.
- [ ] Commit `feat: streamline paged Ozon FBS packing`.

### D2: Full verification, migration và performance fixture

**Files:** Create `src/test/java/com/vncode/app/performance/SellerWorkspaceFixture.java`, `tools/windows-seller-workspace-benchmark.ps1`, `docs/validation/2026-10-11-vn-code-1.4.0.md`; extend `ui/FxmlSmokeTest.java`, `config/DatabaseMigrationCompatibilityTest.java`, `shared/LocalDataSnapshotServiceTest.java`. Benchmark fixture uses only generated data/appdata tmp.

**Interfaces:** `SellerWorkspaceFixture.create(Path appdata):void` seeds exactly 5 shops (3 WB, 2 Ozon), 10.000 products/shop, 1.000 active orders/shop; benchmark accepts `-AppImage`, `-FixtureRoot`, `-OutputDirectory`, reports hardware/data/build IDs/median/p95/heap/FX stalls/queue/cache/listener stats. Instrumentation must be opt-in, no credentials or PII logged.

- [ ] Viết `schema4To5KeepsShopTemplatesAndHistory`, `newerSchemaFailsClosed`, `consistentSnapshotRestoresForeignKeys`, `twentyShopCyclesReleaseReadsAndStayWithinCacheBudget`. Assert integrity_check=ok, foreign_key_check empty; after20×5 switches pending drains, cache<=67108864, no retained view/listener growth. Chạy targeted migration/UI/performance fixture tests; xác nhận red cho paths mới.
- [ ] Implement synthetic seed, opt-in timing/read/thumbnail diagnostics và reproducible benchmark instructions. JavaFX tests verify routes/lazy behavior/4 locales at 1024×600/1366×768 and DPI simulation; actual Windows DPI verification remains D3. Do not change JVM heap blindly; record measured allocation/heap before recommending flags.
- [ ] Chạy `MVN clean verify` dưới Xvfb đầy đủ; `node --test tools/*.test.mjs`; không bỏ test do feature chưa đúng. Record fresh test counts/log locations/remaining external contract gates. Làm reviewer độc lập cuối nhánh theo execution skill đã chọn, sửa issues và chạy lại checks liên quan.
- [ ] Đo baseline commit `62e7e32` vs new build trên cùng Windows 2 CPU/4GB dataset; mục tiêu cached click/tab/shop p95<=300 ms, first page<=1s, phân tích FX stalls>500 ms; network/PIN/printer separate. Không suy % cải thiện hoặc Windows pass từ Linux tests. Nếu thiếu Windows capacity, lưu script/fixture và ghi native benchmark chưa chạy, tiếp tục checks có sẵn.
- [ ] Commit validation/fixture sau khi kết quả phản ánh đúng thực tế, không đánh dấu performance target đạt từ plan.

### D3: Bản nâng cấp VN code 1.4.0 Preview

**Files:** Modify `pom.xml` và các version declarations thực sự được `tools/release-version.mjs` xác minh (không tạo package.json); `README.md`, `news/feed.json`, release descriptions chỉ mô tả VN code; thêm `tools/windows-seller-workspace-smoke.ps1`, integrate `.github/workflows/build-java.yml` khi native checks cần; dùng `.github/workflows/publish-vncode-preview.yml` hiện có cho preview. Giữ installer identity/UpgradeCode/icon/data roots.

**Interfaces:** Existing native build/preview-publish contracts in `.github/workflows/publish-vncode-preview.yml`, `tools/publish-vncode-windows.mjs`, `windows-native-smoke.ps1`, `windows-upgrade-smoke.ps1`; build version `1.4.0`, prerelease tag `v1.4.0-preview.1` (quyết định triển khai). New smoke accepts existing app image/fixture args and verifies Products/GS1/Copy/Ozon routes without real credentials.

- [ ] Viết/update version/manifest/native smoke contract assertions trước bump: installer product VN code, UpgradeCode giữ nguyên, source revision/hash đúng, expected filename/hash URL; run Node contract tests, xác nhận red khi version/feature artifact chưa khớp.
- [ ] Bump toàn bộ version sources đồng nhất bằng existing tooling, cập nhật mô tả tính năng đã hoàn tất và giới hạn API/translation/benchmark thật. Chạy Maven verify/Node checks lại nếu version/packaging changes require; không sửa old tags/assets/feed historical entries.
- [ ] Build app-image/EXE trên Windows CI hiện có; verify app launcher/module routes, DPAPI config storage, temporary data/migration and upgrade 1.3.0→1.4.0, side-by-side isolation với ứng dụng khác. Use DPI100/125/150%, capture screenshots. CryptoPro/live balances/import/translation không có certificate/account là limitations, không fake pass.
- [ ] Publish vào Bancapnhat sau khi native gates đạt, attach installer+SHA256+update-manifest khớp bytes/source/runID; verify release links/digest and same-app updater rules. Chức năng chưa verified contract/live-ready phải trình bày chính xác trong release; chưa hoàn tất phạm vi thì không công bố là đầy đủ thiết kế.
- [ ] Commit final release metadata/validation, push nhánh mới và ghi release/installer URL cùng fresh test evidence. Người dùng đã yêu cầu phát hành bản cập nhật; không yêu cầu lại quyền push/publish đã có.
