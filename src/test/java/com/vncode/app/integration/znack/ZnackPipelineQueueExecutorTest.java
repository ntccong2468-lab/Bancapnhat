package com.vncode.app.integration.znack;

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
    void preservesGtinFifoAfterFailureWhileIndependentGtinCanProgress() throws Exception {
        int shopId = 999;
        String gtin = "04601234567890";
        List<String> sameGtin = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch independentDone = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        ZnackPipelineQueueExecutor.submit(shopId, gtin, () -> {
            sameGtin.add("FIRST");
            firstEntered.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        try {
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ZnackPipelineQueueExecutor.submit(shopId, gtin, () -> {
                sameGtin.add("FAILED");
                done.countDown();
                throw new RuntimeException("Simulated failure in the same GTIN lane");
            });
            ZnackPipelineQueueExecutor.submit(shopId, gtin, () -> {
                sameGtin.add("RETRY");
                done.countDown();
            });
            ZnackPipelineQueueExecutor.submit(shopId, "04601234567891", independentDone::countDown);

            assertTrue(independentDone.await(5, TimeUnit.SECONDS),
                    "A blocked GTIN must leave capacity for another GTIN in the same shop");
            assertEquals(List.of("FIRST"), sameGtin,
                    "Later work for the blocked GTIN must not overtake its first task");
        } finally {
            releaseFirst.countDown();
        }

        assertTrue(done.await(5, TimeUnit.SECONDS),
                "The same GTIN lane must continue after the intermediate task fails");
        assertEquals(List.of("FIRST", "FAILED", "RETRY"), sameGtin,
                "FIFO is preserved per GTIN, including cleanup after a thrown exception");
    }
}
