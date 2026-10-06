package com.tuandev.fbsbarcode.integration.znack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackPipelineQueueExecutorTest {

    @AfterEach
    void tearDown() {
        ZnackPipelineQueueExecutor.shutdownAll();
    }

    @Test
    void executesTasksSequentiallyInFifoOrderPerShop() throws Exception {
        int shopId = 999;
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(3);

        ZnackPipelineQueueExecutor.submit(shopId, "04601234567890", () -> {
            executed.add("GTIN-1");
            done.countDown();
        });
        ZnackPipelineQueueExecutor.submit(shopId, "04601234567891", () -> {
            throw new RuntimeException("Simulated GTIN-2 failure");
        });
        ZnackPipelineQueueExecutor.submit(shopId, "04601234567891", () -> {
            executed.add("GTIN-2-RETRY");
            done.countDown();
        });
        ZnackPipelineQueueExecutor.submit(shopId, "04601234567892", () -> {
            executed.add("GTIN-3");
            done.countDown();
        });

        boolean finished = done.await(5, TimeUnit.SECONDS);
        assertTrue(finished, "All queued tasks should finish within 5 seconds");
        assertEquals(List.of("GTIN-1", "GTIN-2-RETRY", "GTIN-3"), executed,
                "Tasks should execute sequentially in FIFO order even when an intermediate task throws an exception");
    }
}
