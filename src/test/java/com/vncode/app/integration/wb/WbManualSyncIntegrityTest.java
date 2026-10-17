package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WbManualSyncIntegrityTest {
    @TempDir Path directory; Shop shop; static final Gson JSON = new Gson();
    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,synced_at) VALUES(1,'S',0,2,'old')");
            s.execute("INSERT INTO wb_orders(shop_id,order_id,supply_id,supplier_status,wb_status,synced_at) VALUES(1,1,'S','new','waiting','old'),(1,2,'S','new','waiting','old')");
            s.execute("INSERT INTO wb_supply_orders(shop_id,supply_id,order_id) VALUES(1,'S',1),(1,'S',2)");
        }
    }
    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }
    WbOrderSyncService orders(WbApiClient api) { return new WbOrderSyncService(api,new WbOrderRepository(),new WbSupplyRepository(),new WbSyncStateRepository(),new WbSyncRunRepository()); }
    WbSupplySyncService supplies(WbApiClient api) { return new WbSupplySyncService(api,new WbSupplyRepository(),new WbSyncStateRepository(),new WbSyncRunRepository()); }
    int count(String sql) throws Exception { try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){return r.getInt(1);} }
    static WbOrdersResponse fullOrders() {
        var rows = new JsonArray(); for (int id=100;id<1100;id++) {var row=new JsonObject();row.addProperty("id",id);rows.add(row);}
        var page=new JsonObject();page.add("orders",rows);page.addProperty("next",1000);return JSON.fromJson(page,WbOrdersResponse.class);
    }
    @Test void manualNewOrdersCannotAcceptAMissingListAsEmptySuccess() {
        var api = new WbApiClient(){@Override public WbOrdersResponse getNewOrders(String key){return JSON.fromJson("{}",WbOrdersResponse.class);}};
        assertThrows(IOException.class,()->orders(api).syncNewOrders(shop));
    }
    @Test void failedHistoryPageCannotAdvanceTheCompletedWindow() {
        var calls=new AtomicInteger();var api=new WbApiClient(){@Override public WbOrdersResponse getOrders(String key,long next,int limit,Long from,Long to)throws IOException{
            if(calls.incrementAndGet()==1)return fullOrders();throw new IOException("NETWORK_TIMEOUT");
        }};
        assertThrows(IOException.class,()->orders(api).syncOrdersWindow(shop));
        assertNull(new WbSyncStateRepository().getShopSyncState(1).ordersWindowTo());
    }
    @Test void repeatedHistoryCursorIsStoppedBeforeAnotherRequest() {
        var calls=new AtomicInteger();var api=new WbApiClient(){@Override public WbOrdersResponse getOrders(String key,long next,int limit,Long from,Long to)throws IOException{
            if(calls.incrementAndGet()>2)throw new IOException("UNEXPECTED_REPLAY");return fullOrders();
        }};
        assertEquals("FBS_INVALID_CURSOR",assertThrows(IOException.class,()->orders(api).syncOrdersWindow(shop)).getMessage());assertEquals(2,calls.get());
    }
    @Test void partialStatusesCannotClearExistingOrderState() throws Exception {
        var api=new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String key,java.util.List<Long> ids){return JSON.fromJson("{\"orders\":[{\"id\":1,\"supplierStatus\":\"cancel\"}]}",WbOrderStatusesResponse.class);}};
        assertThrows(IOException.class,()->orders(api).syncOrderStatusesForSupply(shop,"S"));
        assertEquals(2,count("SELECT count(*) FROM wb_orders WHERE supplier_status='new' AND wb_status='waiting'"));
    }
    @Test void missingShipmentContentsCannotDetachExistingOrders() throws Exception {
        var api=new WbApiClient(){@Override public WbSupplyOrderIdsResponse getSupplyOrderIds(String key,String id){return JSON.fromJson("{}",WbSupplyOrderIdsResponse.class);}};
        assertThrows(IOException.class,()->orders(api).syncSupplyOrders(shop,"S"));
        assertEquals(2,count("SELECT count(*) FROM wb_supply_orders WHERE supply_id='S'"));
    }
    @Test void missingShipmentCountCannotOverwriteTheKnownCountWithZero() throws Exception {
        var api=new WbApiClient(){@Override public WbSupplyOrderIdsResponse getSupplyOrderIds(String key,String id){return JSON.fromJson("{}",WbSupplyOrderIdsResponse.class);}};
        assertThrows(IOException.class,()->supplies(api).syncOpenSupplyCounts(shop));
        assertEquals(2,count("SELECT order_count FROM wb_supplies WHERE supply_id='S'"));
    }
    @Test void missingSupplyListIsAnErrorInManualIncrementalSync() {
        var api=new WbApiClient(){@Override public WbSuppliesResponse getSupplies(String key,long next,int limit){return JSON.fromJson("{}",WbSuppliesResponse.class);}};
        assertThrows(IOException.class,()->supplies(api).syncIncremental(shop));
        assertNull(new WbSyncStateRepository().getShopSyncState(1).suppliesLastSyncedAt());
    }
}
