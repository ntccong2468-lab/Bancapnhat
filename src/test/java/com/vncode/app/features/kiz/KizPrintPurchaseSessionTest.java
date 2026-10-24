package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class KizPrintPurchaseSessionTest {
    @TempDir Path path;
    Shop shop;ZnackRepository repository;KizStockReplenisher stock;Buyer buyer;
    final String gtin=KizWarehouseTest.GTIN;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(1,'"+gtin+"','2026-01-01T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+gtin+"',5,'READY','fixture','fixture')");
        }
        shop=new Shop(1,"WB","fixture");repository=new ZnackRepository(new ZnackModels.ShopContext(1,"WB"));buyer=new Buyer();stock=new KizStockReplenisher(s->buyer);
        addCodes(2,"IN_CIRCULATION");
    }
    @AfterEach void reset(){System.clearProperty("vncode.appdata.dir");}
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void applyBuysOnlyShortageAndExportsOnceOnlyAfterCirculation(Marketplace market)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET marketplace=?,client_id=? WHERE id=1")){p.setString(1,market.name());p.setString(2,market==Marketplace.OZON?"client":null);p.executeUpdate();}
        shop=new Shop(1,market.name(),market,market==Marketplace.OZON?"client":null,"fixture");
        var confirmations=new AtomicInteger();var polls=new AtomicInteger();var exports=new AtomicInteger();var messages=new ArrayList<String>();
        var session=new KizPrintPurchaseSession(shop,proposal->{
            assertEquals(0,buyer.quantity,"No paid request before Apply");confirmations.incrementAndGet();
            assertEquals(List.of(new KizPrintPurchaseSession.Row(gtin,5,2,3,3)),proposal.rows());return true;
        },messages::add,()->false,()->{},pending->{
            assertEquals(0,exports.get());assertEquals(3,buyer.quantity);
            if(polls.incrementAndGet()==1){assertEquals(2,available());addCodes(3,"RECEIVED");}
            else {assertEquals(2,available(),"Downloaded codes must not count as circulating");circulate();}
        },null);
        assertEquals("PDF",session.run(()->{ensure();exports.incrementAndGet();return "PDF";}));
        assertEquals(1,confirmations.get());assertEquals(2,polls.get());assertEquals(3,buyer.quantity);assertEquals(1,exports.get());assertFalse(messages.isEmpty());assertEquals(1,repository.findActivePipelines().size());
    }
    @Test void readonlyPreflightDefersShortageWithoutPromptOrPaidEnqueue()throws Exception{
        KizPrintPurchaseSession.preflight(shop,()->{ensure();return null;});
        assertEquals(0,buyer.quantity);assertTrue(repository.findActivePipelines().isEmpty());assertFalse(KizPrintPurchaseSession.active());
    }
    @Test void cancelProposalNeverBuysOrExports()throws Exception{
        var exports=new AtomicInteger();var session=session(p->false,p->fail("Must not wait"));
        assertThrows(CancellationException.class,()->session.run(()->{ensure();exports.incrementAndGet();return null;}));
        assertEquals(0,buyer.quantity);assertEquals(0,exports.get());assertTrue(repository.findActivePipelines().isEmpty());assertFalse(KizPrintPurchaseSession.active());
    }
    @Test void enoughStockSkipsProposalAndPurchase()throws Exception{
        addCodes(3,"IN_CIRCULATION");var session=session(p->{fail("Stock is sufficient");return false;},p->fail());
        assertEquals("PDF",session.run(()->{ensure();return "PDF";}));assertEquals(0,buyer.quantity);
    }
    @Test void pendingPurchaseResumesWithoutBuyingAgain()throws Exception{
        repository.enqueuePipeline(gtin,3,UUID.randomUUID().toString());
        var session=session(p->{assertEquals(0,p.quantity());return true;},pending->{addCodes(3,"IN_CIRCULATION");});
        assertEquals("PDF",session.run(()->{ensure();return "PDF";}));assertEquals(0,buyer.quantity);assertEquals(1,buyer.resumes);
    }
    @Test void changedSettingsWhileProposalIsOpenCannotAuthorizePurchase()throws Exception{
        var session=session(p->{repository.saveSettings(ZnackModels.Settings.empty().withAutoIntroduction(true));return true;},p->fail());
        assertEquals("KIZ_OWNER_CHANGED",assertThrows(IllegalStateException.class,()->session.run(()->{ensure();return null;})).getMessage());
        assertEquals(0,buyer.quantity);assertTrue(repository.findActivePipelines().isEmpty());
    }
    @Test void ownerChangesAfterApplyStopsExportAndRetainsTheOriginalPaidRequest()throws Exception{
        var session=session(p->true,p->{try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='changed' WHERE id=1");}catch(Exception e){throw new RuntimeException(e);}});
        assertThrows(SecurityException.class,()->session.run(()->{ensure();return "PDF";}));assertEquals(3,buyer.quantity);assertEquals(1,repository.findActivePipelines().size());
    }
    @Test void ambiguousPurchaseWaitDoesNotEnqueueASecondRequest()throws Exception{
        var polls=new AtomicInteger();var session=session(p->true,p->{var pipeline=repository.findActivePipelines().getFirst();repository.updatePipeline(pipeline.id(),null,ZnackModels.PurchaseStage.RECONCILING_ORDER,"response lost");if(polls.incrementAndGet()==3)throw new CancellationException();});
        assertThrows(CancellationException.class,()->session.run(()->{ensure();return "PDF";}));assertEquals(3,buyer.quantity);assertEquals(3,polls.get());assertEquals(1,repository.findActivePipelines().size());
    }
    @Test void failedIntroductionReportsActionAndDoesNotPrintOrRepurchase()throws Exception{
        var session=new KizPrintPurchaseSession(shop,p->true,s->{},()->false,()->{
            var pipeline=repository.findActivePipelines().getFirst();repository.updatePipeline(pipeline.id(),null,ZnackModels.PurchaseStage.INTRODUCTION_FAILED,"fixture");
        },null,null);
        var failure=assertThrows(KizAssignmentException.class,()->session.run(()->{ensure();return "PDF";}));
        assertEquals("KIZ_PRINT_PURCHASE_NEEDS_ACTION",failure.code());assertEquals(gtin,failure.product());assertEquals(3,buyer.quantity);
    }
    @Test void unverifiedStockWithoutAnActivePipelineStopsForReviewRatherThanBuyingAgain()throws Exception{
        addCodes(3,"RECEIVED");var session=session(p->{assertEquals(0,p.quantity());return true;},p->fail("Review must not spin"));
        assertEquals("review",assertThrows(KizPurchasePendingException.class,()->session.run(()->{ensure();return null;})).reason());assertEquals(0,buyer.quantity);
    }
    @Test void perPrintCirculationIsDurableAndDoesNotChangeGlobalOrOlderPipelineSettings()throws Exception{
        var original=ZnackModels.Settings.empty();repository.saveSettings(original);
        long previous=repository.enqueuePipeline(gtin,1,UUID.randomUUID().toString());
        long print=repository.enqueuePrintPipeline(gtin,3,UUID.randomUUID().toString(),original);
        assertEquals(original,repository.getSettings());assertFalse(repository.pipelineSettings(previous).autoIntroduction());assertTrue(repository.pipelineSettings(print).autoIntroduction());
        repository.saveSettings(original.withDefaultGoodsDocument("CONFORMITY_DECLARATION","fixture","01.01.2026"));
        assertEquals(original.withAutoIntroduction(true),new ZnackRepository(repository.shop()).pipelineSettings(print));
        assertThrows(ZnackRepository.SettingsConflictException.class,()->repository.enqueuePrintPipeline(gtin,2,UUID.randomUUID().toString(),original));
        try(var c=Database.getConnection();var s=c.createStatement()){
            try(var rows=s.executeQuery("PRAGMA integrity_check")){assertTrue(rows.next());assertEquals("ok",rows.getString(1));}
            try(var rows=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM znack_purchase_pipelines")){assertTrue(rows.next());assertEquals(2,rows.getInt(1),"Rejected snapshot must rollback its pipeline");}
        }
    }
    private KizPrintPurchaseSession session(java.util.function.Predicate<KizPrintPurchaseSession.Proposal> consent,java.util.function.Consumer<KizPurchasePendingException> poll){return new KizPrintPurchaseSession(shop,consent,s->{},()->false,()->{},poll,null);}
    private void ensure()throws Exception{var result=stock.ensurePurchased(shop,Map.of(gtin,5),missing->{});if(result.waiting())throw new KizPurchasePendingException(Map.of(gtin,5),result);}
    private int available(){return new ZnackGtinInventoryService().purchasedAvailableCounts(1,List.of(gtin)).getOrDefault(gtin,0);}
    private void circulate(){try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION'");}catch(Exception e){throw new RuntimeException(e);}}
    private void addCodes(int number,String status){try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,?,?,'fixture','AVAILABLE',?,'fixture','fixture')")){for(int i=0;i<number;i++){p.setString(1,gtin);p.setString(2,UUID.randomUUID().toString());p.setString(3,status);p.addBatch();}p.executeBatch();}catch(Exception e){throw new RuntimeException(e);}}
    class Buyer implements KizStockReplenisher.Purchases {
        int quantity,resumes;
        public void validate(String g,int n){}
        public void enqueue(String g,int n,String key){quantity+=n;repository.enqueuePipeline(g,n,key);}
        public void resume(){resumes++;}
    }
}
