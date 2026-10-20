package com.vncode.app.ui;

import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class StartupProgressTest {
    @TempDir Path data;
    Stage stage;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @AfterEach void cleanup()throws Exception{fx(()->{if(stage!=null)stage.close();return null;});System.clearProperty("vncode.appdata.dir");}
    @Test void firstWindowIsVisibleAndResponsiveBeforeSlowRecoveryAndDoesNotReadSqlSettings()throws Exception{
        // Exercise even an already-installed global theme observer without opening SQL.
        System.setProperty("vncode.appdata.dir",data.resolve("theme").toString());
        com.vncode.app.config.Database.initDatabase();fx(()->{ThemeService.applyTheme(new Scene(new VBox()));return null;});
        System.setProperty("vncode.appdata.dir",data.resolve("opening").toString());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CompletableFuture<Integer>();var wrongThread=new AtomicBoolean();
        try{
            fx(()->{stage=new Stage();StartupProgress.show(stage,phase->{wrongThread.set(Platform.isFxApplicationThread());phase.accept("CHECKING_BACKUP");entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new TimeoutException();return 42;},value->{assertTrue(Platform.isFxApplicationThread());done.complete(value);},done::completeExceptionally);return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            fx(()->{assertTrue(stage.isShowing());assertNotNull(stage.getScene().lookup("#startupPhase"));assertFalse(((Label)stage.getScene().lookup("#startupPhase")).getText().isBlank());return null;});
            assertFalse(done.isDone());assertFalse(wrongThread.get());assertFalse(Files.exists(data.resolve("opening/database.db")));
            release.countDown();assertEquals(42,done.get(5,TimeUnit.SECONDS));
        }finally{release.countDown();}
    }
    @Test void failedRecoveryDoesNotOpenWorkspaceAndKeepsAnActionableWindow()throws Exception{
        var failed=new CompletableFuture<Throwable>();var opened=new AtomicBoolean();
        fx(()->{stage=new Stage();StartupProgress.show(stage,phase->{throw new java.io.IOException("fixture recovery failure");},ignored->opened.set(true),failed::complete);return null;});
        assertInstanceOf(java.io.IOException.class,failed.get(5,TimeUnit.SECONDS));assertFalse(opened.get());
        fx(()->{assertTrue(stage.isShowing());assertTrue(stage.getScene().getRoot().lookupAll(".button").stream().anyMatch(javafx.scene.Node::isVisible));return null;});
    }
    @Test void aRealHeldDataLockShowsTrayGuidanceAndPreservesTheExistingOwner()throws Exception{
        Path appData=data.resolve("already-running");System.setProperty("vncode.appdata.dir",appData.toString());
        var failed=new CompletableFuture<Throwable>();var opened=new AtomicBoolean();
        try(var owner=com.vncode.app.shared.AppDataLock.acquire(appData,"first-instance")){
            fx(()->{stage=new Stage();StartupProgress.show(stage,phase->{try(var ignored=com.vncode.app.shared.AppDataLock.acquire(appData,"second-instance")){return 42;}},ignored->opened.set(true),failed::complete);return null;});
            assertInstanceOf(com.vncode.app.shared.AppDataLock.AlreadyRunningException.class,failed.get(5,TimeUnit.SECONDS));assertFalse(opened.get());assertFalse(Files.exists(appData.resolve("database.db")));
            fx(()->{assertTrue(stage.isShowing());String text=((Label)stage.getScene().lookup("#startupPhase")).getText();assertTrue(text.contains("STARTUP_ALREADY_RUNNING"));assertTrue(text.contains(messages().getString("startup.failure.already_running")));assertFalse(text.contains(appData.toString()));
                ((Button)stage.getScene().lookup("#startupCopyDiagnostics")).fire();String report=Clipboard.getSystemClipboard().getString();assertTrue(report.contains("AppDataLock$AlreadyRunningException"));assertFalse(report.contains(appData.toString()));return null;});
            assertThrows(com.vncode.app.shared.AppDataLock.AlreadyRunningException.class,()->com.vncode.app.shared.AppDataLock.acquire(appData,"third-instance"),"A failed second launch must not release or replace the original lock");
        }
    }
    @Test void onlyAcknowledgedActivationClosesTheSecondaryStartupWithoutReportingAnError()throws Exception{
        var closed=new CompletableFuture<Boolean>();var opened=new AtomicBoolean();var reported=new AtomicBoolean();
        fx(()->{stage=new Stage();stage.showingProperty().addListener((o,wasShowing,isShowing)->{if(wasShowing&&!isShowing)closed.complete(true);});
            StartupProgress.show(stage,phase->{throw new java.io.IOException("activation acknowledgement",new com.vncode.app.shared.AppInstanceActivation.ActivatedException());},ignored->opened.set(true),ignored->reported.set(true));return null;});
        assertTrue(closed.get(5,TimeUnit.SECONDS));assertFalse(opened.get());assertFalse(reported.get());
        fx(()->{assertFalse(stage.isShowing());assertFalse(stage.getScene().lookup("#startupCopyDiagnostics").getParent().isVisible());return null;});
    }
    @Test void preparationFailureCopiesPhaseAndCauseTypesWithoutMessagesCredentialsOrPaths()throws Exception{
        System.setProperty("vncode.appdata.dir",data.resolve("diagnostic-only").toString());
        String secret="do-not-share-credential-827";String jwt="eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJwcml2YXRlIn0.privateSignature";String privatePath="C:\\Users\\SellerPrivate\\data";
        var error=new java.io.IOException("api_key="+secret+" Bearer "+jwt+" "+privatePath,new java.sql.SQLException("password="+secret+" client_secret="+secret,"HY000",14));
        var failed=new CompletableFuture<Throwable>();var opened=new AtomicBoolean();
        fx(()->{stage=new Stage();StartupProgress.show(stage,phase->{phase.accept("INITIALIZING_DATA");throw error;},ignored->opened.set(true),failed::complete);return null;});
        assertSame(error,failed.get(5,TimeUnit.SECONDS));assertFalse(opened.get());
        fx(()->{String text=((Label)stage.getScene().lookup("#startupPhase")).getText();assertTrue(text.contains("STARTUP_DATABASE"));assertFalse(text.contains(secret));
            var copy=(Button)stage.getScene().lookup("#startupCopyDiagnostics");assertTrue(copy.isVisible());copy.fire();String report=Clipboard.getSystemClipboard().getString();assertNotNull(report);
            assertTrue(report.contains("VN code version: "+com.vncode.app.BuildConfig.getAppVersion()));assertTrue(report.contains("Startup phase: INITIALIZING_DATA"));assertTrue(report.contains("java.io.IOException"));assertTrue(report.contains("java.sql.SQLException"));assertTrue(report.contains("SQL code 14"));
            for(String value:new String[]{secret,jwt,privatePath,"api_key","Bearer","password","client_secret"})assertFalse(report.contains(value),value);
            assertEquals(messages().getString("startup.diagnostics_copied"),copy.getText());return null;});
        assertFalse(Files.exists(data.resolve("diagnostic-only/database.db")),"Failure reporting must not initialize SQL or application settings");
    }
    @Test void failureOpeningTheWorkspaceRetainsTheFailureWindowAndReportsThatPhase()throws Exception{
        var failed=new CompletableFuture<Throwable>();
        fx(()->{stage=new Stage();StartupProgress.show(stage,phase->42,ignored->{throw new IllegalStateException("token=workspace-private-value");},failed::complete);return null;});
        assertInstanceOf(IllegalStateException.class,failed.get(5,TimeUnit.SECONDS));
        fx(()->{assertTrue(stage.isShowing());assertTrue(((Label)stage.getScene().lookup("#startupPhase")).getText().contains("STARTUP_WORKSPACE"));((Button)stage.getScene().lookup("#startupCopyDiagnostics")).fire();String report=Clipboard.getSystemClipboard().getString();assertTrue(report.contains("Startup phase: OPENING_WORKSPACE"));assertFalse(report.contains("workspace-private-value"));return null;});
    }
    @Test void everyStartupFailureAndCopyActionHasAllSupportedTranslations(){
        for(String language:new String[]{"vi","en","ru","zh"}){
            var bundle=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",java.util.Locale.forLanguageTag(language));
            for(String key:new String[]{"startup.copy_diagnostics","startup.diagnostics_copied","startup.diagnostics_copy_failed","startup.failure.already_running","startup.failure.access_denied","startup.failure.database_busy","startup.failure.database","startup.failure.workspace","startup.failure.preparation"})assertFalse(bundle.getString(key).isBlank(),language+": "+key);
        }
    }
    private static java.util.ResourceBundle messages(){String language=java.util.Locale.getDefault().getLanguage();if(!java.util.Set.of("vi","en","ru","zh").contains(language))language="en";return java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",java.util.Locale.forLanguageTag(language));}
    @Test void closeWaitsForDataWorkerToReleaseOwnershipAndNeverOpensWorkspace()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var opened=new AtomicBoolean();
        try{
            fx(()->{stage=new Stage();StartupProgress.show(stage,phase->{entered.countDown();boolean released=false;while(!released)try{released=release.await(5,TimeUnit.SECONDS);}catch(InterruptedException requested){/* A disk operation is still completing. */}return 42;},ignored->opened.set(true),failure->{});return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            fx(()->{var event=new javafx.stage.WindowEvent(stage,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST);stage.fireEvent(event);assertTrue(event.isConsumed());assertTrue(stage.isShowing());return null;});
            assertFalse(opened.get());release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(fx(()->stage.isShowing())&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse(fx(()->stage.isShowing()));assertFalse(opened.get());
        }finally{release.countDown();}
    }
}
