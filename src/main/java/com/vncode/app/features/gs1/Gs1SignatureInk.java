package com.vncode.app.features.gs1;

import java.util.ArrayList;
import java.util.List;

/** One-dialog ink buffer. Coordinates are normalized; no image or reusable signature is persisted. */
public final class Gs1SignatureInk implements AutoCloseable {
    private static final int MAX_POINTS = 20_000;
    private final List<List<double[]>> strokes = new ArrayList<>();
    private List<double[]> current;
    private int points;
    private boolean closed;

    public void start(double x, double y) {
        ensureOpen();
        current = new ArrayList<>();
        strokes.add(current);
        append(x, y);
    }

    public void append(double x, double y) {
        ensureOpen();
        if (current == null) throw new IllegalStateException("Start a signature stroke first");
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
            throw new IllegalArgumentException("Invalid signature coordinate");
        if (points >= MAX_POINTS) throw new IllegalStateException("Signature point limit reached");
        current.add(new double[]{x, y});
        points++;
    }

    public void end() { current = null; }

    public boolean isEmpty() {
        return strokes.stream().noneMatch(stroke -> stroke.size() >= 2 &&
                stroke.stream().anyMatch(point -> Math.abs(point[0] - stroke.getFirst()[0]) +
                        Math.abs(point[1] - stroke.getFirst()[1]) > 0.005));
    }

    /** A fresh copy for immediate rendering; callers must not retain it after generating the document. */
    public List<double[][]> snapshot() {
        ensureOpen();
        return strokes.stream().map(stroke -> stroke.stream().map(double[]::clone).toArray(double[][]::new)).toList();
    }

    public void clear() {
        for (var stroke : strokes) for (var point : stroke) java.util.Arrays.fill(point, 0);
        strokes.clear(); current = null; points = 0;
    }

    @Override public void close() { clear(); closed = true; }
    @Override public String toString() { return "Gs1SignatureInk[transient]"; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Signature buffer is closed"); }
}
