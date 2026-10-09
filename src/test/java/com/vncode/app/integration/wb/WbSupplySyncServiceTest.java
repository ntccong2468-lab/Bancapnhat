package com.vncode.app.integration.wb;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WbSupplySyncServiceTest {
    @Test void incrementalSupplySyncRequestsOneRemoteBatchOfTwenty() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var api=new WbApiClient(){@Override public WbSuppliesResponse getSupplies(String key,long next,int limit){
            calls.incrementAndGet();assertEquals(20,limit);
            return new com.google.gson.Gson().fromJson("{\"next\":20,\"supplies\":[{\"id\":\"WB-GI-FIXTURE\",\"done\":true}]}",WbSuppliesResponse.class);
        }};
        assertEquals(1,service(api).syncIncremental(shop));assertEquals(1,calls.get());
        assertEquals(20,new WbSyncStateRepository().getShopSyncState(shop.getId()).suppliesNext());
    }
    @TempDir Path appData;
    private final Shop shop = new Shop(1, "WB", Marketplace.WILDBERRIES, null, "token");

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) "
                    + "VALUES(1,'WB','WILDBERRIES','token'),(2,'Other','WILDBERRIES','other')");
            statement.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,name,synced_at) VALUES "
                    + "(1,'WB-GI-MISSING',0,0,'Missing','2026-09-03T00:00:00Z'),"
                    + "(2,'WB-GI-MISSING',0,0,'Other shop copy','2026-09-03T00:00:00Z')");
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void authoritativeDetailNotFoundRemovesOnlyTheSelectedShopsStaleSupply() throws Exception {
        WbSupplySyncService service = service(new MissingSupplyApiClient(404));

        service.syncOpenSupplyDetails(shop);

        WbSupplyRepository repository = new WbSupplyRepository();
        assertNull(repository.findSupplySummary(1, "WB-GI-MISSING"));
        assertNotNull(repository.findSupplySummary(2, "WB-GI-MISSING"));
    }

    @Test
    void authoritativeOrderCountNotFoundAlsoRemovesTheStaleSupply() throws Exception {
        WbSupplySyncService service = service(new WbApiClient() {
            @Override
            public WbSupplyOrderIdsResponse getSupplyOrderIds(String apiKey, String supplyId) throws IOException {
                throw new WbApiException("WB supply response", 404, "");
            }
        });

        service.syncOpenSupplyCounts(shop);

        assertNull(new WbSupplyRepository().findSupplySummary(1, "WB-GI-MISSING"));
    }

    @Test
    void transientDetailFailureNeverDeletesLocalSupply() throws Exception {
        WbSupplySyncService service = service(new MissingSupplyApiClient(503));

        try {
            service.syncOpenSupplyDetails(shop);
        } catch (IOException expected) {
            // The caller may surface the transient failure, but local data must remain intact.
        }

        assertNotNull(new WbSupplyRepository().findSupplySummary(1, "WB-GI-MISSING"));
    }

    @Test
    void openSupplyVerificationPrioritizesOldZeroCountRows() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,name,created_at,synced_at) VALUES "
                    + "(1,'RECENT-WITH-ORDERS',0,5,'Recent','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z'),"
                    + "(1,'OLD-ZERO',0,0,'Old','2026-06-01T00:00:00Z','2026-06-01T00:00:00Z')");
        }

        assertEquals("OLD-ZERO", new WbSupplyRepository().getOpenSupplyIds(1, 1).getFirst());
    }

    private static WbSupplySyncService service(WbApiClient api) {
        return new WbSupplySyncService(api, new WbSupplyRepository(),
                new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    private static final class MissingSupplyApiClient extends WbApiClient {
        private final int status;

        private MissingSupplyApiClient(int status) {
            this.status = status;
        }

        @Override
        public WbSupplyDto getSupplyDetail(String apiKey, String supplyId) throws IOException {
            throw new WbApiException("WB supply response", status, "");
        }
    }
}
