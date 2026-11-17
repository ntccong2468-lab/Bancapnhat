package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite emission checkpoints with synthetic API/auth/signers only; never sends an HTTP request. */
@Timeout(15)
class OwnerBBatchEngineTest {
    private static final int SHOP = 93501;
    private static final String OWNER = "7700000001";
    @TempDir Path directory;
    private ZnackRepository repository;
    private ZnackPurchaseBatchStore store;
    private Settings settings;
    private RecordingApi api;
    private RecordingAuth auth;
    private RecordingSigner signer;
    private ZnackKizOrderService service;

    @BeforeEach void setup() throws Exception {
        ZnackSigningSession.resetForTests();
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,api_key) VALUES(" + SHOP + ",'Synthetic emission batch','fixture')");
        }
        repository = new ZnackRepository(new ShopContext(SHOP, "Synthetic emission batch"));
        String commandProcessor = System.getenv("COMSPEC");
        Path executable;
        if (commandProcessor != null && Files.isRegularFile(Path.of(commandProcessor))) {
            executable = Path.of(commandProcessor);
        } else {
            executable = directory.resolve("fixture-cryptcp");
            Files.writeString(executable, "Synthetic executable fixture; never invoked");
            assertTrue(executable.toFile().setExecutable(true));
        }
        settings = new Settings("https://true.fixture.invalid", "https://suz.fixture.invalid", "fixture-oms",
                "fixture-connection", OWNER, OWNER, OWNER, "", "fixture-cert", "[]", "FIXTURE",
                "01.01.2026", "", true, "", "[]", "{}", Instant.now(), "", executable.toString(), "", 60, "")
                .withDefaultGoodsDocument("CONFORMITY_DECLARATION", "FIXTURE", "01.01.2026", "");
        repository.saveSettings(settings);
        var products = new ArrayList<Product>();
        for (int index = 1; index <= 20; index++) {
            products.add(new Product(gtin(index), "Synthetic item " + index, "6103100000",
                    "CONFORMITY_DECLARATION", "FIXTURE", "01.01.2026", null,
                    true, true, "published", "", "clothing", Instant.now(), "UNIT"));
        }
        repository.upsertProducts(products);
        store = new ZnackPurchaseBatchStore(repository);
        signer = new RecordingSigner();
        api = new RecordingApi();
        auth = new RecordingAuth(api, signer);
        service = new ZnackKizOrderService(api, auth, signer, repository);
    }

    @AfterEach void cleanup() {
        ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackPipelineQueueExecutor.shutdownAll();
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void tenDistinctApprovalsUseOneSignedPostAndEachGtinKeepsItsRemoteOrderAndQuantity() throws Exception {
        var ids = approve(11);
        var batch = store.prepare(ids.getFirst()).orElseThrow();
        assertEquals(10, batch.lines().size());
        var accepted = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.ACCEPTED, accepted.state());
        assertEquals(1, api.wirePosts.get());
        assertEquals(1, signer.calls.get());
        assertEquals(accepted.payload(), signer.bodies.getFirst());
        assertEquals(accepted.payload(), api.postBodies.getFirst());
        var payload = JsonParser.parseString(api.postBodies.getFirst()).getAsJsonObject();
        assertEquals("lp", payload.get("productGroup").getAsString());
        assertEquals("PRODUCTION", payload.getAsJsonObject("attributes").get("releaseMethodType").getAsString());
        assertEquals(10, payload.getAsJsonArray("products").size());
        var approved = new LinkedHashMap<String, Integer>();
        batch.lines().forEach(line -> approved.put(line.gtin(), line.quantity()));
        var submitted = new LinkedHashMap<String, Integer>();
        for (var item : payload.getAsJsonArray("products")) {
            var product = item.getAsJsonObject();
            assertNull(submitted.put(product.get("gtin").getAsString(), product.get("quantity").getAsInt()));
            assertEquals(10, product.get("templateId").getAsInt());
            assertEquals("UNIT", product.get("cisType").getAsString());
        }
        assertEquals(approved, submitted);
        for (var line : batch.lines()) {
            var local = repository.findOrder(line.orderId()).orElseThrow();
            assertEquals(accepted.externalOrderId(), local.externalOrderId());
            assertEquals(line.gtin(), local.gtin());
            assertEquals(line.quantity(), local.quantity());
            assertEquals(PurchaseStage.POLLING_ORDER, repository.findPipeline(line.pipelineId()).orElseThrow().stage());
        }
        assertEquals(1, store.prepare(ids.getLast()).orElseThrow().lines().size());
        service.advanceBatch(settings, batch.id());
        assertEquals(1, api.wirePosts.get(), "An accepted batch cannot be emitted again");
    }

    @Test void directBuyReturnsItsOwnGtinWhenAnOlderDifferentGtinJoinsTheBatch() throws Exception {
        var earlier = store.enqueueApproved(settings, List.of(request(1, 2))).getFirst();
        ZnackSigningSession.authorizePipeline(SHOP, earlier, store.settingsForPipeline(earlier));
        var bought = service.buy(settings, gtin(2), 3);
        assertEquals(gtin(2), bought.gtin());
        assertEquals(3, bought.quantity());
        assertEquals(1, api.wirePosts.get());
        var batch = store.findByOrder(bought.id()).orElseThrow();
        assertEquals(2, batch.lines().size());
        for (var line : batch.lines()) {
            assertNotNull(ZnackPurchaseCoordinator.nextCheckAt(SHOP, line.pipelineId()),
                    "The direct compatibility entry must wake every accepted batch member");
            assertEquals(bought.externalOrderId(), repository.findOrder(line.orderId()).orElseThrow().externalOrderId());
        }
        ZnackPurchaseCoordinator.cancelPendingPolls();
    }

    @ParameterizedTest @ValueSource(ints = {408, 500, 503})
    void uncertainHttpOutcomeNeverPostsAgainAcrossServiceReconstruction(int status) throws Exception {
        var batch = prepare(2);
        api.failureStatus = status;
        service.advanceBatch(settings, batch.id());
        assertReconcilingAndNoRemoteOrder(batch);
        for (int attempt = 0; attempt < 3; attempt++) {
            new ZnackKizOrderService(api, auth, signer, repository).advanceBatch(settings, batch.id());
        }
        assertEquals(1, api.wirePosts.get());
        assertEquals(3, api.listReads.get());
        assertReconcilingAndNoRemoteOrder(batch);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"success\":false,\"orderId\":\"untrusted\"}",
            "{\"orderId\":\"untrusted\",\"globalErrors\":[{\"errorCode\":1090}]}"})
    void malformedOrBusinessFailureHttp200IsUnknownAndNeverReposted(String response) throws Exception {
        var batch = prepare(2);
        api.createResponse = object(response);
        var rejected = assertThrows(Exception.class, () -> service.advanceBatch(settings, batch.id()));
        assertTrue(rejected instanceof IOException || rejected instanceof IllegalStateException,
                "Malformed success can fail decoding or required-ID validation, but must remain unknown");
        assertReconcilingAndNoRemoteOrder(batch);
        service.advanceBatch(settings, batch.id());
        assertEquals(1, api.wirePosts.get());
        assertReconcilingAndNoRemoteOrder(batch);
    }

    @Test void exact3350HoldsEveryMemberAndTheAccountWithoutTerminalFailure() throws Exception {
        var batch = prepare(3);
        api.failureStatus = 400;
        api.failureBody = globalError(3350).toString();
        var held = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.DEFERRED, held.state());
        assertTrue(held.knownNotCreated());
        assertEquals(0, held.recoveryAttempt());
        assertTrue(held.nextAttemptAt().isAfter(Instant.now()));
        assertTrue(store.capacityBlocked(held.accountKey()));
        assertAllMembersNonterminal(batch);
        for (var line : batch.lines()) assertTrue(repository.findPipeline(line.pipelineId()).orElseThrow().errorMessage().contains("3350"));
        service.advanceBatch(settings, batch.id());
        assertEquals(1, api.wirePosts.get());
        assertEquals(batch.lines(), store.find(batch.id()).orElseThrow().lines());
    }

    @Test void mixedGlobalErrorsAreNotMisclassifiedAsAnExactQuotaRejection() throws Exception {
        var batch = prepare(2);
        var mixed = globalError(3350);
        mixed.getAsJsonArray("globalErrors").add(globalError(9999).getAsJsonArray("globalErrors").get(0));
        api.failureStatus = 400;
        api.failureBody = mixed.toString();
        var failed = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.FAILED, failed.state());
        assertFalse(store.capacityBlocked(failed.accountKey()));
        assertEquals(1, api.wirePosts.get());
    }

    @Test void http429DefersTheSamePayloadAndLaterRetriesOnlyAfterKnownUnsentProof() throws Exception {
        var batch = prepare(2);
        api.failureStatus = 429;
        var deferred = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.DEFERRED, deferred.state());
        assertTrue(deferred.knownNotCreated());
        assertTrue(deferred.nextAttemptAt().isAfter(Instant.now().plusSeconds(60)));
        assertAllMembersNonterminal(batch);
        service.advanceBatch(settings, batch.id());
        assertEquals(1, api.wirePosts.get());
        makeRetryDue(batch.id());
        api.failureStatus = 0;
        assertEquals(ZnackPurchaseBatchStore.State.ACCEPTED, service.advanceBatch(settings, batch.id()).state());
        assertEquals(2, api.wirePosts.get());
        assertEquals(api.postBodies.getFirst(), api.postBodies.getLast());
        assertEquals(batch.lines(), store.find(batch.id()).orElseThrow().lines());
    }

    @Test void typedPrewirePacingDeferralDoesNotCountAsAnUncertainPaidPost() throws Exception {
        var batch = prepare(2);
        api.pacingDeferred = true;
        var deferred = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.DEFERRED, deferred.state());
        assertTrue(deferred.knownNotCreated());
        assertEquals(1, api.createCalls.get());
        assertEquals(0, api.wirePosts.get());
        assertAllMembersNonterminal(batch);
        service.advanceBatch(settings, batch.id());
        assertEquals(1, api.createCalls.get());
        assertEquals(0, api.listReads.get());
    }

    @Test void authentication429BeforeSubmissionDefersEveryMemberWithoutCreatingAnAttempt() throws Exception {
        var batch = prepare(2);
        auth.failure = new ZnackApiClient.ZnackApiException("Synthetic auth limit", 429, "{}", "POST", "",
                Duration.ofSeconds(90));
        var deferred = assertDoesNotThrow(() -> service.advanceBatch(settings, batch.id()));
        assertEquals(ZnackPurchaseBatchStore.State.DEFERRED, deferred.state());
        assertNull(deferred.attemptedAt());
        assertTrue(deferred.nextAttemptAt().isAfter(Instant.now().plusSeconds(60)));
        assertEquals(0, api.createCalls.get());
        assertAllMembersNonterminal(batch);
    }

    @Test void coordinatorAuthTransportFailureKeepsTheWholePreparedBatchNonterminal() throws Exception {
        var batch = prepare(2);
        auth.failure = new IOException("Synthetic authentication transport failure before emission");
        var coordinator = new ZnackPurchaseCoordinator(repository, service, null, null);
        assertThrows(IOException.class, () -> coordinator.advance(settings, batch.lines().getFirst().pipelineId()));
        assertAllMembersNonterminal(batch);
        assertEquals(ZnackPurchaseBatchStore.State.PREPARED, store.find(batch.id()).orElseThrow().state());
        assertNull(store.find(batch.id()).orElseThrow().attemptedAt());
        assertEquals(0, api.createCalls.get());
    }

    @Test void credential1090CreatesSingleGtinChildrenOnceAndStopsAtThreeRecoveryAttempts() throws Exception {
        var root = prepare(2);
        api.failureStatus = 400;
        api.failureBody = globalError(1090).toString();
        service.advanceBatch(settings, root.id());
        assertEquals(ZnackPurchaseBatchStore.State.REJECTED, store.find(root.id()).orElseThrow().state());
        var children = root.lines().stream().map(line -> store.findByPipeline(line.pipelineId()).orElseThrow()).toList();
        assertEquals(2, children.stream().map(ZnackPurchaseBatchStore.Batch::id).distinct().count());
        for (var child : children) {
            assertEquals(1, child.lines().size());
            assertEquals(root.id(), child.rootBatchId());
            assertEquals(1, child.recoveryAttempt());
            var approved = root.lines().stream().filter(line -> line.pipelineId() == child.lines().getFirst().pipelineId()).findFirst().orElseThrow();
            assertEquals(approved.quantity(), child.lines().getFirst().quantity());
            assertEquals(approved.requestKey(), child.lines().getFirst().requestKey());
        }
        service.advanceBatch(settings, root.id());
        assertEquals(1, api.wirePosts.get(), "The rejected parent must never be submitted again");
        var current = children.getFirst();
        for (int attempt = 1; attempt <= 3; attempt++) {
            service.advanceBatch(settings, current.id());
            var rejected = store.find(current.id()).orElseThrow();
            if (attempt == 3) {
                assertEquals(ZnackPurchaseBatchStore.State.FAILED, rejected.state());
                service.advanceBatch(settings, rejected.id());
            } else {
                assertEquals(ZnackPurchaseBatchStore.State.REJECTED, rejected.state());
                assertEquals(current.id(), store.findByPipeline(current.lines().getFirst().pipelineId()).orElseThrow().id());
                makeRetryDue(current.id());
                service.advanceBatch(settings, current.id());
                current = store.findByPipeline(current.lines().getFirst().pipelineId()).orElseThrow();
                assertEquals(attempt + 1, current.recoveryAttempt());
                assertEquals(1, current.lines().size());
            }
        }
        assertEquals(4, api.wirePosts.get(), "One root attempt plus three child attempts is the recovery limit");
        assertEquals(0, repository.findCodes(current.lines().getFirst().orderId()).size());
    }

    @Test void aPausedFollowerPreventsTheBatchPostEvenWhenTheLeaderIsAuthorized() throws Exception {
        var batch = prepare(2);
        pauseFollower(batch.lines().getLast().pipelineId());
        assertThrows(ZnackSigningSession.SigningDeferredException.class, () -> service.advanceBatch(settings, batch.id()));
        assertEquals(0, api.createCalls.get());
        assertEquals(0, signer.calls.get());
        assertEquals(ZnackPurchaseBatchStore.State.PREPARED, store.find(batch.id()).orElseThrow().state());
        assertNull(store.find(batch.id()).orElseThrow().attemptedAt());
        assertAllMembersNonterminal(batch);
    }

    @ParameterizedTest @ValueSource(strings = {"top-error", "wrong-oms", "wrong-group", "wrong-method"})
    void plausibleMatchingOrderCannotOverrideErrorsOrSuppliedForeignScope(String scenario) throws Exception {
        var batch = prepare(2);
        api.failureStatus = 408;
        service.advanceBatch(settings, batch.id());
        var unknown = store.find(batch.id()).orElseThrow();
        api.listResponse = matchingList(unknown);
        switch (scenario) {
            case "top-error" -> api.listResponse.add("globalErrors", globalError(1090).getAsJsonArray("globalErrors"));
            case "wrong-oms" -> api.listResponse.addProperty("omsId", "foreign-oms");
            case "wrong-group" -> api.listResponse.getAsJsonArray("orderInfos").get(0).getAsJsonObject().addProperty("productGroup", "shoes");
            case "wrong-method" -> api.listResponse.getAsJsonArray("orderInfos").get(0).getAsJsonObject().addProperty("releaseMethodType", "COMMISSION");
            default -> fail("Unexpected synthetic scenario");
        }
        assertThrows(IOException.class, () -> service.advanceBatch(settings, batch.id()));
        assertReconcilingAndNoRemoteOrder(batch);
        assertEquals(1, api.wirePosts.get());
    }

    @Test void oneExactReconciliationMatchAdoptsTheOldOrderWithoutAnotherPost() throws Exception {
        var batch = prepare(2);
        api.failureStatus = 408;
        service.advanceBatch(settings, batch.id());
        api.listResponse = matchingList(store.find(batch.id()).orElseThrow());
        var accepted = service.advanceBatch(settings, batch.id());
        assertEquals(ZnackPurchaseBatchStore.State.ACCEPTED, accepted.state());
        assertEquals("matching-old-order", accepted.externalOrderId());
        assertEquals(1, api.wirePosts.get());
        for (var line : batch.lines()) assertEquals("matching-old-order", repository.findOrder(line.orderId()).orElseThrow().externalOrderId());
    }

    private List<Long> approve(int count) {
        var requests = new ArrayList<ZnackPurchaseBatchStore.Approved>();
        for (int index = 1; index <= count; index++) requests.add(request(index, index));
        var ids = store.enqueueApproved(settings, requests);
        for (long id : ids) ZnackSigningSession.authorizePipeline(SHOP, id, store.settingsForPipeline(id));
        return ids;
    }

    private ZnackPurchaseBatchStore.Batch prepare(int count) { return store.prepare(approve(count).getFirst()).orElseThrow(); }
    private ZnackPurchaseBatchStore.Approved request(int index, int quantity) {
        return new ZnackPurchaseBatchStore.Approved(gtin(index), quantity, UUID.randomUUID().toString());
    }
    private void assertAllMembersNonterminal(ZnackPurchaseBatchStore.Batch batch) {
        for (var line : batch.lines()) {
            var pipeline = repository.findPipeline(line.pipelineId()).orElseThrow();
            assertNotEquals(PurchaseStage.FAILED, pipeline.stage());
            assertNotEquals(PurchaseStage.INTRODUCTION_FAILED, pipeline.stage());
            assertNotEquals(PurchaseStage.INTRODUCED, pipeline.stage());
            assertNotEquals(PurchaseStage.COMPLETED, pipeline.stage());
            assertNull(repository.findOrder(line.orderId()).orElseThrow().externalOrderId());
        }
    }
    private void assertReconcilingAndNoRemoteOrder(ZnackPurchaseBatchStore.Batch batch) {
        assertEquals(ZnackPurchaseBatchStore.State.RECONCILING, store.find(batch.id()).orElseThrow().state());
        for (var line : batch.lines()) {
            assertEquals(PurchaseStage.RECONCILING_ORDER, repository.findPipeline(line.pipelineId()).orElseThrow().stage());
            assertNull(repository.findOrder(line.orderId()).orElseThrow().externalOrderId());
        }
    }
    private void makeRetryDue(long batchId) throws Exception {
        try (var connection = Database.getConnection();
             var update = connection.prepareStatement("UPDATE znack_purchase_batches SET next_attempt_at=? WHERE shop_id=? AND id=?")) {
            update.setString(1, Instant.now().minusSeconds(1).toString());
            update.setInt(2, SHOP);
            update.setLong(3, batchId);
            assertEquals(1, update.executeUpdate());
        }
    }
    private void pauseFollower(long id) throws Exception {
        var failing = ZnackSigningSession.guard(SHOP, settings.signerCertificate(), (body, context) -> {
            throw new CryptoProException(CryptoProErrorCode.KEY_NOT_CONNECTED, "Synthetic key is not connected");
        });
        try (var ignored = ZnackSigningSession.openPipeline(SHOP, id)) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    () -> failing.sign(new byte[]{1}, ZnackSignatureContext.SUZ_POST_BODY));
        }
    }
    private static String gtin(int index) {
        String base = "046%010d".formatted(index);
        int sum = 0;
        for (int digit = 0; digit < 13; digit++) sum += (base.charAt(digit) - '0') * (digit % 2 == 0 ? 3 : 1);
        return base + (10 - sum % 10) % 10;
    }
    private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject globalError(int code) { return object("{\"success\":false,\"globalErrors\":[{\"errorCode\":" + code + "}]}"); }
    private static JsonObject matchingList(ZnackPurchaseBatchStore.Batch batch) {
        var row = new JsonObject();
        row.addProperty("orderId", "matching-old-order");
        row.addProperty("orderStatus", "READY");
        row.addProperty("createdTimestamp", batch.attemptedAt().toEpochMilli());
        var buffers = new JsonArray();
        for (var line : batch.lines()) {
            var buffer = new JsonObject();
            buffer.addProperty("gtin", line.gtin());
            buffer.addProperty("totalCodes", line.quantity());
            buffers.add(buffer);
        }
        row.add("buffers", buffers);
        var list = new JsonArray(); list.add(row);
        var result = new JsonObject(); result.add("orderInfos", list); return result;
    }

    private final class RecordingSigner implements ZnackSignatureProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> bodies = new ArrayList<>();
        @Override public CryptoProSigningResult sign(byte[] body, ZnackSignatureContext context) {
            assertEquals(ZnackSignatureContext.SUZ_POST_BODY, context);
            calls.incrementAndGet();
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return new CryptoProSigningResult("fixture-signature".getBytes(StandardCharsets.US_ASCII), "Synthetic only");
        }
    }
    private final class RecordingAuth extends ZnackAuthService {
        Exception failure;
        RecordingAuth(ZnackApiClient api, ZnackSignatureProvider signer) { super(api, signer); }
        @Override public String suzToken(Settings ignored) throws Exception {
            if (failure != null) throw failure;
            return "fixture-client-token";
        }
        @Override public String trueApiToken(Settings ignored) { return "fixture-true-token"; }
        @Override public synchronized boolean invalidateSuzToken(Settings ignored, String rejected) { return true; }
    }
    private final class RecordingApi extends ZnackApiClient {
        final AtomicInteger createCalls = new AtomicInteger(), wirePosts = new AtomicInteger(), listReads = new AtomicInteger();
        final List<String> postBodies = new ArrayList<>();
        int failureStatus;
        String failureBody = "{}";
        boolean pacingDeferred;
        JsonObject createResponse;
        JsonObject listResponse = object("{\"orderInfos\":[]}");
        @Override public JsonObject createOrder(String base, String token, String oms, byte[] body, String signature) throws IOException {
            createCalls.incrementAndGet();
            assertEquals(settings.resolvedSuzBaseUrl(), base);
            assertEquals(settings.omsId(), oms);
            if (pacingDeferred) throw new SuzRequestPacing.DeferredException(60_000, new IOException("Synthetic prewire wait"));
            int posted = wirePosts.incrementAndGet();
            postBodies.add(new String(body, StandardCharsets.UTF_8));
            if (failureStatus != 0) throw new ZnackApiException("Synthetic emission rejection", failureStatus,
                    failureBody, "POST", base, Duration.ofSeconds(90));
            return createResponse != null ? createResponse.deepCopy() : object("{\"orderId\":\"accepted-" + posted + "\"}");
        }
        @Override public JsonObject orderList(String base, String token, String oms) {
            listReads.incrementAndGet(); return listResponse.deepCopy();
        }
        @Override public JsonArray orderStatus(String base, String token, String oms, String remoteOrder) { return new JsonArray(); }
        @Override public JsonElement productInfo(String base, String token, String gtin) {
            throw new AssertionError("Products have a known UNIT type; no product HTTP lookup is allowed");
        }
    }
}
