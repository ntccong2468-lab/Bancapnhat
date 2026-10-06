package com.tuandev.fbsbarcode.integration.znack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sequential queue executor for Znack purchase and introduction operations.
 * Ensures that for any given shop, exactly one GTIN pipeline is processed at a time,
 * with a 500ms pacing delay between tasks to prevent HTTP 429 rate limit failures.
 */
public final class ZnackPipelineQueueExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackPipelineQueueExecutor.class);
    private static final Map<Integer, ExecutorService> SHOP_EXECUTORS = new ConcurrentHashMap<>();
    private static final long PACING_DELAY_MS = 500L;

    private ZnackPipelineQueueExecutor() {
    }

    /**
     * Submits a pipeline task for sequential execution under the specified shop ID.
     */
    public static void submit(int shopId, String gtin, Runnable task) {
        if (task == null) return;
        ExecutorService executor = SHOP_EXECUTORS.computeIfAbsent(shopId, id ->
                Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "znack-pipeline-queue-shop-" + id);
                    thread.setDaemon(true);
                    return thread;
                }));
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("Error executing Znack pipeline for GTIN {} in shop {}", gtin, shopId, t);
            } finally {
                try {
                    Thread.sleep(PACING_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /**
     * Shuts down all shop queue executors (useful for app shutdown or test cleanup).
     */
    public static void shutdownAll() {
        for (ExecutorService executor : SHOP_EXECUTORS.values()) {
            executor.shutdownNow();
        }
        SHOP_EXECUTORS.clear();
    }
}
