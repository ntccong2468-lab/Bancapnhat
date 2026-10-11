# VN code — Products and Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Có màn Sản phẩm WB/Ozon, sửa hàng loạt có đối soát, đọc/ảnh có giới hạn.

**Architecture:** Giữ bảng/ID của từng sàn, thêm read model bất biến và trạng thái giấy tờ có provenance. UI đọc snapshot nền theo generation; cache thumbnail tách với ảnh chất lượng đầy đủ dùng in/sao chép.

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

1. Search tiếng Nga/Việt, ký tự `%_` và GTIN có số 0 đầu: matching đúng, không biến thành SQL wildcard ngoài ý muốn — A1.
2. Chọn nhiều size của cùng thẻ với giá trị bulk khác nhau: chặn xung đột trước gửi — A4.
3. Shop mới/thẻ không có size, ảnh hoặc giấy tờ: vẫn xem được, không giả trạng thái hợp lệ — A1/A3.
4. Cuộn nhanh, ảnh quá lớn/hỏng và 5 shop: pending/cache bounded, lỗi ảnh phục hồi sau TTL — A2.
5. Đổi shop khi callback cũ về: không lẫn dữ liệu/selection hoặc hủy mutation đang gửi — A3/A4.

---

### A1: Read model, truy vấn và migration sản phẩm

**Files:** Create `features/products/{ProductKey,ProductRow,ProductQuery,ProductPage,ProductSnapshot,ProductVariant,ProductAttribute,ProductMedia,PackageDimensions,ProductDocument,DocumentState,ProductSchema,ProductRepository}.java`; modify `config/Database.java`. Tests `features/products/ProductRepositoryTest.java`, `config/DatabaseMigrationCompatibilityTest.java`.

**Interfaces:** Các type theo bản đồ trong [kế hoạch điều phối](2026-10-11-vn-code-products-copy-update.md). `ProductSchema.ensureTables(Connection):void`; `ProductRepository.loadPage(ProductQuery):ProductPage`, `localSnapshot(ProductKey):Optional<ProductSnapshot>`, `saveSnapshot(ProductSnapshot):void`, `freezeKeys(ProductQuery):List<ProductKey>`. Snapshot đọc mới từ C1, không coi cache là readback mutation.

- [x] Viết test `searchAndPagingAreShopScoped`, `unicodeSearchAndLiteralWildcards`, `cardWithoutVariantsRemainsVisible`, `gtinPreservesLeadingZero`, `documentsRequireVerifiedSource`, `migration4To5PreservesRows`: assertion `assertEquals(100, page.rows().size())`, total đúng 251, cursor hết không lặp, không đọc shop B, số `046...` giữ nguyên, giấy đã upload là UNVERIFIED.
- [x] Chạy `MVN -Dtest=ProductRepositoryTest,DatabaseMigrationCompatibilityTest test`; xác nhận red của hành vi mới.
- [x] Triển khai SQL prepared/batch, count cùng predicate/snapshot với page, cursor ổn định (sort key + card/variant), bộ search normalize Unicode có index riêng nếu SQLite lower không đủ; expose cả thẻ chưa có size. Additive product snapshots/documents/search index; schema 5, bảo toàn logic lock/snapshot/fail-closed hiện hữu.
- [x] Chạy lại command trên; mong đợi 0 failures/errors. Kiểm tra EXPLAIN QUERY PLAN với 50.000 sản phẩm không có truy vấn mỗi cell/row để dựng metadata.
- [x] Commit files A1 với `feat: add shop scoped product read model`.

### A2: Điều phối đọc và thumbnail cache giới hạn

**Files:** Create `shared/ShopReadCoordinator.java`, `shared/ThumbnailCache.java`, `shared/ThumbnailLoader.java`; modify `features/fbo/FboProductImageService.java` tại đường tải ảnh; tests `shared/{ShopReadCoordinatorTest,ThumbnailCacheTest,ThumbnailLoaderTest}.java`.

**Interfaces:** `ShopReadCoordinator.Key(int shopId,String workload,String scope)`; `submit(Key,boolean interactive,Callable<T>):CompletableFuture<T>`, `cancelReads(int shopId):void`, `close():void`, `activeCount():int`, `pendingCount():int`. `ThumbnailLoader.load(URI,int width,int height):CompletableFuture<Image>`, `close():void`; `ThumbnailCache.retainedBytes():long`. Cả hai nhận clock/executor/budget trong constructor để test; live defaults low-end: 2 workers, 64 pending; thumbnail budget `64L*1024*1024`.

- [ ] Viết `duplicatesShareOneRead`, `boundedQueueRejectsOrEvictsOnlyReads`, `twoWorkerLimit`, `lruNeverExceeds64MiB`, `oversizedImageRejectedBeforeDecode`, `failureExpiresAndCanRecover`. Assert trùng key chỉ chạy callable 1 lần; active<=2, pending<=64, retained<=67108864; image lỗi retry sau TTL; callback không block FX.
- [ ] Chạy `MVN -Dtest=ShopReadCoordinatorTest,ThumbnailCacheTest,ThumbnailLoaderTest test`; xác nhận red.
- [ ] Implement bounded priority read queues, completion/error rõ ràng, cancellation không ảnh hưởng mutation. Thumbnail downsample trước decode, giới hạn input 8 MiB/40 megapixels (quyết định triển khai), failure TTL 5 phút, HTTPS allowed source/redirect policy không chuyển credential. Giữ ảnh in/full media pipeline riêng; không dùng thumbnail tạo PDF/barcode.
- [ ] Chạy lại command; kiểm tra các test PDF/image hiện có qua `MVN -Dtest=FboBarcodePdfExporterTest,ThumbnailLoaderTest test`, 0 failures/errors.
- [ ] Commit `perf: bound shop reads and thumbnail memory`.

### A3: Màn Sản phẩm, selection và điều hướng lazy

**Files:** Create `ui/products/{ProductsPane,ProductSelection}.java`; modify `ui/workspace/HomeController.java`, `ui/shop/ShopSidebarController.java`, `src/main/resources/com/vncode/app/ui/shop/shop-sidebar-view.fxml`, `src/main/resources/com/vncode/app/styles/theme.css`, bốn `src/main/resources/com/vncode/app/i18n/messages_{vi,ru,en,zh}.properties`. Tests `ui/products/ProductSelectionTest.java`, `ui/FxmlSmokeTest.java`.

**Interfaces:** `ProductsPane.setShop(Shop):void`, `dispose():void`, `setOnCopy(Consumer<List<ProductKey>>):void`; consumes A1 repository/A2 readers. `ProductSelection.selectPage(List<ProductKey>):void`, `freeze():List<ProductKey>`, `switchShop(int,Marketplace):void`; riêng explicit all-results dùng A1.freezeKeys, không cập nhật tập frozen sau sync. Sidebar `setOnProducts(Runnable):void`; Home `showProducts():void`, C4 đăng ký callback copy.

- [ ] Viết `selectionSurvivesFilterButClearsOnShopChange`, `explicitAllResultsIsFrozen`, `staleSnapshotCannotUpdateCurrentShop`, `productsMenuAndFourLocales`. Assert page-select chỉ chọn 100 đã tải, explicit-all là 251, sản phẩm thêm sau freeze không gia nhập; generation A không vẽ vào B; không có TN VED sidebar.
- [ ] Chạy `MVN -Dtest=ProductSelectionTest,FxmlSmokeTest test` dưới Xvfb nếu cần; xác nhận red liên quan UI/selection.
- [ ] Implement ListView/TableView ảo, debounce 300 ms, page 100/count/load-more, category/document/missing-GTIN filters, chi tiết snapshot và bottom bulk bar như ảnh đã duyệt. Lazy initialize Home views khi mở; tránh setShop gây full Ozon sync hoặc init các view chưa mở; dispose listener/read theo vòng đời. Responsive 1024×600/1366×768, DPI 100–150%, vùng phụ collapsible.
- [ ] Chạy lại test, thêm screenshot fixture hai kích thước qua UI smoke không credential; xác nhận menu/product count/unknown document đúng cả bốn locale.
- [ ] Commit `feat: add products workspace and lazy navigation`.

### A4: Bulk edit brand/material/TN VED/documents

**Files:** Create `features/products/{ProductBulkEdit,ProductBulkEditService,ProductEditRepository}.java`; modify adapter C2 `integration/{wb/WbListingAdapter,ozon/OzonListingAdapter}.java` và `ui/products/ProductsPane.java`; tests `features/products/ProductBulkEditServiceTest.java`.

**Interfaces:** `ProductBulkEditService.preview(Shop,List<ProductKey>,ProductBulkEdit):ProductEditPreview`, `apply(Shop,String previewId,boolean confirmed):ProductEditResult`, `reconcile(Shop,String jobId):ProductEditResult`. Nested immutable preview/result records ở service; preview binds sorted/frozen keys, snapshot hashes and selected field values; checkpoint repository owns SQL transaction and owner checks. Adapter C2 supplies `previewUpdate(Shop,ProductSnapshot,ProductBulkEdit):ProductEditPreview`, `submitUpdate(Shop,ProductEditPreview):RemoteSubmission`, `reconcile(Shop,RemoteSubmission):RemoteResult`.

- [ ] Viết `cardLevelConflictingSizesCannotSubmit`, `previewRequiresFreshMatchingRevision`, `confirmationRequired`, `documentUploadDoesNotImplyValid`, `timeoutNeverResends`, `shopChangeDoesNotReassignJob`; assert conflicting values 0 HTTP writes, unconfirmed 0 writes, timeout 1 write and RECONCILE_REQUIRED, unrelated fields preserved.
- [ ] Chạy `MVN -Dtest=ProductBulkEditServiceTest test`; xác nhận red.
- [ ] Gom card-level edits, đọc mới bằng C1 trước preview, xác nhận tập/old-new, atomic claim job trước send, ledger per card, adapter chỉ ghi field chọn và đọc lại; TN VED gọi module lookup hiện có trong dialog. Giấy tờ cần file/metadata/permissions theo contract C2; incomplete contract giữ action disabled với lý do cụ thể, không nhận thành công giả.
- [ ] Chạy lại test và targeted GTIN/mutation ownership tests liên quan; mong đợi không gửi trùng sau restart/timeout.
- [ ] Commit `feat: add confirmed product bulk edits with reconciliation`.
