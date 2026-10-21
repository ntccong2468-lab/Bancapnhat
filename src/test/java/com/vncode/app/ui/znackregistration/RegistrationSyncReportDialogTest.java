package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSyncReportDialogTest {
    @TempDir Path directory;
    RegistrationSyncReportDialog.Report report;Shop shop;String run;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=new Shop(1,"Synthetic report",Marketplace.OZON,"fixture","synthetic");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic report','OZON','fixture','synthetic')");c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(1,?,?,?,'Synthetic','test')")){
                for(int n=1;n<=650;n++){p.setString(1,""+n);p.setString(2,"offer-"+n);p.setString(3,"sku-"+n);p.addBatch();}p.executeBatch();
            }c.commit();
        }
        run=RegistrationSourceFixture.ready(shop);report=fx(()->new RegistrationSyncReportDialog.Report(shop,run));awaitIdle();
    }
    @AfterEach void clean()throws Exception{fx(()->{report.dispose();return null;});awaitIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void reportShowsRealReceiptStateAndUsesBoundedPages()throws Exception {
        fx(()->{assertEquals(300,table().getItems().size());assertTrue(label("summary").getText().contains("SOURCES_COMPLETE"));RegistrationScreenshot.capture(report,"registration-report");button("next").fire();return null;});awaitIdle();
        fx(()->{assertEquals(300,table().getItems().size());button("next").fire();return null;});awaitIdle();
        fx(()->{assertEquals(50,table().getItems().size());assertTrue(button("next").isDisabled());button("previous").fire();return null;});awaitIdle();fx(()->{assertEquals(300,table().getItems().size());return null;});
    }
    @Test void reportReadsNewTerminalStateAndItsSafeError()throws Exception {
        new com.vncode.app.features.sync.SyncRunRepository().stage(shop,run,"FAILED","READING_ZNACK","NETWORK_TIMEOUT");
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertTrue(label("summary").getText().contains("NETWORK_TIMEOUT"),label("summary").getText());assertTrue(label("events").getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.report_step.READING_ZNACK")),label("events").getText());return null;});
    }
    @Test void sourceReceiptExplicitlyStatesThatItDidNotRegisterCards()throws Exception{
        fx(()->{assertTrue(label("summary").getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.report_sources_only")));return null;});
    }
    @Test void actualRegistrationReportIsSelectableAndKeepsItsFailure()throws Exception{
        var repository=new com.vncode.app.features.sync.SyncRunRepository();
        String attempt=repository.begin(shop,com.vncode.app.features.sync.RegistrationAttemptReports.KIND,"PREFLIGHT");
        repository.add(shop,attempt,"1","offer-1","sku-1","Article","Brown","52","","NEEDS_ACTION","ZNACK_MISSING_PRODUCT_DOCUMENT","{}");
        repository.stage(shop,attempt,"PARTIAL","PREFLIGHT","ZNACK_REGISTRATION_NEEDS_ACTION");
        fx(()->{report.dispose();report=new RegistrationSyncReportDialog.Report(shop,attempt);return null;});awaitIdle();
        fx(()->{assertEquals(1,table().getItems().size());assertTrue(label("summary").getText().contains("ZNACK_REGISTRATION_NEEDS_ACTION"),label("summary").getText());return null;});
    }
    @Test void marketplaceReadbackIsReportedAsUpdatedWhileLocalActivationIsPending()throws Exception{
        var repository=new com.vncode.app.features.sync.SyncRunRepository();String gtinRun=repository.begin(shop,"znack_marketplace","READBACK");
        repository.add(shop,gtinRun,"1","v1","","","","","04631993764370","WAITING_REMOTE","","{\"marketplaceVerified\":true,\"cohortScope\":\"STORE\"}");
        repository.add(shop,gtinRun,"2","v2","","","","","04631993764363","WAITING_REMOTE","READBACK_PENDING","{}");
        repository.add(shop,gtinRun,"3","v3","","","","","04631993764356","NEEDS_ACTION","GTIN_TNVED_GROUP_CHANGED","{}");
        repository.add(shop,gtinRun,"4","v4","","","","","","NEEDS_ACTION","ZNACK_MAPPING_UNVERIFIED","{}");
        var counts=repository.marketplaceCounts(gtinRun);assertEquals(1L,counts.get("UPDATED"));assertEquals(3L,counts.get("WITH_GTIN"));assertEquals(1L,counts.get("WITHOUT_GTIN"));assertEquals(2L,counts.get("NOT_UPDATED"));
        fx(()->{report.dispose();report=new RegistrationSyncReportDialog.Report(shop,gtinRun);return null;});awaitIdle();
        fx(()->{assertTrue(label("summary").getText().contains("1/3"));assertTrue(label("summary").getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.report_scope_store")));assertEquals(4,table().getItems().size());return null;});
        assertEquals("WAITING_REMOTE",repository.items(gtinRun,0,10).getFirst().state(),"Reporting must not bypass native candidate activation");
    }
    @Test void reportFiltersTheWholeRunAndShowsActualBarcodeCatalogTimeAndNextAction()throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_run_items SET state='FAILED',error_code='NETWORK_TIMEOUT',updated_at='2026-10-21T01:02:03Z',evidence_json=? WHERE run_id=? AND variant_id='offer-650'")){
            p.setString(1,"{\"barcodes\":[\"000123\"],\"goodId\":\"919\",\"catalogStatus\":\"draft\",\"catalogDetailedStatus\":[\"moderation\"],\"catalogCheckedAt\":\"2026-10-21T00:00:00Z\",\"checkResult\":\"UNKNOWN\",\"syncResult\":\"UNKNOWN\",\"nextAction\":\"READBACK_REQUIRED\"}");p.setString(2,run);assertEquals(1,p.executeUpdate());
        }
        fx(()->{button("refresh").fire();return null;});awaitIdle();
        fx(()->{((ComboBox<String>)report.lookup("#registrationSyncReportState")).setValue("FAILED");return null;});awaitIdle();
        fx(()->{assertEquals(1,table().getItems().size());var row=table().getItems().getFirst();assertEquals("offer-650",((com.vncode.app.features.sync.SyncRunRepository.Item)row).variantId());var values=new java.util.ArrayList<String>();for(var column:table().getColumns())values.add(String.valueOf(((TableColumn)column).getCellData(row)));String cells=String.join("\n",values);for(String expected:java.util.List.of("000123","919","draft / moderation","2026-10-21T00:00:00Z","2026-10-21T01:02:03Z","READBACK_REQUIRED"))assertTrue(cells.contains(expected),expected);assertTrue(button("next").isDisabled());assertFalse(((Button)report.lookup("#registrationSyncReportExport")).isDisabled());assertTrue(label("summary").getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.report_scope_store")));assertTrue(label("summary").getText().contains("650"));assertEquals("FAILED",((ComboBox<?>)report.lookup("#registrationSyncReportState")).getValue());return null;});
    }
    @Test void freeTextSearchFindsItemsBeyondTheLoadedPage()throws Exception{
        fx(()->{((TextField)report.lookup("#registrationSyncReportSearch")).setText("offer-650");return null;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){if(fx(()->table().getItems().size()==1))break;Thread.sleep(10);}awaitIdle();
        fx(()->{assertEquals(1,table().getItems().size());assertEquals("offer-650",((com.vncode.app.features.sync.SyncRunRepository.Item)table().getItems().getFirst()).variantId());assertTrue(button("next").isDisabled());return null;});
    }
    @Test void transientReportReadFailureKeepsPreviousResultsAndOwnerChangeClearsThem()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE sync_run_events RENAME TO temporarily_unavailable_events");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertEquals(300,table().getItems().size());assertTrue(label("summary").getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.latest_read_unknown")));return null;});
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE temporarily_unavailable_events RENAME TO sync_run_events");s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertTrue(table().getItems().isEmpty());assertTrue(((Button)report.lookup("#registrationSyncReportExport")).isDisabled());return null;});
    }
    @SuppressWarnings("unchecked") TableView<?> table(){return (TableView<?>)report.lookup("#registrationSyncReportItems");}
    private Object field(String name)throws Exception{var f=RegistrationSyncReportDialog.Report.class.getDeclaredField(name);f.setAccessible(true);return f.get(report);}
    private Button button(String name)throws Exception{return (Button)field(name);}private Label label(String name)throws Exception{return (Label)field(name);}
    private void awaitIdle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<end)Thread.sleep(10);fx(()->null);assertFalse(AppTaskExecutor.hasRunningTasks());}
    private static <T>T fx(Callable<T> action)throws Exception{var result=new FutureTask<>(action);Platform.runLater(result);return result.get(10,TimeUnit.SECONDS);}
}
