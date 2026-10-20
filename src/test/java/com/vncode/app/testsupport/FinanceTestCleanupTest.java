package com.vncode.app.testsupport;

import com.vncode.app.features.finance.AnalyticsDatabase;
import com.vncode.app.features.finance.FinanceExecutor;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceTestCleanupTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    @Test void waitsForAnalyticsHandleBeforeFixtureDeletionWithoutShuttingDownSharedWorkers()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var cleanup=Executors.newSingleThreadExecutor();Future<?> drained=null;
        try {
            FinanceExecutor.executeSync(()->{try(var connection=AnalyticsDatabase.getConnection()){
                entered.countDown();release.await(5,TimeUnit.SECONDS);
            }catch(Throwable error){failure.set(error);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            drained=cleanup.submit(()->{try{FinanceTestCleanup.detachAndAwait();}catch(Exception e){throw new RuntimeException(e);}});
            var pending=drained;assertThrows(TimeoutException.class,()->pending.get(100,TimeUnit.MILLISECONDS));
            release.countDown();drained.get(10,TimeUnit.SECONDS);assertNull(failure.get());
            Files.delete(AnalyticsDatabase.databasePath());assertFalse(Files.exists(AnalyticsDatabase.databasePath()));
            var usable=new CountDownLatch(1);FinanceExecutor.executeSync(usable::countDown);assertTrue(usable.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();if(drained!=null)drained.get(10,TimeUnit.SECONDS);cleanup.shutdownNow();FinanceTestCleanup.detachAndAwait();System.clearProperty("vncode.appdata.dir");}
    }
}
