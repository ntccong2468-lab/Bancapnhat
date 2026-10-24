package com.vncode.app.shared;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Stop admitting work for installation; durable workers yield only at saved checkpoints. */
public final class UpdateHandoff {
    private static final Object MONITOR = new Object();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Root> ROOT = new ThreadLocal<>();
    private static final class Root {
        final Root parent;
        boolean live = true, suspended;
        Root(Root parent) { this.parent = parent; }
    }
    public static final class Ticket {
        private final Root owner;
        private Ticket(Root owner) { this.owner = owner; }
    }
    private static int pauses, active;
    private static boolean stopping;
    private UpdateHandoff() { }

    public interface Lease extends AutoCloseable { @Override void close(); }

    public static Ticket continuation() { return new Ticket(ROOT.get()); }

    public static Lease work() { return work(null); }

    public static Lease work(Ticket ticket) {
        synchronized (MONITOR) {
            int depth = DEPTH.get();
            if (depth == 0) {
                // A parent already inside a transaction may await this child. Let that
                // continuation finish, rather than deadlocking installation behind it.
                boolean continuation = ticket != null && ticket.owner != null
                        && ticket.owner.live && !ticket.owner.suspended;
                if (!continuation) awaitOpen();
                if (stopping) throw new CancellationException("APPLICATION_STOPPING");
                ROOT.set(new Root(continuation ? ticket.owner : null)); active++;
            }
            DEPTH.set(depth + 1);
        }
        return new Lease() {
            private boolean closed;
            @Override public void close() {
                synchronized (MONITOR) {
                    if (closed) return;
                    closed = true;
                    int depth = DEPTH.get() - 1;
                    if (depth == 0) { ROOT.get().live = false; ROOT.remove(); DEPTH.remove(); active--; MONITOR.notifyAll(); }
                    else DEPTH.set(depth);
                }
            }
        };
    }

    public static Lease pause() {
        synchronized (MONITOR) { pauses++; MONITOR.notifyAll(); }
        return new Lease() {
            private boolean closed;
            @Override public void close() {
                synchronized (MONITOR) {
                    if (!closed) { closed = true; pauses--; MONITOR.notifyAll(); }
                }
            }
        };
    }

    public static boolean isStopping() { synchronized(MONITOR) { return stopping; } }

    public static boolean requested() { synchronized (MONITOR) { return pauses > 0; } }

    /** Call only after a durable item/batch transaction and its receipt have been saved. */
    public static void checkpoint() {
        synchronized (MONITOR) {
            if (pauses == 0) return;
            boolean owned = DEPTH.get() > 0;
            // A caller awaiting this continuation still owns its critical section.
            // Drain it; only independent durable workers yield between saved items.
            if (owned && ROOT.get().parent != null && ROOT.get().parent.live && !ROOT.get().parent.suspended) return;
            if (owned) { ROOT.get().suspended = true; active--; MONITOR.notifyAll(); }
            try { awaitOpen(); }
            finally { if (owned) { active++; ROOT.get().suspended = false; } }
        }
    }

    /** The caller is the separate installer worker, never a tracked application worker. */
    public static boolean awaitIdle(long milliseconds) throws InterruptedException {
        synchronized (MONITOR) {
            if (active == 0) return true;
            TimeUnit.MILLISECONDS.timedWait(MONITOR, milliseconds);
            return active == 0;
        }
    }

    public static void stopping() {
        synchronized (MONITOR) { stopping = true; MONITOR.notifyAll(); }
    }

    private static void awaitOpen() {
        while (pauses > 0 && !stopping) {
            try { MONITOR.wait(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("UPDATE_WORK_INTERRUPTED");
            }
        }
        if (stopping) throw new CancellationException("APPLICATION_STOPPING");
    }
}
