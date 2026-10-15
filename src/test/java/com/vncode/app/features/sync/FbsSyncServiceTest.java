package com.vncode.app.features.sync;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbsSyncServiceTest {
    @TempDir Path root;Shop shop;MockWebServer server;
    static final Gson JSON=new Gson();
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear()throws Exception{try{if(server!=null)server.shutdown();}finally{System.clearProperty("vncode.appdata.dir");}}
    void orders(int count)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_orders(shop_id,order_id,supplier_status,wb_status,synced_at) VALUES(1,?,'new','waiting','now')")){
            c.setAutoCommit(false);for(int id=1;id<=count;id++){p.setLong(1,id);p.addBatch();}p.executeBatch();c.commit();
        }
    }
    int count(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){return r.getInt(1);}}
    FbsSyncService wb(WbApiClient client){return new FbsSyncService(s->{throw new AssertionError("Not Ozon");},()->client);}
    @Test void thousandPlusWbStatusesUseBoundedPagesAndResumeBeyondFirstBatch()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(1205);var batches=new ArrayList<Integer>();
        var service=wb(new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){
            batches.add(ids.size());var rows=new JsonArray();for(long id:ids){var row=new JsonObject();row.addProperty("id",id);row.addProperty("supplierStatus","confirm");row.addProperty("wbStatus","waiting");rows.add(row);}var body=new JsonObject();body.add("orders",rows);return JSON.fromJson(body,WbOrderStatusesResponse.class);
        }});
        var first=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(first.complete());assertEquals(1000,first.changed());
        var second=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,first.checkpoint());assertTrue(second.complete());assertEquals(List.of(1000,205),batches);
        assertEquals(1205,count("SELECT count(*) FROM wb_orders WHERE shop_id=1 AND supplier_status='confirm'"));
    }
    @Test void incompleteWbStatusBatchCannotAdvanceOrSilentlyReplaceExistingState()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(2);
        var service=wb(new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){return JSON.fromJson("{\"orders\":[{\"id\":1,\"supplierStatus\":\"cancel\"}]}",WbOrderStatusesResponse.class);}});
        assertEquals("FBS_INCOMPLETE_RESPONSE",assertThrows(java.io.IOException.class,()->service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"")).getMessage());
        assertEquals(2,count("SELECT count(*) FROM wb_orders WHERE supplier_status='new'"));
    }
    @Test void preferredSupplyRefreshesMembershipAndCountBeforeOtherOpenSupplies()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(105);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,synced_at) VALUES(1,'A',0,'now'),(1,'Z',0,'now')");}
        var read=new ArrayList<String>();
        var service=wb(new WbApiClient(){
            @Override public WbSupplyDto getSupplyDetail(String token,String id){read.add(id);return JSON.fromJson("{\"id\":\""+id+"\",\"done\":false}",WbSupplyDto.class);}
            @Override public WbSupplyOrderIdsResponse getSupplyOrderIds(String token,String id){return JSON.fromJson("{\"orderIds\":"+(id.equals("Z")?java.util.stream.LongStream.rangeClosed(1,105).boxed().toList():List.of())+"}",WbSupplyOrderIdsResponse.class);}
        });service.selectSupply(1,"Z");
        var first=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,"");assertEquals(105,first.changed());assertFalse(first.complete());
        var second=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,first.checkpoint());assertEquals(List.of("Z","A"),read);
        assertTrue(service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,second.checkpoint()).complete());
        assertEquals(105,count("SELECT count(*) FROM wb_supply_orders WHERE shop_id=1 AND supply_id='Z'"));assertEquals(105,count("SELECT order_count FROM wb_supplies WHERE shop_id=1 AND supply_id='Z'"));
    }
    FbsSyncService ozon()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);server=new MockWebServer();server.start();
        return new FbsSyncService(s->new OzonApiClient(s.getId(),new OzonCredentials(s.getClientId(),s.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))),()->{throw new AssertionError("Not WB");});
    }
    @Test void ozonActivePostingLeavingUnfulfilledQueueIsReconciledByDetailStatus()throws Exception{
        var service=ozon();server.enqueue(new MockResponse().setBody("{\"postings\":[{\"posting_number\":\"101-1-1\",\"status\":\"awaiting_packaging\",\"products\":[]}],\"has_next\":false}"));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"").complete());
        server.enqueue(new MockResponse().setBody("{\"result\":{\"posting_number\":\"101-1-1\",\"status\":\"cancelled\",\"products\":[]}}"));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"").complete());assertEquals(1,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='cancelled'"));
        assertEquals("/v4/posting/fbs/unfulfilled/list",server.takeRequest().getPath());assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());
    }
    @Test void missingOzonPostingListIsAnErrorRatherThanSuccessfulEmptySnapshot()throws Exception{
        var service=ozon();server.enqueue(new MockResponse().setBody("{}"));
        assertEquals("FBS_INVALID_RESPONSE",assertThrows(java.io.IOException.class,()->service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"")).getMessage());
    }
}
