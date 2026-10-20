package com.vncode.app.ui.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gs1.Gs1UiTestFixture;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1SyncReportDialogTest {
    @TempDir Path directory;Shop shop;Gs1SyncReportDialog.Report report;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void init()throws Exception {System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);Gs1UiTestFixture.sync(shop,true);report=fx(()->new Gs1SyncReportDialog.Report(shop));awaitIdle();}
    @AfterEach void clean()throws Exception{fx(()->{report.dispose();return null;});awaitIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void openingReportShowsBothSourcesWithoutAuthenticationOrBusinessWrites()throws Exception{
        fx(()->{assertEquals(2,table().getItems().size());assertFalse(label().getText().contains("gs1.sync.step."));return null;});
        try(var c=Database.getConnection();var sql=c.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM gs1_requests")){assertEquals(0,rows.getInt(1));}
    }
    @Test void refreshDisplaysASourcesActualErrorAndTerminalState()throws Exception{
        var reports=new com.vncode.app.features.sync.SyncRunRepository();String id=reports.list(shop.getId(),0,1).getFirst().id();reports.stage(shop,id,"FAILED","READING_BALANCE","NETWORK_TIMEOUT");
        fx(()->{var field=Gs1SyncReportDialog.Report.class.getDeclaredField("refresh");field.setAccessible(true);((Button)field.get(report)).fire();return null;});awaitIdle();fx(()->{assertTrue(label().getText().contains("NETWORK_TIMEOUT"));assertFalse(label().getText().contains("READING_BALANCE"));return null;});
    }
    private Label label()throws Exception{var field=Gs1SyncReportDialog.Report.class.getDeclaredField("status");field.setAccessible(true);return (Label)field.get(report);}
    private TableView<?> table(){return (TableView<?>)report.lookup("#gs1SyncReportSources");}
    private void awaitIdle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<end)Thread.sleep(10);fx(()->null);assertFalse(AppTaskExecutor.hasRunningTasks());}
    private static <T>T fx(Callable<T> callable)throws Exception{var work=new FutureTask<>(callable);Platform.runLater(work);return work.get(10,TimeUnit.SECONDS);}
}
