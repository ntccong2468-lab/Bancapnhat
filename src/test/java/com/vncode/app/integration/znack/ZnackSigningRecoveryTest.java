package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.products.ProductJson;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real persisted jobs and signing-consent guard; all signers/API data are synthetic and local. */
class ZnackSigningRecoveryTest {
    private static final int SHOP = 90141, OTHER_SHOP = 90142;
    private static final String GTIN = "04601234567890";
    private static final String OWNER = "7700000001";
    private static final String FINGERPRINT = "A".repeat(40);
    @TempDir Path directory;
    private ZnackRepository repository;
    private Settings original;

    @BeforeEach void setup() throws Exception {
        ZnackSigningSession.resetForTests();
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,api_key) VALUES(" + SHOP + ",'Synthetic one','fixture-one'),(" + OTHER_SHOP + ",'Synthetic two','fixture-two')");
        }
        repository = new ZnackRepository(new ShopContext(SHOP, "Synthetic one"));
        original = settings("original-oms", "synthetic-connection", FINGERPRINT,
                Instant.parse("2026-01-01T00:00:00Z"), true);
        repository.saveSettings(original);
        repository.upsertProducts(List.of(new Product(GTIN, "Synthetic product", "6103100000",
                "CONFORMITY_DECLARATION", "FIXTURE-PERMIT", "01.01.2026", null)));
    }

    @AfterEach void cleanup() {
        ZnackPipelineQueueExecutor.shutdownAll();
        ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void pausedPaidReadinessDoesNotOpenSignerOrScheduleAutomaticRetries() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, null);
        var harness = new Harness(repository);

        pause(harness, job);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    () -> harness.coordinator.advance(original, job.pipeline()));
            harness.coordinator.schedule(job.pipeline());
        }

        assertEquals(0, harness.signatures.get(), "A paused job must not reopen CryptoPro on each poll");
        assertTrue(ZnackPurchaseCoordinator.isSigningConfirmationPending(SHOP, job.pipeline(),
                pipeline(job).stage().name(), pipeline(job).errorMessage()));
        assertNull(ZnackPurchaseCoordinator.nextCheckAt(SHOP, job.pipeline()));
        assertFalse(ZnackPurchaseCoordinator.isScheduledForTests(SHOP, job.pipeline()));
        assertEquals(0, new ZnackGtinInventoryService().availableCount(SHOP, GTIN));
        assertPreserved(job, harness);
    }

    @Test void foregroundResumeKeepsPaidIdentityRawCodesAndConsumedInventory() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, null);
        var originalCodes = repository.findCodes(job.order());
        try (var connection = Database.getConnection();
             var update = connection.prepareStatement("UPDATE kiz_codes SET status='CONSUMED' WHERE shop_id=" + SHOP + " AND id=?")) {
            update.setLong(1, originalCodes.getFirst().id());
            assertEquals(1, update.executeUpdate());
        }
        var harness = new Harness(repository);
        pause(harness, job);

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(originalCodes.stream().map(KizCode::rawCode).toList(),
                repository.findCodes(job.order()).stream().map(KizCode::rawCode).toList());
        assertEquals(KizInventoryStatus.CONSUMED, repository.findCodes(job.order()).getFirst().inventoryStatus());
        assertEquals(1, new ZnackGtinInventoryService().availableCount(SHOP, GTIN),
                "Confirming legal circulation must not make an already consumed code available again");
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP, job.pipeline()));
        assertEquals(1, harness.signatures.get());
    }

    @ParameterizedTest @ValueSource(strings = {"DOWNLOADING_CODES", "POLLING_ORDER"})
    void foregroundResumeDownloadsOnlyFromTheExistingPaidOrder(String stage) throws Exception {
        var job = purchase(PurchaseStage.valueOf(stage), 2, 0, ZnackSigningSession.WAITING_MESSAGE);
        var harness = new Harness(repository);

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(1, harness.downloads.get());
        assertEquals(2, repository.findCodes(job.order()).size());
        assertEquals(codes(job.order(), 2), repository.findCodes(job.order()).stream().map(KizCode::rawCode).toList());
        assertEquals(2, new ZnackGtinInventoryService().availableCount(SHOP, GTIN));
    }

    @ParameterizedTest @ValueSource(strings = {"VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER"})
    void oldPreSendStageWithAnAcceptedOrderResumesThatOrderWithoutCreatingAnother(String stage) throws Exception {
        var job = purchase(PurchaseStage.valueOf(stage), 2, 0, ZnackSigningSession.WAITING_MESSAGE);
        var harness = new Harness(repository);

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(1, harness.orderReads.get());
        assertEquals(1, harness.downloads.get());
    }

    @Test void deniedLicensePreservesThePauseAndAllPaidDataWithoutGrantingSigning() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, null);
        var harness = new Harness(repository);
        pause(harness, job);
        var before = pipeline(job);
        var codesBefore = repository.findCodes(job.order());
        var previousLicense = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied-license"))) {
            try {
                LicenseRuntime.install(denied);
                assertThrows(LicenseDeniedException.class, () -> harness.coordinator.resumeSigning(job.pipeline()));
            } finally {
                LicenseRuntime.install(previousLicense);
            }
        }

        assertEquals(before, pipeline(job));
        assertEquals(codesBefore, repository.findCodes(job.order()));
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP, job.pipeline()));
        assertEquals(0, harness.signatures.get());
        assertEquals(0, harness.readbacks.get());
        assertPreserved(job, harness);
    }

    @Test void missingOriginalConnectionCannotBorrowCurrentSettingsOrGrantConsent() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, ZnackSigningSession.WAITING_MESSAGE);
        try (var connection = Database.getConnection();
             var remove = connection.prepareStatement("DELETE FROM znack_pipeline_connections WHERE pipeline_id=?")) {
            remove.setLong(1, job.pipeline());
            assertEquals(1, remove.executeUpdate());
        }
        try (var connection = Database.getConnection();
             var remove = connection.prepareStatement("DELETE FROM znack_purchase_contexts WHERE shop_id=? AND pipeline_id=?")) {
            remove.setInt(1,SHOP);remove.setLong(2,job.pipeline());
            assertEquals(1,remove.executeUpdate(),"The canonical original profile must also be absent in this negative fixture");
        }
        var harness = new Harness(repository);
        var before = pipeline(job);

        var failure = assertThrows(IllegalStateException.class,
                () -> harness.coordinator.resumeSigning(job.pipeline()));

        assertEquals("KIZ_CIRCULATION_ORIGINAL_PROFILE_MISSING", failure.getMessage());
        assertTrue(repository.boundPipelineSettings(job.pipeline()).isEmpty());
        assertEquals(before, pipeline(job));
        assertEquals(0, harness.signatures.get());
        assertPreserved(job, harness);
        assertNotAuthorized(harness, job.pipeline());
    }

    @ParameterizedTest @ValueSource(strings = {"certificate-missing", "certificate-unverified", "oms-connection-missing"})
    void invalidOriginalSigningConfigurationCannotBeReplacedOrAuthorized(String invalidPart) throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, ZnackSigningSession.WAITING_MESSAGE);
        Settings invalid = settings("original-oms", "oms-connection-missing".equals(invalidPart) ? "" : "synthetic-connection",
                "certificate-missing".equals(invalidPart) ? "" : FINGERPRINT,
                "certificate-unverified".equals(invalidPart) ? null : original.signerTestedAt(), true);
        replaceOriginalConnection(job, invalid);
        var before = pipeline(job);
        var harness = new Harness(repository);

        assertThrows(IllegalStateException.class, () -> harness.coordinator.resumeSigning(job.pipeline()));

        assertEquals(before, pipeline(job));
        assertEquals(invalid, repository.boundPipelineSettings(job.pipeline()).orElseThrow());
        assertEquals(original, repository.getSettings());
        assertEquals(0, harness.signatures.get());
        assertPreserved(job, harness);
        assertNotAuthorized(harness, job.pipeline());
    }

    @Test void aPipelineIdFromAnotherShopCannotMutateOrAuthorizeEitherShop() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, ZnackSigningSession.WAITING_MESSAGE);
        var before = pipeline(job);
        var foreignRepository = new ZnackRepository(new ShopContext(OTHER_SHOP, "Synthetic two"));
        var foreign = new Harness(foreignRepository);
        var local = new Harness(repository);

        assertThrows(IllegalArgumentException.class, () -> foreign.coordinator.resumeSigning(job.pipeline()));

        assertEquals(before, pipeline(job));
        assertTrue(foreignRepository.findOrders().isEmpty());
        assertPreserved(job, local);
        assertNotAuthorized(local, job.pipeline());
        assertNotAuthorized(foreign, job.pipeline());
    }

    @ParameterizedTest @ValueSource(strings = {"COMPLETED", "INTRODUCED", "FAILED", "INTRODUCTION_FAILED", "WAITING_INTRODUCTION_DOCUMENTS"})
    void closedOrDifferentRecoveryStatesCannotUseTheSigningResumeAction(String stage) throws Exception {
        var job = purchase(PurchaseStage.valueOf(stage), 1, 1, ZnackSigningSession.WAITING_MESSAGE);
        var before = pipeline(job);
        var harness = new Harness(repository);

        assertThrows(IllegalStateException.class, () -> harness.coordinator.resumeSigning(job.pipeline()));

        assertEquals(before, pipeline(job));
        assertEquals(0, harness.signatures.get());
        assertPreserved(job, harness);
        assertNotAuthorized(harness, job.pipeline());
    }

    @Test void anActiveJobWithAnUnrelatedErrorCannotTurnTheActionIntoSigningConsent() throws Exception {
        var job = purchase(PurchaseStage.DOWNLOADING_CODES, 1, 0,
                "Remote server said Waiting for the user to select this shop before signing.");
        var before = pipeline(job);
        var harness = new Harness(repository);

        assertFalse(ZnackPurchaseCoordinator.isSigningConfirmationPending(SHOP, job.pipeline(),
                before.stage().name(), before.errorMessage()));
        assertThrows(IllegalStateException.class, () -> harness.coordinator.resumeSigning(job.pipeline()));
        assertEquals(before, pipeline(job));
        assertPreserved(job, harness);
        assertNotAuthorized(harness, job.pipeline());
    }

    @Test void aMissingJobCannotGrantShopOrPipelineSigningConsent() {
        var harness = new Harness(repository);

        assertThrows(IllegalArgumentException.class, () -> harness.coordinator.resumeSigning(90901));

        assertTrue(repository.findOrders().isEmpty());
        assertTrue(repository.findActivePipelines().isEmpty());
        assertNotAuthorized(harness, 90901);
        assertEquals(0, harness.signatures.get());
    }

    @Test void aRealSignerCancellationIsPersistedWithoutAutomaticRetryAndCanBeExplicitlyResumed() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, null);
        var harness = new Harness(repository);
        ZnackSigningSession.authorizePipeline(SHOP, job.pipeline());
        harness.cancellationsRemaining.set(1);

        var cancellation = assertThrows(CryptoProException.class,
                () -> harness.coordinator.advance(original, job.pipeline()));
        assertEquals(CryptoProErrorCode.CANCELLED, cancellation.code());
        assertTrue(ZnackSigningSession.isSigningDeferred(pipeline(job).errorMessage()));
        assertTrue(pipeline(job).errorMessage().endsWith("[CRYPTOPRO_CANCELLED]"));
        for (int poll = 0; poll < 3; poll++) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    () -> harness.coordinator.advance(original, job.pipeline()));
            harness.coordinator.schedule(job.pipeline());
        }
        assertEquals(1, harness.signatures.get(), "Cancellation must not reopen PIN/signing dialogs automatically");
        assertNull(ZnackPurchaseCoordinator.nextCheckAt(SHOP, job.pipeline()));

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(2, harness.signatures.get());
    }

    @ParameterizedTest @ValueSource(strings = {"legacy", "cause"})
    void savedPauseAfterProcessRestartNeedsAndAcceptsForegroundConsent(String marker) throws Exception {
        String reason = ZnackSigningSession.WAITING_MESSAGE
                + ("cause".equals(marker) ? " [CRYPTOPRO_CANCELLED]" : "");
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, reason);
        ZnackSigningSession.resetForTests();
        var harness = new Harness(repository);
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP, job.pipeline()));
        assertTrue(ZnackPurchaseCoordinator.isSigningConfirmationPending(SHOP, job.pipeline(),
                pipeline(job).stage().name(), pipeline(job).errorMessage()));
        assertEquals(0, harness.signatures.get());

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(1, harness.signatures.get());
    }

    @Test void laterGlobalConnectionChangesCannotRedirectAResumeToTheNewOmsOrCertificate() throws Exception {
        var job = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 2, 2, ZnackSigningSession.WAITING_MESSAGE);
        var replacement = settings("different-oms", "different-connection", "B".repeat(40),
                original.signerTestedAt(), true);
        repository.saveSettings(replacement);
        var harness = new Harness(repository);

        harness.coordinator.resumeSigning(job.pipeline());

        assertIntroduced(job, harness);
        assertEquals(List.of(original), harness.observedProfiles);
        assertEquals(original, repository.boundPipelineSettings(job.pipeline()).orElseThrow());
        assertEquals(replacement, repository.getSettings());
    }

    @Test void resumingOneLotDoesNotUnblockAnUnvisitedSibling() throws Exception {
        var first = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, null);
        var sibling = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, null);
        var selected = new Harness(repository);
        var other = new Harness(repository);
        ZnackSigningSession.authorizePipeline(SHOP, first.pipeline());
        ZnackSigningSession.authorizePipeline(SHOP, sibling.pipeline());
        selected.cancellationsRemaining.set(1);
        assertThrows(CryptoProException.class, () -> selected.coordinator.advance(original, first.pipeline()));
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP, first.pipeline()));
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP, sibling.pipeline()),
                "The sibling has not been polled after the shop-wide signer cancellation");
        var siblingBefore = pipeline(sibling);
        var siblingCodes = repository.findCodes(sibling.order());

        selected.coordinator.resumeSigning(first.pipeline());

        assertIntroduced(first, selected, 2);
        try (var scope = ZnackSigningSession.openPipeline(SHOP, sibling.pipeline())) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    () -> other.guarded.sign("synthetic sibling challenge".getBytes(StandardCharsets.UTF_8),
                            ZnackSignatureContext.AUTH_CHALLENGE));
        }
        assertEquals(0, other.signatures.get(), "Retrying one lot cannot renew an unvisited sibling's consent");
        assertEquals(siblingBefore, pipeline(sibling));
        assertEquals(siblingCodes, repository.findCodes(sibling.order()));
        assertEquals(0, other.buys.get());
        assertEquals(0, other.submissions.get());
    }

    @Test void confirmingOneJobLeavesAnotherAlreadyPausedJobWaiting() throws Exception {
        var first = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, null);
        var second = purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS, 1, 1, null);
        var selected = new Harness(repository);
        var other = new Harness(repository);
        pause(selected, first);
        pause(other, second);
        var secondBefore = pipeline(second);
        var secondCodes = repository.findCodes(second.order());

        selected.coordinator.resumeSigning(first.pipeline());

        assertIntroduced(first, selected, 2);
        assertEquals(secondBefore, pipeline(second));
        assertEquals(secondCodes, repository.findCodes(second.order()));
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP, second.pipeline()));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> other.coordinator.advance(original, second.pipeline()));
        other.coordinator.schedule(second.pipeline());
        assertEquals(0, other.signatures.get());
        assertNull(ZnackPurchaseCoordinator.nextCheckAt(SHOP, second.pipeline()));
        assertEquals(0, other.buys.get());
        assertEquals(0, other.submissions.get());
    }

    private void pause(Harness harness, Job job) throws Exception {
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> harness.coordinator.advance(original, job.pipeline()));
        assertTrue(ZnackSigningSession.isWaitingForSignature(harness.repo.shop().shopId(), job.pipeline()));
        assertTrue(ZnackSigningSession.isSigningDeferred(pipeline(job).errorMessage()));
    }

    private void assertNotAuthorized(Harness harness, long pipelineId) {
        try (var scope = ZnackSigningSession.openPipeline(harness.repo.shop().shopId(), pipelineId)) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    () -> harness.guarded.sign("synthetic challenge".getBytes(StandardCharsets.UTF_8),
                            ZnackSignatureContext.AUTH_CHALLENGE));
        }
        assertEquals(0, harness.signatures.get());
    }

    private void assertIntroduced(Job job, Harness harness) { assertIntroduced(job, harness, 1); }

    private void assertIntroduced(Job job, Harness harness, int totalOrders) {
        assertEquals(PurchaseStage.INTRODUCED, pipeline(job).stage());
        assertEquals(OrderStatus.INTRODUCED, repository.findOrder(job.order()).orElseThrow().localStatus());
        assertEquals(job.quantity(), repository.findCodes(job.order()).size());
        assertTrue(repository.findCodes(job.order()).stream().allMatch(c -> c.legalStatus() == KizLegalStatus.IN_CIRCULATION));
        assertPreserved(job, harness, totalOrders);
        assertTrue(harness.signContexts.stream().allMatch(c -> c == ZnackSignatureContext.AUTH_CHALLENGE));
    }

    private void assertPreserved(Job job, Harness harness) { assertPreserved(job, harness, 1); }

    private void assertPreserved(Job job, Harness harness, int totalOrders) {
        var saved = pipeline(job);
        assertEquals(job.order(), saved.orderId());
        assertEquals(GTIN, saved.gtin());
        assertEquals(job.quantity(), saved.quantity());
        assertEquals(job.pipeline(), repository.findPipelineByRequestKey(job.requestKey()).orElseThrow().id());
        var order = repository.findOrder(job.order()).orElseThrow();
        assertEquals("synthetic-order-" + job.order(), order.externalOrderId());
        assertEquals(GTIN, order.gtin());
        assertEquals(job.quantity(), order.quantity());
        assertEquals(totalOrders, repository.findOrders().size());
        assertTrue(repository.findLatestDocument(job.order()).isEmpty());
        assertEquals(0, harness.buys.get(), "An explicit signing retry must not enqueue/create a new paid order");
        assertEquals(0, harness.submissions.get(), "Already INTRODUCED fixture codes must not create another document");
    }

    private ZnackPurchasePipelineState pipeline(Job job) { return repository.findPipeline(job.pipeline()).orElseThrow(); }

    private Job purchase(PurchaseStage stage, int quantity, int downloaded, String error) {
        long order = repository.createDraft(GTIN, quantity);
        repository.updateOrder(order, "synthetic-order-" + order, "READY", OrderStatus.CODES_DOWNLOADED, null);
        String request = UUID.randomUUID().toString();
        long pipeline = repository.enqueuePipeline(GTIN, quantity, request);
        repository.updatePipeline(pipeline, order, stage, error);
        if (downloaded > 0) repository.insertCodes(order, GTIN, new DownloadedCodes(codes(order, downloaded), "synthetic-block"));
        return new Job(pipeline, order, quantity, request);
    }

    private static List<String> codes(long order, int quantity) {
        var result = new ArrayList<String>();
        for (int i = 0; i < quantity; i++) result.add("01" + GTIN + "21"
                + String.format(Locale.ROOT, "%013d", order * 100 + i) + "\u001d91ABCD\u001d92fixture");
        return result;
    }

    private void replaceOriginalConnection(Job job, Settings settings) throws Exception {
        try (var connection = Database.getConnection();
             var update = connection.prepareStatement("UPDATE znack_pipeline_connections SET settings_json=? WHERE shop_id=" + SHOP + " AND pipeline_id=?")) {
            update.setString(1, ProductJson.GSON.toJson(settings));
            update.setLong(2, job.pipeline());
            assertEquals(1, update.executeUpdate());
        }
        var context=new ZnackPurchaseContext(settings,ZnackProductGroup.LP,ZnackPurchaseMethod.PRODUCTION);
        try (var connection=Database.getConnection();
             var update=connection.prepareStatement("UPDATE znack_purchase_contexts SET settings_json=?,identity_key=? WHERE shop_id=? AND pipeline_id=?")) {
            update.setString(1,ProductJson.GSON.toJson(settings));update.setString(2,context.identity());
            update.setInt(3,SHOP);update.setLong(4,job.pipeline());
            assertEquals(1,update.executeUpdate(),"Mutate the canonical original profile, preserving the intended negative case");
        }
    }

    private Settings settings(String oms, String connection, String fingerprint, Instant testedAt, boolean auto) {
        return new Settings("http://127.0.0.1:9", "http://127.0.0.1:9", oms, connection,
                OWNER, "", OWNER, "", fingerprint, "[]", "FIXTURE-PERMIT", "01.01.2026", "", auto,
                "", "[]", "{}", testedAt, "", directory.resolve("never-executed-cryptcp").toString(),
                "", 60, "31.12.2030", "CONFORMITY_DECLARATION");
    }

    private record Job(long pipeline, long order, int quantity, String requestKey) {}

    private final class Harness {
        final ZnackRepository repo;
        final AtomicInteger signatures = new AtomicInteger(), cancellationsRemaining = new AtomicInteger();
        final AtomicInteger buys = new AtomicInteger(), submissions = new AtomicInteger();
        final AtomicInteger downloads = new AtomicInteger(), orderReads = new AtomicInteger(), readbacks = new AtomicInteger();
        final List<Settings> observedProfiles = new ArrayList<>();
        final List<ZnackSignatureContext> signContexts = new ArrayList<>();
        final ZnackSignatureProvider guarded;
        final ZnackPurchaseCoordinator coordinator;

        Harness(ZnackRepository repo) {
            this.repo = repo;
            guarded = ZnackSigningSession.guard(repo.shop().shopId(), FINGERPRINT, (payload, context) -> {
                signatures.incrementAndGet();
                signContexts.add(context);
                assertEquals(ZnackSignatureContext.AUTH_CHALLENGE, context);
                if (cancellationsRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                    throw new CryptoProException(CryptoProErrorCode.CANCELLED, "Synthetic PIN cancellation");
                return new CryptoProSigningResult(new byte[]{1}, "synthetic fixture");
            });
            var api = new ZnackApiClient() {
                @Override public JsonElement cisesInfo(String base, String token, JsonElement body) {
                    readbacks.incrementAndGet();
                    var response = new JsonArray();
                    for (var requested : body.getAsJsonArray()) {
                        var info = new JsonObject();
                        info.addProperty("requestedCis", requested.getAsString());
                        info.addProperty("cis", requested.getAsString());
                        info.addProperty("gtin", GTIN);
                        info.addProperty("status", "INTRODUCED");
                        info.addProperty("statusEx", "EMPTY");
                        info.addProperty("ownerInn", OWNER);
                        info.addProperty("productName", "Synthetic product");
                        var row = new JsonObject(); row.add("cisInfo", info); response.add(row);
                    }
                    return response;
                }
                @Override public JsonElement productCards(String base, String token, String gtin) {
                    throw new AssertionError("Already circulating codes must not need a new card/document submission");
                }
            };
            var auth = new ZnackAuthService(api, guarded) {
                @Override public String trueApiToken(Settings settings) throws Exception {
                    observedProfiles.add(settings);
                    guarded.sign("synthetic True API challenge".getBytes(StandardCharsets.UTF_8), ZnackSignatureContext.AUTH_CHALLENGE);
                    return "synthetic-true-token";
                }
                @Override public String suzToken(Settings settings) throws Exception {
                    observedProfiles.add(settings);
                    guarded.sign("synthetic SUZ challenge".getBytes(StandardCharsets.UTF_8), ZnackSignatureContext.AUTH_CHALLENGE);
                    return "synthetic-suz-token";
                }
            };
            var orders = new ZnackKizOrderService(null, auth, guarded, repo) {
                @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings settings,long batchId) {
                    buys.incrementAndGet();throw new AssertionError("Signing recovery cannot submit a purchase batch");
                }
                @Override public KizOrder buy(Settings settings, String gtin, int quantity) {
                    buys.incrementAndGet(); throw new AssertionError("Signing recovery cannot buy another order");
                }
                @Override public KizOrder refresh(Settings settings, long id) throws Exception {
                    auth.suzToken(settings); orderReads.incrementAndGet();
                    repo.updateOrder(id, null, "READY", OrderStatus.CODES_READY, null);
                    return repo.findOrder(id).orElseThrow();
                }
                @Override public java.util.OptionalInt availableCodes(long id) {
                    return java.util.OptionalInt.of(repo.findOrder(id).orElseThrow().quantity());
                }
            };
            var downloaded = new ZnackKizCodeService(null, auth, repo) {
                @Override public int download(Settings settings,long id,int available)throws Exception {
                    return download(settings,id);
                }
                @Override public int download(Settings settings, long id) throws Exception {
                    auth.suzToken(settings); downloads.incrementAndGet();
                    return repo.insertCodes(id, GTIN, new DownloadedCodes(ZnackSigningRecoveryTest.codes(id,
                            repo.findOrder(id).orElseThrow().quantity()), "synthetic-block"));
                }
            };
            var introduction = new ZnackIntroductionService(null, auth, guarded, repo) {
                @Override public long submit(Settings settings, KizOrder order, Product product, List<KizCode> codes) {
                    submissions.incrementAndGet(); throw new AssertionError("An already circulating batch cannot be submitted again");
                }
            };
            coordinator = new ZnackPurchaseCoordinator(repo, orders, downloaded, introduction,
                    new ZnackIntroductionReadinessService(api, auth, repo));
        }
    }
}
