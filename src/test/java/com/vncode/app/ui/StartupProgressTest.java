package com.vncode.app.ui;

import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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
