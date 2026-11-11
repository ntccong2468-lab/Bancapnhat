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

/** Uses only released1.6.7 APIs, allowing an exact-source RED before the repair. */
class ZnackExhaustedBufferWorkflowRegressionTest {
    @TempDir Path directory;
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void exhausted3390RecoversTheOriginalIssuedBlockInsteadOfRepeatingOrdinaryDelivery()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic shop','fixture-token')");}
        var repository=new ZnackRepository(new ShopContext(1,"Synthetic shop"));String gtin="04600000000015";
        repository.upsertProducts(List.of(new Product(gtin,"Synthetic product",null,null,null,null,null)));
        long order=repository.createDraft(gtin,2);repository.updateOrder(order,"synthetic-order","ACTIVE",OrderStatus.CODES_READY,null);
        long pipeline=repository.enqueuePipeline(gtin,2,"synthetic-request");repository.updatePipeline(pipeline,order,PurchaseStage.DOWNLOADING_CODES,null);
        var ordinary=new AtomicInteger();var recoveries=new AtomicInteger();
        var codes=new ZnackKizCodeService(null,null,repository){
            @Override public int download(Settings ignored,long id)throws Exception{
                ordinary.incrementAndGet();throw new ZnackApiClient.ZnackApiException("Synthetic SUZ response",400,
                        "{\"globalErrors\":[{\"errorCode\":3390,\"error\":\"Буфер не активный. Текущий статус буфера: EXHAUSTED\"}]}");
            }
            @Override public int recoverIssuedBlocks(Settings ignored,long id){
                recoveries.incrementAndGet();return repository.insertCodes(id,gtin,new DownloadedCodes(List.of("synthetic-code-one","synthetic-code-two"),"issued-block"));
            }
        };
        var settings=new Settings("","","oms","connection","","","","","certificate","[]","","","",false,"","[]","",Instant.now(),"","","",60,"");
        assertDoesNotThrow(()->new ZnackPurchaseCoordinator(repository,null,codes,null).advance(settings,pipeline));
        assertEquals(1,ordinary.get());assertEquals(1,recoveries.get());assertEquals(2,repository.findCodes(order).size());
        assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());
        assertEquals(OrderStatus.WAITING_INTRODUCTION_READINESS,repository.findOrder(order).orElseThrow().localStatus());
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(1,repository.findOrders().size(),"Recovery must not create another paid order");
    }
}
