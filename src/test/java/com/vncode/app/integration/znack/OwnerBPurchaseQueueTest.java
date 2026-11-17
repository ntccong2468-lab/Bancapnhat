package com.vncode.app.integration.znack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.vncode.app.integration.znack.ZnackPipelineQueueExecutor.WorkStage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real bounded worker coordination; no API, purchases, database, signing or scheduler sleeps. */
@Timeout(10)
class OwnerBPurchaseQueueTest {
    @AfterEach void cleanup() {
        ZnackPipelineQueueExecutor.shutdownAll();
    }

    @Test void differentGtinsProgressWhileAnotherGtinIsBlockedInTheSameShop() throws Exception {
        int shop = 93421;
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var otherFinished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try {
            ZnackPipelineQueueExecutor.submit(shop, "04601234567890", () -> {
                firstStarted.countDown();
                awaitWorker(releaseFirst, failure);
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "The first paid pipeline must start");
            ZnackPipelineQueueExecutor.submit(shop, "04601234567807", otherFinished::countDown);

            assertTrue(otherFinished.await(1, TimeUnit.SECONDS),
                    "An independent GTIN in the same shop must progress while the first GTIN waits");
        } finally {
            releaseFirst.countDown();
        }
        assertNull(failure.get(), "Worker coordination must not hide a task failure");
    }

    @Test void sameGtinRequestsKeepFifoAndNeverOverlap() throws Exception {
        int shop = 93422;
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        try {
            ZnackPipelineQueueExecutor.submit(shop, "04601234567890", () -> {
                events.add("first-start");
                firstStarted.countDown();
                awaitWorker(releaseFirst, failure);
                events.add("first-end");
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            ZnackPipelineQueueExecutor.submit(shop, "04601234567890", () -> {
                events.add("second-start");
                secondStarted.countDown();
            });
            assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS),
                    "The next request for the same paid GTIN must not overtake the first");
            releaseFirst.countDown();
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS), "FIFO must eventually release the next GTIN request");
            assertEquals(List.of("first-start", "first-end", "second-start"), events);
            assertNull(failure.get());
        } finally {
            releaseFirst.countDown();
        }
    }

    @ParameterizedTest @EnumSource(WorkStage.class)
    void eachStageAllowsItsDocumentedCapacityWithoutExceedingIt(WorkStage stage) throws Exception {
        int capacity = capacity(stage);
        int shop = 93430 + stage.ordinal();
        var capacityReached = new CountDownLatch(capacity);
        var anyExcessStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(capacity + 1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var started = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        try {
            for (int index = 0; index <= capacity; index++) {
                assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(index), stage, () -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    if (started.incrementAndGet() > capacity) anyExcessStarted.countDown();
                    capacityReached.countDown();
                    try {
                        awaitWorker(release, failure);
                    } finally {
                        active.decrementAndGet();
                        finished.countDown();
                    }
                }));
            }
            assertTrue(capacityReached.await(2, TimeUnit.SECONDS), stage + " must use its available worker capacity");
            assertFalse(anyExcessStarted.await(150, TimeUnit.MILLISECONDS), stage + " must not exceed its capacity");
            assertEquals(capacity, peak.get());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS), "All accepted work must complete after capacity becomes free");
        assertTrue(peak.get() <= capacity);
        assertNull(failure.get());
    }

    @Test void sameGtinIsFifoAcrossStagesWhileUnrelatedGtinCanProgress() throws Exception {
        int shop = 93440;
        var createStarted = new CountDownLatch(1);
        var releaseCreate = new CountDownLatch(1);
        var retrieveStarted = new CountDownLatch(1);
        var introduceFinished = new CountDownLatch(1);
        var unrelatedFinished = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        try {
            assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), WorkStage.CREATE, () -> {
                events.add("create-start");
                createStarted.countDown();
                awaitWorker(releaseCreate, failure);
                events.add("create-end");
            }));
            assertTrue(createStarted.await(2, TimeUnit.SECONDS));
            assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), WorkStage.RETRIEVE, () -> {
                events.add("retrieve");
                retrieveStarted.countDown();
            }));
            assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), WorkStage.INTRODUCE, () -> {
                events.add("introduce");
                introduceFinished.countDown();
            }));
            assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(1), WorkStage.INTRODUCE, unrelatedFinished::countDown));
            assertTrue(unrelatedFinished.await(2, TimeUnit.SECONDS), "A blocked GTIN cannot monopolize a different stage");
            assertFalse(retrieveStarted.await(150, TimeUnit.MILLISECONDS));
            assertEquals(1, introduceFinished.getCount());
        } finally {
            releaseCreate.countDown();
        }
        assertTrue(introduceFinished.await(3, TimeUnit.SECONDS));
        assertEquals(List.of("create-start", "create-end", "retrieve", "introduce"), events);
        assertNull(failure.get());
    }

    @ParameterizedTest @EnumSource(WorkStage.class)
    void taskFailureDoesNotRetainGtinOrStagePermits(WorkStage stage) throws Exception {
        int shop = 93450 + stage.ordinal();
        var failuresStarted = new CountDownLatch(capacity(stage));
        for (int index = 0; index < capacity(stage); index++) {
            assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(index), stage, () -> {
                failuresStarted.countDown();
                throw new AssertionError("Synthetic worker failure for permit-cleanup verification");
            }));
        }
        assertTrue(failuresStarted.await(2, TimeUnit.SECONDS));
        var reusedGtinFinished = new CountDownLatch(1);
        var otherGtinFinished = new CountDownLatch(1);
        assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), stage, reusedGtinFinished::countDown));
        assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(99), stage, otherGtinFinished::countDown));
        assertTrue(reusedGtinFinished.await(2, TimeUnit.SECONDS), "A failing task must release its GTIN queue");
        assertTrue(otherGtinFinished.await(2, TimeUnit.SECONDS), "A failing task must release its stage admission");
    }

    @Test void invalidStageOrTaskIsRejectedWithoutExecutingWork() {
        var executed = new AtomicInteger();
        assertFalse(ZnackPipelineQueueExecutor.submit(93460, gtin(0), null, executed::incrementAndGet));
        assertFalse(ZnackPipelineQueueExecutor.submit(93460, gtin(0), WorkStage.CREATE, null));
        assertEquals(0, executed.get());
    }

    @Test @Timeout(15)
    void shutdownRejectsNewWorkDuringDrainAndFreshGenerationReleasesCapacity() throws Exception {
        int shop = 93461;
        var started = new CountDownLatch(1);
        var interruptedByShutdown = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejectedWorkExecuted = new AtomicInteger();
        assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), WorkStage.CREATE, () -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) return;
                } catch (InterruptedException expected) {
                    interruptedByShutdown.countDown();
                }
            }
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        var shutdown = new Thread(ZnackPipelineQueueExecutor::shutdownAll, "synthetic-queue-shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
        try {
            assertTrue(interruptedByShutdown.await(2, TimeUnit.SECONDS), "Shutdown must cancel its active generation");
            assertFalse(ZnackPipelineQueueExecutor.submit(shop, gtin(1), WorkStage.CREATE, rejectedWorkExecuted::incrementAndGet),
                    "New work must not enter a generation being drained");
        } finally {
            release.countDown();
            shutdown.join(3000);
        }
        assertFalse(shutdown.isAlive(), "Shutdown must finish after the owned work exits");
        assertEquals(0, rejectedWorkExecuted.get());
        var freshFinished = new CountDownLatch(1);
        assertTrue(ZnackPipelineQueueExecutor.submit(shop, gtin(0), WorkStage.CREATE, freshFinished::countDown),
                "Existing callers can create a fresh queue generation after shutdownAll returns");
        assertTrue(freshFinished.await(2, TimeUnit.SECONDS), "Shutdown must not leak old admission permits or GTIN ownership");
    }

    private static int capacity(WorkStage stage) {
        return stage == WorkStage.CREATE ? 4 : 2;
    }

    private static String gtin(int index) {
        return "04601234567%03d".formatted(index);
    }

    private static void awaitWorker(CountDownLatch latch, AtomicReference<Throwable> failure) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) failure.compareAndSet(null, new AssertionError("Worker release timed out"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, interrupted);
        }
    }
}
