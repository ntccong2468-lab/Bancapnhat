package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in counting fixtures only; no purchase, signing or remote API is called. */
class KizPurchaseAvailabilityPolicyTest {
    @TempDir Path path;
    private final String gtin=KizWarehouseTest.GTIN;
    private Shop shop;
    private ZnackRepository repository;
    private Buyer buyer;
    private KizStockReplenisher stock;
    private final AtomicInteger eligible=new AtomicInteger(1);
    private KizPurchaseAvailability policy;

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(1,'"+gtin+"','2026-01-01T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+gtin+"',3,'READY','fixture','fixture')");
        }
        shop=new Shop(1,"Fixture","fixture");repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));
        buyer=new Buyer();stock=new KizStockReplenisher(ignored->buyer);
        // The generic inventory has always counted these rows. An adapter may reject
        // the two legacy/malformed payloads without altering the shared WB behavior.
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,?,?,'fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture')")){
            for(String raw:List.of(KizWarehouseTest.mark(1),"legacy-unmarked","malformed-payload")){
                p.setString(1,gtin);p.setString(2,raw);p.addBatch();
            }p.executeBatch();
        }
        policy=(owner,gtins)->{
            assertEquals(shop.getId(),owner.getId());assertEquals(Set.of(gtin),Set.copyOf(gtins));
            return Map.of(gtin,eligible.get());
        };
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void defaultPurchasedCountsAndOldPendingConstructorKeepTheirExistingBehavior()throws Exception{
        assertEquals(3,new ZnackGtinInventoryService().purchasedAvailableCounts(1,List.of(gtin)).get(gtin));
        var result=stock.ensurePurchased(shop,Map.of(gtin,3),missing->fail("No shortage under the unchanged default counter"));
        assertTrue(result.missing().isEmpty());assertEquals(0,buyer.quantity);
        var pending=new KizPurchasePendingException(Map.of(gtin,4),new KizStockReplenisher.Result(Map.of(gtin,1),0,true,"existing"));
        assertEquals(Map.of(gtin,3),pending.availableCounts(shop,List.of(gtin)));
    }

    @Test void injectedPolicyBuysOnlyItsExactDeficitDespiteBroaderLegacyStock()throws Exception{
        var proof=new AtomicInteger();
        var result=stock.ensurePurchased(shop,Map.of(gtin,3),missing->{assertEquals(Map.of(gtin,2),missing);proof.incrementAndGet();},policy);
        assertEquals(Map.of(gtin,2),result.missing());assertEquals(1,result.enqueued());assertEquals(2,buyer.quantity);assertEquals(2,proof.get());
        var again=stock.ensurePurchased(shop,Map.of(gtin,3),missing->fail("Do not enqueue above an unresolved request"),policy);
        assertTrue(again.waiting());assertEquals(0,again.enqueued());assertEquals(2,buyer.quantity);assertEquals(1,repository.findActivePipelines().size());
    }

    @Test void pendingExceptionRetainsItsDynamicPolicyAndDoesNotPadMissingKeys(){
        var pending=new KizPurchasePendingException(Map.of(gtin,3),new KizStockReplenisher.Result(Map.of(gtin,2),1,true,"queued"),policy);
        assertEquals(List.of(new KizPurchasePendingException.Deficit(gtin,3,1,2)),pending.deficits());
        assertEquals(Map.of(gtin,1),pending.availableCounts(shop,List.of(gtin)));
        eligible.set(2);assertEquals(Map.of(gtin,2),pending.availableCounts(shop,List.of(gtin)));
        var empty=new KizPurchasePendingException(Map.of(gtin,3),new KizStockReplenisher.Result(Map.of(gtin,3),0,true,"existing"),(s,g)->Map.of());
        assertTrue(empty.availableCounts(shop,List.of(gtin)).isEmpty());
    }

    @Test void awaitCirculationCannotFinishOnBroaderStockThanTheAllocatorCanUse()throws Exception{
        var polls=new AtomicInteger();var exports=new AtomicInteger();var attempts=new AtomicInteger();var approvals=new AtomicInteger();
        var session=new KizPrintPurchaseSession(shop,proposal->{
            approvals.incrementAndGet();assertEquals(List.of(new KizPrintPurchaseSession.Row(gtin,3,1,2,2)),proposal.rows());return true;
        },s->{},()->false,()->{},pending->{
            assertEquals(0,exports.get());assertEquals(2,buyer.quantity);
            assertEquals(eligible.get(),policyCounts(pending));
            if(polls.incrementAndGet()==2)eligible.set(3);
        },null);
        assertEquals("PDF",session.run(()->{
            if(attempts.incrementAndGet()>2)assertEquals(3,eligible.get(),"A wait cannot retry export using only the broader generic count");
            ensure();exports.incrementAndGet();return "PDF";
        }));
        assertEquals(2,polls.get());assertEquals(1,exports.get());assertEquals(1,approvals.get());assertEquals(2,buyer.quantity);
    }

    @Test void pipelineCheckUsesTheSamePolicyBeforeDeclaringAWaitActionable()throws Exception{
        long id=repository.enqueuePipeline(gtin,2,"synthetic-completed");
        repository.updatePipeline(id,null,ZnackModels.PurchaseStage.COMPLETED,null);
        var pending=new KizPurchasePendingException(Map.of(gtin,3),new KizStockReplenisher.Result(Map.of(gtin,2),0,true,"existing"),policy);
        var attempts=new AtomicInteger();
        var session=new KizPrintPurchaseSession(shop,p->fail("No new buy is requested"),s->{},()->false,
                ()->{throw new AssertionError("The completed pipeline cannot provide adapter-eligible stock");},null,null);
        var failure=assertThrows(KizAssignmentException.class,()->session.run(()->{
            assertEquals(1,attempts.incrementAndGet(),"Broader generic stock cannot trigger a premature export retry");throw pending;
        }));
        assertEquals("KIZ_PRINT_PURCHASE_NEEDS_ACTION",failure.code());assertEquals(0,buyer.quantity);
    }

    @Test void cancellingAnExactPolicyProposalDoesNotBuyOrExport()throws Exception{
        var session=new KizPrintPurchaseSession(shop,p->{assertEquals(2,p.quantity());return false;},s->{},()->false,()->{},p->fail("Cancelled before waiting"),null);
        assertThrows(CancellationException.class,()->session.run(()->{ensure();return "PDF";}));
        assertEquals(0,buyer.quantity);assertTrue(repository.findActivePipelines().isEmpty());
    }

    @Test void anUnknownPurchaseKeepsItsOriginalPaidRequestWhilePolicyStockIsMissing()throws Exception{
        var polls=new AtomicInteger();var attempts=new AtomicInteger();
        var session=new KizPrintPurchaseSession(shop,p->true,s->{},()->false,()->{},pending->{
            var pipeline=repository.findActivePipelines().getFirst();
            repository.updatePipeline(pipeline.id(),null,ZnackModels.PurchaseStage.RECONCILING_ORDER,"synthetic response lost");
            if(polls.incrementAndGet()==3)throw new CancellationException();
        },null);
        assertThrows(CancellationException.class,()->session.run(()->{
            if(attempts.incrementAndGet()>2)fail("An unknown request must remain in the same availability wait");ensure();return "PDF";
        }));
        assertEquals(3,polls.get());assertEquals(2,buyer.quantity);assertEquals(1,repository.findActivePipelines().size());
    }

    private Integer policyCounts(KizPurchasePendingException pending){return pending.availableCounts(shop,List.of(gtin)).get(gtin);}
    private void ensure()throws Exception{
        var required=Map.of(gtin,3);var result=stock.ensurePurchased(shop,required,missing->{},policy);
        if(result.waiting())throw new KizPurchasePendingException(required,result,policy);
    }
    private class Buyer implements KizStockReplenisher.Purchases{
        int quantity;
        public void validate(String g,int n){assertEquals(gtin,g);assertTrue(n>0);}
        public void enqueueBatch(java.util.List<com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Approved> approved)throws Exception{
            for(var item:approved)enqueue(item.gtin(),item.quantity(),item.requestKey());
        }
        public void enqueue(String g,int n,String key){quantity+=n;repository.enqueuePipeline(g,n,key);}
        public void resume(){}
    }
}
