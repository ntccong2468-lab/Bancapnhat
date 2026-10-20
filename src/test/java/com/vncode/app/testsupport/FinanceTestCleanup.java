package com.vncode.app.testsupport;

import com.vncode.app.features.finance.FinanceExecutor;
import com.vncode.app.features.finance.FinanceSyncScheduler;
import javafx.application.Platform;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Await owned analytics work before changing the fixture's app-data path or deleting SQLite. */
public final class FinanceTestCleanup {
    private FinanceTestCleanup() {}
    public static void detachAndAwait() throws Exception {
        if(Platform.isFxApplicationThread())throw new IllegalStateException("Cleanup must run off FX");
        FinanceSyncScheduler.getInstance().updateShops(List.of(),false);
        for(int pass=0;pass<2;pass++) {
            for(String name:List.of("SYNC_WORKER","QUERY_WORKER")) {
                var field=FinanceExecutor.class.getDeclaredField(name);field.setAccessible(true);
                ((ExecutorService)field.get(null)).submit(()->{}).get(15,TimeUnit.SECONDS);
            }
            var fx=new FutureTask<Void>(()->null);Platform.runLater(fx);fx.get(5,TimeUnit.SECONDS);
        }
    }
}
