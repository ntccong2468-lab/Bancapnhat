package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DetailedErrorActionTest {
    @TempDir Path profile;
    @BeforeEach void profile(){System.setProperty("vncode.appdata.dir",profile.toString());}
    @AfterEach void clearProfile(){System.clearProperty("vncode.appdata.dir");}
    @BeforeAll static void toolkit() {
        try { Platform.startup(()->Platform.setImplicitExit(false)); } catch(IllegalStateException alreadyStarted) { }
    }
    @Test void CopyPreservesReportAndOnlyExplicitActionRunsAfterClosing() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        fx(()->{
            Platform.runLater(()->{
                var pane=dialog();
                var copy=pane.getButtonTypes().stream().filter(t->t.getText().equals(I18nService.getInstance().tr("common.copy"))).findFirst().orElseThrow();
                ((Button)pane.lookupButton(copy)).fire();
                assertTrue(pane.getScene().getWindow().isShowing());assertEquals(0,calls.get());
                ((Button)pane.lookup("#detailedErrorAction")).fire();
            });
            AlertService.showDetailedError("Quota exhausted","synthetic report","Review GS1",()->{
                assertTrue(Window.getWindows().stream().noneMatch(w->w.isShowing()&&w.getScene().lookup("#detailedErrorAction")!=null));calls.incrementAndGet();
            });
            assertEquals(1,calls.get());return null;
        });
    }
    @Test void ClosingTheReportDoesNotNavigateOrSendAnything() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        fx(()->{
            Platform.runLater(()->{
                var pane=dialog();var close=pane.getButtonTypes().stream().filter(t->t.getButtonData().isCancelButton()).findFirst().orElseThrow();
                ((Button)pane.lookupButton(close)).fire();
            });
            AlertService.showDetailedError("Membership","synthetic report","Review GS1",calls::incrementAndGet);
            assertEquals(0,calls.get());return null;
        });
    }
    private static DialogPane dialog(){return Window.getWindows().stream().filter(Window::isShowing)
            .map(w->w.getScene().lookup(".dialog-pane")).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();}
    private static <T>T fx(Callable<T> action)throws Exception{
        FutureTask<T> task=new FutureTask<>(action);Platform.runLater(task);try{return task.get(20,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Error cause)throw cause;if(failed.getCause() instanceof Exception cause)throw cause;throw failed;}
    }
}
