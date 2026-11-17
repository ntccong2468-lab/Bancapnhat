package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Original 3390 regression adapted to the current explicit availability/download-cap seam. */
class ZnackExhaustedBufferWorkflowRegressionTest {
    @TempDir Path directory;
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void exhausted3390RecoversTheOriginalIssuedBlockInsteadOfRepeatingOrdinaryDelivery()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic shop','fixture-token')");}
        var repository=new ZnackRepository(new ShopContext(1,"Synthetic shop"));String gtin="04600000000015";
        repository.upsertProducts(List.of(new Product(gtin,"Synthetic product",null,null,null,null,null)));
        var settings=new Settings("","","oms","connection","","","","","certificate","[]","","","",false,"","[]","",Instant.now(),"","","",60,"");
        repository.saveSettings(settings);
        long order=repository.createDraft(gtin,2);repository.updateOrder(order,"synthetic-order","ACTIVE",OrderStatus.CODES_READY,null);
        long pipeline=repository.enqueuePipeline(gtin,2,"synthetic-request");repository.updatePipeline(pipeline,order,PurchaseStage.DOWNLOADING_CODES,null);
        var ordinary=new AtomicInteger();var recoveries=new AtomicInteger();
        List<String> raw=List.of("01"+gtin+"21SERIAL0000001\u001d91ABCD\u001d92synthetic-one",
                "01"+gtin+"21SERIAL0000002\u001d91EFGH\u001d92synthetic-two");
        var codes=new ZnackKizCodeService(null,null,repository){
            @Override public int download(Settings original,long id,int maximumNewCodes)throws Exception{
                assertEquals(2,maximumNewCodes);return download(original,id);
            }
            @Override public int download(Settings ignored,long id)throws Exception{
                ordinary.incrementAndGet();throw new ZnackApiClient.ZnackApiException("Synthetic SUZ response",400,
                        "{\"globalErrors\":[{\"errorCode\":3390,\"error\":\"Буфер не активный. Текущий статус буфера: EXHAUSTED\"}]}");
            }
            @Override public int recoverIssuedBlocks(Settings ignored,long id){
                recoveries.incrementAndGet();return repository.insertCodes(id,gtin,new DownloadedCodes(raw,"issued-block"));
            }
        };
        var orders=new ZnackKizOrderService(null,null,null,repository){
            @Override public java.util.OptionalInt availableCodes(long id){return java.util.OptionalInt.of(2);}
            @Override public KizOrder buy(Settings original,String product,int quantity){throw new AssertionError("Recovery must not buy again");}
            @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings original,long batchId){throw new AssertionError("Recovery must not enter the paid batch engine");}
        };
        assertDoesNotThrow(()->new ZnackPurchaseCoordinator(repository,orders,codes,null).advance(settings,pipeline));
        assertEquals(1,ordinary.get());assertEquals(1,recoveries.get());assertEquals(2,repository.findCodes(order).size());
        assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());
        assertEquals(OrderStatus.WAITING_INTRODUCTION_READINESS,repository.findOrder(order).orElseThrow().localStatus());
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(1,repository.findOrders().size(),"Recovery must not create another paid order");
        assertEquals(raw,repository.findCodes(order).stream().map(KizCode::rawCode).toList(),"Full CIS and crypto separators must survive recovery unchanged");
    }
}
