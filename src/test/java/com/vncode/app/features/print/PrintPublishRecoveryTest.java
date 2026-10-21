package com.vncode.app.features.print;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.print.history.*;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.*;
import com.vncode.app.models.Order;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real PDF rendering, failed atomic file moves, SQLite restart, and exact frozen KIZ recovery. */
class PrintPublishRecoveryTest {
    private static final String GTIN="04631993764363";
    @TempDir Path directory;
    private final Shop shop=new Shop(1,"Fixture main","fixture-main");
    private final Shop other=new Shop(2,"Fixture other","fixture-other");
    private final ZnackGtinInventoryService inventory=new ZnackGtinInventoryService();
    private final PrintHistoryRepository history=new PrintHistoryRepository();

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.resolve("data").toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture main','fixture-main'),(2,'Fixture other','fixture-other')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,gender,size,synced_at) VALUES(1,'"+GTIN+"','Frozen original product','МУЖСКОЙ','50-52','fixture')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+GTIN+"',3,'INTRODUCED','fixture','fixture')");
            try(var p=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(?,1,1,?,?,?,'AVAILABLE','IN_CIRCULATION','fixture','fixture')")){
                for(int id=1;id<=3;id++){p.setInt(1,id);p.setString(2,mark(id));p.setString(3,"fixture-"+id);p.setString(4,GTIN);p.addBatch();}p.executeBatch();
            }
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void standalonePublicationFailureKeepsExactConsumedCodesRecoverableAfterRestart()throws Exception {
        Path blocked=nonemptyDirectory("blocked-labels.pdf");
        assertThrows(IOException.class,()->new ZnackKizExportService().export(1,GTIN,2,blocked.toFile()));
        var before=new PrintHistoryService();var job=onlyJob(1);
        assertEquals("pdf_ready",job.status());assertTrue(job.isKizExport());assertTrue(job.canReprint());
        assertEquals(List.of(mark(1),mark(2)),before.getItems(job.id()).stream().map(PrintHistoryItem::kiz).toList());
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));
        assertTrue(Files.exists(blocked.resolve("keep.txt")));assertEquals(1,count("kiz_orders","1=1"));
        var noFreshReservation=new ZnackGtinInventoryService(){
            @Override public List<Kiz> reserveAvailable(int shopId,String gtin,int quantity){throw new AssertionError("A new export must be blocked before reserving another KIZ");}
        };
        Path retry=directory.resolve("forbidden-new-kiz-export.pdf");
        var retryService=new ZnackKizExportService(noFreshReservation,new com.vncode.app.features.kizmapping.KizMappingRepository(),new ZnackKizLabelPdfExporter());
        var retryFailure=assertThrows(IllegalStateException.class,()->retryService.export(1,GTIN,1,retry.toFile()));
        assertTrue(retryFailure.getMessage().contains("Lịch sử in"));assertFalse(Files.exists(retry));assertEquals(job.id(),onlyJob(1).id());
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));assertEquals(1,count("kiz_orders","1=1"));
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE znack_products SET product_name='Changed live product',size='DIFFERENT' WHERE shop_id=1");
        }
        forbidInventoryOrPurchaseWrites();
        var restarted=new PrintHistoryService();var frozen=onlyJob(1);assertEquals("pdf_ready",frozen.status());
        Path labels=directory.resolve("recovered-labels.pdf"),details=directory.resolve("recovered-details.pdf");
        restarted.reprint(frozen,labels.toFile(),details.toFile());
        assertEquals("success",onlyJob(1).status());assertDoesNotThrow(()->history.requireNoRecoverableKiz(1,GTIN));
        assertEquals(List.of(mark(1),mark(2)),restarted.getItems(job.id()).stream().map(PrintHistoryItem::kiz).toList());
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));assertEquals(1,count("kiz_orders","1=1"));
        assertTrue(Files.size(details)>0);
        try(var pdf=Loader.loadPDF(labels.toFile())){
            assertEquals(2,pdf.getNumberOfPages());String text=new PDFTextStripper().getText(pdf).replaceAll("\\s+"," ");
            assertTrue(text.contains("Frozen original product"));assertFalse(text.contains("Changed live product"));
            var renderer=new PDFRenderer(pdf);
            for(int page=0;page<2;page++){
                var image=renderer.renderImageWithDPI(page,600,ImageType.GRAY);var left=image.getSubimage(0,0,(int)(image.getWidth()*.55),image.getHeight());
                String decoded=new DataMatrixReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(left)))).getText();
                assertEquals(KizService.scannerSafeCode(mark(page+1)),KizService.scannerSafeCode(decoded));
            }
        }
    }

    @Test void wbSecondPdfPublicationFailureCanReprintBothFilesWithoutAnotherReservation()throws Exception {
        var reservations=new AtomicInteger();var metadataReads=new AtomicInteger();
        var planning=new OrderExportWorkflow.KizPlanningData(){
            public Map<Long,KizService.SgtinMetadata> metadata(Shop selected,List<Long> ids){metadataReads.incrementAndGet();return Map.of();}
            public Map<Long,String> mappings(int shopId,List<Long> ids){return Map.of(101L,GTIN);}
            public Set<Long> required(int shopId,List<Long> ids){return Set.of(101L);}
            public Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities){reservations.incrementAndGet();return inventory.reserveAvailableBulk(shopId,quantities);}
            public void release(int shopId,List<Kiz> codes){inventory.release(shopId,codes);}
            public void validate(Shop selected)throws IOException{KizService.requireCurrentShop(selected.getId(),selected.getApiKey());}
        };
        Path labels=directory.resolve("first-labels.pdf"),blockedDetails=nonemptyDirectory("blocked-details.pdf");
        var request=new OrderExportWorkflow.ExportRequest(shop,"SUP-FIXTURE","Fixture shipment",List.of(order(501),order(502)),PrintJobOptions.defaults(),labels.toFile(),blockedDetails.toFile());
        assertThrows(IOException.class,()->new OrderExportWorkflow(planning).export(request));
        assertTrue(Files.size(labels)>0,"The first PDF was already published before the second move failed");
        assertTrue(Files.exists(blockedDetails.resolve("keep.txt")));var job=onlyJob(1);
        assertEquals("pdf_ready",job.status());assertEquals("SUP-FIXTURE",job.supplyId());assertTrue(job.canReprint());
        assertEquals(List.of(mark(1),mark(2)),history.findItems(job.id()).stream().map(PrintHistoryItem::kiz).toList());
        assertEquals(1,reservations.get());assertEquals(1,metadataReads.get());assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));
        var retryFailure=assertThrows(IllegalStateException.class,()->new OrderExportWorkflow(planning).export(request));
        assertTrue(retryFailure.getMessage().contains("Lịch sử in"));assertEquals(1,reservations.get());assertEquals(1,metadataReads.get());
        assertEquals(job.id(),onlyJob(1).id());assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));
        Database.initDatabase();forbidInventoryOrPurchaseWrites();
        var restarted=new PrintHistoryService();Path recovered=directory.resolve("wb-recovered.pdf"),details=directory.resolve("wb-details.pdf");
        restarted.reprint(onlyJob(1),recovered.toFile(),details.toFile());
        assertEquals("success",onlyJob(1).status());assertDoesNotThrow(()->history.requireNoRecoverableOrders(1,List.of(501L,502L)));
        assertTrue(Files.size(recovered)>0);assertTrue(Files.size(details)>0);assertEquals(1,reservations.get());
        assertEquals(List.of(mark(1),mark(2)),restarted.getItems(job.id()).stream().map(PrintHistoryItem::kiz).toList());
        assertEquals(1,count("kiz_orders","1=1"));assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));
    }

    @Test void anUnknownPrintJobRollsBackEveryConsumption() {
        var reserved=inventory.reserveAvailable(1,GTIN,2);long own=prepared(shop,reserved);
        assertThrows(IllegalStateException.class,()->inventory.consumeForPrint(1,reserved,Long.MAX_VALUE));
        assertReservedAndUnconsumed(reserved);assertEquals("prepared",history.findJobsByShop(1).getFirst().status());assertEquals(own,onlyJob(1).id());
    }

    @Test void aForeignShopSnapshotCannotConsumeOwnedReservedCodes() {
        var reserved=inventory.reserveAvailable(1,GTIN,2);long foreign=prepared(other,reserved);
        assertThrows(IllegalStateException.class,()->inventory.consumeForPrint(1,reserved,foreign));
        assertReservedAndUnconsumed(reserved);assertEquals("prepared",onlyJob(2).status());assertTrue(history.findJobsByShop(1).isEmpty());
    }

    @Test void matchingCallerAndSnapshotTextCannotHideADifferentReservedDatabaseCode() {
        var reserved=inventory.reserveAvailable(1,GTIN,2);var real=reserved.getFirst();
        var forged=new Kiz(real.getId(),mark(99),real.getReservationToken());var wrong=List.of(forged,reserved.getLast());long job=prepared(shop,wrong);
        assertThrows(IllegalStateException.class,()->inventory.consumeForPrint(1,wrong,job));
        assertReservedAndUnconsumed(reserved);assertEquals("prepared",onlyJob(1).status());
        assertEquals(List.of(mark(99),mark(2)),history.findItems(job).stream().map(PrintHistoryItem::kiz).toList());
    }

    @Test void exactSnapshotAndConsumptionAdvanceAtomicallyAndCannotBeConsumedAgain() {
        var reserved=inventory.reserveAvailable(1,GTIN,2);long job=prepared(shop,reserved);
        assertEquals(2,inventory.consumeForPrint(1,reserved,job));assertEquals("pdf_ready",onlyJob(1).status());
        assertEquals(List.of(mark(1),mark(2)),history.findItems(job).stream().map(PrintHistoryItem::kiz).toList());
        assertThrows(IllegalStateException.class,()->inventory.consumeForPrint(1,reserved,job));
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));assertTrue(onlyJob(1).canReprint());
    }

    @Test void simultaneousStandaloneExportsForTheSameNormalizedGtinCannotReserveTwice()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        var blocking=new ZnackKizLabelPdfExporter(){
            @Override public void write(List<Kiz> codes,com.vncode.app.features.kizmapping.ZnackKizLabelMetadata metadata,java.io.File target)throws IOException {
                entered.countDown();await(release);super.write(codes,metadata,target);
            }
        };
        var exporting=new ZnackKizExportService(inventory,new com.vncode.app.features.kizmapping.KizMappingRepository(),blocking);
        try {
            var first=worker.submit(()->exporting.export(1,GTIN,2,directory.resolve("concurrent-first.pdf").toFile()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var forbidden=new ZnackGtinInventoryService(){@Override public List<Kiz> reserveAvailable(int id,String code,int quantity){throw new AssertionError("Duplicate print reached inventory");}};
            var second=new ZnackKizExportService(forbidden,new com.vncode.app.features.kizmapping.KizMappingRepository(),new ZnackKizLabelPdfExporter());
            assertThrows(IllegalStateException.class,()->second.export(1,GTIN.substring(1),1,directory.resolve("concurrent-second.pdf").toFile()));
            assertEquals(2,count("kiz_codes","status='RESERVED'"));assertEquals(0,count("print_jobs","1=1"));assertEquals(1,inventory.availableCount(1,GTIN));
            release.countDown();assertEquals(2,first.get(20,TimeUnit.SECONDS).pageCount());
            assertEquals(1,history.findJobsByShop(1).size());assertEquals(2,count("kiz_codes","status='CONSUMED'"));
            new ZnackKizExportService().export(1,GTIN,1,directory.resolve("concurrent-later.pdf").toFile());
            assertEquals(3,count("kiz_codes","status='CONSUMED'"));assertEquals(1,count("kiz_orders","1=1"));
        } finally {release.countDown();worker.shutdownNow();}
    }

    @Test void overlappingWbOrdersAreClaimedBeforeMetadataAndFailedClaimsLeaveNoRetainedKeys()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        var reads=new AtomicInteger();var reservations=new AtomicInteger();
        var planning=new OrderExportWorkflow.KizPlanningData(){
            public Map<Long,KizService.SgtinMetadata> metadata(Shop selected,List<Long> ids)throws IOException {if(reads.incrementAndGet()==1){entered.countDown();await(release);}return Map.of();}
            public Map<Long,String> mappings(int id,List<Long> ids){return Map.of(101L,GTIN);}
            public Set<Long> required(int id,List<Long> ids){return Set.of(101L);}
            public Map<String,List<Kiz>> reserve(int id,Map<String,Integer> quantities){reservations.incrementAndGet();return inventory.reserveAvailableBulk(id,quantities);}
            public void release(int id,List<Kiz> codes){inventory.release(id,codes);}
        };
        var firstRequest=new OrderExportWorkflow.ExportRequest(shop,"SUP-FIRST","Fixture",List.of(order(501),order(502)),PrintJobOptions.defaults(),directory.resolve("first-wb.pdf").toFile(),directory.resolve("first-wb-details.pdf").toFile());
        var duplicate=new OrderExportWorkflow.ExportRequest(shop,"SUP-OTHER","Fixture",List.of(order(503),order(502)),PrintJobOptions.defaults(),directory.resolve("duplicate-wb.pdf").toFile(),directory.resolve("duplicate-details.pdf").toFile());
        try {
            var first=worker.submit(()->new OrderExportWorkflow(planning).export(firstRequest));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->new OrderExportWorkflow(planning).export(duplicate));assertEquals(1,reads.get());assertEquals(0,reservations.get());assertEquals(3,inventory.availableCount(1,GTIN));
            release.countDown();assertEquals(2,first.get(20,TimeUnit.SECONDS).consumedKizs().size());
            var later=new OrderExportWorkflow.ExportRequest(shop,"SUP-LATER","Fixture",List.of(order(503)),PrintJobOptions.defaults(),directory.resolve("later-wb.pdf").toFile(),directory.resolve("later-details.pdf").toFile());
            assertEquals(1,new OrderExportWorkflow(planning).export(later).consumedKizs().size());assertEquals(2,reads.get());assertEquals(2,reservations.get());
            assertEquals(3,count("kiz_codes","status='CONSUMED'"));assertEquals(1,count("kiz_orders","1=1"));
        } finally {release.countDown();worker.shutdownNow();}
    }

    @Test void actualStartupRecoveryReleasesOnlyUnconsumedPreparedCodesAndKeepsReadySnapshotReprintable()throws Exception {
        var consumed=inventory.reserveAvailable(1,GTIN,2);
        long ready=new PrintHistoryService().recordPreparedKizJob(shop,new com.vncode.app.features.kizmapping.ZnackKizLabelMetadata("Frozen original product","МУЖСКОЙ","50-52"),consumed,GTIN);
        inventory.consumeForPrint(1,consumed,ready);var unconsumed=inventory.reserveAvailable(1,GTIN,1);long incomplete=prepared(shop,unconsumed);
        Database.initDatabase();history.recoverInterruptedFboJobs();assertEquals(1,inventory.releaseRecoverableReservations());
        var jobs=history.findJobsByShop(1);var recoverable=jobs.stream().filter(job->job.id()==ready).findFirst().orElseThrow();
        var abandoned=jobs.stream().filter(job->job.id()==incomplete).findFirst().orElseThrow();
        assertEquals("pdf_ready",recoverable.status());assertTrue(recoverable.canReprint());assertEquals("failed",abandoned.status());assertFalse(abandoned.canReprint());
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(0,count("kiz_codes","status='RESERVED'"));assertEquals(1,inventory.availableCount(1,GTIN));
        assertEquals(List.of(mark(1),mark(2)),history.findItems(ready).stream().map(PrintHistoryItem::kiz).toList());
        forbidInventoryOrPurchaseWrites();new PrintHistoryService().reprint(recoverable,directory.resolve("startup-ready.pdf").toFile(),directory.resolve("startup-details.pdf").toFile());
        assertEquals("success",history.findJobsByShop(1).stream().filter(job->job.id()==ready).findFirst().orElseThrow().status());
        assertEquals(2,count("kiz_codes","status='CONSUMED'"));assertEquals(1,inventory.availableCount(1,GTIN));assertEquals(1,count("kiz_orders","1=1"));
    }

    private long prepared(Shop owner,List<Kiz> codes){
        var templates=new PrintTemplateService();var template=templates.getDefaultTemplate();var items=new ArrayList<PrintHistoryItem>();
        for(int i=0;i<codes.size();i++)items.add(new PrintHistoryItem(0,i,100+i,"Fixture","Frozen original product","Pants","XL","52","Black","ARTICLE",GTIN,"","",codes.get(i).getCode(),null));
        return history.insertPreparedJob(owner,"KIZ/fixture-"+UUID.randomUUID(),"Fixture",Instant.now().toString(),template.getId(),template.getName(),templates.toJson(template),items);
    }
    private static String mark(int id){return "01"+GTIN+"21SERIAL"+id+"\u001D91EE11\u001D92signature"+id;}
    private static void await(CountDownLatch latch)throws IOException {try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Fixture print latch timed out");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("Fixture interrupted",stopped);}}
    private static Order order(long id){var order=new Order();order.setId(id);order.setNmId(101L);order.setRequiresKiz(true);order.setName("Frozen WB product");order.setArticle("ARTICLE");order.setBarcode("2040000000001");order.setSize("XL");order.setColor("Black");order.setStickerCode("WB-FIXTURE-CODE");return order;}
    private Path nonemptyDirectory(String name)throws IOException {Path target=directory.resolve(name);Files.createDirectory(target);Files.writeString(target.resolve("keep.txt"),"Keep existing destination");return target;}
    private PrintHistoryJobSummary onlyJob(int shopId){var jobs=history.findJobsByShop(shopId);assertEquals(1,jobs.size());return jobs.getFirst();}
    private void assertReservedAndUnconsumed(List<Kiz> reserved){
        assertEquals(0,count("kiz_codes","status='CONSUMED'"));assertEquals(2,count("kiz_codes","status='RESERVED'"));assertEquals(1,inventory.availableCount(1,GTIN));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT raw_code,status,reservation_token FROM kiz_codes WHERE id=? AND shop_id=1")){
            for(var code:reserved){p.setInt(1,code.getId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(code.getCode(),r.getString(1));assertEquals("RESERVED",r.getString(2));assertEquals(code.getReservationToken(),r.getString(3));}}
        }catch(java.sql.SQLException failed){throw new AssertionError(failed);}
    }
    private void forbidInventoryOrPurchaseWrites()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TRIGGER fixture_no_purchase BEFORE INSERT ON kiz_orders BEGIN SELECT RAISE(ABORT,'Reprint cannot purchase'); END");
            s.execute("CREATE TRIGGER fixture_no_new_code BEFORE INSERT ON kiz_codes BEGIN SELECT RAISE(ABORT,'Reprint cannot create a code'); END");
            s.execute("CREATE TRIGGER fixture_no_inventory_change BEFORE UPDATE ON kiz_codes BEGIN SELECT RAISE(ABORT,'Reprint cannot reserve, release or consume'); END");
        }
    }
    private int count(String table,String where){try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table+" WHERE "+where)){assertTrue(r.next());return r.getInt(1);}catch(java.sql.SQLException failed){throw new AssertionError(failed);}}
}
