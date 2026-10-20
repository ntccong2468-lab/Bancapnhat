package com.vncode.app;

import com.vncode.app.features.gs1.Gs1AutoReminderScheduler;
import com.vncode.app.shared.AppDataLock;
import com.vncode.app.shared.AppInstanceActivation;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MainApplicationActivationTest {
    @TempDir Path profile;
    MainApplication application;
    Stage stage;

    @BeforeAll static void toolkit(){
        try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}
    }
    static <T>T fx(Callable<T> action)throws Exception {
        var work=new FutureTask<>(action);Platform.runLater(work);return work.get(10,TimeUnit.SECONDS);
    }
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",profile.toString());
        fx(()->{application=new MainApplication();stage=new Stage();var scene=new Scene(new VBox(),240,120);scene.getProperties().put(ThemeService.STARTUP_SCENE,Boolean.TRUE);stage.setScene(scene);stage.show();stage.setIconified(true);stage.hide();return null;});
    }
    @AfterEach void cleanup()throws Exception {
        try{fx(()->{
            if(stage!=null)stage.close();
            if(application!=null){var reminders=MainApplication.class.getDeclaredField("gs1Reminders");reminders.setAccessible(true);((Gs1AutoReminderScheduler)reminders.get(application)).close();}
            return null;
        });}finally{System.clearProperty("vncode.appdata.dir");}
    }
    @SuppressWarnings("unchecked") CompletionStage<Void> restore(){
        try{var method=MainApplication.class.getDeclaredMethod("restoreWindow",Stage.class);method.setAccessible(true);return (CompletionStage<Void>)method.invoke(application,stage);}
        catch(ReflectiveOperationException failure){return CompletableFuture.failedFuture(failure instanceof InvocationTargetException wrapped?wrapped.getCause():failure);}
    }

    @Test void restoreCompletesAfterTheHiddenWindowIsShownAndUniconifiedOnFx()throws Exception {
        var completedOnFx=new AtomicBoolean();var visibleAtCompletion=new AtomicBoolean();
        var restored=fx(()->{
            assertFalse(stage.isShowing());
            var completion=restore().toCompletableFuture();
            assertFalse(completion.isDone(),"The queued window restore has not executed yet");
            // Waiting on the parent can execute its dependent observer on the waiting thread.
            // Wait for the observer instead, keeping the exact restored-window assertions on FX.
            return completion.thenRun(()->{completedOnFx.set(Platform.isFxApplicationThread());visibleAtCompletion.set(stage.isShowing()&&!stage.isIconified());});
        });
        restored.get(5,TimeUnit.SECONDS);
        fx(()->{assertTrue(completedOnFx.get(),"Completion observer ran outside FX");assertTrue(visibleAtCompletion.get(),"Window was not visible and restored when observed");assertTrue(stage.isShowing(),"Restored window is hidden");assertFalse(stage.isIconified(),"Restored window is iconified");return null;});
    }

    @Test void aSecondLaunchIsAcknowledgedOnlyAfterTheRealWindowRestore()throws Exception {
        try(var ownership=AppDataLock.acquire(profile,"first");var listener=AppInstanceActivation.listen(ownership,this::restore)){
            assertTrue(AppInstanceActivation.requestActivation(profile,Duration.ofSeconds(3)));
            fx(()->{assertTrue(stage.isShowing());assertFalse(stage.isIconified());return null;});
            assertThrows(AppDataLock.AlreadyRunningException.class,()->AppDataLock.acquire(profile,"second"));
        }
    }

    @Test void stoppingApplicationRejectsActivationAndLeavesTheWindowHidden()throws Exception {
        fx(()->{var stopping=MainApplication.class.getDeclaredField("stopping");stopping.setAccessible(true);stopping.setBoolean(application,true);return null;});
        var failure=assertThrows(ExecutionException.class,()->restore().toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertEquals("APPLICATION_STOPPING",failure.getCause().getMessage());
        fx(()->{assertFalse(stage.isShowing());return null;});
    }
}
