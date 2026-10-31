package com.vncode.app.ui.ozon;

import com.vncode.app.integration.ozon.OzonApiException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Retries only an explicit, unambiguous rate-limit response; unknown outcomes are reconciled elsewhere. */
final class OzonAcceptanceRetry {
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration DEFAULT_DELAY = Duration.ofSeconds(60);
    private static final Duration MAX_INLINE_WAIT = Duration.ofMinutes(2);
    @FunctionalInterface interface Operation<T> { T run() throws Exception; }
    @FunctionalInterface interface Pause { void waitFor(Duration delay, BooleanSupplier stopping) throws InterruptedException; }

    static <T> T run(Operation<T> operation, Runnable checkOwner, BooleanSupplier stopping,
                     Consumer<Duration> waiting) throws Exception {
        return run(operation, checkOwner, stopping, waiting, OzonAcceptanceRetry::pause);
    }

    static <T> T run(Operation<T> operation, Runnable checkOwner, BooleanSupplier stopping,
                     Consumer<Duration> waiting, Pause pause) throws Exception {
        for (int attempt = 1; ; attempt++) {
            if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            checkOwner.run();
            try { return operation.run(); }
            catch (OzonApiException failure) {
                if (failure.statusCode() != 429 || failure.ambiguousMutation() || attempt >= MAX_ATTEMPTS) throw failure;
                Duration delay = failure.retryAfter();
                if (delay == null || delay.isNegative() || delay.isZero()) delay = DEFAULT_DELAY;
                // Do not pretend a long deferral has completed, or monopolize the batch worker for hours.
                if (delay.compareTo(MAX_INLINE_WAIT) > 0) throw failure;
                waiting.accept(delay);
                try { pause.waitFor(delay, stopping); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            }
        }
    }

    static void pause(Duration delay, BooleanSupplier stopping) throws InterruptedException {
        long remaining = delay.toNanos();
        while (remaining > 0) {
            if (stopping.getAsBoolean()) throw new CancellationException();
            long step = Math.min(remaining, Duration.ofMillis(250).toNanos());
            long started = System.nanoTime();
            Thread.sleep(step / 1_000_000L, (int) (step % 1_000_000L));
            remaining -= Math.max(1, System.nanoTime() - started);
        }
    }
    private OzonAcceptanceRetry() {}
}
