# VN code — GS1 Balance and Official News Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hiển thị GTIN còn lại, tiền Честный ЗНАК và tin WB gốc/bản dịch có nguồn đúng.

**Architecture:** Tái sử dụng Znack certificate/INN/settings, nhưng mỗi dịch vụ giữ phiên riêng. Cache có scope và revision; UI hiện dữ liệu cũ trước, refresh nền khi người dùng yêu cầu hoặc phiên đã được cho phép.

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

1. Prefix hết hạn hoặc một GCP không có gtinsLeft: tổng không cộng số không hợp lệ, hiện partial/unknown — B1.
2. Số dư 0 thực, tiền âm và lỗi/thiếu quyền: BigDecimal đúng minor-unit, unknown không thành 0 — B2.
3. Hai shop cùng INN nhưng môi trường/nhóm hàng khác: không dùng chung ví/cache/token sai scope — B1/B2.
4. WB sửa cùng news ID và phiên token shop thay đổi: invalidates translation/read state, không lộ cache cũ — B3.
5. Tin dài, Unicode/HTML, provider timeout/quota: chia giới hạn 10.000, giữ original, không giả bản dịch — B4.

---

### B1: Tổng GTIN và thẻ GS1 có cache

**Files:** Create `features/gs1/{GtinAvailability,Gs1DashboardService}.java`; modify `integration/gs1/Gs1Membership.java`, `features/gs1/Gs1Repository.java`, `ui/gs1/Gs1Pane.java`, bốn i18n resource. Tests `features/gs1/Gs1DashboardServiceTest.java`, `integration/gs1/Gs1MembershipTest.java`.

**Interfaces:** `GtinAvailability(OptionalLong available,boolean complete,int activePrefixes,int unknownPrefixes)`; `Gs1DashboardService.cached(String inn,String environment):Optional<Gs1Membership>`, `availability(Gs1Membership,LocalDate):GtinAvailability`; request coordination consumes A2 with key environment+verified INN. GS1 pane keeps existing letters/membership commands and certificate queue.

- [x] Viết `expiredPrefixesAreExcluded`, `missingLimitMakesTotalPartial`, `environmentAndInnCacheIsolation`, `openingFiveShopsDoesNotTriggerSigning`. Assert active 100 + expired 900 =100; missing active limit yields complete=false; unsupported/malformed is unknown, no PIN prompt on navigation.
- [x] Chạy `MVN -Dtest=Gs1DashboardServiceTest,Gs1MembershipTest,Gs1RepositoryTest test`; xác nhận red.
- [x] Thêm scoped cache tương thích bản cũ (cache cũ không có environment chỉ dùng khi scope được xác minh), tổng đúng GCP đủ quyền, ba summary cards, source/time/stale/unknown. Lấy chứng thư/INN từ context Znack đã kiểm tra; không thêm password GS1; reuse authorized session hoặc explicit refresh, không tự ký 5 shop.
- [x] Chạy lại command, 0 failures/errors; kiểm tra đổi certificate/environment làm invalid session/cache đúng scope.
- [x] Commit `feat: show scoped GS1 GTIN availability`.

### B2: Adapter số dư Честный ЗНАК chỉ đọc

**Files:** Create `integration/znack/{ChestnyZnakBalanceClient,BalanceScope,BalanceSnapshot}.java`, `features/gs1/{Gs1BalanceSchema,Gs1BalanceRepository}.java`, `docs/research/2026-10-11-vn-code-balance-contract.md`; modify `config/Database.java`, `features/gs1/Gs1DashboardService.java`, `ui/gs1/Gs1Pane.java`. Tests `integration/znack/ChestnyZnakBalanceClientTest.java`, `features/gs1/Gs1BalanceRepositoryTest.java`.

**Interfaces:** `BalanceScope(String inn,String environment,String productGroup,String accountId)`; `BalanceSnapshot(BalanceScope scope,BigDecimal amount,String currency,Instant checkedAt,URI source)`; `ChestnyZnakBalanceClient.read(BalanceScope,ServiceSession):BalanceSnapshot`, nested `ServiceSession` holds auth kind/base URI/verified INN and transient token with redacted toString; `Gs1BalanceRepository.load(BalanceScope):Optional<BalanceSnapshot>`, `save(BalanceSnapshot):void`; `Gs1BalanceSchema.ensureTables(Connection):void`.

- [x] Trước test adapter, lưu contract từ public script/doc chính thức: complete endpoint/base, auth domain, profile INN, contractId/productGroup/currency và minor-unit. Hash/script hiện có `c4dfb18519b64d4c5b0da1f660e10212754ef6b389471fb04f2c67b49be5fdd6`; phân biệt BFF ELK với True API. Không gọi private account. Nếu không đủ bằng chứng, test verified-contract capability từ chối đọc live và ghi blocker, không đoán token interchange.
- [x] Viết `zeroIsKnownButFailureIsUnknown`, `minorUnitsStayExact`, `foreignInnOrGroupRejected`, `separateServiceSessions`, `balanceCacheScopeIsolation`. Assert known zero `BigDecimal("0.00")`; fixture minor 12345→`123.45` khi contract xác nhận; missing currency/INN/group bị từ chối; False auth domain không gửi token. Chạy `MVN -Dtest=ChestnyZnakBalanceClientTest,Gs1BalanceRepositoryTest test`; xác nhận red.
- [x] Implement bounded read-only adapter theo contract đã xác minh, parse BigDecimal không double, nhóm hàng/tài khoản riêng, cache/source/freshness; không cộng ví khác. Znack credentials/certificate chỉ làm gốc xác thực; reuse True API token chỉ khi đúng auth contract. Error không ghi đè snapshot bằng 0; UI “Chưa lấy được” rõ nguồn.
- [x] Chạy lại test; fixture zero/malformed/401/429/timeout/foreign scopes đều đúng, response/token không xuất log hoặc exception.
- [x] Commit `feat: add scoped Chestny Znak balance summary` khi code đạt test; chưa gọi adapter live nếu evidence chưa đủ.

### B3: Tin WB chính thức, provenance và cache riêng

**Files:** Create `features/news/{NewsScope,SellerNews,SellerNewsSchema,SellerNewsRepository,OfficialNewsService}.java`, `integration/wb/WbNewsClient.java`, `docs/research/2026-10-11-vn-code-wb-news-contract.md`; modify `config/Database.java`, `features/news/NewsService.java`, `ui/news/NewsPane.java`, `ui/workspace/HomeController.java`. Tests `features/news/OfficialNewsServiceTest.java`, `integration/wb/WbNewsClientTest.java`.

**Interfaces:** `NewsScope(int shopId,Marketplace marketplace,String ownerFingerprint)`; `SellerNews` source/id/revision/scope/publishedAt/originalTitle/originalBody/sourceURL; `OfficialNewsService.refresh(Shop,NewsScope,boolean force):List<SellerNews>`, `cached(NewsScope):List<SellerNews>`; `WbNewsClient.readSince(Shop,Instant from,String fromId):NewsBatch`, nested `NewsBatch(List<SellerNews> items,String nextFromId)`; `SellerNewsRepository.markRead(NewsScope,String sourceId,String revision):void`; schema helper ensureTables(Connection).

- [ ] Đối chiếu `/api/communications/v2/news` hiện có từ mirror 2025 với official schema/frontend/document; lưu nguồn và version. Endpoint/from/fromID inclusive/page 100/rate limit chỉ trở thành live contract sau xác minh; fixture offline không tự chứng minh hợp đồng mới.
- [ ] Viết `sameIdNewRevisionBecomesUnread`, `inclusiveCursorDoesNotRepeatNews`, `scopeDoesNotExposeOtherShopCache`, `backgroundRefreshHonorsThirtyMinutes`, `rateLimitHonorsRetryAfter`, `vnFeedIsNeverLabeledOfficialWb`. Assert min TTL 30 phút, Ozon credentials tạo 0 WB requests, update revision invalidate translation/read. Chạy `MVN -Dtest=OfficialNewsServiceTest,WbNewsClientTest,NewsServiceTest test`; xác nhận red.
- [ ] Implement scoped source adapter/cache, bounded parse/plain text/safe sourceURL, publish-time sorting, coalesced refresh A2 và rate-limit per rights scope. Giữ VN feed có nhãn VN code riêng và tương thích read/cache cũ. No-WB-shop/rights-error hiện valid own-scope cache hoặc hướng dẫn; không publish authenticated seller feed lên GitHub.
- [ ] Chạy lại command; ownership, stale generations, pagination và failure fallback đúng.
- [ ] Commit `feat: add official WB seller news with provenance`.

### B4: Dịch tiếng Việt và cấu hình Yandex an toàn

**Files:** Create `integration/translation/{NewsTranslator,YandexNewsTranslator,TranslationSettingsRepository}.java`, `features/news/NewsTranslationService.java`; modify `ui/news/NewsPane.java`, settings dialog hiện có trong Home, bốn i18n resources. Tests `integration/translation/YandexNewsTranslatorTest.java`, `features/news/NewsTranslationServiceTest.java`; extend `ui/FxmlSmokeTest.java`.

**Interfaces:** `NewsTranslator.translate(List<String> texts,String sourceLanguage,String targetLanguage):List<String>`, `version():String`; `NewsTranslationService.translate(SellerNews,String targetLanguage):TranslatedNews`, nested `TranslatedNews` original+translatedTitle/body+providerVersion/hash/state. `TranslationSettingsRepository.save(URI endpoint,String folderId,String apiKey):void`, `load():Optional<Settings>`; stored key protected by existing WindowsSecretProtector CurrentUser, transient Settings redacted; no plaintext fallback.

- [ ] Viết `splitsTenThousandCharactersWithoutBreakingUnicode`, `revisionInvalidatesCache`, `missingKeyOrQuotaKeepsOriginal`, `numbersDatesAndGtinPreserved`, `credentialNeverAppearsInRequestTextOrErrors`, `providerKeyUsesDpapi`. Assert mỗi request tổng<=10000 ký tự; output không hợp lệ/mất protected literal bị từ chối; repeated hash+vi+version chỉ dịch một lần. Chạy `MVN -Dtest=YandexNewsTranslatorTest,NewsTranslationServiceTest test`; xác nhận red.
- [ ] Implement theo official proto `/translate/v2/translate`, xác minh host/auth bằng Yandex cloud SDK/doc chính thức; source ru/target vi, text chunks, cache by hash/language/provider, deadline/quota/backoff. UI setting dùng key seller nhập qua cấu hình an toàn; user-initiated validation, không tự mua dịch vụ hoặc nhúng owner key. Tin có nhãn “Dịch tự động”, xem bản gốc/source; unconfigured/error “Chưa có bản dịch tiếng Việt”.
- [ ] Chạy tests và `MVN -Dtest=FxmlSmokeTest test`; native Windows DPAPI test thực hiện ở D3. Không khẳng định đã dịch live nếu chưa có account/quota thật.
- [ ] Commit `feat: translate WB news with cached Vietnamese fallback`.
