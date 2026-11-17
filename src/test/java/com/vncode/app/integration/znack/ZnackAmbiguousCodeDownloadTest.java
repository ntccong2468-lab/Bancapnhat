package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Paid-order delivery protocol with synthetic SQLite/API data; no real purchase or signing. */
class ZnackAmbiguousCodeDownloadTest {
    private static final String GTIN = "04600000000015";
    private static final String OLD = "01" + GTIN + "21SERIAL0000001\u001d91ABCD\u001d92synthetic-crypto-old";
    private static final String NEW = "01" + GTIN + "21SERIAL0000002\u001d91EFGH\u001d92synthetic-crypto-new";
    private static final String UNCERTAIN = "SUZ_CODE_DOWNLOAD_UNCERTAIN";
    @TempDir Path directory;
    private ZnackRepository repository;
    private Settings settings;
    private long order;
    private long pipeline;
    private FakeApi api;

    @BeforeEach void setup() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
    }

    @AfterEach void cleanup() {
        ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    private void purchase(String marketplace) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic',?,'fixture-client','fixture-key')")) {
            p.setString(1, marketplace);
            p.executeUpdate();
        }
        repository = new ZnackRepository(new ShopContext(1, "Synthetic"));
        repository.upsertProducts(List.of(new Product(GTIN, "Synthetic", null, null, null, null, null)));
        settings = new Settings("", "", "fixture-oms", "fixture-connection", "", "", "", "",
                "fixture-certificate", "[]", "", "", "", false, "", "[]", "", Instant.now());
        repository.saveSettings(settings);
        order = repository.createDraft(GTIN, 2);
        repository.updateOrder(order, "original-paid-order", "ACTIVE", OrderStatus.CODES_READY, null);
        pipeline = repository.enqueuePipeline(GTIN, 2, "original-request");
        repository.updatePipeline(pipeline, order, PurchaseStage.DOWNLOADING_CODES, null);
        // This suite represents an already paid legacy download-only purchase.
        // Current enqueue always grants circulation, so restore the historic snapshot
        // explicitly rather than weakening the current purchase policy.
        try (var c = Database.getConnection()) {
            try (var p = c.prepareStatement("UPDATE znack_pipeline_connections SET settings_json=? WHERE pipeline_id=?")) {
                p.setString(1, com.vncode.app.features.products.ProductJson.GSON.toJson(settings)); p.setLong(2, pipeline); p.executeUpdate();
            }
            try (var p = c.prepareStatement("DELETE FROM znack_purchase_contexts WHERE pipeline_id=?")) {
                p.setLong(1, pipeline); p.executeUpdate();
            }
        }
        api = new FakeApi();
    }

    @ParameterizedTest
    @CsvSource({"WILDBERRIES,timeout", "OZON,timeout", "WILDBERRIES,server", "OZON,server",
            "WILDBERRIES,request-timeout", "OZON,request-timeout"})
    void lostOrUncertainResponseRecoversTheIssuedPackageBeforeAnotherDelivery(String marketplace, String failure) throws Exception {
        purchase(marketplace);
        api.firstFailure = failure;
        assertThrows(IOException.class, () -> coordinator().advance(settings, pipeline));
        coordinator().advance(settings, pipeline);

        assertEquals(1, api.ordinary, "A restart must recover the paid package, not issue another one");
        assertEquals(1, api.lists);
        assertEquals(List.of("issued"), api.retries);
        assertEquals(List.of(2), api.requested);
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void anEmptyBlockIndexAfterUnknownDeliveryIsNotPermissionToIssueAgain(String marketplace) throws Exception {
        purchase(marketplace);
        api.firstFailure = "timeout";
        api.emptyListOnce = true;
        assertThrows(IOException.class, () -> coordinator().advance(settings, pipeline));
        attemptRecovery();

        assertEquals(1, api.ordinary);
        assertEquals(PurchaseStage.DOWNLOADING_CODES, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
        assertTrue(repository.findCodes(order).isEmpty());

        coordinator().advance(settings, pipeline);
        assertEquals(1, api.ordinary);
        assertEquals(2, api.lists);
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void unavailableReplayRetainsTheUncertainCheckpointAcrossAnotherRestart(String marketplace) throws Exception {
        purchase(marketplace);
        api.firstFailure = "timeout";
        api.unavailableListOnce = true;
        assertThrows(IOException.class, () -> coordinator().advance(settings, pipeline));
        attemptRecovery();

        assertEquals(1, api.ordinary);
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
        coordinator().advance(settings, pipeline);
        assertEquals(1, api.ordinary);
        assertEquals(2, api.lists);
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void recoveryRetainsConsumedStockAndEveryGs1CryptoByte(String marketplace) throws Exception {
        purchase(marketplace);
        repository.insertCodes(order, GTIN, new DownloadedCodes(List.of(OLD), "old"));
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "UPDATE kiz_codes SET status='CONSUMED',legal_status='IN_CIRCULATION' WHERE shop_id=1 AND order_id=?")) {
            p.setLong(1, order);
            assertEquals(1, p.executeUpdate());
        }
        api.partialBeforeLoss = true;
        api.firstFailure = "timeout";
        assertThrows(IOException.class, () -> coordinator().advance(settings, pipeline));
        coordinator().advance(settings, pipeline);

        assertEquals(1, api.ordinary);
        assertEquals(List.of(1), api.requested);
        assertEquals(List.of("new"), api.retries, "The complete original block need not be issued or replayed again");
        var old = repository.findCodes(order).stream().filter(code -> OLD.equals(code.rawCode())).findFirst().orElseThrow();
        assertEquals(KizInventoryStatus.CONSUMED, old.inventoryStatus());
        assertEquals(KizLegalStatus.IN_CIRCULATION, old.legalStatus());
        assertTrue(repository.findCodes(order).stream().anyMatch(code -> NEW.equals(code.rawCode())));
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void failureBeforeTheConsumingRequestDoesNotInventAnUnknownIssuance(String marketplace) throws Exception {
        purchase(marketplace);
        api.authFailureOnce = true;
        assertThrows(IOException.class, () -> coordinator().advance(settings, pipeline));
        assertEquals(0, api.ordinary);
        assertNotEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());

        coordinator().advance(settings, pipeline);
        assertEquals(1, api.ordinary);
        assertEquals(0, api.lists);
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest
    @CsvSource({"WILDBERRIES,401", "OZON,401", "WILDBERRIES,429", "OZON,429", "WILDBERRIES,400", "OZON,400"})
    void definiteClientRejectionDoesNotPermanentlyBlockAStillUnissuedOrder(String marketplace, int status) throws Exception {
        purchase(marketplace);
        api.firstFailure = Integer.toString(status);
        assertThrows(ZnackApiClient.ZnackApiException.class, () -> service().download(settings, order));
        assertNotEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
        service().download(settings, order);

        assertEquals(2, api.ordinary);
        assertEquals(0, api.lists);
        assertEquals(2, repository.findCodes(order).size());
        assertEquals(OrderStatus.CODES_DOWNLOADED, repository.findOrder(order).orElseThrow().localStatus());
        assertTrue(Objects.toString(repository.findOrder(order).orElseThrow().errorMessage(), "").isBlank());
        assertEquals(1, repository.findOrders().size());
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void writeAheadReceiptSurvivesAbruptTerminationInsideTheDeliveryCall(String marketplace) throws Exception {
        purchase(marketplace);
        api.firstFailure = "crash";
        assertThrows(SimulatedTermination.class, () -> coordinator().advance(settings, pipeline));
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage(),
                "The recovery receipt must exist before the consuming request, even without an exception handler");

        coordinator().advance(settings, pipeline);
        assertEquals(1, api.ordinary);
        assertEquals(List.of("issued"), api.retries);
        assertDownloadedWithoutFalseCirculation();
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void manualDownloadUsesTheSameDurableRecoveryProtocol(String marketplace) throws Exception {
        purchase(marketplace);
        api.firstFailure = "timeout";
        assertThrows(IOException.class, () -> service().download(settings, order));
        service().download(settings, order);

        assertEquals(1, api.ordinary);
        assertEquals(1, api.lists);
        assertEquals(List.of("issued"), api.retries);
        assertEquals(2, repository.findCodes(order).size());
        assertEquals(OrderStatus.CODES_DOWNLOADED, repository.findOrder(order).orElseThrow().localStatus());
        assertTrue(Objects.toString(repository.findOrder(order).orElseThrow().errorMessage(), "").isBlank());
        assertEquals(1, repository.findOrders().size());
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void conflictingLogicalCisInAReplayCannotBePersistedAsTwoPaidCodes(String marketplace) throws Exception {
        purchase(marketplace);
        repository.updateOrder(order, null, null, OrderStatus.WAITING_CODES, UNCERTAIN);
        api.replayOverride = reply("issued", OLD, OLD.replace("crypto-old", "crypto-conflict"));

        assertThrows(IOException.class, () -> service().download(settings, order));
        assertTrue(repository.findCodes(order).isEmpty(), "Validate the complete replay before inserting any code");
        assertEquals(0, api.ordinary);
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"WILDBERRIES", "OZON"})
    void replayCannotOverflowTheOriginalQuantityOrModifyConsumedStock(String marketplace) throws Exception {
        purchase(marketplace);
        repository.insertCodes(order, GTIN, new DownloadedCodes(List.of(OLD), "previous"));
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "UPDATE kiz_codes SET status='CONSUMED',legal_status='IN_CIRCULATION' WHERE shop_id=1 AND order_id=?")) {
            p.setLong(1, order);
            assertEquals(1, p.executeUpdate());
        }
        repository.updateOrder(order, null, null, OrderStatus.WAITING_CODES, UNCERTAIN);
        api.replayOverride = reply("issued", NEW, NEW.replace("SERIAL0000002", "SERIAL0000003"));

        assertThrows(IOException.class, () -> service().download(settings, order));
        var retained = repository.findCodes(order);
        assertEquals(1, retained.size(), "A valid-looking block cannot exceed the paid order's remaining quantity");
        assertEquals(OLD, retained.getFirst().rawCode());
        assertEquals(KizInventoryStatus.CONSUMED, retained.getFirst().inventoryStatus());
        assertEquals(KizLegalStatus.IN_CIRCULATION, retained.getFirst().legalStatus());
        assertEquals(0, api.ordinary);
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
    }

    @ParameterizedTest
    @CsvSource({"WILDBERRIES,boolean", "OZON,boolean", "WILDBERRIES,number", "OZON,number",
            "WILDBERRIES,cis-boolean", "OZON,cis-boolean", "WILDBERRIES,cis-number", "OZON,cis-number"})
    void malformedReplayEntriesCannotBecomeOpaquePaidCodes(String marketplace, String kind) throws Exception {
        purchase(marketplace);
        repository.updateOrder(order, null, null, OrderStatus.WAITING_CODES, UNCERTAIN);
        var payload = reply("issued", NEW);
        JsonElement invalid = kind.endsWith("boolean") ? new JsonPrimitive(true) : new JsonPrimitive(123);
        if (kind.startsWith("cis-")) {
            var object = new JsonObject();
            object.add("cis", invalid);
            invalid = object;
        }
        payload.getAsJsonArray("codes").add(invalid);
        api.replayOverride = payload;

        assertThrows(IOException.class, () -> service().download(settings, order));
        assertTrue(repository.findCodes(order).isEmpty(), "Reject a malformed later entry before saving the earlier valid one");
        assertEquals(0, api.ordinary);
        assertEquals(UNCERTAIN, repository.findOrder(order).orElseThrow().errorMessage());
    }

    private void attemptRecovery() throws Exception {
        try { coordinator().advance(settings, pipeline); }
        catch (IOException pending) { /* A read-only recovery may still be unavailable; state assertions follow. */ }
    }

    private ZnackPurchaseCoordinator coordinator() {
        var orders = new ZnackKizOrderService(null, null, null, repository) {
            @Override public java.util.OptionalInt availableCodes(long id) {
                return java.util.OptionalInt.of(repository.findOrder(id).orElseThrow().quantity());
            }
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) {
                fail("Delivery recovery must not create another paid order");
                return null;
            }
        };
        return new ZnackPurchaseCoordinator(repository, orders, service(), null);
    }

    private ZnackKizCodeService service() {
        var auth = new ZnackAuthService(api, null) {
            @Override public String suzToken(Settings ignored) throws Exception {
                if (api.authFailureOnce) {
                    api.authFailureOnce = false;
                    throw new IOException("Synthetic authentication read failed before delivery");
                }
                return "fixture-token";
            }
        };
        return new ZnackKizCodeService(api, auth, repository);
    }

    private void assertDownloadedWithoutFalseCirculation() {
        var saved = repository.findOrder(order).orElseThrow();
        assertEquals("original-paid-order", saved.externalOrderId());
        assertEquals(2, saved.quantity());
        assertEquals(2, repository.findCodes(order).size());
        assertEquals(OrderStatus.WAITING_INTRODUCTION_READINESS, saved.localStatus());
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS, repository.findPipeline(pipeline).orElseThrow().stage());
        assertTrue(repository.findCodes(order).stream().anyMatch(code -> code.legalStatus() != KizLegalStatus.IN_CIRCULATION),
                "Downloading alone is not authoritative circulation proof");
        assertEquals(1, repository.findOrders().size());
        assertEquals(1, repository.findActivePipelines().size());
    }

    private static JsonObject reply(String blockId, String... codes) {
        var result = new JsonObject();
        result.addProperty("omsId", "fixture-oms");
        result.addProperty("blockId", blockId);
        var rows = new JsonArray();
        for (String code : codes) rows.add(code);
        result.add("codes", rows);
        return result;
    }

    private static JsonObject block(String id, int quantity) {
        var result = new JsonObject();
        result.addProperty("blockId", id);
        result.addProperty("quantity", quantity);
        return result;
    }

    private static final class SimulatedTermination extends Error {}

    private final class FakeApi extends ZnackApiClient {
        int ordinary;
        int lists;
        String firstFailure;
        boolean emptyListOnce;
        boolean unavailableListOnce;
        boolean partialBeforeLoss;
        boolean authFailureOnce;
        JsonElement replayOverride;
        final List<Integer> requested = new ArrayList<>();
        final List<String> retries = new ArrayList<>();

        @Override public JsonElement codes(String base, String token, String oms, String external, int quantity, String gtin) throws IOException {
            ordinary++;
            requested.add(quantity);
            assertEquals(settings.omsId(), oms);
            assertEquals("original-paid-order", external);
            assertEquals(GTIN, gtin);
            if (ordinary == 1 && firstFailure != null) {
                switch (firstFailure) {
                    case "timeout" -> throw new SocketTimeoutException("Synthetic response lost after issuance");
                    case "server" -> throw new ZnackApiException("Synthetic server response", 503, "{}");
                    case "request-timeout" -> throw new ZnackApiException("Synthetic request timeout", 408, "{}");
                    case "crash" -> throw new SimulatedTermination();
                    default -> throw new ZnackApiException("Synthetic client rejection", Integer.parseInt(firstFailure), "{}");
                }
            }
            return partialBeforeLoss ? reply("new", NEW) : reply("issued", OLD, NEW);
        }

        @Override public JsonElement codeBlocks(String base, String token, String oms, String external, String gtin) throws IOException {
            lists++;
            assertEquals(settings.omsId(), oms);
            assertEquals("original-paid-order", external);
            assertEquals(GTIN, gtin);
            if (unavailableListOnce) {
                unavailableListOnce = false;
                throw new SocketTimeoutException("Synthetic recovery lookup unavailable");
            }
            var result = new JsonObject();
            result.addProperty("omsId", oms);
            result.addProperty("orderId", external);
            result.addProperty("gtin", gtin);
            var rows = new JsonArray();
            if (emptyListOnce) emptyListOnce = false;
            else if (partialBeforeLoss) { rows.add(block("old", 1)); rows.add(block("new", 1)); }
            else rows.add(block("issued", 2));
            result.add("blocks", rows);
            return result;
        }

        @Override public JsonElement retryCodeBlock(String base, String token, String oms, String blockId) {
            retries.add(blockId);
            if (replayOverride != null) return replayOverride;
            return switch (blockId) {
                case "old" -> reply("old", OLD);
                case "new" -> reply("new", NEW);
                default -> reply("issued", OLD, NEW);
            };
        }

        @Override public JsonObject createOrder(String base, String token, String oms, byte[] body, String signature) {
            fail("A recovery cannot create another paid SUZ order");
            return null;
        }
    }
}
