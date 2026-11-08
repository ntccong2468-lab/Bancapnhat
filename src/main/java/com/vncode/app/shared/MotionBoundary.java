package com.vncode.app.shared;

import javafx.scene.Node;
import java.lang.ref.WeakReference;
import java.util.Objects;

/** One interruptible content transition. It owns no views, scenes, routes or lifecycle hooks. */
public final class MotionBoundary implements AutoCloseable {
    private WeakReference<Node> current = new WeakReference<>(null);

    /** Call after the host has installed the incoming page in its content slot. */
    public void show(Node incoming, NavigationContext context) {
        Objects.requireNonNull(incoming);
        Objects.requireNonNull(context);
        Node previous = current.get();
        if (previous != null) WorkspaceMotion.stop(previous);
        if (previous == incoming) return;
        current = new WeakReference<>(incoming);
        WorkspaceMotion.navigation(incoming, context);
    }

    public void cancel() {
        Node node = current.get();
        current.clear();
        if (node != null) WorkspaceMotion.stop(node);
    }

    @Override public void close() { cancel(); }
}
