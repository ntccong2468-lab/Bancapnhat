package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackPurchaseBatchStore;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** A seller-approved shortage is admitted as one complete intent, not racing per-GTIN sends. */
class Wb1326PurchaseProducerBatchTest {
    @TempDir Path directory;
    private final String first = KizWarehouseTest.GTIN;
    private final String second = gtin("0464558878125");
    private final List<String> events = new ArrayList<>();
    private Buyer buyer;
    private ZnackRepository repository;
    private KizStockReplenisher replenisher;

    @BeforeEach void initialize() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
    }

    @AfterEach void reset() {
        System.clearProperty("vncode.appdata.dir");
    }

    private Shop setup(Marketplace market) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic',?,?, 'fixture')")) {
            statement.setString(1, market.name());
            statement.setString(2, market == Marketplace.OZON ? "synthetic-client" : null);
            statement.executeUpdate();
        }
        var shop = new Shop(1, "Synthetic", market, market == Marketplace.OZON ? "synthetic-client" : null, "fixture");
        repository = new ZnackRepository(new ZnackModels.ShopContext(1, "Synthetic"));
        repository.upsertProducts(List.of(new ZnackModels.Product(first, "First", null, null, null, null, null),
                new ZnackModels.Product(second, "Second", null, null, null, null, null)));
        buyer = new Buyer();
        replenisher = new KizStockReplenisher(ignored -> buyer);
        return shop;
    }

    @Test void allValidatedAndRecheckedDeficitsReachOneBulkSubmission() throws Exception {
        var shop = setup(Marketplace.WILDBERRIES);
        var expected = Map.of(first, 2, second, 3);
        var result = replenisher.ensurePurchased(shop, expected, missing -> {
            assertEquals(expected, missing);
            events.add("proof");
        });
        assertEquals(2, result.enqueued());
        assertEquals(expected, result.missing());
        assertEquals(1, buyer.batches);
        assertEquals(2, buyer.approved.size());
        assertEquals(2, buyer.approved.stream().map(ZnackPurchaseBatchStore.Approved::requestKey).distinct().count());
        buyer.approved.forEach(item -> assertDoesNotThrow(() -> UUID.fromString(item.requestKey())));
        assertEquals(List.of("proof", "validate:" + first, "validate:" + second, "proof", "batch"), events);
        var keys = buyer.approved.stream().map(ZnackPurchaseBatchStore.Approved::requestKey).toList();

        var again = replenisher.ensurePurchased(shop, expected, missing -> fail("Existing approved jobs must not be purchased twice"));
        assertEquals(0, again.enqueued());
        assertTrue(again.waiting());
        assertEquals(1, buyer.batches);
        assertEquals(keys, buyer.approved.stream().map(ZnackPurchaseBatchStore.Approved::requestKey).toList());
        assertEquals(2, repository.findActivePipelines().size());
    }

    @Test void changedNativeProofRejectsTheWholeGroupBeforeSubmission() throws Exception {
        var shop = setup(Marketplace.WILDBERRIES);
        var calls = new AtomicInteger();
        assertThrows(KizAssignmentException.class, () -> replenisher.ensurePurchased(shop, Map.of(first, 2, second, 3), missing -> {
            if (calls.incrementAndGet() == 2) throw new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING", "", "");
        }));
        assertEquals(2, calls.get());
        assertEquals(0, buyer.batches);
        assertEquals(0, buyer.resumes);
        assertTrue(repository.findActivePipelines().isEmpty());
    }

    @Test void invalidLastMemberCannotLeaveAnEarlierPaidRequest() throws Exception {
        var shop = setup(Marketplace.WILDBERRIES);
        buyer.reject = second;
        assertThrows(IllegalArgumentException.class,
                () -> replenisher.ensurePurchased(shop, Map.of(first, 2, second, 3), missing -> {}));
        assertEquals(0, buyer.batches);
        assertEquals(0, buyer.resumes);
        assertTrue(repository.findActivePipelines().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Marketplace.class, names = {"WILDBERRIES", "OZON"})
    void adapterEligibleCountsStillDetermineEachApprovedDeficit(Marketplace market) throws Exception {
        var shop = setup(market);
        var policy = (KizPurchaseAvailability) (owner, gtins) -> Map.of(first, 1, second, 2);
        var result = replenisher.ensurePurchased(shop, Map.of(first, 3, second, 5),
                missing -> assertEquals(Map.of(first, 2, second, 3), missing), policy);
        assertEquals(Map.of(first, 2, second, 3), result.missing());
        assertEquals(1, buyer.batches);
        assertEquals(Map.of(first, 2, second, 3), buyer.approved.stream().collect(
                java.util.stream.Collectors.toMap(ZnackPurchaseBatchStore.Approved::gtin, ZnackPurchaseBatchStore.Approved::quantity)));
    }

    private final class Buyer implements KizStockReplenisher.Purchases {
        private int batches;
        private int resumes;
        private String reject;
        private List<ZnackPurchaseBatchStore.Approved> approved = List.of();

        @Override public void validate(String gtin, int quantity) {
            events.add("validate:" + gtin);
            if (gtin.equals(reject)) throw new IllegalArgumentException("Synthetic invalid member");
        }

        @Override public void enqueueBatch(List<ZnackPurchaseBatchStore.Approved> items) {
            events.add("batch");
            batches++;
            approved = List.copyOf(items);
            for (var item : items) repository.enqueuePipeline(item.gtin(), item.quantity(), item.requestKey());
        }

        @Override public void resume() { resumes++; }
    }

    private static String gtin(String prefix) {
        int sum = 0;
        for (int i = 0; i < prefix.length(); i++) sum += (prefix.charAt(i) - '0') * (i % 2 == 0 ? 3 : 1);
        return prefix + ((10 - sum % 10) % 10);
    }
}
