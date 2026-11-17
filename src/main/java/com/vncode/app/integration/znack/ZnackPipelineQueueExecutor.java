package com.vncode.app.integration.znack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Separate emission, retrieval and document workers, with FIFO across stages for each GTIN.
 */
public final class ZnackPipelineQueueExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackPipelineQueueExecutor.class);
    private static final Map<Integer, ShopQueue> SHOPS = new HashMap<>();
    private static boolean shuttingDown;
    public enum WorkStage { CREATE, RETRIEVE, INTRODUCE }

    private ZnackPipelineQueueExecutor() {
    }

    /**
     * Compatibility entry point; submission without a stage is emission work.
     */
    public static void submit(int shopId, String gtin, Runnable task) {
        submit(shopId, gtin, WorkStage.CREATE, task);
    }

    public static synchronized boolean submit(int shopId, String gtin, WorkStage stage, Runnable task) {
        if (task == null || stage == null || shuttingDown) return false;
        if (shopId <= 0) throw new IllegalArgumentException("A persisted shop is required.");
        if (gtin == null || gtin.isBlank()) throw new IllegalArgumentException("A GTIN queue key is required.");
        return SHOPS.computeIfAbsent(shopId, ShopQueue::new).offer(gtin, new Work(stage, task));
    }

    /** Stop this generation; persisted jobs can resume in the next application lifecycle. */
    public static void shutdownAll() {
        List<ExecutorService> executors = new ArrayList<>();
        synchronized (ZnackPipelineQueueExecutor.class) {
            if (shuttingDown) return;
            shuttingDown = true;
            for (ShopQueue shop : SHOPS.values()) executors.addAll(shop.stop());
            SHOPS.clear();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            for (ExecutorService executor : executors) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (ZnackPipelineQueueExecutor.class) { shuttingDown = false; }
        }
    }

    private record Work(WorkStage stage, Runnable task) { }

    private static final class ShopQueue {
        private final int shopId;
        private final EnumMap<WorkStage, ExecutorService> workers = new EnumMap<>(WorkStage.class);
        private final Map<String, ArrayDeque<Work>> lanes = new HashMap<>();
        private boolean stopped;

        ShopQueue(int shopId) {
            this.shopId = shopId;
            for (WorkStage stage : WorkStage.values()) {
                int capacity = stage == WorkStage.CREATE ? 4 : 2;
                workers.put(stage, Executors.newFixedThreadPool(capacity, runnable -> {
                    Thread thread = new Thread(runnable, "vncode-kiz-" + stage + "-shop-" + shopId);
                    thread.setDaemon(true);
                    return thread;
                }));
            }
        }

        synchronized boolean offer(String gtin, Work work) {
            if (stopped) return false;
            ArrayDeque<Work> lane = lanes.computeIfAbsent(gtin, ignored -> new ArrayDeque<>());
            lane.addLast(work);
            if (lane.size() == 1 && !dispatch(gtin, work)) {
                lane.removeFirst();
                lanes.remove(gtin);
                return false;
            }
            return true;
        }

        private boolean dispatch(String gtin, Work work) {
            try {
                workers.get(work.stage()).execute(() -> run(gtin, work));
                return true;
            } catch (RejectedExecutionException stoppedExecutor) {
                return false;
            }
        }

        private void run(String gtin, Work work) {
            try {
                work.task().run();
            } catch (Throwable failure) {
                LOGGER.error("KIZ {} work failed in shop {} for GTIN {}", work.stage(), shopId, gtin, failure);
            } finally {
                synchronized (this) {
                    ArrayDeque<Work> lane = lanes.get(gtin);
                    if (lane == null) return;
                    lane.removeFirstOccurrence(work);
                    if (stopped || lane.isEmpty()) {
                        lanes.remove(gtin);
                    } else if (!dispatch(gtin, lane.getFirst())) {
                        // All intents already live in SQLite; recover them on restart.
                        lanes.remove(gtin);
                    }
                }
            }
        }

        synchronized List<ExecutorService> stop() {
            stopped = true;
            lanes.clear();
            workers.values().forEach(ExecutorService::shutdownNow);
            return List.copyOf(workers.values());
        }
    }
}
