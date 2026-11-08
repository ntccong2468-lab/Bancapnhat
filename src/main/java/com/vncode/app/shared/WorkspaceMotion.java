package com.vncode.app.shared;

import javafx.animation.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.transform.Scale;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Bounded, interruptible desktop motion. Never snapshots a table or delays business work. */
public final class WorkspaceMotion {
    private static final String ACTIVE = WorkspaceMotion.class.getName() + ".active";
    private static final String INSTALLED = WorkspaceMotion.class.getName() + ".installed";
    private static final String WINDOW_BINDING = WorkspaceMotion.class.getName() + ".window";
    private static final String TAB_BOUNDARY = WorkspaceMotion.class.getName() + ".tabs";
    private static final Runnable NOTHING = () -> { };
    private static final Set<Node> ACTIVE_NODES = Collections.newSetFromMap(new WeakHashMap<>());

    private WorkspaceMotion() { }

    public static boolean isReduced() { return ThemeService.isReducedMotion(); }
    public static void setReduced(boolean value) { ThemeService.setReducedMotion(value); }

    /** Cancelling restores the exact pre-animation transforms and removes local listeners. */
    public static void stop(Node node) {
        if (node != null && node.getProperties().get(ACTIVE) instanceof ActiveMotion active) active.finish(false);
    }

    /** Commits final state immediately when motion is disabled. FX thread only. */
    public static void cancelAll() {
        for (Node node : List.copyOf(ACTIVE_NODES)) {
            if (node.getProperties().get(ACTIVE) instanceof ActiveMotion active) active.finish(true);
        }
    }

    public static boolean isAnimating(Node node) {
        return node != null && node.getProperties().containsKey(ACTIVE);
    }

    public static void navigation(Node node, NavigationContext context) {
        Objects.requireNonNull(context);
        install(node);
        switch (context) {
            case FORWARD -> enter(node, WorkspaceMotionTokens.HIERARCHY, WorkspaceMotionTokens.FORWARD_X, 0, .78, NOTHING);
            case BACK -> enter(node, WorkspaceMotionTokens.HIERARCHY, WorkspaceMotionTokens.BACK_X, 0, .78, NOTHING);
            case TAB -> enter(node, WorkspaceMotionTokens.TAB, 0, 0, .74, NOTHING);
            case OVERLAY -> scaled(node, WorkspaceMotionTokens.MODAL_OPEN, .96, null, NOTHING);
            case SIBLING -> enter(node, WorkspaceMotionTokens.SIBLING, 0, WorkspaceMotionTokens.SIBLING_Y, .82, NOTHING);
        }
    }

    public static void drawer(Node node, boolean opening, Runnable finished) {
        if (opening) enter(node, WorkspaceMotionTokens.DRAWER_OPEN, WorkspaceMotionTokens.DRAWER_X, 0, .78, finished);
        else animate(node, WorkspaceMotionTokens.DRAWER_CLOSE, 0, 0, 1, WorkspaceMotionTokens.DRAWER_X, 0, 0, null, finished);
    }

    public static void selection(Node caption) {
        enter(caption, WorkspaceMotionTokens.TAB, 0, 0, .74, NOTHING);
    }

    public static void savedDraft(Node bar) {
        enter(bar, WorkspaceMotionTokens.DRAFT, 0, 8, .78, NOTHING);
    }

    private static void enter(Node node, Duration duration, double x, double y, double opacity, Runnable finished) {
        animate(node, duration, x, y, opacity, 0, 0, 1, null, finished);
    }

    private static void scaled(Node node, Duration duration, double fromScale, Point2D pivot, Runnable finished) {
        var bounds = node.getLayoutBounds();
        Point2D origin = pivot == null ? new Point2D(bounds.getCenterX(), bounds.getCenterY()) : pivot;
        var scale = new Scale(fromScale, fromScale, origin.getX(), origin.getY());
        animate(node, duration, 0, 0, .8, 0, 0, 1, scale, finished);
    }

    private static void animate(Node node, Duration duration, double fromX, double fromY, double fromOpacity,
                                double toX, double toY, double toOpacity, Scale scale, Runnable finished) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(finished);
        stop(node);
        // Respect transforms owned by the application and leave bound properties untouched.
        if (node.opacityProperty().isBound() || node.translateXProperty().isBound() || node.translateYProperty().isBound()) {
            finished.run();
            return;
        }
        boolean reduced = isReduced();
        var active = new ActiveMotion(node, finished, reduced ? null : scale);
        node.getProperties().put(ACTIVE, active);
        ACTIVE_NODES.add(node);
        if (reduced) {
            fromX = fromY = toX = toY = 0;
            fromOpacity = .85;
            // Retain state feedback without a spatial exit or scale.
            toOpacity = 1;
            duration = WorkspaceMotionTokens.REDUCED;
        }
        node.setTranslateX(active.x + fromX);
        node.setTranslateY(active.y + fromY);
        node.setOpacity(active.opacity * fromOpacity);
        if (active.scale != null) node.getTransforms().add(active.scale);
        var frame = new KeyFrame(duration,
                new KeyValue(node.translateXProperty(), active.x + toX, WorkspaceMotionTokens.ARRIVE),
                new KeyValue(node.translateYProperty(), active.y + toY, WorkspaceMotionTokens.ARRIVE),
                new KeyValue(node.opacityProperty(), active.opacity * toOpacity, WorkspaceMotionTokens.ARRIVE));
        var timeline = new Timeline(frame);
        if (active.scale != null) timeline.getKeyFrames().add(new KeyFrame(duration,
                new KeyValue(active.scale.xProperty(), 1, WorkspaceMotionTokens.ARRIVE),
                new KeyValue(active.scale.yProperty(), 1, WorkspaceMotionTokens.ARRIVE)));
        active.animation = timeline;
        timeline.setOnFinished(event -> active.finish(true));
        active.watch();
        timeline.play();
    }

    /** Installs only tab listeners on logical content, skipping virtualized list/table subtrees. */
    public static void install(Node root) {
        if (root == null) return;
        if (root instanceof TabPane tabs) {
            installTabs(tabs);
            for (var tab : tabs.getTabs()) install(tab.getContent());
            return;
        }
        if (root instanceof TableView<?> || root instanceof TreeTableView<?> || root instanceof ListView<?> || root instanceof TreeView<?>) return;
        // Logical children are available before skins/CSS; inspecting skins would miss hidden tabs.
        if (root instanceof ScrollPane scroll) { install(scroll.getContent()); return; }
        if (root instanceof TitledPane titled) { install(titled.getContent()); return; }
        if (root instanceof Accordion accordion) { for (var pane : accordion.getPanes()) install(pane); return; }
        if (root instanceof SplitPane split) { for (Node child : split.getItems()) install(child); return; }
        if (root instanceof ToolBar toolbar) { for (Node child : toolbar.getItems()) install(child); return; }
        if (root instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) install(child);
    }

    /** ThemeService calls this for normal, dialog and popup scenes. All observation is scene-local. */
    public static void install(Scene scene) {
        if (scene == null || scene.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        installSceneRoot(scene);
        scene.rootProperty().addListener((observable, previous, root) -> {
            stop(previous);
            installSceneRoot(scene);
        });
        scene.windowProperty().addListener((observable, previous, window) -> installSceneRoot(scene));
    }

    public static void install(DialogPane pane) {
        if (pane == null) return;
        install((Node) pane);
        bindWindow(pane, false);
    }

    private static void installSceneRoot(Scene scene) {
        var root = scene.getRoot();
        install((Node) root);
        if (root instanceof DialogPane pane) install(pane);
        else if (scene.getWindow() instanceof PopupWindow) bindWindow(root, true);
    }

    private static void installTabs(TabPane tabs) {
        if (tabs.getProperties().containsKey(TAB_BOUNDARY)) return;
        var boundary = new MotionBoundary();
        tabs.getProperties().put(TAB_BOUNDARY, boundary);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (previous != null) stop(previous.getContent());
            if (selected != null && selected.getContent() != null) boundary.show(selected.getContent(), NavigationContext.TAB);
        });
        tabs.getTabs().addListener((ListChangeListener<Tab>) changes -> {
            while (changes.next()) {
                for (var tab : changes.getRemoved()) stop(tab.getContent());
                if (changes.wasAdded()) for (var tab : changes.getAddedSubList()) install(tab.getContent());
            }
        });
        tabs.sceneProperty().addListener((observable, previous, scene) -> { if (scene == null) boundary.cancel(); });
    }

    private static void bindWindow(Node node, boolean popup) {
        if (node.getProperties().containsKey(WINDOW_BINDING)) return;
        var binding = new WindowMotionBinding(node, popup);
        node.getProperties().put(WINDOW_BINDING, binding);
        binding.watchScene(node.getScene());
        node.sceneProperty().addListener((observable, previous, scene) -> binding.watchScene(scene));
    }

    private static final class WindowMotionBinding {
        private final Node node;
        private final boolean popup;
        private Scene scene;
        private Window window;
        private final ChangeListener<Window> windowChanged = (observable, previous, next) -> watchWindow(next);
        private final EventHandler<WindowEvent> shown = event -> open();
        private final EventHandler<WindowEvent> hidden;

        private WindowMotionBinding(Node node, boolean popup) {
            this.node = node; this.popup = popup;
            hidden = event -> stop(node);
        }

        private void watchScene(Scene next) {
            if (scene != null) scene.windowProperty().removeListener(windowChanged);
            scene = next;
            if (scene != null) scene.windowProperty().addListener(windowChanged);
            watchWindow(scene == null ? null : scene.getWindow());
        }

        private void watchWindow(Window next) {
            if (window == next) return;
            if (window != null) {
                window.removeEventHandler(WindowEvent.WINDOW_SHOWN, shown);
                window.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
            }
            stop(node);
            window = next;
            if (window != null) {
                window.addEventHandler(WindowEvent.WINDOW_SHOWN, shown);
                window.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
                if (window.isShowing()) open();
            }
        }

        private void open() {
            Point2D pivot = null;
            if (popup && window instanceof PopupWindow popupWindow) {
                var bounds = node.getLayoutBounds();
                var anchor = node.sceneToLocal(popupWindow.getAnchorX() - window.getX(), popupWindow.getAnchorY() - window.getY());
                pivot = new Point2D(Math.clamp(anchor.getX(), bounds.getMinX(), bounds.getMaxX()),
                        Math.clamp(anchor.getY(), bounds.getMinY(), bounds.getMaxY()));
            }
            scaled(node, popup ? WorkspaceMotionTokens.POPOVER_OPEN : WorkspaceMotionTokens.MODAL_OPEN, popup ? .97 : .96, pivot, NOTHING);
        }
    }

    private static final class ActiveMotion {
        private final Node node;
        private final Runnable finished;
        private final Scale scale;
        private final double x, y, opacity;
        private Animation animation;
        private boolean done;
        private final ChangeListener<Scene> detached;
        private final ChangeListener<Parent> removed;

        private ActiveMotion(Node node, Runnable finished, Scale scale) {
            this.node = node; this.finished = finished; this.scale = scale;
            x = node.getTranslateX(); y = node.getTranslateY(); opacity = node.getOpacity();
            detached = (observable, previous, next) -> { if (previous != null && next == null) finish(false); };
            removed = (observable, previous, next) -> { if (previous != null && next == null) finish(false); };
        }

        private void watch() { node.sceneProperty().addListener(detached); node.parentProperty().addListener(removed); }

        private void finish(boolean complete) {
            if (done) return;
            done = true;
            if (animation != null) { animation.setOnFinished(null); animation.stop(); }
            node.sceneProperty().removeListener(detached);
            node.parentProperty().removeListener(removed);
            if (node.getProperties().get(ACTIVE) == this) node.getProperties().remove(ACTIVE);
            ACTIVE_NODES.remove(node);
            node.setTranslateX(x); node.setTranslateY(y); node.setOpacity(opacity);
            if (scale != null) node.getTransforms().remove(scale);
            if (complete) finished.run();
        }
    }
}
