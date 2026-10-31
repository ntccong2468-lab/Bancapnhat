package com.vncode.app.integration.znack.registration;

import com.vncode.app.models.Shop;
import com.vncode.app.shared.UpdateHandoff;
import com.vncode.app.testsupport.ShopReadTestCleanup;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.fail;

/** Drain real asynchronous fixture work before resetting app-data or deleting SQLite on Windows. */
final class OzonRegistrationTestCleanup {
    private OzonRegistrationTestCleanup() { }

    static void cancelAndAwait(OzonRegistrationBatchService service,OzonCardRegistrationWorkflow workflow,Shop shop)throws Exception {
        if(service!=null&&shop!=null)service.cancel(shop);
        awaitIdle(workflow,shop);
    }

    static void awaitIdle(OzonCardRegistrationWorkflow workflow,Shop shop)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);int stable=0;
        while(System.nanoTime()<deadline){
            // This also drains queued FX callbacks and the shared UI/SQLite executors.
            ShopReadTestCleanup.awaitSharedIdle();
            boolean idle=!OzonRegistrationBatchService.hasPendingJobs()
                    &&(workflow==null||shop==null||!workflow.isRunning(shop))
                    &&UpdateHandoff.awaitIdle(20);
            stable=idle?stable+1:0;if(stable==2)return;Thread.sleep(20);
        }
        fail("Ozon fixture work must finish before clearing app-data and deleting SQLite");
    }
}
