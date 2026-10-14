package com.vncode.app.testsupport;

import com.vncode.app.shared.ShopReadCoordinator;
import com.vncode.app.shared.AppTaskExecutor;
import javafx.application.Platform;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Run off the FX thread, before clearing app-data scope or deleting an SQLite fixture. */
public final class ShopReadTestCleanup {
    private ShopReadTestCleanup() { }
    public static void closeAndAwait(ShopReadCoordinator reads) throws InterruptedException {
        reads.close();
        // close() requests cancellation; native SQLite reads must return and close their handles.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reads.activeCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, reads.activeCount(), "Fixture reads must release database handles before cleanup");
        assertEquals(0, reads.pendingCount());
    }

    /** Drain shared work without closing the shared pool or cancelling accepted seller writes. */
    public static void awaitSharedIdle() throws Exception {
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("Fixture cleanup must run off FX");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int stable = 0;
        while (System.nanoTime() < deadline) {
            var barrier = new FutureTask<Void>(() -> null);
            Platform.runLater(barrier);
            barrier.get(5, TimeUnit.SECONDS);
            boolean idle = !AppTaskExecutor.hasRunningTasks()
                    && ShopReadCoordinator.shared().activeCount() == 0
                    && ShopReadCoordinator.shared().pendingCount() == 0
                    && ShopReadCoordinator.images().activeCount() == 0
                    && ShopReadCoordinator.images().pendingCount() == 0;
            stable = idle ? stable + 1 : 0;
            if (stable == 2) return;
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.fail("Shared fixture work must release SQLite before cleanup");
    }
}
