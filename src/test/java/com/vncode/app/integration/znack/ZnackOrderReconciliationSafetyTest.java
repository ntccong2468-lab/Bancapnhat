package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackOrderReconciliationSafetyTest {
    private static final String GTIN="04601234567890";
    @TempDir Path directory;
    ZnackRepository repository;Settings settings;
    AtomicReference<JsonObject> response=new AtomicReference<>();AtomicInteger reads=new AtomicInteger(),buys=new AtomicInteger(),polls=new AtomicInteger();
    ZnackKizOrderService orders;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Fixture"));repository.upsertProducts(List.of(new Product(GTIN,"Synthetic",null,null,null,null,null)));
        var commandProcessor=System.getenv("COMSPEC");Path executable;
        if(commandProcessor!=null&&Files.isRegularFile(Path.of(commandProcessor)))executable=Path.of(commandProcessor);
        else{executable=directory.resolve("synthetic-cryptcp");Files.writeString(executable,"synthetic fixture, never executed");assertTrue(executable.toFile().setExecutable(true));}
        settings=new Settings("","","oms","connection","","","","","certificate","[]","","","",false,"","[]","",Instant.now(),"",executable.toString(),"",60,"");
        var api=new ZnackApiClient(){@Override public JsonObject orderList(String base,String token,String oms){assertEquals("oms",oms);reads.incrementAndGet();return response.get().deepCopy();}};
        var auth=new ZnackAuthService(api,(input,context)->null){@Override public String suzToken(Settings ignored){return "synthetic-token";}};
        orders=new ZnackKizOrderService(api,auth,(input,context)->null,repository){
            @Override public KizOrder buy(Settings ignored,String gtin,int quantity){buys.incrementAndGet();throw new AssertionError("Recovery must not create another paid order");}
            @Override public KizOrder refresh(Settings ignored,long id){polls.incrementAndGet();return repository.findOrder(id).orElseThrow();}
        };
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}
    private KizOrder local(){return repository.findOrder(repository.createDraft(GTIN,2)).orElseThrow();}
    private static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    private static JsonObject candidate(String id,long at){return object("{\"orderId\":\""+id+"\",\"orderStatus\":\"READY\",\"createdTimestamp\":"+at+",\"buffers\":[{\"gtin\":\""+GTIN+"\",\"totalCodes\":2}]}");}
    private void list(JsonObject... rows){var list=new JsonArray();for(var row:rows)list.add(row);var root=new JsonObject();root.add("orderInfos",list);response.set(root);}
    @Test void malformedWrapperAndBusinessFailureAreUnknownInsteadOfConfirmedAbsence(){
        var local=local();for(String json:List.of("{}","{\"orderInfos\":null}","{\"orderInfos\":{}}","{\"orderInfos\":[],\"globalErrors\":[{\"errorCode\":1090}]}","{\"orderInfos\":[],\"error\":true}","{\"orderInfos\":[],\"omsId\":\"other\"}")){
            response.set(object(json));var failure=assertThrows(IOException.class,()->orders.reconcile(settings,local));assertEquals("SUZ_ORDER_RECONCILIATION_INVALID",failure.getMessage());
        }assertEquals(0,buys.get());
    }
    @Test void aMalformedLaterRowCannotBeSkippedAfterAnOtherwiseExactMatch(){
        var local=local();var valid=candidate("valid",local.createdAt().toEpochMilli());
        var malformed=new ArrayList<JsonObject>();
        for(String missing:List.of("orderId","orderStatus","createdTimestamp","buffers")){var row=valid.deepCopy();row.addProperty("orderId","other");row.remove(missing);malformed.add(row);}
        var fractionalTime=valid.deepCopy();fractionalTime.addProperty("orderId","fractional-time");fractionalTime.addProperty("createdTimestamp",1.5);malformed.add(fractionalTime);
        for(String raw:List.of("null","{}","[]","[null]","[{\"gtin\":\"broken\",\"totalCodes\":2}]","[{\"gtin\":\""+GTIN+"\",\"totalCodes\":2.5}]","[{\"gtin\":\""+GTIN+"\",\"totalCodes\":-1}]","[{\"gtin\":\""+GTIN+"\"}]")){
            var row=valid.deepCopy();row.addProperty("orderId","bad-buffer");row.add("buffers",JsonParser.parseString(raw));malformed.add(row);
        }
        for(var row:malformed){list(valid,row);assertThrows(IOException.class,()->orders.reconcile(settings,local));}
        list(valid,valid.deepCopy());assertThrows(IOException.class,()->orders.reconcile(settings,local));
    }
    @Test void validEmptyExactOneAndConflictingMatchesRemainDistinctWithoutInventingMandatoryOms()throws Exception{
        var local=local();list();assertEquals(ZnackKizOrderService.ReconciliationStatus.NOT_FOUND,orders.reconcile(settings,local).status());
        list(candidate("one",local.createdAt().toEpochMilli()));var matched=orders.reconcile(settings,local);assertEquals(ZnackKizOrderService.ReconciliationStatus.MATCHED,matched.status());assertEquals("one",matched.order().externalOrderId());
        response.get().addProperty("omsId","oms");assertEquals(ZnackKizOrderService.ReconciliationStatus.MATCHED,orders.reconcile(settings,local).status());
        list(candidate("one",local.createdAt().toEpochMilli()),candidate("two",local.createdAt().toEpochMilli()));assertEquals(ZnackKizOrderService.ReconciliationStatus.CONFLICT,orders.reconcile(settings,local).status());
    }
    @Test void malformedListAfterGraceKeepsUnknownPurchaseAndTheNextRequestQueued()throws Exception{
        long local=local().id();repository.updateOrder(local,null,null,OrderStatus.FAILED,"response lost");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE kiz_orders SET created_at=? WHERE id=?")){p.setString(1,Instant.now().minusSeconds(600).toString());p.setLong(2,local);p.executeUpdate();}
        long pending=repository.createPipeline(GTIN,2);repository.updatePipeline(pending,local,PurchaseStage.RECONCILING_ORDER,"response lost");long next=repository.enqueuePipeline(GTIN,2,null);
        var coordinator=new ZnackPurchaseCoordinator(repository,orders,null,null);response.set(object("{}"));
        for(int attempt=0;attempt<2;attempt++){assertThrows(IOException.class,()->coordinator.advance(settings,pending));assertEquals(PurchaseStage.RECONCILING_ORDER,repository.findPipeline(pending).orElseThrow().stage());assertEquals(PurchaseStage.QUEUED,repository.findPipeline(next).orElseThrow().stage());}
        assertEquals(local,repository.findPipeline(pending).orElseThrow().orderId());assertEquals(2,reads.get());assertEquals(0,buys.get());
    }
    @Test void legacyPreSendLabelsWithAcceptedOrderUseExactOrderReadbackAndNeverBuyAgain()throws Exception{
        for(var stage:List.of(PurchaseStage.QUEUED,PurchaseStage.VALIDATING,PurchaseStage.CREATING_ORDER,PurchaseStage.RECONCILING_ORDER)){
            long local=local().id();repository.updateOrder(local,"accepted-"+local,"CREATED",OrderStatus.SUBMITTED,null);
            long pending=repository.enqueuePipeline(GTIN,2,null);repository.updatePipeline(pending,local,stage,"legacy checkpoint");
            new ZnackPurchaseCoordinator(repository,orders,null,null).advance(settings,pending);
            assertEquals(PurchaseStage.POLLING_ORDER,repository.findPipeline(pending).orElseThrow().stage());assertEquals(local,repository.findPipeline(pending).orElseThrow().orderId());
            repository.updatePipeline(pending,local,PurchaseStage.COMPLETED,null);
        }assertEquals(4,polls.get());assertEquals(0,reads.get());assertEquals(0,buys.get());
    }
}
