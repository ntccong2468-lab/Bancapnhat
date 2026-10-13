package com.vncode.app.testsupport;

import com.vncode.app.shared.ShopReadCoordinator;
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
}
