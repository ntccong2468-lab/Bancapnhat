package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.integration.znack.ZnackModels.OrderStatus;
import com.vncode.app.integration.znack.ZnackModels.PurchaseStage;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Persisted queue ordering must not hide the existing paid lot's signing recovery action. */
class KizSigningSummaryRecoveryTest {
    private static final String GTIN = "04601234567890";
    private static final String OTHER_GTIN = "04601234567891";
    private static final String OLDER = "2026-11-01T00:00:00Z";
    private static final String NEWER = "2026-11-02T00:00:00Z";
    private static final String WAITING = "Waiting for the user to select this shop before signing.";
    @TempDir Path appData;
    private ZnackRepository purchases;
    private final KizMappingRepository mapping = new KizMappingRepository();

    @BeforeEach void setup() throws Exception {
        ZnackSigningSession.resetForTests();
        System.setProperty("vncode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,?,?,'fixture')")) {
            for (int id = 1; id <= 2; id++) {
                p.setInt(1, id); p.setString(2, "Synthetic shop " + id);
                p.setString(3, "WILDBERRIES"); p.setString(4, "fixture-client"); p.executeUpdate();
            }
        }
        product(1, GTIN); product(2, GTIN);
        purchases = new ZnackRepository(new ShopContext(1, "Synthetic shop 1"));
    }

    @AfterEach void cleanup() {
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void queuedRequestDoesNotHideAnOlderPaidDownloadWaitingForSignature(String marketplace) throws Exception {
        marketplace(marketplace);
        long paid = paid(PurchaseStage.DOWNLOADING_CODES, WAITING);
        long queued = purchases.enqueuePipeline(GTIN, 1, "later-queued");
        assertEquals(PurchaseStage.QUEUED, purchases.findPipeline(queued).orElseThrow().stage(),
                "This is a real persisted enqueue behind a download mutation, not a fabricated terminal row");
        assertEquals(paid, purchases.findActivePipeline(GTIN).orElseThrow().id());
        assertPausedInAllViews(paid, PurchaseStage.DOWNLOADING_CODES, WAITING);
        assertEquals(2, pipelineCount());
        assertEquals(1, orderCount());
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void laterActivePurchaseDoesNotHideReadinessSigningPause(String marketplace) throws Exception {
        marketplace(marketplace);
        long paid = paid(PurchaseStage.WAITING_INTRODUCTION_READINESS, WAITING + " [CRYPTOPRO_CANCELLED]");
        long active = purchases.enqueuePipeline(GTIN, 1, "later-active");
        assertEquals(PurchaseStage.VALIDATING, purchases.findPipeline(active).orElseThrow().stage(),
                "Readiness is not one of the mutation stages that queues the next intent");
        assertEquals(active, purchases.findActivePipeline(GTIN).orElseThrow().id());
        assertPausedInAllViews(paid, PurchaseStage.WAITING_INTRODUCTION_READINESS,
                WAITING + " [CRYPTOPRO_CANCELLED]");
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void newerTerminalLotDoesNotHideAnotherLotsCurrentSigningPause(String marketplace) throws Exception {
        marketplace(marketplace);
        long paused = paid(PurchaseStage.WAITING_INTRODUCTION_READINESS, WAITING);
        long later = purchases.enqueuePipeline(GTIN, 1, "later-finished");
        purchases.updatePipeline(later, null, PurchaseStage.INTRODUCED, null);
        timestamp(later, NEWER);
        assertPausedInAllViews(paused, PurchaseStage.WAITING_INTRODUCTION_READINESS, WAITING);
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void ordinaryOlderActiveLotDoesNotReplaceTheLatestPurchaseStatus(String marketplace) throws Exception {
        marketplace(marketplace);
        paid(PurchaseStage.WAITING_INTRODUCTION_READINESS, "Waiting for external circulation");
        long newer = purchases.enqueuePipeline(GTIN, 1, "latest-normal");
        purchases.updatePipeline(newer, null, PurchaseStage.INTRODUCED, "latest explanation");
        timestamp(newer, NEWER);
        for (var rows : views()) {
            var summary = rows.getFirst();
            assertEquals(newer, summary.latestPipelineId());
            assertEquals("INTRODUCED", summary.latestPipelineStage());
            assertEquals("latest explanation", summary.latestError());
            assertFalse(pending(summary));
        }
    }

    @Test void terminalSigningMarkerDoesNotAuthorizeResumingATerminalLot() throws Exception {
        long terminal = paid(PurchaseStage.INTRODUCED, WAITING);
        for (var rows : views()) {
            assertEquals(terminal, rows.getFirst().latestPipelineId());
            assertFalse(pending(rows.getFirst()));
        }
    }

    @Test void substringResemblingThePauseMarkerDoesNotHideLatestStatus() throws Exception {
        paid(PurchaseStage.WAITING_INTRODUCTION_READINESS, "Unrelated error: " + WAITING);
        long newer = purchases.enqueuePipeline(GTIN, 1, "latest-after-unrelated");
        for (var rows : views()) {
            assertEquals(newer, rows.getFirst().latestPipelineId());
            assertFalse(pending(rows.getFirst()));
        }
    }

    @Test void runtimeSigningPauseWithoutPersistedMarkerRemainsVisible() throws Exception {
        long paused = paid(PurchaseStage.DOWNLOADING_CODES, null);
        var signer = ZnackSigningSession.guard(1, (bytes, context) -> {
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "Synthetic signer cancellation");
        });
        ZnackSigningSession.authorizePipeline(1, paused);
        try (var ignored = ZnackSigningSession.openPipeline(1, paused)) {
            assertThrows(CryptoProException.class, () -> signer.sign(new byte[0], ZnackSignatureContext.AUTH_CHALLENGE));
        }
        assertTrue(ZnackSigningSession.isWaitingForSignature(1, paused));
        purchases.enqueuePipeline(GTIN, 1, "later-after-runtime-pause");
        String persistedError = purchases.findPipeline(paused).orElseThrow().errorMessage();
        assertTrue(persistedError == null || persistedError.isBlank(), "Pause comes from the actual signing session");
        assertPausedInAllViews(paused, PurchaseStage.DOWNLOADING_CODES, persistedError);
    }

    @Test void latestNormalPipelineUsesHigherIdWhenUpdateTimesAreEqual() throws Exception {
        long older = paid(PurchaseStage.COMPLETED, "older");
        long newer = purchases.enqueuePipeline(GTIN, 1, "tied-normal");
        purchases.updatePipeline(newer, null, PurchaseStage.INTRODUCED, "newer");
        timestamp(older, NEWER); timestamp(newer, NEWER);
        for (var rows : views()) {
            assertEquals(newer, rows.getFirst().latestPipelineId());
            assertEquals("INTRODUCED", rows.getFirst().latestPipelineStage());
            assertEquals("newer", rows.getFirst().latestError());
        }
    }

    @Test void latestEligibleSigningPauseUsesHigherIdWhenUpdateTimesAreEqual() throws Exception {
        long older = paid(PurchaseStage.WAITING_INTRODUCTION_READINESS, WAITING);
        long newer = purchases.enqueuePipeline(GTIN, 1, "tied-paused");
        purchases.updatePipeline(newer, null, PurchaseStage.WAITING_INTRODUCTION_READINESS,
                WAITING + " [CRYPTOPRO_CANCELLED]");
        timestamp(older, NEWER); timestamp(newer, NEWER);
        assertPausedInAllViews(newer, PurchaseStage.WAITING_INTRODUCTION_READINESS,
                WAITING + " [CRYPTOPRO_CANCELLED]");
    }

    @Test void pauseSelectionDoesNotCrossShopOrGtin() throws Exception {
        product(1, OTHER_GTIN);
        long normal = purchases.enqueuePipeline(GTIN, 1, "own-normal");
        long otherGtin = purchases.createPipeline(OTHER_GTIN, 1, "other-gtin-paused");
        purchases.updatePipeline(otherGtin, null, PurchaseStage.WAITING_INTRODUCTION_READINESS, WAITING);
        var foreign = new ZnackRepository(new ShopContext(2, "Synthetic shop 2"));
        long foreignId = foreign.createPipeline(GTIN, 1, "foreign-paused");
        foreign.updatePipeline(foreignId, null, PurchaseStage.DOWNLOADING_CODES, WAITING);
        for (var rows : views()) {
            var own = rows.stream().filter(row -> GTIN.equals(row.gtin())).findFirst().orElseThrow();
            assertEquals(normal, own.latestPipelineId());
            assertFalse(pending(own));
        }
    }

    @Test void pauseBeyondOneSqlParameterBatchIsFoundWithoutChangingInventory() throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES(1,?,'Batch fixture','Synthetic','2026-11-01T00:00:00Z')")) {
            c.setAutoCommit(false);
            for (int i = 1; i <= 901; i++) {
                p.setString(1, "046" + String.format(java.util.Locale.ROOT, "%011d", i)); p.addBatch();
            }
            p.executeBatch(); c.commit();
        }
        long paused = paid(PurchaseStage.DOWNLOADING_CODES, WAITING);
        purchases.enqueuePipeline(GTIN, 1, "later-after-batch");
        assertEquals(902, mapping.findGtinSummaries(1).size());
        assertEquals(902, mapping.findPurchasedGtinSummaries(1).size());
        for (var rows : views()) {
            var row = rows.stream().filter(value -> GTIN.equals(value.gtin())).findFirst().orElseThrow();
            assertEquals(paused, row.latestPipelineId());
            assertEquals(0, row.available()); assertEquals(0, row.reserved()); assertEquals(0, row.consumed());
        }
    }

    private long paid(PurchaseStage stage, String error) throws Exception {
        long order = purchases.createDraft(GTIN, 1);
        purchases.updateOrder(order, "fixture-order-" + order, "ACTIVE", OrderStatus.CODES_DOWNLOADED, null);
        long pipeline = purchases.createPipeline(GTIN, 1, "paid-" + order);
        purchases.updatePipeline(pipeline, order, stage, error);
        timestamp(pipeline, OLDER);
        return pipeline;
    }

    private List<List<ZnackGtinInventorySummary>> views() {
        return List.of(mapping.findGtinSummaries(1), mapping.findPurchasedGtinSummaries(1),
                mapping.findGtinSummariesPage(1, GTIN, List.of("Synthetic"), 100, 0));
    }

    private void assertPausedInAllViews(long id, PurchaseStage stage, String error) {
        for (var rows : views()) {
            var summary = rows.getFirst();
            assertEquals(id, summary.latestPipelineId(), "Keep recovery attached to the existing paid pipeline");
            assertEquals(stage.name(), summary.latestPipelineStage());
            assertEquals(error, summary.latestError());
            assertTrue(pending(summary));
            assertEquals(0, summary.available(), "A signing pause never proves legal circulation");
        }
    }

    private static boolean pending(ZnackGtinInventorySummary row) {
        return ZnackPurchaseCoordinator.isSigningConfirmationPending(1, row.latestPipelineId(),
                row.latestPipelineStage(), row.latestError());
    }

    private void product(int shopId, String gtin) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES(?,?,'Synthetic product','Synthetic','2026-11-01T00:00:00Z')")) {
            p.setInt(1, shopId); p.setString(2, gtin); p.executeUpdate();
        }
    }

    private void marketplace(String value) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement("UPDATE shops SET marketplace=? WHERE id=1")) {
            p.setString(1, value); p.executeUpdate();
        }
    }

    private void timestamp(long id, String timestamp) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "UPDATE znack_purchase_pipelines SET updated_at=? WHERE shop_id=1 AND id=?")) {
            p.setString(1, timestamp); p.setLong(2, id); p.executeUpdate();
        }
    }

    private static int pipelineCount() throws Exception { return count("znack_purchase_pipelines"); }
    private static int orderCount() throws Exception { return count("kiz_orders"); }
    private static int count(String table) throws Exception {
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(r.next()); return r.getInt(1);
        }
    }
}
