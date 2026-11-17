package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** A paused signer must unwind the in-memory print wait, retaining the original paid request. */
class KizPrintPurchasePauseTest {
    private static final int SHOP=901732;
    private static final String GTIN="04600000000015";
    @TempDir Path directory;
    private Shop shop;
    private ZnackRepository repository;
    private Buyer buyer;
    private KizStockReplenisher stock;
    private final AtomicInteger pauses=new AtomicInteger(),exports=new AtomicInteger();

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,api_key) VALUES(?,'Pause fixture','fixture')")){
            p.setInt(1,SHOP);p.executeUpdate();
        }
        repository=new ZnackRepository(new ShopContext(SHOP,"Pause fixture"));
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product",null,null,null,null,null)));
        buyer=new Buyer();stock=new KizStockReplenisher(ignored->buyer);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    static Stream<Arguments> pausedStages(){
        return Stream.of(Marketplace.WILDBERRIES,Marketplace.OZON).flatMap(market->
                Stream.of(PurchaseStage.DOWNLOADING_CODES,PurchaseStage.WAITING_INTRODUCTION_READINESS,
                        PurchaseStage.POLLING_INTRODUCTION).map(stage->Arguments.of(market,stage)));
    }
    @ParameterizedTest @MethodSource("pausedStages")
    void signingPauseStopsBusyWaitWithoutSigningPrintingOrBuyingAgain(Marketplace market,PurchaseStage stage)throws Exception{
        select(market);buyer.pausedStage=stage;
        var signer=ZnackSigningSession.guard(SHOP,(bytes,context)->{fail("Waiting must not authorize signing");return null;});
        assertThrows(CryptoProException.class,()->signer.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        var failure=assertThrows(IllegalStateException.class,()->session(this::boundedPause).run(this::export));
        assertTrue(failure.getMessage().contains(I18nService.getInstance().tr("znack.error.signing_confirmation_required")));
        assertTrue(failure.getMessage().contains(GTIN));assertEquals(0,pauses.get(),"A stopped signer has no automatic poll to await");
        assertEquals(1,buyer.quantity);assertEquals(0,exports.get());assertFalse(KizPrintPurchaseSession.active());
        var original=repository.findPipeline(buyer.pipeline).orElseThrow();
        assertEquals(stage,original.stage());assertEquals(ZnackSigningSession.signingWaitMessage(SHOP),original.errorMessage());
        assertEquals(buyer.pipeline,repository.findPipelineByRequestKey(buyer.request).orElseThrow().id());
        assertEquals(1,repository.findActivePipelines().size());
        assertThrows(CryptoProException.class,()->signer.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void queuedRequestReportsTheOlderPausedDownloadThatBlocksItsQueue(Marketplace market)throws Exception{
        select(market);
        long older=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        repository.updatePipeline(older,null,PurchaseStage.DOWNLOADING_CODES,ZnackSigningSession.signingWaitMessage(SHOP));
        long queued=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        assertEquals(PurchaseStage.QUEUED,repository.findPipeline(queued).orElseThrow().stage());
        var failure=assertThrows(IllegalStateException.class,()->session(this::boundedPause).run(this::export));
        assertTrue(failure.getMessage().contains(I18nService.getInstance().tr("znack.error.signing_confirmation_required")));
        assertEquals(0,pauses.get());assertEquals(0,buyer.quantity);assertEquals(0,exports.get());
        assertEquals(PurchaseStage.DOWNLOADING_CODES,repository.findPipeline(older).orElseThrow().stage());
        assertEquals(PurchaseStage.QUEUED,repository.findPipeline(queued).orElseThrow().stage());
        assertFalse(KizPrintPurchaseSession.active());
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void anotherActiveRequestStillIdentifiesTheOlderPausedCirculationLot(Marketplace market)throws Exception{
        select(market);
        long older=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        repository.updatePipeline(older,null,PurchaseStage.WAITING_INTRODUCTION_READINESS,ZnackSigningSession.signingWaitMessage(SHOP));
        long newer=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        assertEquals(PurchaseStage.VALIDATING,repository.findPipeline(newer).orElseThrow().stage());
        var failure=assertThrows(KizPrintPurchaseSession.PurchasePaused.class,()->session(this::boundedPause).run(this::export));
        assertEquals(older,failure.pipelineId());assertEquals(GTIN,failure.gtin());
        assertTrue(failure.getMessage().contains("#"+older));assertEquals(0,pauses.get());
        assertEquals(0,buyer.quantity);assertEquals(0,exports.get());assertEquals(2,repository.findActivePipelines().size());
        assertEquals(PurchaseStage.VALIDATING,repository.findPipeline(newer).orElseThrow().stage());
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void ordinaryPendingResultStillWaitsForCirculationAndExportsWithoutAnotherPurchase(Marketplace market)throws Exception{
        select(market);buyer.pausedStage=null;
        assertEquals("PDF",session(()->{
            assertEquals(1,pauses.incrementAndGet());
            var pipeline=repository.findPipeline(buyer.pipeline).orElseThrow();
            assertEquals(PurchaseStage.DOWNLOADING_CODES,pipeline.stage());
            long order=repository.createDraft(GTIN,1);
            repository.insertCodes(order,GTIN,new DownloadedCodes(List.of("synthetic-ready"),"fixture-block"));
            repository.markCodes(order,KizLegalStatus.IN_CIRCULATION,null,null);
        }).run(this::export));
        assertEquals(1,pauses.get());assertEquals(1,buyer.quantity);assertEquals(1,exports.get());
        assertFalse(KizPrintPurchaseSession.active());
    }

    private KizPrintPurchaseSession session(Runnable pause){
        return new KizPrintPurchaseSession(shop,p->true,ignored->{},()->false,pause,null,null);
    }
    private String export()throws Exception{
        var result=stock.ensurePurchased(shop,Map.of(GTIN,1),ignored->{});
        if(result.waiting())throw new KizPurchasePendingException(Map.of(GTIN,1),result);
        exports.incrementAndGet();return "PDF";
    }
    private void boundedPause(){
        if(pauses.incrementAndGet()>=2)fail("Signing is stopped, but the print wait keeps polling forever");
    }
    private void select(Marketplace marketplace)throws Exception{
        String client=marketplace==Marketplace.OZON?"fixture-client":null;
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET marketplace=?,client_id=? WHERE id=?")){
            p.setString(1,marketplace.name());p.setString(2,client);p.setInt(3,SHOP);p.executeUpdate();
        }
        shop=new Shop(SHOP,"Pause fixture",marketplace,client,"fixture");
    }
    private final class Buyer implements KizStockReplenisher.Purchases {
        int quantity;long pipeline;String request;PurchaseStage pausedStage;
        public void validate(String gtin,int count){}
        public void enqueueBatch(java.util.List<com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Approved> approved)throws Exception{
            for(var item:approved)enqueue(item.gtin(),item.quantity(),item.requestKey());
        }
        public void enqueue(String gtin,int count,String key){
            quantity+=count;request=key;pipeline=repository.enqueuePipeline(gtin,count,key);
            repository.updatePipeline(pipeline,null,pausedStage==null?PurchaseStage.DOWNLOADING_CODES:pausedStage,
                    pausedStage==null?"Synthetic temporary network wait":ZnackSigningSession.signingWaitMessage(SHOP));
        }
        public void resume(){}
    }
}
