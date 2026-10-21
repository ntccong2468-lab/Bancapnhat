package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Kn3BatchReplacementTest {
    @TempDir Path root;Shop shop;final Kn3Repository repository=new Kn3Repository();
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,100,'Synthetic','fixture')");s.execute("INSERT INTO wb_orders(shop_id,order_id,nm_id,article,supplier_status,synced_at) VALUES(1,101,100,'A','confirm','fixture'),(1,102,100,'B','confirm','fixture'),(1,103,100,'C','confirm','fixture')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Kn3Mark red(String id){return new Kn3Mark(id,"","","old-"+id,Kn3Mark.State.REJECTED,"sgtinRetired");}
    Kiz reserveStock()throws Exception{
        var categories=new KizStockCategoryRepository();var category=categories.create(shop,"Synthetic category");categories.assignProducts(shop,category.id(),List.of(new ProductKey(1,Marketplace.WILDBERRIES,"100","")));
        var file=root.resolve("synthetic.txt");Files.writeString(file,"0104645588781154210000000000001\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=");
        var imported=new KizWarehouseService().importFile(shop,KizImportReader.read(file),true);categories.classifyBatch(shop,category.id(),imported.batch());
        return new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("101","100",1))).get("101").getFirst();
    }
    String stockState(Kiz stock)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("SELECT status FROM kiz_codes WHERE id=?")){p.setInt(1,stock.getId());try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    @Test void batchUsesImportedCategoryStockWithoutZnackOrGtinChecks()throws Exception{
        var stock=reserveStock();new ZnackGtinInventoryService().release(shop.getId(),List.of(stock));try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM znack_settings");}
        var next=new Kn3BatchReplacement.StockActions().reserve(shop,new Kn3BatchReplacement.Row(red("101"),"100","not-a-gtin","","Synthetic"),KizSourceSelection.SELLER_ONLY);
        assertEquals(stock.getId(),next.getId());assertEquals("RESERVED",stockState(next));new Kn3BatchReplacement.StockActions().releaseUnclaimed(shop,next);assertEquals("AVAILABLE",stockState(next));
    }
    @Test void pendingStockSurvivesRestartCleanupAndOnlyReadbackConsumesIt()throws Exception{
        var stock=reserveStock();var old=red("101");repository.observe(shop,"101",List.of(old));var id=repository.begin(shop,old,stock.getCode(),"{}",stock);repository.dispatch(shop,id,"AWAITING");
        Database.initDatabase();assertEquals(0,new ZnackGtinInventoryService().releaseRecoverableReservations());assertEquals("RESERVED",stockState(stock));
        repository.observe(shop,"101",List.of(new Kn3Mark("101","","",stock.getCode(),Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));assertEquals("CONSUMED",stockState(stock));
        repository.dispatch(shop,id,"AWAITING");assertEquals(Kn3Mark.State.ACCEPTED,Kn3Mark.summary(repository.read(shop,"101")),"Late HTTP acknowledgement must not reopen a confirmed replacement");
    }
    @Test void restartReleasesOnlyProvablyUnsentReplacementStock()throws Exception{
        var stock=reserveStock();var old=red("101");repository.observe(shop,"101",List.of(old));repository.begin(shop,old,stock.getCode(),"{}",stock);
        assertEquals(1,Kn3Repository.recoverUnsent());assertEquals("AVAILABLE",stockState(stock));assertEquals(Kn3Mark.State.REJECTED,Kn3Mark.summary(repository.read(shop,"101")));
        var next=new Kn3BatchReplacement.StockActions().reserve(shop,new Kn3BatchReplacement.Row(old,"100","","",""),KizSourceSelection.SELLER_ONLY);var id=repository.begin(shop,old,next.getCode(),"{}",next);repository.dispatch(shop,id,"SENDING");assertEquals(0,Kn3Repository.recoverUnsent());assertEquals("RESERVED",stockState(next));
    }
    @Test void deletingShopCascadesReplacementStockLinks()throws Exception{
        var stock=reserveStock();repository.begin(shop,red("101"),stock.getCode(),"{}",stock);
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM shops WHERE id=?")){p.setInt(1,shop.getId());assertEquals(1,p.executeUpdate());}
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kn3_replacement_stock")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void knownUnappliedHttpFailureReturnsTheUnusedCodeInsteadOfBurningIt()throws Exception{
        var stock=reserveStock();var old=red("101");repository.observe(shop,"101",List.of(old));var id=repository.begin(shop,old,stock.getCode(),"{}",stock);repository.dispatch(shop,id,"SENDING");repository.dispatch(shop,id,"FAILED");
        assertEquals("AVAILABLE",stockState(stock));assertEquals(Kn3Mark.State.REJECTED,Kn3Mark.summary(repository.read(shop,"101")));
    }
    @Test void aRejectedReplacementIsConsumedAndNeverReturnsToAvailableStock()throws Exception{
        var stock=reserveStock();var old=red("101");repository.observe(shop,"101",List.of(old));repository.begin(shop,old,stock.getCode(),"{}",stock);
        repository.observe(shop,"101",List.of(new Kn3Mark("101","","",stock.getCode(),Kn3Mark.State.REJECTED,"sgtinRetired")));
        assertEquals("CONSUMED",stockState(stock));assertEquals(0,new ZnackGtinInventoryService().releaseRecoverableReservations());
    }
    static String syntheticCode(int n){return "010464558878115421"+String.format(java.util.Locale.ROOT,"%013d",n);}
    @Test void repeatedRedReplacementsUseOtherPoolsAndNeverReturnOrReimportRejectedCodes()throws Exception{
        var category=new KizStockCategoryRepository().create(shop,"Other product pool");var file=root.resolve("repeat.txt");var codes=new ArrayList<String>();for(int n=1;n<=33;n++)codes.add(syntheticCode(n));
        var original=new KizWarehouseService().importFile(shop,writeFile(file,codes),true,category.id());assertEquals(33,original.inserted());
        var target=new Kn3Mark("101","","",syntheticCode(1),Kn3Mark.State.REJECTED,"sgtinRetired");repository.observe(shop,"101",List.of(target));
        var stock=new Kn3BatchReplacement.StockActions();
        for(int attempt=0;attempt<30;attempt++){
            var next=stock.reserve(shop,new Kn3BatchReplacement.Row(target,"100","","",""),KizSourceSelection.SELLER_ONLY);assertNotEquals(target.fingerprint(),Kn3Mark.fingerprint(next.getCode()));
            var id=repository.begin(shop,target,next.getCode(),"{}",next);repository.dispatch(shop,id,"AWAITING");target=new Kn3Mark("101","","",next.getCode(),Kn3Mark.State.REJECTED,"sgtinRetired");repository.observe(shop,"101",List.of(target));assertEquals("CONSUMED",stockState(next));
        }
        var reimported=new KizWarehouseService().importFile(shop,writeFile(file,codes),true,category.id());assertEquals(31,reimported.rejected());assertEquals(2,reimported.inserted());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM kiz_codes WHERE shop_id=1 AND legal_status='REJECTED' AND status='AVAILABLE'");var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        assertThrows(IllegalStateException.class,()->repository.begin(shop,red("102"),syntheticCode(1),"{}"));
    }
    KizImportReader.Preview writeFile(Path file,List<String> codes)throws Exception{Files.write(file,codes);return KizImportReader.read(file);}
    @Test void aRedCisAlsoDiscardsFullCryptoCopiesInOtherCategories()throws Exception{
        var categories=new KizStockCategoryRepository();var a=categories.create(shop,"A");var b=categories.create(shop,"B");String cis=syntheticCode(77),full=cis+"\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=";
        var preview=writeFile(root.resolve("same-name.txt"),List.of(full,full));new KizWarehouseService().importFile(shop,preview,true,a.id());new KizWarehouseService().importFile(shop,preview,true,b.id());
        repository.observe(shop,"101",List.of(new Kn3Mark("101","","",cis,Kn3Mark.State.REJECTED,"sgtinRetired")));
        assertNull(new KizCategoryAllocator().reserveReplacement(shop));var result=new KizWarehouseService().importFile(shop,preview,true,a.id());assertEquals(2,result.rejected());assertEquals(0,result.inserted());
    }
    @Test void batchContinuesAfterNewRedCodesUntilAnExactGreenReadback()throws Exception{
        repository.observe(shop,"101",List.of(red("101")));var writes=new AtomicInteger();var latest=new AtomicReference<String>();
        var service=new Kn3BatchReplacement(new Kn3BatchReplacement.Actions(){
            public Kiz reserve(Shop s,Kn3BatchReplacement.Row row,KizSourceSelection sources){if(writes.get()>0)assertEquals(latest.get(),row.mark().code());return new Kiz(writes.get()+1,"attempt-"+(writes.get()+1));}
            public void replace(Shop s,Kn3BatchReplacement.Row row,Kiz code){writes.incrementAndGet();latest.set(code.getCode());repository.begin(s,row.mark(),code.getCode(),"{}");}
            public void refresh(Shop s,String order){repository.observe(s,order,List.of(new Kn3Mark(order,"","",latest.get(),writes.get()<3?Kn3Mark.State.REJECTED:Kn3Mark.State.ACCEPTED,writes.get()<3?"sgtinRetired":"sgtinIntroduced")));}
            public void releaseUnclaimed(Shop s,Kiz source){fail("Known applied codes must not return to stock");}
        });
        service.start(shop,service.report(shop,List.of("101")),KizSourceSelection.SELLER_ONLY).get(25,TimeUnit.SECONDS);assertEquals(3,writes.get());assertEquals(Kn3Mark.State.ACCEPTED,Kn3Mark.summary(repository.read(shop,"101")));
    }
    @Test void labelsUseOnlyConfirmedReplacementAndKeepOriginalHistoryUnchanged()throws Exception{
        var old=red("101");repository.observe(shop,"101",List.of(old));repository.begin(shop,old,"new-101","{}");var slots=List.of(new Kn3PrintProjection.Slot("","",old.code()));
        assertThrows(java.io.IOException.class,()->Kn3PrintProjection.codes(shop,"101",slots));
        repository.observe(shop,"101",List.of(new Kn3Mark("101","","","new-101",Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));
        assertEquals(List.of("new-101"),Kn3PrintProjection.codes(shop,"101",slots));assertEquals("old-101",slots.getFirst().code());
        var second=new Kn3Mark("101","","","new-101",Kn3Mark.State.REJECTED,"sgtinRetired");repository.observe(shop,"101",List.of(second));repository.begin(shop,second,"newer-101","{}");
        assertThrows(java.io.IOException.class,()->Kn3PrintProjection.codes(shop,"101",slots));repository.observe(shop,"101",List.of(new Kn3Mark("101","","","newer-101",Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));
        assertEquals(List.of("newer-101"),Kn3PrintProjection.codes(shop,"101",slots));
    }
    @Test void batchWaitsForAcceptanceAndSkipsGreenOrders()throws Exception{
        repository.observe(shop,"101",List.of(red("101")));repository.observe(shop,"102",List.of(red("102")));repository.observe(shop,"103",List.of(new Kn3Mark("103","","","good",Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));
        var writes=new AtomicInteger();var firstSent=new CountDownLatch(1);var allowed=new AtomicBoolean();
        var service=new Kn3BatchReplacement(new Kn3BatchReplacement.Actions(){
            public Kiz reserve(Shop s,Kn3BatchReplacement.Row row,KizSourceSelection sources){assertEquals(KizSourceSelection.SELLER_ONLY,sources);return new Kiz(writes.get()+1,"new-"+row.mark().orderId());}
            public void replace(Shop s,Kn3BatchReplacement.Row row,Kiz source){writes.incrementAndGet();repository.begin(s,row.mark(),source.getCode(),"{}");firstSent.countDown();}
            public void refresh(Shop s,String order){if(allowed.get())repository.observe(s,order,List.of(new Kn3Mark(order,"","","new-"+order,Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));}
            public void releaseUnclaimed(Shop s,Kiz source){fail("No unused source");}
        });
        var report=service.report(shop,List.of("101","102","103"));assertEquals(2,report.rows().size());assertTrue(report.rows().stream().allMatch(r->r.productId().equals("100")));var result=service.start(shop,report,KizSourceSelection.SELLER_ONLY);
        assertTrue(firstSent.await(5,TimeUnit.SECONDS));assertFalse(result.isDone(),"HTTP acknowledgement must not complete the pill");allowed.set(true);result.get(15,TimeUnit.SECONDS);assertEquals(2,writes.get());
    }
    @Test void failureInOneOrderDoesNotPreventTryingTheOtherRejectedOrders()throws Exception{
        repository.observe(shop,"101",List.of(red("101")));repository.observe(shop,"102",List.of(red("102")));var attempts=new AtomicInteger();
        var service=new Kn3BatchReplacement(new Kn3BatchReplacement.Actions(){
            public Kiz reserve(Shop s,Kn3BatchReplacement.Row row,KizSourceSelection sources)throws Exception{attempts.incrementAndGet();throw new java.io.IOException("KIZ_INVENTORY_SHORTAGE");}
            public void replace(Shop s,Kn3BatchReplacement.Row row,Kiz code){fail();}public void refresh(Shop s,String order){fail();}public void releaseUnclaimed(Shop s,Kiz code){fail();}
        });
        var result=service.start(shop,service.report(shop,List.of("101","102")),KizSourceSelection.SELLER_ONLY);assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));assertEquals(2,attempts.get());
    }
}
