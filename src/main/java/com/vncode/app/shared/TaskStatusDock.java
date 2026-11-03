package com.vncode.app.shared;

import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.util.IdentityHashMap;
import java.util.Map;

/** Reserves a footer for task feedback without replacing the live table or its selection. */
public final class TaskStatusDock {
    private TaskStatusDock() { }

    public static void install(StackPane host) {
        if (host.getProperties().containsKey(TaskStatusDock.class)) return;
        var state = new State(host);
        host.getProperties().put(TaskStatusDock.class, state);
        host.getChildren().addListener((ListChangeListener<Node>) change -> state.refresh());
        state.refresh();
    }

    private static final class State {
        private final StackPane host;
        private final Map<Node, Insets> originalMargins = new IdentityHashMap<>();
        private final Map<Node, InvalidationListener> statuses = new IdentityHashMap<>();

        private State(StackPane host) { this.host = host; }

        private void refresh() {
            statuses.entrySet().removeIf(entry -> {
                if (host.getChildren().contains(entry.getKey())) return false;
                var node = entry.getKey(); var listener = entry.getValue();
                node.layoutBoundsProperty().removeListener(listener);
                node.visibleProperty().removeListener(listener);
                node.managedProperty().removeListener(listener);
                return true;
            });
            for (Node node : host.getChildren()) {
                if (!node.getStyleClass().contains("task-status-pill") || statuses.containsKey(node)) continue;
                InvalidationListener listener = ignored -> reserve();
                node.layoutBoundsProperty().addListener(listener);
                node.visibleProperty().addListener(listener);
                node.managedProperty().addListener(listener);
                statuses.put(node, listener);
            }
            originalMargins.keySet().removeIf(node -> !host.getChildren().contains(node));
            reserve();
        }

        private void reserve() {
            double footer = 0;
            for (Node node : statuses.keySet()) if (node.isVisible() && node.isManaged()) {
                Insets margin = StackPane.getMargin(node);
                footer = Math.max(footer, node.getLayoutBounds().getHeight()
                        + (margin == null ? 0 : margin.getTop() + margin.getBottom()));
            }
            for (Node node : host.getChildren()) {
                if (statuses.containsKey(node) || !node.isManaged()) continue;
                if (footer > 0) {
                    if (!originalMargins.containsKey(node)) originalMargins.put(node, StackPane.getMargin(node));
                    Insets original = originalMargins.get(node);
                    Insets base = original == null ? Insets.EMPTY : original;
                    Insets next = new Insets(base.getTop(), base.getRight(), base.getBottom() + footer, base.getLeft());
                    if (!next.equals(StackPane.getMargin(node))) StackPane.setMargin(node, next);
                } else if (originalMargins.containsKey(node)) {
                    StackPane.setMargin(node, originalMargins.remove(node));
                }
            }
        }
    }
}
