package com.vncode.app.features.kiz;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.print.ZnackKizLabelPdfExporter;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizWarehouseTest {
    static final String GTIN="04645588781154",INN="7700000000",SOURCE="National Catalog@fixture";
    @TempDir Path directory;Shop shop;KizWarehouseRepository repository;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture'),(2,'Other','OZON','client','fixture2')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'"+GTIN+"','Fixture','2026-10-15T00:00:00Z'),(2,'"+GTIN+"','Other','2026-10-15T00:00:00Z')");
            s.execute("INSERT INTO znack_settings(shop_id,participant_inn,updated_at) VALUES(1,'"+INN+"','fixture'),(2,'"+INN+"','fixture')");
        }
        shop=new Shop(1,"WB","fixture");repository=new KizWarehouseRepository();
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    static String mark(int number){return "01"+GTIN+"21"+String.format(Locale.ROOT,"%013d",number)+"\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=";}
    KizImportReader.Preview preview(int count,String name)throws Exception{var path=directory.resolve(name);var data=new StringBuilder();for(int i=1;i<=count;i++)data.append(mark(i)).append('\n');Files.writeString(path,data);return KizImportReader.read(path);}
    KizWarehouseRepository.Imported commit(KizImportReader.Preview preview)throws Exception{return repository.commit(shop,new RegisteredGtinProof.Scope(SOURCE,INN,"fixture","production"),preview);}
    void verifyAll()throws Exception{var pending=repository.pending(shop,SOURCE,1000,0);var verdicts=new HashMap<String,KizWarehouseRepository.Verdict>();pending.forEach(p->verdicts.put(p.cis(),new KizWarehouseRepository.Verdict(true,"KIZ_READY")));repository.verified(shop,SOURCE,pending,verdicts);}
    @Test void preservesCryptoAndGsAndRejectsMissingSeparatorsAndBadCheckDigit(){
        var parsed=KizMark.parse(mark(1));assertEquals(GTIN,parsed.gtin());assertEquals(mark(1),parsed.raw());assertEquals(31,parsed.cis().length());
        assertEquals(parsed,KizMark.parse("]d2"+mark(1).replace("\u001d","<GS>")));
        assertThrows(IllegalArgumentException.class,()->KizMark.parse(mark(1).replace("\u001d","")));
        assertThrows(IllegalArgumentException.class,()->KizMark.parse(mark(1).replace(GTIN,"04645588781155")));
        assertFalse(parsed.toString().contains(parsed.cis()));
    }
    @Test void txtJsonAndCsvRetainExactCodesAcrossMultipleCodeColumns()throws Exception{
        var text=preview(2,"marks.txt");assertEquals(2,text.marks().size());assertTrue(text.valid());
        var json=directory.resolve("marks.json");Files.writeString(json,new Gson().toJson(Map.of("codes",List.of(mark(1),mark(2)))));assertEquals(text.marks(),KizImportReader.read(json).marks());
        var csv=directory.resolve("marks.csv");Files.writeString(csv,"note;cis\nfixture;\""+mark(1)+"\"\n");assertEquals(mark(1),KizImportReader.read(csv).marks().getFirst().raw());
        Files.writeString(csv,"cis;code\n"+mark(1)+";"+mark(2));assertEquals(List.of(mark(1),mark(2)),KizImportReader.read(csv).marks().stream().map(KizMark::raw).toList());
    }
    @Test void xlsxNeverEvaluatesFormulasAndRequiresTextCells()throws Exception{
        var path=directory.resolve("marks.xlsx");try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
            var sheet=book.createSheet();sheet.createRow(0).createCell(0).setCellValue("cis");sheet.createRow(1).createCell(0).setCellValue(mark(1).replace("\u001d","<GS>"));sheet.createRow(2).createCell(0).setCellFormula("1+1");try(var out=Files.newOutputStream(path)){book.write(out);}
        }
        var parsed=KizImportReader.read(path);assertTrue(parsed.valid());assertEquals(1,parsed.damaged());assertEquals(1,parsed.marks().size(),()->parsed.issues().toString());assertEquals("KIZ_TEXT_CELL_REQUIRED",parsed.issues().getFirst().code());
    }
    @Test void decodesActualGs1DataMatrixFromPdfWithoutUsingHumanReadableText()throws Exception{
        var path=directory.resolve("labels.pdf");new ZnackKizLabelPdfExporter().write(List.of(new Kiz(1,mark(1)),new Kiz(2,mark(2))),null,path.toFile());
        var parsed=KizImportReader.read(path);assertTrue(parsed.valid(),()->parsed.issues().toString());assertEquals(List.of(mark(1),mark(2)),parsed.marks().stream().map(KizMark::raw).toList());
    }
    @Test void importedMarksRemainUnavailableUntilVerifiedAndArePreferredBeforePurchasedStock()throws Exception{
        var preview=preview(2,"marks.txt");var stock=new ZnackGtinInventoryService();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(100,1,'"+GTIN+"',1,'READY','fixture','fixture')");
            try(var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(1,100,?,? ,?,'AVAILABLE','IN_CIRCULATION','fixture','fixture')")){p.setString(1,mark(99));p.setString(2,"fixture");p.setString(3,GTIN);p.executeUpdate();}
        }
        commit(preview);assertEquals(1,stock.availableCount(1,GTIN));assertThrows(IllegalStateException.class,()->stock.reserveAvailable(1,GTIN,2));
        verifyAll();var selected=stock.reserveAvailable(1,GTIN,3);assertEquals(List.of(mark(1),mark(2),mark(99)),selected.stream().map(Kiz::getCode).toList());assertEquals(3,stock.consume(1,selected));assertEquals(0,commit(preview).inserted());
        var other=new Shop(2,"Other",Marketplace.OZON,"client","fixture2");assertEquals(0,repository.commit(other,new RegisteredGtinProof.Scope(SOURCE,INN,"fixture","production"),preview).inserted());
        assertEquals(0,stock.availableCount(2,GTIN));
    }
    @Test void aNewGtinOnlyCreatesAnUnverifiedPlaceholderAndCannotBecomeCatalogAuthority()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM znack_products WHERE shop_id=1");}
        commit(preview(1,"first.txt"));assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertTrue(new GtinSyncRepository().catalog(1,shop.getMarketplace()).isEmpty());
        var znack=new ZnackRepository(new ZnackModels.ShopContext(1,"WB"));assertThrows(IllegalArgumentException.class,()->ZnackPurchaseCoordinator.create(znack).validatePrerequisites(ZnackModels.Settings.empty(),GTIN,1));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT card_status FROM znack_products WHERE shop_id=1 AND gtin=?")){p.setString(1,GTIN);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("imported_unverified",r.getString(1));}}
    }
    @Test void suzDownloadCannotDuplicateAnImportedShortCisAndImportedHistoryRemainsReadable()throws Exception{
        String code=KizMark.parse(mark(1)).cis();var file=directory.resolve("short.txt");Files.writeString(file,code);commit(KizImportReader.read(file));
        var znack=new ZnackRepository(new ZnackModels.ShopContext(1,"WB"));assertEquals(ZnackModels.OrderStatus.IMPORTED,znack.findOrders().getFirst().localStatus());
        long draft=znack.createDraft(GTIN,1);assertEquals(0,znack.insertCodes(draft,GTIN,new ZnackModels.DownloadedCodes(List.of(mark(1)),"fixture")));
        assertTrue(znack.findCodes(draft).isEmpty());assertEquals(1,repository.summaries(shop).getFirst().total());
    }
    @Test void twoConcurrentThousandOrderPlansCannotReserveTheSameImportedMark()throws Exception{
        commit(preview(1000,"bulk.txt"));verifyAll();var stock=new ZnackGtinInventoryService();var executor=Executors.newFixedThreadPool(2);
        try{
            var gate=new CountDownLatch(1);Callable<List<Kiz>> task=()->{gate.await();try{return stock.reserveAvailable(1,GTIN,1000);}catch(IllegalStateException shortage){return List.of();}};
            var a=executor.submit(task);var b=executor.submit(task);gate.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);assertEquals(1000,first.size()+second.size());assertTrue(first.isEmpty()||second.isEmpty());
            assertEquals(1000,stock.release(1,first.isEmpty()?second:first));assertEquals(1000,stock.availableCount(1,GTIN));
        }finally{executor.shutdownNow();}
    }
    @Test void ozonUsesVerifiedImportedStockBeforeOlderPurchasedCodes()throws Exception{
        var other=new Shop(2,"Other",Marketplace.OZON,"client","fixture2");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(100,2,'"+GTIN+"',1,'READY','fixture','fixture')");
            try(var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(2,100,?,'fixture',?,'AVAILABLE','IN_CIRCULATION','fixture','fixture')")){p.setString(1,mark(99));p.setString(2,GTIN);p.executeUpdate();}
        }
        repository.commit(other,new RegisteredGtinProof.Scope(SOURCE,INN,"fixture","production"),preview(1,"ozon.txt"));
        var pending=repository.pending(other,SOURCE,1000,0);repository.verified(other,SOURCE,pending,Map.of(pending.getFirst().cis(),new KizWarehouseRepository.Verdict(true,"KIZ_READY")));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(2,'posting-1','awaiting_packaging','fixture')");
            s.execute("INSERT INTO ozon_posting_items(shop_id,posting_number,item_index,product_id,sku,quantity) VALUES(2,'posting-1',0,'777','sku',1)");
        }
        var jobs=new com.vncode.app.integration.ozon.OzonExemplarJobRepository();var plan=new com.vncode.app.integration.ozon.OzonRequirementGuard.PreparationPlan("posting-1",List.of(new com.vncode.app.integration.ozon.OzonRequirementGuard.RequiredItem(0,"777","sku",GTIN,1,true)));
        var reservation=jobs.stageLocalForPrint(2,plan,()->{});assertTrue(reservation.ready());assertEquals(pending.getFirst().id(),jobs.bindings(reservation.job().id()).getFirst().kizId());
        assertEquals(1,new ZnackGtinInventoryService().availableCount(2,GTIN));
    }
    @Test void credentialRotationAndDifferentEnterprisePreventUsingOldImportedStock()throws Exception{
        commit(preview(2,"marks.txt"));verifyAll();var stock=new ZnackGtinInventoryService();assertEquals(2,stock.availableCount(1,GTIN));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertEquals(0,stock.availableCount(1,GTIN));
        var rotated=new Shop(1,"WB","rotated");assertEquals(0,repository.commit(rotated,new RegisteredGtinProof.Scope("National Catalog@new",INN,"fixture","production"),preview(2,"marks.txt")).inserted());repository.prepareVerification(rotated,"National Catalog@new",INN);assertEquals(0,stock.availableCount(1,GTIN));assertEquals(2,repository.pending(rotated,"National Catalog@new",1000,0).size());
    }
    @Test void onlyUnusedImportsCanBeArchivedAndReservedOrConsumedMarksArePreserved()throws Exception{
        commit(preview(2,"marks.txt"));assertEquals(2,repository.changeArchive(shop,GTIN,false));assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(2,repository.changeArchive(shop,GTIN,true));assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        verifyAll();var held=new ZnackGtinInventoryService().reserveAvailable(1,GTIN,1);assertEquals(1,repository.changeArchive(shop,GTIN,false));assertEquals(0,repository.changeArchive(shop,GTIN,false));assertEquals(1,new ZnackGtinInventoryService().consume(1,held));
    }
    @Test void shortagePurchaseValidationCannotUseASoftDeletedGtin()throws Exception{
        var znack=new ZnackRepository(new ZnackModels.ShopContext(1,"WB"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_products SET deleted_at='fixture' WHERE shop_id=1");}
        assertThrows(IllegalArgumentException.class,()->ZnackPurchaseCoordinator.create(znack).validatePrerequisites(ZnackModels.Settings.empty(),GTIN,1));
    }
    @Test void verificationRequiresExactCisGtinOwnerAndCirculationAndRejectsDuplicateResponses(){
        var mark=KizMark.parse(mark(1));var pending=List.of(new KizWarehouseRepository.Pending(1,mark.raw(),mark.cis(),GTIN));
        var valid=JsonParser.parseString(new Gson().toJson(List.of(Map.of("cisInfo",Map.of("cis",mark.cis(),"gtin",GTIN,"ownerInn",INN,"status","INTRODUCED")))));
        assertTrue(KizWarehouseService.inspect(pending,INN,valid).get(mark.cis()).ready());
        assertFalse(KizWarehouseService.inspect(pending,"7800000000",valid).get(mark.cis()).ready());
        valid.getAsJsonArray().add(valid.getAsJsonArray().get(0).deepCopy());assertFalse(KizWarehouseService.inspect(pending,INN,valid).get(mark.cis()).ready());
        assertEquals("KIZ_VERIFY_INCOMPLETE",KizWarehouseService.inspect(pending,INN,new JsonArray()).get(mark.cis()).reason());
    }
}
