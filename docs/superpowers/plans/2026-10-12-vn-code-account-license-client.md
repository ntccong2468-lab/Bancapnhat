# VN code Account License Client Implementation Plan

> Cập nhật 13/10: phần mật khẩu và hợp đồng thiết bị trong tài liệu này là kế hoạch lịch sử. Luồng đã chốt chuyển sang Gmail/OTP trong ứng dụng và khóa VNC từ Portal 0.3.0. Đã có mã backend chuẩn bị và client; xem [hợp đồng hiện tại](../../research/2026-10-13-vn-code-desktop-license-contract.md) nếu đọc từ plans, hoặc `2026-10-13-vn-code-desktop-license-contract.md` trong research. Production vẫn chờ pin/deploy/nghiệm thu.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Người sở hữu đã chọn triển khai trực tiếp trong phiên; giữ lựa chọn đó, không hỏi lại cách thực hiện.

**Goal:** Thêm đăng nhập tài khoản website, quyền offline ký số và giới hạn thiết bị vào cùng VN code 1.4.0, giữ dữ liệu và các bản đã phát hành.

**Architecture:** Một phiên `LicenseService` dùng chung cho toàn ứng dụng, tách verifier, clock, DPAPI store và HTTP adapter. Service nghiệp vụ kiểm tra quyền trước tác vụ mới và trước mỗi lần gửi mới trong batch; journal đã gửi tiếp tục đối soát theo owner/checkpoint hiện có. Client có thể triển khai và kiểm thử độc lập bằng API fixture; nối website production và phát hành phải chờ backend thực tế cùng public signing pin được xác minh.

**Tech Stack:** Java 25, JavaFX/FXML, JDK Ed25519, Gson, OkHttp, Windows current-user DPAPI hiện có, JUnit 5/MockWebServer; Node 22 Web Crypto cho kiểm thử byte format. Không thêm SDK cấp phép thương mại hoặc desktop runtime khác.

**Spec:** [Thiết kế B đã duyệt](../specs/2026-10-12-vn-code-web-license-design.md); [hợp đồng API đề xuất](../../research/2026-10-12-vn-code-account-license-contract.md).

**Trạng thái:** kế hoạch để người sở hữu rà soát; chưa viết code giấy phép. HEAD cơ sở `463b92bd03ee0de2a53dcc85ded65b04d4bbd58f`, nhánh `update/vn-code-products-copy-20261011`.

## Global Constraints

- Cùng Java 25/JavaFX app, phiên bản 1.4.0, AppData và UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`; không sửa các installer/tag cũ.
- Email Gmail đã đăng ký và mật khẩu website; không Google OAuth, không truy cập Gmail, không lưu mật khẩu. Admin website duyệt/gia hạn/khóa.
- Mặc định 1 thiết bị và offline 72 giờ; admin chỉnh 1–10 máy và 6–168 giờ. Hạn lease không vượt hạn tài khoản; token 7 ngày không phải quyền 7 ngày.
- Public key pin tin cậy trong build; private signing key chỉ trên server. Không phát hành pin test, không property/server tùy chọn để bỏ kiểm tra.
- Device ID là SHA-256 SPKI public key thiết bị; private key/token/lease/clock anchor dùng DPAPI current-user, không plaintext fallback hoặc định danh IP/MAC/MachineGuid.
- Chữ ký xác minh exact byte trước parse; Unix giây UTC, dung sai rollback 300 giây, không gia hạn expiresAt bằng dung sai.
- Một license worker, một timer chung, hàng đợi hữu hạn và renew đồng gộp; không timer/session mỗi shop, không HTTP/crypto/DPAPI/file I/O trên JavaFX.
- HTTP origin cố định `https://vncode.id.vn`, timeout hữu hạn, response ≤65.536 byte, không redirect credential; không log mật khẩu/token/PII/body/private key.
- Xem local/lịch sử/xem trước bản in cũ/xuất dữ liệu/sao lưu/cài đặt tài khoản vẫn được dùng khi hết quyền. Không xóa journal, bỏ checkpoint request đã gửi hoặc tự gửi lại vì lỗi license.
- UI mới đồng bộ VI/RU/EN/ZH, giữ logo VN trắng nền đen, TN VED chạy theo tác vụ và bố cục đã duyệt.
- Chưa deploy Worker/D1 hoặc dùng account/key seller thật trong kiểm thử. Không phát hành 1.4.0 cho đến khi cả license backend, public pin và Windows acceptance đã đạt.

## Review Focus

1. Tài khoản được duyệt nhưng hết hạn hoặc JSON `active=true` không có lease hợp lệ: không mở quyền; metadata admin không thành đường tắt (Task 1/4).
2. Response login/renew chậm đến sau logout/đổi tài khoản: không phục hồi token/quyền, không ghi store hoặc UI cũ (Task 4/7).
3. Tác vụ chờ queue hoặc batch đang xử lý lúc quyền hết hạn: chặn phần chưa gửi, giữ kết quả/checkpoint và đối soát phần đã gửi (Task 5/6).
4. Store hỏng, sao chép sang Windows user khác hoặc đồng hồ lùi sau restart: không tạo quyền mới, vẫn xem/sao lưu dữ liệu và đăng nhập lại (Task 1/2/7).
5. Mười shop với license server chậm/429 và đổi màn/locale liên tục: chỉ một phiên/renew, queue hữu hạn, không chiếm SQL/image workers hoặc thêm listener sau dispose (Task 4/7).

---

## Cấu trúc và giao diện chung

Các đường dẫn Java mới dùng gốc `src/main/java/com/vncode/app/`:

| File | Trách nhiệm |
|---|---|
| `features/license/LicenseModels.java` | Nested immutable records/enums dùng chung, không chứa mạng/SQL/UI |
| `features/license/LicenseVerifier.java` | Pin, chữ ký, JSON nghiêm ngặt và ràng buộc lease |
| `features/license/LicenseClock.java` | Wall/monotonic/server anchor và phát hiện rollback |
| `features/license/LicenseStore.java` | Protected state atomic, giữ khóa thiết bị khi logout |
| `features/license/LicenseDeviceKeys.java` | Sinh/đọc Ed25519, SPKI hash và proof |
| `integration/license/LicenseApi.java` | Interface API để production adapter/mock cùng hợp đồng |
| `integration/license/WebLicenseApi.java` | Fixed-origin HTTP, phân loại lỗi, DTO parsing |
| `features/license/LicenseService.java` | State machine, single session, worker/timer/generation |
| `features/license/LicenseGate.java` | `require(Permission)` dùng clock và snapshot hiện tại; không gọi mạng |
| `features/license/LicenseRuntime.java` | Bootstrap một service trước FXML, close khi app dừng |
| `ui/license/LicenseAccountController.java` | UI tài khoản, chỉ publish state trên FX và dispose listeners |

`LicenseModels` định nghĩa:
- `Permission`: `SELLER_API`, `SHOP_MANAGE`, `GTIN`, `KIZ`, `LISTING_WRITE`, `PACKING`, `PRINT_NEW`, `GS1`; wire values đúng hợp đồng.
- `Status`: `SIGNED_OUT`, `CHECKING`, `ACTIVE`, `OFFLINE`, `PENDING`, `SUSPENDED`, `EXPIRED`, `DEVICE_LIMIT`, `DEVICE_REVOKED`, `SESSION_EXPIRED`, `UNVERIFIED`.
- `Envelope(String keyId,String payload,String signature)`; `Lease(int formatVersion,String product,String accountId,String devicePublicKeyHash,long licenseRevision,long issuedAt,long notBefore,long expiresAt,long subscriptionExpiresAt,int maxDevices,int offlineHours,Set<Permission> permissions)`.
- `Account(String id,String email,String status,Long expiresAt,int maxDevices,int offlineHours,int deviceCount)`; nullable expiry chỉ dùng hiển thị API cũ, không làm entitlement.
- `Snapshot(long generation,Status status,Account account,Lease lease,String errorCode)`; collection immutable; token/private key không có trong snapshot/toString.
- `PersistedState(int formatVersion,String devicePublicKey,String devicePrivateKey,String accessToken,Account account,Envelope envelope,long maxObservedEpoch,long minRevision,String denialCode)`; chỉ serialize bên trong DPAPI store, không toString chứa secret.
- `Purpose`: `ACTIVATE`, `RENEW`, `DEACTIVATE`; `Challenge(String id,String proof,long expiresAt,long serverTime,String accountId,String deviceHash,Purpose purpose)`; `Grant(Account account,Envelope lease)`; `Device(String id,long createdAt,long lastSeenAt,long lastLeaseExpiresAt,boolean current,boolean revoked)`; `Login(String accessToken,boolean active,String status,Long expiresAt)`.

Secret-bearing DTO (`Login`, `PersistedState`) có `toString` đã che nội dung; exception không chứa body/payload hoặc nguyên nhân parse có secret. Tất cả path `features/`, `integration/`, `ui/`, `shared/` trong Tasks là tương đối với gốc Java nêu trên; path `src/`, `tools/`, `docs/` là tương đối repo.

## Task 1: Lease verifier và đồng hồ không kéo dài quyền

**Files:** Create `features/license/{LicenseModels,LicenseVerifier,LicenseClock}.java`. Test `src/test/java/com/vncode/app/features/license/{LicenseTestFixtures,LicenseVerifierTest,LicenseClockTest}.java`.

**Interfaces:** `LicenseVerifier(Map<String,PublicKey> trustedKeys)`; `Lease verify(Envelope envelope,String accountId,String deviceHash,long minRevision,Instant effectiveNow)`. `LicenseClock(Clock wall,LongSupplier nanoTime,long maxObservedEpoch)`; `Instant now()`, `boolean rollbackDetected()`, `long maxObservedEpoch()`, `void observeVerifiedLease(Lease lease)`. Dùng `LicenseVerificationException` nested trong verifier, chỉ mã công khai. Test-only `LicenseTestFixtures.sign(Lease lease,KeyPair pair,String keyId):Envelope`, `LicenseTestFixtures.validLease(String accountId,String deviceHash,long issuedAt):Lease` tạo policy1máy/72h/revision1/quyền đầy đủ, tài khoản hết hạn sau7ngày; dùng key ID `test-fixture-v1`.

- [ ] **RED:** Test `unsignedActiveCannotGrantRights`, `editedPayloadOrSignatureFails`, `wrongAccountDeviceProductAndKeyFail`, `duplicateJsonFieldsAndMillisecondsFail`, `futureOrExpiredLeaseFails`, `revisionCannotGoBack`, `roleAdminWithoutFiniteExpiryFails`; assert các trường/quyền/time/range chính xác theo hợp đồng. Clock test: restart lùi 301 giây bị phát hiện, lùi 300 giây không kéo dài effectiveNow; monotonic tiến làm lease hết hạn dù wall đứng; expiresAt đúng thời điểm bị chặn.

```java
var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
String deviceHash = "ab".repeat(32);
var lease = LicenseTestFixtures.validLease("test-account", deviceHash, 1_791_763_200L);
var envelope = LicenseTestFixtures.sign(lease, pair, "test-fixture-v1");
var verifier = new LicenseVerifier(Map.of("test-fixture-v1", pair.getPublic()));
assertEquals(72, lease.offlineHours());
assertEquals(1, lease.maxDevices());
assertEquals(259_200, lease.expiresAt() - lease.issuedAt());
assertEquals(lease, verifier.verify(envelope, "test-account", deviceHash, 1,
        Instant.ofEpochSecond(lease.issuedAt())));
assertThrows(LicenseVerifier.LicenseVerificationException.class, () ->
        verifier.verify(envelope, "test-account", deviceHash, 1,
                Instant.ofEpochSecond(lease.expiresAt())));
```
- [ ] Run `DISPLAY=:99 /workspace/wcode-setup/mvn-bancapnhat -Dtest=LicenseVerifierTest,LicenseClockTest test`; expected fail vì chưa có implementation.
- [ ] Implement signatures trên: JDK `Signature.getInstance("Ed25519")`, Base64URL giới hạn, strict JSON duplicate/type detection sau chữ ký; phép cộng thời gian kiểm tra overflow. Clock lấy maximum của high-water, wall và elapsed monotonic, ghi nhận issuedAt chỉ sau verify; rollback là lỗi cần online, không sửa hạn.
- [ ] Run lại command; expected all tests pass, không skip.
- [ ] Commit `feat: verify signed account leases and bounded offline time` với các file Task 1.

## Task 2: DPAPI state và khóa thiết bị ổn định

**Files:** Create `features/license/{LicenseStore,LicenseDeviceKeys}.java`; modify `shared/WindowsSecretProtector.java`. Test `src/test/java/com/vncode/app/features/license/{LicenseStoreTest,LicenseDeviceKeysTest}.java`, `src/test/java/com/vncode/app/shared/WindowsSecretProtectorTest.java`.

**Interfaces:** `LicenseStore(Path directory,SecretCodec codec)`; nested `SecretCodec {String protect(String plain)throws IOException; String reveal(String protectedValue)throws IOException;}`; `Optional<PersistedState> load()`, `void save(PersistedState state)`, `void clearSession()`; path `AppPaths.appDataDir()/license/account-state.dpapi`, format 1, protected file ≤512 KiB, decrypted JSON ≤128 KiB. `static LicenseDeviceKeys loadOrCreate(LicenseStore store)`; `String publicKey()`, `String hash()`, `String sign(Challenge challenge)`. Preserve device pair/clock high-water on logout, remove token/account/envelope and denial session; missing/bad state has no lease.

- [ ] **RED:** Tests `ciphertextContainsNoTokenOrPrivateKey`, `protectFailureDoesNotWritePlaintext`, `truncatedOrOversizedStateNeverGrants`, `failedAtomicSaveRetainsOldState`, `logoutKeepsDeviceHashButDropsSession`, `copiedCiphertextFailsForAnotherCodecIdentity`, `proofBindsPurposeAndDevice`. Fake codec is test-only; Windows checks belong Task 7. Assert existing GS1 protection prefix/default entropy remain compatible.
- [ ] Run `DISPLAY=:99 /workspace/wcode-setup/mvn-bancapnhat -Dtest=LicenseStoreTest,LicenseDeviceKeysTest,WindowsSecretProtectorTest test`; expected RED.
- [ ] Add `WindowsSecretProtector(String purpose,int maxPlaintextBytes)` for purpose `VN code account v1`, max128 KiB and bounded ciphertext/stdout; default constructor keeps exact `VN code GS1 mail` entropy and8192-byte limit. Store stages/moves atomically with the app-data lock retained, sanitizes errors, never reads arbitrary path from API. Device key uses JDK generator/PKCS8/SPKI, validates public/private pair by a local sign/verify; challenge proof uses exact domain-separated bytes from contract.
- [ ] Run same targeted tests plus `Gs1MailClientTest,NewsTranslationServiceTest,ZnackCertificateSettingsRepositoryTest`; expected pass and no plaintext fallback.
- [ ] Commit `feat: protect account session and device identity with Windows DPAPI`.

## Task 3: API adapter và kiểm tra byte format giữa Java/Web Crypto

**Files:** Create `integration/license/{LicenseApi,WebLicenseApi}.java`, `src/test/java/com/vncode/app/integration/license/WebLicenseApiTest.java`, `src/test/java/com/vncode/app/features/license/LicenseCryptoInteropTest.java`, `tools/license-crypto-fixture.mjs`, `tools/license-crypto-fixture.test.mjs`.

**Interfaces:** `LicenseApi.login(String email,char[] password):Login`; `me(String token):Login`; `challenge(String token,String publicKey,Purpose purpose):Challenge`; `activate(String token,String publicKey,Challenge challenge,String signature):Grant`; `renew(String token,String publicKey,Challenge challenge,String signature):Grant`; `devices(String token):List<Device>`; `deactivate(String token,String publicKey,Challenge challenge,String signature):void`. All throw IOException; nested `LicenseApi.Failure(int httpStatus,String code,boolean retryable)` exposes safe fields, no raw body. Public `WebLicenseApi()` fixed origin; package-private `(OkHttpClient,HttpUrl)` only adapter tests use loopback.

- [ ] **RED:** MockWebServer tests exact endpoint/body/Bearer, epoch seconds/null old expiry, challenge binding/age, body >65.536 including chunked, redirect to another server makes zero requests there, 401/403/409/429/500 and malformed200 distinct. Email `seller+10@gmail.com` stays intact, Unicode password is not trimmed/logged. `LicenseCryptoInteropTest` checks Node-signed UTF-8 payload with Vietnamese/Cyrillic → JDK verify, and JDK-signed device proof → Node verify; reordered/tampered bytes fail. Keys generated for test, never pin production.
- [ ] Run Maven targeted API/interop tests and `node --test tools/license-crypto-fixture.test.mjs`; expected RED.
- [ ] Implement adapter timeout connect10s/read15s/call20s, no redirects/cookies/auth retry/interceptors exposing secrets. Limit request64 KiB, email254 chars, password1024 UTF-8 bytes without changing website password rules. No automatic login/activation/renew replay; client can get a new challenge once for expired/used nonce. Node fixture CLI accepts JSON on stdin and returns only fixture bytes/results, no real server access. Interop tests invoke Node22 and assert exit/output directly.
- [ ] Run same commands; expected pass, check token7days does not appear in lease duration calculation.
- [ ] Commit `feat: add bounded account and device API adapter`.

## Task 4: Một phiên và state machine chung cho mười shop

**Files:** Create `features/license/{LicenseService,LicenseGate,LicenseRuntime}.java`, tests `src/test/java/com/vncode/app/features/license/{LicenseServiceTest,LicenseGateTest,LicenseRuntimeTest,LicenseTestSupport,LicenseSuiteExtension}.java`; create test-only `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`, `src/test/resources/junit-platform.properties`. Modify `MainApplication.java` bootstrap/stop.

**Interfaces:** `LicenseService(LicenseApi api,LicenseVerifier verifier,LicenseStore store,LicenseClock clock)`; `CompletableFuture<Snapshot> start()`, `login(String email,char[] password)`, `refresh()`, `logout()`, `deactivateCurrentDevice(boolean confirmed)`; `CompletableFuture<List<Device>> devices()`; `Snapshot snapshot()`, `AutoCloseable subscribe(Consumer<Snapshot> listener)`, `LicenseGate gate()`, `void close()`. `LicenseGate.require(Permission permission)` throws safe nested `LicenseDeniedException`; no I/O. `LicenseRuntime.initialize(LicenseService service)` exactly once, `LicenseService get()`, `void close()`; `createProduction(Path directory)` uses fixed origin and production pin resource, no fallback.

- [ ] **RED:** Controlled futures prove `logoutBeatsLateLogin`, `newAccountBeatsLateRenew`, `denialThenServerErrorCannotRestoreLease`, `onlyRetryableFailureUsesStillValidCache`, `firstStartNeedsOnline`, `expiryCheckedAtGateNotOnlyTimer`, `dpapiWriteFailureDoesNotPublishNewRights`, `tenShopsShareOneRefresh`, `fullQueueReturnsBusy`, `closingRemovesListenersAndTimer`. 401/session-expired, malformed200, unknownpin and404 stay read-only; 429/5xx can use valid cache only. Missing permission denies only corresponding operation.
- [ ] Run targeted `LicenseServiceTest,LicenseGateTest,LicenseRuntimeTest` via Maven; expected RED.
- [ ] Implement 1 worker/queue16 with AbortPolicy, one scheduler and coalesced in-flight refresh. Timer heartbeat60s persists advanced high-water; network renew when6h deadline+jitter0–300s arrives. Nếu policy6h khiến lease kết thúc trước deadline này, renew trước expiresAt300giây, giữ tối thiểu60giây giữa lần thử và không gọi dồn khi hạn tài khoản không tăng. Test `shortestOfflinePolicyRenewsBeforeExpiry` chứng minh app online không tự dừng chỉ vì jitter; `nearSubscriptionExpiryDoesNotSpin` chứng minh worker/request hữu hạn. Retry backoff60s,5min,15min, then1h. Every request/store publish checks captured generation. A denial immediately makes gate read-only, then persists protected denial; subsequent transient errors never erase it. Login copies password into worker and clears its buffer in finally; no plaintext saved. Logout clears session before publishing signed-out; DPAPI failure remains signed-out and reports save failure.
- [ ] Production bootstrap before FXML creates read-only service without blocking; start loads/validates cache and performs startup online attempt on license worker. Trong lúc startup online chưa kết thúc, chỉ xem local; timeout/retryable mới chuyển OFFLINE nếu cache hợp lệ. Logout/account-switch và worker publish được serialize theo generation; store write cũ chưa hoàn tất không được sống lại sau logout. Mốc minRevision theo account, không mang revision account khác sang phiên mới. Shutdown waits no new license work, discards stale replies, does not cancel seller checkpoints. Pin missing/invalid fails closed with UNVERIFIED; production-ready build validation rejects missing pin in Task7.
- [ ] Test-only suite extension initializes one cryptographically valid fixture service for legacy unit tests; fixture keys/code remain `src/test`, no production flag/system property, no runtime switch disabling license. License-specific tests instantiate isolated services explicitly. Preserve zero skips and meaningful existing822 regression tests, rather than disabling assertions.
- [ ] Run targeted tests and `HomeControllerStartupRecoveryTest,FxmlSmokeTest`; expected pass. Commit `feat: share account entitlement across the desktop runtime`.

## Task 5: Gate đồng bộ và cấu hình tại service sau hàng đợi

**Files:** Modify `features/shop/{ShopWorkflow,ShopRepository}.java`, `integration/wb/WbSyncWorkflow.java`, `integration/ozon/OzonSyncWorkflow.java`, `features/products/ProductRefreshService.java`, `features/fbosupply/FboSupplySyncService.java`, `features/gtinsync/RegisteredGtinSyncService.java`, `features/news/OfficialNewsService.java`, `features/finance/FinanceSyncScheduler.java`, `integration/gs1/NationalCatalogGs1Client.java`, `integration/znack/ChestnyZnakBalanceClient.java`, `features/gs1/Gs1LettersService.java`; create `src/test/java/com/vncode/app/features/license/LicenseReadBoundaryTest.java`.

**Interfaces:** Existing signatures remain; add constructor overloads accepting `LicenseGate` where tests need isolated entitlement. Production defaults use `LicenseRuntime.get().gate()`. Task1–4 gate does no network wait, so local repositories and SQL read pool remain independent.

- [ ] **RED:** Expire entitlement while a sync waits behind another task; release queue and assert zero remote reads/new sync metadata. Direct service calls (without UI) for WB/Ozon/Products/FBO/GTIN/news/finance deny. Shop create/update/delete denied at repository boundary; `findAll/findById`, Products/Ozon local pages, cached news/finance/history/export/backup still work. Ten shop selections make zero license requests. Test active lease grants exact permissions, suspended/expired/missing permission does not.
- [ ] Run `LicenseReadBoundaryTest,ProductRefreshServiceTest,OzonManualSyncUiTest,HomeControllerStartupRecoveryTest`; expected RED.
- [ ] Insert gate after existing marketplace/owner validation and after queue/lock waits, before initiating remote work; check at each new page/attempt where a long refresh can outlive lease. Gate GS1/CZ new membership/quota/wallet/mail refresh; cached values still read-only. Shop repository guards only user mutations, not internal checkpoint/credential-safe readback. Finance/new auto sync catches typed denial and pauses scheduling; it must not mark API-error/retry indefinitely. Keep startup local views, cached page queries and accepted-job readback separate from new sync paths.
- [ ] Run targeted tests plus `ShopOperationCoordinatorTest,ShopSecretBoundaryTest`; expected pass. Commit `feat: enforce account rights before seller synchronization`.

## Task 6: Gate mutation, in mới và recovery không mất journal

**Files:** Modify `features/listingcopy/{ListingCopyService,ListingCopyRepository}.java`, `features/products/{ProductBulkEditService,ProductEditRepository}.java`, `features/gtinsync/{GtinSyncCoordinator,GtinSyncRepository}.java`, `features/packing/PackingWorkflow.java`, `integration/ozon/{OzonExemplarService,OzonShipService,OzonPrintBundleService,OzonLabelService}.java`, `features/print/{OrderExportWorkflow,KizAttachmentCoordinator}.java`, `features/fbo/FboKizPrintPlanner.java`, `integration/ozon/OzonFboKizPrintPlanner.java`, `integration/znack/{ZnackPurchaseCoordinator,ZnackKizOrderService,ZnackIntroductionService}.java`, `integration/znack/registration/{ZnackCardRegistrationWorkflow,ZnackCardRegistrationRepository}.java`, `features/gs1/{Gs1ApplicationService,Gs1LettersService,Gs1Repository}.java`; test `src/test/java/com/vncode/app/features/license/LicenseMutationBoundaryTest.java`, `LicenseRecoveryBoundaryTest.java` in same test package.

**Interfaces:** Existing service APIs/owner/journal schema retain compatibility. `LicenseGate.require` called in service, not only controller. Recovery uses existing `reconcile`, readback/status and frozen `finishAssets` paths; do not add generic public `skipLicense` boolean, all-access recovery scope or unowned job bypass.

| Service / entry | Permission / placement |
|---|---|
| Copy `draft/preview/publish`; bulk `preview/apply` | SELLER_API for fresh reads; LISTING_WRITE before each unsent card claim/write; `reconcile` allowed only submitted journal/owner |
| GTIN `confirm/runPending/resume`; registration `requestNewGtin/start/resume` | GTIN before enqueue and each not-yet-sent generate/feed/sign; already-submitted status/readback remains |
| KIZ `start/enqueue/retryInsufficientFunds/advance`, introduction `submit` | KIZ before a new purchase/introduction stage; resumed accepted order retrieval/status/checkpoint continues, new purchase retry needs rights |
| Packing create/add/deliver, Ozon exemplar/ship | PACKING before each new remote write; accepted ship/exemplar status only follows frozen checkpoint |
| WB export, Ozon export/exportAll, WB/Ozon FBO plan, new official label job | PRINT_NEW before new job and each unsent item; previously printed local history/preview remains available |
| WB KIZ attach queue | KIZ before each unsent metadata mutation after queue wait; success/ambiguous response checkpoint retained |
| GS1 new signing/submit, letter draft/send/payment-proof preparation | GS1 before new request; already-sent reconcile/replies keep frozen request and no resend |

- [ ] **RED:** Typed tests for each family call service directly with denied gate, assert zero new HTTP/SMTP/write/consume. Bulk100cards: revoke after card1accepted, card2notclaimed/sent and card1remotecheckpoint retained. Queued copy revokes beforeclaim with draft unchanged. Expire immediately before first remote write: mark known-unsent state, not ambiguous RECONCILE_REQUIRED. Expire during accepted request: store actual result even if UI switched/logout; reconcile does not resubmit or drop source/destination locks. Registration/GS1 accepted stages can readback/finish existing frozen request, but recovery cannot create new GTIN/order/card or retry not-sent work. Ozon new print blocked before consuming KIZ; local old-print preview/export remains.
- [ ] Run targeted boundary/recovery tests, `ListingCopyServiceTest,ProductBulkEditServiceTest,GtinSyncCoordinatorTest,ZnackCardRegistrationWorkflowTest,OzonShipServiceTest,OzonPrintBundleServiceTest,Gs1ApplicationServiceTest`; expected RED for new boundary assertions.
- [ ] Implement checks before durable new-work claim and immediately before write after preflight/queue; handle `LicenseDeniedException` outside catches that mark uncertain remote outcomes. Checkpoint/result writes themselves are never license-gated. Claimed-but-known-unsent copy/bulk returns AWAITING_CONFIRMATION by owner/hash-checked transition, releases its pending claim and never creates a fake remote identity; GTIN/registration/KIZ use current paused/not-sent stages without changing schema5. Denial during accepted HTTP preserves its actual checkpoint. For accepted journals, owner and durable phase—not UI checkbox/account role—permit only existing recovery calls. Known-unsent work pauses for restored rights and does not auto-resume just because app restarts; owner confirmation/idempotency remain.
- [ ] Audit direct controller→API/mutation/print callers with `rg` and wire each new-work entry through the guarded service; add missing boundary cases to this same test class before adding guard. Preserve source/destination retention, readonly local history, actual KIZ inventory and paper flow. Run targeted suite; expected pass, no duplicate mutation. Commit `feat: enforce seller task rights while retaining accepted-job recovery`.

## Task 7: UI tài khoản, native proof và cổng phát hành

**Files:** Create `ui/license/LicenseAccountController.java`, `src/main/resources/com/vncode/app/ui/license/license-account-view.fxml`, `src/main/resources/com/vncode/app/license/trusted-keys.json`, `src/test/java/com/vncode/app/ui/license/LicenseAccountUiTest.java`, `tools/WindowsLicenseProbe.java`, `tools/windows-license-smoke.ps1`, `tools/license-release-contract.test.mjs`, `docs/validation/VN-code-1.4.0-license-acceptance.json`. Modify `ui/workspace/HomeController.java`, `ui/shop/ShopSidebarController.java`, `src/main/resources/com/vncode/app/ui/shop/shop-sidebar-view.fxml`, `src/main/resources/com/vncode/app/i18n/messages_{vi,ru,en,zh}.properties`, `tools/SellerWorkspaceProbe.java`, `tools/publish-vncode-preview.mjs`, `.github/workflows/build-java.yml`, `docs/releases/VN-code-1.4.0-status.json`, `docs/releases/VN-code-1.4.0.md`, `README.md`.

**Interfaces:** Controller `setService(LicenseService service)`, `dispose()`; subscriber/UI updates use `Platform.runLater` with generation/dispose check. Settings entry becomes Tài khoản VN code, showing website email/account status/account expiry/offline expiry/device usage; actions login/check/devices/deactivate current/log out/open fixed `https://vncode.id.vn/dashboard`. Native probe emits safe JSON `{sourceCommit,appVersion,deviceDpapiPassed,ciphertextOnly,foreignIdentityRejected,clockRollbackRejected,readonlyDataPreserved,singleSessionPassed,productionPinPresent}` plus SHA proof, never secrets. Publisher requires exact source build+license proof, production pin, backend acceptance evidence and existing native upgrade/workspace proofs.

- [ ] **RED:** `LicenseAccountUiTest` covers pending/suspended/offline/expired/device-limit distinct copy, false metadata active, all4locales, close during request and repeated20open/close listener count0→0. Ten shops/slow429server: cached navigation remains responsive and one refresh; mock is test service injected, not runtime license-disable flag. Node `tools/license-release-contract.test.mjs` rejects missing/fixture-only pin, missing/mismatched native license proof/backend acceptance source, or old run proof. FXML smoke loads new view.
- [ ] Run Maven UI/FXML targeted and `node --test tools/license-release-contract.test.mjs`; expected RED.
- [ ] Implement UI without blocking/secret logs; replace current personal-edition status for1.4.0 with real account state, do not modify old assets/tags. Commit empty production trust map while backend key is pending; service stays read-only and publisher explicitly rejects it. Add real public pin only after it is independently matched to owner's server key; never include private/test key. Native probe runs real current-user DPAPI, malformed/cross-purpose protected data rejection, injected clock tests and test-only verifier fixture outside production app. Different-user/machine proof must come from an actual second Windows identity; missing capability is unrun and blocks that gate, not pass.
- [ ] Existing workspace benchmark can inject fixture service into test/probe JVM via normal constructor/bootstrap, with no new production flag; production launcher remains pinned. Upgrade/coinstall proof still validates same identity/schema/data with app unauthenticated read-only. Website acceptance uses owner-provided disposable test account to approve/login/renew/expire/suspend/offline/revoke; record source and signing key ID only, no password/Bearer. Do not create/change real admin/seller accounts or deploy backend merely to pass CI.
- [ ] Run targeted, then `DISPLAY=:99 /workspace/wcode-setup/mvn-bancapnhat clean verify` and `node --test tools/*.test.mjs`. One fresh whole-license-plan reviewer per executing-plans; regrade and fix Critical/Important once, no looped review. Dispatch exact-source Windows build only after local review fixes pass; preserve existing six workspace/DPI probes, upgrade1.1.34/1.2.0/1.2.1/1.2.2/1.3.0 and side-by-side gates.
- [ ] Commit `feat: add account settings and verified Windows licensing release gate`; push same update branch. Publish preview/manual upgrade only when source/pin/backend/Windows gates are all met; no main merge or rewriting old release, no unsigned automatic feed.

## Backend handoff và thứ tự thực hiện

Task1–7 client/mock/local tests có thể bắt đầu sau khi người sở hữu duyệt kế hoạch này. Native checks nào không cần production endpoint có thể chạy trước; **không biến kết quả mock thành nghiệm thu website**. Giữ status `complete:false` và giấy phép chưa production-ready trong mọi commit trung gian.

Khi nhận Worker/schema/package/tests thật: đọc router/Bearer/admin authorization, mapping ID/expiry và transaction hiện tại; lập **kế hoạch backend riêng với đường dẫn/hàm/migration thật** để rà soát. Plan backend phải test quota cạnh tranh/nonce replay/admin authorization/migration DB cũ/revoke/session/password/finite owner entitlement trong runtime Workers/D1. Nếu API hiện có tương đương, sửa adapter/hợp đồng tương thích thay vì thêm endpoint cạnh tranh. Kế hoạch client này không cho phép đoán schema hoặc deploy website.

## Tự rà soát kế hoạch

- Coverage: account/admin authority → Task3/4 + backend gate; proof/device/offline/clock → Task1/2/3; bounded session → Task4; service gates/recovery → Task5/6; UI/locales/identity/Windows → Task7.
- Interface names/types được định nghĩa ở cấu trúc chung và Interfaces từng task; source backend chưa có được đặt thành prerequisite rõ ràng, không hàm/bảng backend giả.
- Năm Review Focus đều có ca RED trong task sở hữu; không dùng JSON `active`, test pin hoặc token7days để mở offline.
- Local test không thay DPAPI/second identity/Workers/D1/live account acceptance; missing proof chặn phát hành thay vì đánh dấu hoàn tất.
- Chỉ thêm kế hoạch và hợp đồng ở bước hiện tại; không sửa product code/dependency trước review kế hoạch theo `writing-plans`.
