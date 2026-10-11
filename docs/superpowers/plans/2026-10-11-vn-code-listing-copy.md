# VN code — Listing Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sao chép WB→WB, Ozon→Ozon, WB→Ozon và Ozon→WB qua một wizard, có draft và đối soát.

**Architecture:** Snapshot chuẩn từ Products; hai adapter dùng dictionary/ID riêng của sàn đích. Mỗi destination có checkpoint độc lập, preview frozen/source revision; source luôn chỉ đọc.

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

1. Same-sàn vẫn khác shop/category IDs và article trùng: không silently overwrite hay reuse internal IDs — C1/C2.
2. Size khác nhau, GTIN 0 đầu hoặc chưa xác minh: không gộp GTIN sai/auto cấp GTIN/KIZ — C2.
3. Giấy tờ hết hạn/không đọc được file, thiếu giá mandatory: preview chỉ ra lỗi, chặn publish — C1/C2.
4. Ảnh/file redirect lớn hoặc chuyển token sang host khác: bounded/no credential forwarding — C1.
5. Timeout/import async/restart/chuyển shop: durable state, không duplicate và không đổi owner — C3/C4.

---

### C1: Snapshot đầy đủ và transport media/documents

**Files:** Create `features/products/ProductSnapshotReader.java`, `integration/wb/WbProductSnapshotReader.java`, `integration/ozon/OzonProductSnapshotReader.java`, `features/listingcopy/ListingMediaReader.java`, `docs/research/2026-10-11-vn-code-listing-contracts.md`; modify existing `integration/wb/WbApiClient.java`, `integration/ozon/OzonApiClient.java` khi thêm verified read methods. Tests `features/products/ProductSnapshotReaderTest.java`, `features/listingcopy/ListingMediaReaderTest.java`.

**Interfaces:** `ProductSnapshotReader.read(Shop,ProductKey):ProductSnapshot` sử dụng type A1; hai adapter cùng interface. `ListingMediaReader.fetch(URI,String expectedMime):MediaFile`, nested `MediaFile(Path temporaryFile,String sha256,long size,String mimeType)` implements AutoCloseable để xóa tệp; không có parameter source token cho media fetch.

- [ ] Lưu contract read full WB card/photos/characteristics/sizes, Ozon info/attributes/media/dimensions và giấy tờ metadata/files từ nguồn chính thức. Đối chiếu current Ozon `/v3/product/info/list`, `/v4/product/info/attributes` với fixture hiện có; lấy bằng chứng riêng cho trường mới. Public docs bị chặn thì tiếp tục tìm official schema/SDK trước dùng mirror; không coi description card là full document contract.
- [ ] Viết `snapshotKeepsAllPhotosVariantsAndPrecision`, `shopOwnerMustMatch`, `summaryCannotPretendComplete`, `documentFileMissingIsExplicit`, `redirectNeverForwardsCredential`, `oversizeOrMimeMismatchIsRejected`; assert whole image order, package decimal precision, GTIN text, incomplete documents clear. Chạy `MVN -Dtest=ProductSnapshotReaderTest,ListingMediaReaderTest test`; xác nhận red.
- [ ] Implement adapters trả immutable full snapshot hash/revision, giữ provenance và unknown/issues; card/category/variant identity riêng. Media allowlisted HTTPS nguồn thị trường/document được chọn, hop-limit và SSRF/IP/host restrictions, timeouts; giới hạn 20 MiB mỗi ảnh, 25 MiB mỗi giấy tờ, 20 file/job (quyết định triển khai), báo cần bổ sung khi quá hạn mức; không dùng thumbnail làm ảnh upload.
- [ ] Chạy lại command; test fields/photo order/units/no secrets, tmp cleanup khi fail/cancel và cache scope.
- [ ] Commit `feat: read complete listing snapshots for both marketplaces`.

### C2: Adapter đích, mapping và validation copy/update

**Files:** Create `features/listingcopy/{ListingTargetAdapter,CopyOptions,CopyPreview,TargetCategory,TargetAttribute,CopyIssue,RemoteSubmission,RemoteResult}.java`, `integration/wb/WbListingAdapter.java`, `integration/ozon/OzonListingAdapter.java`; tests `features/listingcopy/ListingCopyMappingTest.java`, `integration/wb/WbListingAdapterTest.java`, `integration/ozon/OzonListingAdapterTest.java`.

**Interfaces:** `ListingTargetAdapter.categories(Shop):List<TargetCategory>`, `attributes(Shop,String categoryId):List<TargetAttribute>`, `preview(Shop,ProductSnapshot,CopyOptions):CopyPreview`, `submit(Shop,CopyPreview):RemoteSubmission`, `reconcile(Shop,RemoteSubmission):RemoteResult`. CopyOptions: selected fields/variant IDs, targetCategoryId, attribute-ID mapping, new seller article, optional required target price. CopyPreview: target owner, source revision/hash, canonical request hash, field diff/issues, publishable. RemoteSubmission: owner/job request hash/nullable task ID; RemoteResult state/destination IDs/issues. A4 update signatures also implemented on both concrete adapters; use same RemoteSubmission/RemoteResult.

- [ ] Chốt official create/update/media/document-upload/link/status contracts và idempotency/reconcile identifiers cho hai sàn trong C1 contract doc. Thiếu contract cho một thao tác thì capability rõ ràng, action disabled; không hardcode giả request success hay silently omit mandatory field/doc.
- [ ] Viết `allFourDirectionsUseDestinationDictionaries`, `leadingZeroGtinAndVariantsStayDistinct`, `articleCollisionRequiresExplicitChoice`, `mandatoryPriceOrDocumentBlocksPublish`, `sourceIdsAndRawKizNeverReachDestination`, `packageUnitsConvertExactly`. Assert four direction previews map dest IDs, source nmId/chrtId/SKU/docID not copied; thiếu file/GTIN proof/price mandatory yields publishable=false. Chạy `MVN -Dtest=ListingCopyMappingTest,WbListingAdapterTest,OzonListingAdapterTest test`; xác nhận red.
- [ ] Implement target categories/attributes with typed values/units, explicit variant map and limits, document revalidation/upload supported by API; title/description/photo order/GTIN/size/package metadata diff. No auto-price/stock copy, no GTIN/KIZ allocation; source-only IDs never sent as target identity. Mutation send once, extract official async task/result, current readback of card/variant/media/docs.
- [ ] Chạy lại command, targeted product API/GTIN tests khi client đổi; kiểm tra sanitized errors/no unverified success.
- [ ] Commit `feat: add WB and Ozon destination listing adapters`.

### C3: Drafts/checkpoints và workflow durable

**Files:** Create `features/listingcopy/{ListingCopySchema,ListingCopyRepository,ListingCopyService,CopyJob,CopyState}.java`; modify `config/Database.java`. Test `features/listingcopy/ListingCopyServiceTest.java`.

**Interfaces:** `CopyState`: DRAFT, VALIDATED, AWAITING_CONFIRMATION, SENDING, REMOTE_PROCESSING, RECONCILED, NEEDS_DATA, FAILED, RECONCILE_REQUIRED. `ListingCopyService.draft(Shop source,List<ProductKey>,List<Shop> destinations,CopyOptions):List<CopyJob>`, `preview(Shop destination,String jobId):CopyPreview`, `publish(Shop destination,String jobId,String approvedRequestHash,boolean confirmed):CopyJob`, `reconcile(Shop destination,String jobId):CopyJob`; repository `find(int destinationShopId,String jobId):Optional<CopyJob>`, `claim(int destinationShopId,String jobId,String approvedHash):boolean`; schema helper ensureTables(Connection).

- [ ] Viết `unconfirmedOrChangedPreviewCannotSend`, `doubleClickClaimsOnlyOnce`, `lostResponseRequiresReconciliation`, `restartCannotRepeatSubmittedJob`, `destinationsHaveIndependentResults`, `foreignOwnerCannotResume`. Assert 0 writes unconfirmed/stale; concurrency same job 1 write; timeout remains RECONCILE_REQUIRED across repository restart, source unchanged.
- [ ] Chạy `MVN -Dtest=ListingCopyServiceTest test`; xác nhận red.
- [ ] Implement additive durable per-destination checkpoints/source hash/frozen variant/fields/mapping; atomic compare-and-set claim before send. Batch progress reports success/error/pending separately. Definite unsent failures may release draft; sent/unknown cannot automatically reset. Before retry query task/status/readback; require identity match before RECONCILED.
- [ ] Chạy lại command; race/restart/task-ID absence tests pass, no job reassignment when selected shop changes.
- [ ] Commit `feat: persist listing copy jobs and reconciliation`.

### C4: Wizard trong Công cụ và preview đa đích

**Files:** Create `ui/listingcopy/ListingCopyPane.java`; modify `ui/products/ProductsPane.java`, `ui/workspace/HomeController.java`, `ui/shop/ShopSidebarController.java`, sidebar FXML, theme.css và bốn i18n resource; tests `ui/listingcopy/ListingCopyPaneTest.java`, extend `ui/FxmlSmokeTest.java`.

**Interfaces:** `ListingCopyPane.open(Shop,List<ProductKey>):void`, `setShop(Shop):void`, `dispose():void`; Sidebar `setOnListingCopy(Runnable):void`. Consumes A3 onCopy frozen keys, C1 snapshot, C2 dictionaries/preview, C3 drafts/publish/reconcile; callbacks carry job owner independently of screen generation.

- [ ] Viết `copyNavigationAndFourDirections`, `previewMissingDocumentsCannotPublish`, `leavingViewPreservesSentJob`, `localeAndSmallWindowLayout`; assert same/cross destinations available, frozen variant set visible, require explicit selected-field preview confirmation; no default price/stock checkbox enabled.
- [ ] Chạy `MVN -Dtest=ListingCopyPaneTest,FxmlSmokeTest test`; xác nhận red.
- [ ] Implement wizard source → destination(s) → fields/category/attribute/variant/file mapping → preview issues → save draft/confirmed publish → per-destination async result/reconcile. Missing credentials/files/price stay editable, no silent skip. Tools retains GTIN/FBO/finance and Products action launches same wizard. Busy read generation canceled on leave; sent job durable.
- [ ] Chạy lại test, screenshots under fixture at 1024×600 and 1366×768; errors/progress/localized routes keep destination owner.
- [ ] Commit `feat: add listing copy tools wizard`.
