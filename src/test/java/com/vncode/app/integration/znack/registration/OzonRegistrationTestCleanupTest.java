package com.vncode.app.integration.znack.registration;

import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.UpdateHandoff;
import javafx.application.Platform;
import javafx.concurrent.Task;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonRegistrationTestCleanupTest {
    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void drainEarlierWork()throws Exception{OzonRegistrationTestCleanup.awaitIdle(null,null);}

    @Test void waitsForARealWorkerEvenIfNoBatchIsPending()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var work=pool.submit(()->{try(var ignored=UpdateHandoff.work()){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return null;});
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(OzonRegistrationBatchService.hasPendingJobs());
                var drain=pool.submit(()->{OzonRegistrationTestCleanup.awaitIdle(null,null);return null;});
                assertThrows(TimeoutException.class,()->drain.get(150,TimeUnit.MILLISECONDS));release.countDown();drain.get(5,TimeUnit.SECONDS);work.get(5,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }

    @Test void drainsFxCallbacksThatStartBackgroundWork()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            try{
                Platform.runLater(()->AppTaskExecutor.execute(new Task<Void>(){@Override protected Void call()throws Exception{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}}));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var drain=pool.submit(()->{OzonRegistrationTestCleanup.awaitIdle(null,null);return null;});
                assertThrows(TimeoutException.class,()->drain.get(150,TimeUnit.MILLISECONDS));release.countDown();drain.get(5,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
}
