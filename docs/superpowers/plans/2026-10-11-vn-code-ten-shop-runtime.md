# VN code — Ten-shop runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tổ chức lại vận hành VN code 1.4.0 cho 10 shop, xử lý các đường Ozon gây đơ và giữ toàn bộ nghiệp vụ trong bản cập nhật đang làm.

**Architecture:** Bộ điều phối đọc tách SQLite khỏi mạng; pool và cache có giới hạn không tăng theo số shop. Giữ các dịch vụ nghiệp vụ và nhật ký ghi; cập nhật UI nạp từng trang và loại callback cũ. Kế hoạch bổ sung vào nhánh hiện tại, thực hiện sau D1, trước D2/D3; một reviewer toàn nhánh cuối cùng.

**Tech Stack:** Java 25/JavaFX 25, SQLite/JDBC, OkHttp, JUnit 5/MockWebServer; không thêm runtime/server.

**Spec:** [Thiết kế 10 shop đã duyệt](../specs/2026-10-11-vn-code-ten-shop-runtime-design.md), commit b2685fa; người dùng duyệt ngày 11/10/2026.

## Global Constraints

- Cùng VN code 1.4.0, Windows 10/11 x64; giữ appdata/schema 5 additive, template, lịch sử và UpgradeCode 8CBBA0E2-6E73-4F56-9101-6BC0948D3C72.
- Số shop không làm tăng worker tuyến tính: một worker đọc cục bộ dành riêng, tối đa hai worker đọc mạng; ảnh hai worker; queue đọc/ảnh 64, ghi mới chờ tối đa 32; thumbnail cache tổng 64 MiB.
- Không đổi quy tắc GTIN/KIZ/giấy tờ/ký số; đọc mới, preview, xác nhận, checkpoint, gửi một lần rồi đối soát.
- Shop switch chỉ bỏ kết quả đọc cũ, không gán lại owner hoặc hủy mutation đã gửi. Không auto tải toàn catalog/GS1 khi chọn shop/tab.
- Chỉ fixture giả và appdata tạm; không thao tác seller thật. Diagnostics opt-in không credential/PII.
- Cached p95 ≤300 ms, trang cục bộ đầu ≤1 s là mục tiêu cần đo; không nhận Linux/Windows CI mạnh hơn làm bằng chứng máy 2 lõi/4 GB.

## Review Focus

1. Hai request Ozon treo/429 vẫn cho phép xem trang SQLite và không làm UI đợi — E1/E2.
2. Hàng đợi đầy giữ tác vụ chưa gửi và trả UI khỏi busy; mutation đã nhận không bị drop — E2/E3.
3. 10 shop Ozon cùng refresh không tạo pool riêng/giành hết lượt của shop hiện tại — E1/E2.
4. Lịch sử 100 tác vụ mỗi tác vụ có giấy tờ lớn không tải toàn Base64 vào RAM — E3.
5. 20 vòng chuyển 10 shop, token đổi hoặc xóa shop giữa request: không stale callback và listener/view/cache không tăng — E3/D2.

---

### E1: Điều phối đọc cục bộ và mạng có giới hạn

**Files:** Modify `src/main/java/com/vncode/app/shared/ShopReadCoordinator.java`, `ui/products/ProductsPane.java`, `ui/ozon/OzonDashboardController.java`, `ui/gs1/Gs1Pane.java`, `ui/news/NewsPane.java`; test `src/test/java/com/vncode/app/shared/ShopReadCoordinatorTest.java` (đường dẫn UI dùng prefix `src/main/java/com/vncode/app/`).

**Interfaces:** Add `ShopReadCoordinator(int workers,int capacity,int localWorkers)`, `submitLocal(Key,Callable<T>):CompletableFuture<T>`; giữ `submit(Key,boolean,Callable<T>)`, cancellation/active/pending. Shared = (3,64,1); images = (2,64,0). Fair background selection theo shop, tương tác ưu tiên; không đổi API mutation.

- [x] Test `slowNetworkCannotBlockLocalPage` giữ hai network requests bằng latch và assert local future <300 ms; `tenShopsShareBoundedWorkersAndFairTurns` assert global workers=3, queue≤64, shop đang tương tác không bị mười background lượt liên tiếp; `cancelOldShopLeavesOtherOwnerRead` và coalescing/close.
- [x] Run `MVN -Dtest=ShopReadCoordinatorTest test`; Expected RED cho API/độ trễ mới.
- [x] Implement lanes trong cùng coordinator; chuyển truy vấn page/category/cache SQLite sang submitLocal, mạng sang submit. Không đồng bộ dữ liệu vendor trong local callable.
- [x] Run targeted và Maven suite; Expected PASS, không tăng cache/worker theo shop. Commit `perf: reserve local reads and schedule shop work fairly`.

### E2: Hàng đợi tác vụ và ngân sách HTTP dùng chung

**Files:** Modify `shared/AppTaskExecutor.java`, các caller hiện có trong UI/workflows, `integration/ozon/OzonApiClient.java`, `integration/listing/ListingHttpClient.java` và shared HTTP client thực sự được chúng dùng; create `shared/MarketplaceRequestBudget.java`; tests `shared/AppTaskExecutorTest.java`, `shared/MarketplaceRequestBudgetTest.java`.

**Interfaces:** `AppTaskExecutor.execute(Task<?>):boolean` chấp nhận vào pool hữu hạn hoặc trả false/cancel trước gửi; giữ bộ đếm hasRunningTasks/getRunningTaskCount. `MarketplaceRequestBudget.acquire():AutoCloseable` semaphore công bằng 2, interruptible, release trong finally quanh thực thi HTTP/đọc body. Hai worker legacy, queue 64; outgoing merchant mutations dùng nhật ký/queue riêng 32, không ngắt tác vụ đã gửi.

- [x] Test `fullQueueRejectsBeforeActionAndDoesNotLeaveBusy`, `tenShopRequestsNeverExceedTwoInFlight`, `interruptedWaitReleasesNoForeignPermit`, `timeoutReleasesSlotForNextShop`; assert 0 writes cho rejected task và permit phục hồi.
- [x] Run targeted; Expected RED.
- [x] Implement limits; audit mọi callsite AppTaskExecutor để xử lý false và bỏ busy, giữ thông báo/selection/unsent job. Không giữ slot khi sleep Retry-After hoặc chờ ký số/PIN; giới hạn quanh HTTP từng request, không quanh toàn workflow. Không retry mutation tự động.
- [x] Run targeted HTTP/ownership/ship/signature và full suite; Expected PASS. Commit `perf: bound task queues and marketplace request concurrency`.

### E3: Payload nhẹ và vòng đời UI toàn luồng

**Files:** Modify `features/listingcopy/{CopyJob,ListingCopySchema,ListingCopyRepository,ListingCopyService}.java`, `ui/listingcopy/ListingCopyPane.java`, `ui/products/ProductBulkEditPane.java`, `ui/{workspace/HomeController,packing/PackingController,supply/SupplyDetailController,fbo/FboPackingController,ozon/OzonDashboardController}.java` và repository batch methods được các luồng này dùng. Tests domain/UI tương ứng.

**Interfaces:** Repository listing reads summary (không source.payload/preview/submission Base64), full `find` chỉ một selected job; service `get(Shop,String):CopyJob` ownership validated. UI selection asynchronously reads full job with generation. Mutation executor shared bounded ThreadPoolExecutor 1/32, accepted jobs keep owner. Existing module business signatures preserved.

- [x] Test `largeDocumentHistoryLoadsSummariesOnly` 100 jobs có payload lớn, summary bytes bounded/no Base64; `selectJobLoadsOnlyOwnedDetail`; `repeatedOpenCloseDisposesReadsAndListeners`; `switchShopReturnsBeforeSlowRepository`. Verify frozen sets/partial jobs không bị bỏ khi đổi trang/shop.
- [x] Run targeted; Expected RED.
- [x] Implement additive summary/source-owner columns, lazy detail; bound mutation queues; move discovered heavy SQL/catalog/sort/signing work off FX in listed major flows; remove tab/shop-triggered full remote sync; debounce/coalesce UI updates and dispose timers/listeners. Ghi từng điểm đã rà soát vào validation.
- [x] Run targeted 4 locales/two window sizes và full suite; Expected PASS. Commit `perf: load task summaries and release seller workspace resources`.

### D2 bổ sung: 10 shop và nghiệm thu cuối nhánh

**Files:** Existing D2 `SellerWorkspaceFixture`, Windows benchmark script, migration/UI tests và validation 1.4.0.

**Interfaces:** `SellerWorkspaceFixture.create(Path,int ozonShops):void` luôn 10 shop, ozonShops=4 hoặc10; mỗi shop 10.000 sản phẩm/1.000 đơn. Benchmark nhận dataset/build/hardware IDs; median/p95/heap/FX stalls/queues/cache cùng điều kiện cho baseline/current.

- [x] Add 20×10 switch test, bounded queue/cache, 100.000 products/10.000 orders, slow HTTP fixture; schema4 migration/data preserve. Run targeted RED trước fixture/instrumentation.
- [x] Implement opt-in counters + reproducible fixture/harness; benchmark 6WB/4Ozon và10Ozon. Windows2core/4GB chưa có phải ghi chưa đo; tiếp tục native CI tương ứng.
- [x] `MVN clean verify`, `node --test tools/*.test.mjs`, actual four-locale/two-size UI; Expected 0 failure/error/skipped. One fresh most-capable reviewer for original + supplemental plans; fix important bugs RED→GREEN/full suite.
- [x] Commit validation sau số đo thật; D3 build và publish exact successful Windows commit như kế hoạch gốc. Không phát hành “không bao giờ lag” khi chưa có bằng chứng.

Self-review: mọi mục spec có task E1/E2/E3/D2; interfaces không trùng mutation; giữ Native đã chọn, cùng ledger/nhánh và cùng preview. D1 tiếp tục theo kế hoạch đã duyệt trong khi chờ duyệt kế hoạch bổ sung.

D2 evidence: 798/798 Java/JavaFX, 33/33 Node; 2 datasets × 20×10 switches, 64 PNG and local reports in `docs/validation/2026-10-11-vn-code-1.4.0.md`. Windows 2 CPU/4 GiB baseline chưa đo; không công bố đạt. Ruling: final whole-branch review runs after D3 source/metadata are ready, before native build/publication, so it includes packaging changes.

