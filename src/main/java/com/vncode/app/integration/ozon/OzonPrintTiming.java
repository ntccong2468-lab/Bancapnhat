package com.vncode.app.integration.ozon;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-export aggregate timings; contains no shop, posting, path, KIZ or credential data. */
public final class OzonPrintTiming implements AutoCloseable {
    public enum Stage { STOCK, PREPARATION, LABEL_NETWORK, PAGE_IDENTITY, COMPOSITION, PICKING }
    public record Summary(long totalMillis, Map<Stage,Long> stageMillis, int labelGroups, int isolatedLabels) {
        public long millis(Stage stage) { return stageMillis.getOrDefault(stage, 0L); }
    }
    private static final ThreadLocal<OzonPrintTiming> CURRENT = new ThreadLocal<>();
    private final OzonPrintTiming previous;
    private final LongSupplier clock;
    private final long started;
    private final EnumMap<Stage,Long> elapsed = new EnumMap<>(Stage.class);
    private int labelGroups;
    private int isolatedLabels;

    private OzonPrintTiming(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        this.previous = CURRENT.get();
        this.started = clock.getAsLong();
        CURRENT.set(this);
    }
    public static OzonPrintTiming begin() { return new OzonPrintTiming(System::nanoTime); }
    static OzonPrintTiming begin(LongSupplier clock) { return new OzonPrintTiming(clock); }
    public static long tick() { var timer = CURRENT.get(); return timer == null ? 0 : timer.clock.getAsLong(); }
    public static void add(Stage stage, long start) {
        var timer = CURRENT.get();
        if (timer != null) timer.elapsed.merge(stage, Math.max(0L, timer.clock.getAsLong() - start), Long::sum);
    }
    static void labelGroup() { var timer=CURRENT.get(); if(timer!=null) timer.labelGroups++; }
    static void isolatedLabel() { var timer=CURRENT.get(); if(timer!=null) timer.isolatedLabels++; }
    public Summary snapshot() {
        var values=new EnumMap<Stage,Long>(Stage.class);
        elapsed.forEach((stage,nanos)->values.put(stage,TimeUnit.NANOSECONDS.toMillis(nanos)));
        return new Summary(TimeUnit.NANOSECONDS.toMillis(Math.max(0L,clock.getAsLong()-started)),Map.copyOf(values),labelGroups,isolatedLabels);
    }
    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("OZON_TIMING_SCOPE_CHANGED");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
