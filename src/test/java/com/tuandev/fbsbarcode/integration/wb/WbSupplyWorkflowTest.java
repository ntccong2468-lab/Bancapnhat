package com.tuandev.fbsbarcode.integration.wb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WbSupplyWorkflowTest {
    @TempDir
    Path temporaryDirectory;

    private final Shop shop = new Shop(1, "WB", Marketplace.WILDBERRIES, null, "token");

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','token')");
            statement.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,name,synced_at) "
                    + "VALUES(1,'WB-GI-100',0,0,'Empty supply','2026-08-25T00:00:00Z')");
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void deletesLocalSupplyOnlyAfterWildberriesConfirmsItIsStillEmpty() throws Exception {
        FakeWbApiClient api = new FakeWbApiClient(List.of());
        WbSupplyWorkflow workflow = new WbSupplyWorkflow(api);

        workflow.deleteEmptySupply(shop, "WB-GI-100");

        assertEquals(1, api.deleteCalls);
        assertEquals(null, new WbSupplyRepository().findSupplySummary(1, "WB-GI-100"));
    }

    @Test
    void refusesDeleteWhenWildberriesReportsAnyOrderAndKeepsLocalSupply() {
        FakeWbApiClient api = new FakeWbApiClient(List.of(42L));
        WbSupplyWorkflow workflow = new WbSupplyWorkflow(api);

        assertThrows(IllegalStateException.class, () -> workflow.deleteEmptySupply(shop, "WB-GI-100"));

        assertEquals(0, api.deleteCalls);
        assertNotNull(new WbSupplyRepository().findSupplySummary(1, "WB-GI-100"));
    }

    private static final class FakeWbApiClient extends WbApiClient {
        private final List<Long> remoteOrderIds;
        private int deleteCalls;

        private FakeWbApiClient(List<Long> remoteOrderIds) {
            this.remoteOrderIds = remoteOrderIds;
        }

        @Override
        public WbSupplyOrderIdsResponse getSupplyOrderIds(String apiKey, String supplyId) {
            return new WbSupplyOrderIdsResponse() {
                @Override
                public List<Long> getOrderIds() {
                    return remoteOrderIds;
                }
            };
        }

        @Override
        public void deleteSupply(String apiKey, String supplyId) {
            deleteCalls++;
        }
    }
}
