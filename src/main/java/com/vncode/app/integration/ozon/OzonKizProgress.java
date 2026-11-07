package com.vncode.app.integration.ozon;

import java.util.Objects;
import java.util.function.Consumer;

/** Operation-scoped progress; never carries codes or credentials across worker/UI boundaries. */
public final class OzonKizProgress {
    public enum Stage { READ, VALIDATE, REPLACE, VERIFY }
    private static final ThreadLocal<Consumer<Stage>> LISTENER = new ThreadLocal<>();

    private OzonKizProgress() {}

    public static Scope listen(Consumer<Stage> listener) {
        var previous = LISTENER.get();
        LISTENER.set(Objects.requireNonNull(listener));
        return () -> { if (previous == null) LISTENER.remove(); else LISTENER.set(previous); };
    }

    static void report(Stage stage) {
        var listener = LISTENER.get();
        if (listener != null) listener.accept(stage);
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
