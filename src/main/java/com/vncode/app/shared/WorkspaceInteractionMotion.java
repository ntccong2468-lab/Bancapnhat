package com.vncode.app.shared;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Cell;
import javafx.scene.control.DialogPane;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/** Scene-wide feedback for current and future controls. Never consumes or re-fires an action. */
public final class WorkspaceInteractionMotion {
    private static final String SCENE_BINDING = WorkspaceInteractionMotion.class.getName() + ".scene";
    private static final String NODE_BINDING = WorkspaceInteractionMotion.class.getName() + ".node";
    private static final String WINDOW_BINDING = WorkspaceInteractionMotion.class.getName() + ".window";
    private static final String PRESS = WorkspaceInteractionMotion.class.getName() + ".press";
    private static final String GHOST = WorkspaceInteractionMotion.class.getName() + ".exit";
    private static final String OWNER_EPOCH = WorkspaceInteractionMotion.class.getName() + ".ownerEpoch";
    private static final String SENSITIVE = "vncode.motion.sensitive";
    private static final Set<Node> PRESSED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ExitGhost> GHOSTS = new LinkedHashSet<>();
    private static final int MAX_GHOST_PIXELS = 1_200_000;
    private static final int MAX_INSPECTED_NODES = 12_000;
    private static final Interpolator ARRIVE = Interpolator.SPLINE(.16, 1, .3, 1);

    private WorkspaceInteractionMotion() { }

    /** One filter per scene covers controls created later, including virtualized row actions. */
    public static void install(Scene scene) {
        if (scene == null || Boolean.TRUE.equals(scene.getProperties().get(ThemeService.STARTUP_SCENE))
                || scene.getProperties().containsKey(SCENE_BINDING)) return;
        var binding = new SceneBinding(scene);
        scene.getProperties().put(SCENE_BINDING, binding);
        binding.start();
    }

    public static void install(DialogPane pane) { installWindow(pane, false); }

    /** DialogPane can be themed before it has a Scene; installation follows that attachment. */
    public static void installWindow(Node node, boolean popup) {
        if (node == null) return;
        if (node.getProperties().putIfAbsent(NODE_BINDING, Boolean.TRUE) == null) {
            node.sceneProperty().addListener((observable, previous, scene) -> install(scene));
        }
        install(node.getScene());
        if (node.getScene() != null && node.getScene().getWindow() != null
                && !Boolean.TRUE.equals(node.getScene().getProperties().get(ThemeService.STARTUP_SCENE))) {
            observe(node.getScene().getWindow(), popup);
        }
    }

    /** Accessibility changes immediately remove our transforms and transient visual copies. */
    public static void cancelAll() {
        for (var node : List.copyOf(PRESSED)) finishPress(node);
        for (var ghost : List.copyOf(GHOSTS)) ghost.dispose();
    }

    /** A route owner changes its data scope without keeping old report pixels over the next shop. */
    public static void invalidateOwner(Scene scene) {
        if (scene == null) return;
        scene.getProperties().put(OWNER_EPOCH, epoch(scene) + 1);
        cancelFeedback(scene);
        if (scene.getProperties().get(SCENE_BINDING) instanceof SceneBinding binding) binding.lastPoint = null;
        for (var ghost : List.copyOf(GHOSTS)) if (ghost.contextScene == scene) ghost.dispose();
    }

    private static long epoch(Scene scene) {
        return scene.getProperties().get(OWNER_EPOCH) instanceof Long value ? value : 0;
    }

    private static void cancelFeedback(Scene scene) {
        if (scene == null) return;
        for (var node : List.copyOf(PRESSED)) if (node.getScene() == scene) finishPress(node);
    }

    private static void finishPress(Node node) {
        if (node.getProperties().get(PRESS) instanceof PressFeedback feedback) feedback.finish();
    }

    private static void observe(Window window, boolean popup) {
        if (window == null) return;
        Object existing = window.getProperties().get(WINDOW_BINDING);
        if (existing instanceof WindowBinding binding) {
            binding.popup |= popup;
            return;
        }
        var binding = new WindowBinding(window, popup || window instanceof PopupWindow);
        window.getProperties().put(WINDOW_BINDING, binding);
        binding.start();
    }

    private static Node target(Object target) {
        if (!(target instanceof Node current)) return null;
        // Text selection, scrolling, slider drags and signature/design canvases keep their geometry.
        for (int depth = 0; current != null && depth < 64; depth++, current = current.getParent()) {
            if (current instanceof TextInputControl || current instanceof Slider || current instanceof ScrollBar
                    || current instanceof Canvas || current.getClass().getName().equals("javafx.scene.web.WebView")) return null;
            if (current.isDisabled() || !current.isVisible()) return null;
            if (current instanceof ButtonBase) return current;
            if (current instanceof Cell<?>) return current;
            var classes = current.getStyleClass();
            if (classes.contains("menu-item") || classes.contains("tab") || classes.contains("arrow-button")
                    || classes.contains("increment-arrow-button") || classes.contains("decrement-arrow-button")
                    || classes.contains("column-header")) return current;
            if (classes.contains("title")) {
                for (Parent parent = current.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof TitledPane) return current;
                }
            }
        }
        return null;
    }

    private static PressFeedback feedback(Node node) {
        if (node.getProperties().get(PRESS) instanceof PressFeedback feedback) return feedback;
        var feedback = new PressFeedback(node);
        node.getProperties().put(PRESS, feedback);
        PRESSED.add(node);
        return feedback;
    }

    private static final class SceneBinding {
        private final Scene scene;
        private Point2D lastPoint;
        private long lastPointAt;

        private SceneBinding(Scene scene) { this.scene = scene; }

        private void start() {
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                lastPoint = new Point2D(event.getScreenX(), event.getScreenY());
                lastPointAt = System.nanoTime();
                if (event.getButton() != MouseButton.PRIMARY || ThemeService.isReducedMotion()) return;
                var node = target(event.getTarget());
                if (node != null) {
                    if (node.getStyleClass().contains("column-header")) {
                        Point2D point = node.sceneToLocal(event.getSceneX(), event.getSceneY());
                        double width = node.getLayoutBounds().getWidth();
                        if (point.getX() < 5 || point.getX() > width - 5) return;
                    }
                    feedback(node).press();
                }
            });
            scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) return;
                for (var node : List.copyOf(PRESSED)) if (node.getScene() == scene
                        && node.getProperties().get(PRESS) instanceof PressFeedback feedback) feedback.release();
            });
            scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
                if (!event.isStillSincePress()) cancelFeedback(scene);
            });
            scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> cancelFeedback(scene));
            scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() != KeyCode.SPACE && event.getCode() != KeyCode.ENTER) return;
                if (ThemeService.isReducedMotion()) return;
                var node = target(event.getTarget());
                if (node != null) feedback(node).press();
            });
            scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
                if (event.getCode() != KeyCode.SPACE && event.getCode() != KeyCode.ENTER) return;
                for (var node : List.copyOf(PRESSED)) if (node.getScene() == scene
                        && node.getProperties().get(PRESS) instanceof PressFeedback feedback) feedback.release();
            });
            scene.addEventFilter(ActionEvent.ACTION, event -> {
                if (ThemeService.isReducedMotion()) return;
                var node = target(event.getTarget());
                if (node == null) node = target(event.getSource());
                if (node != null) feedback(node).activate();
            });
            scene.rootProperty().addListener((observable, previous, root) -> cancelFeedback(scene));
            scene.windowProperty().addListener((observable, previous, window) -> {
                cancelFeedback(scene);
                if (window != null) observe(window, window instanceof PopupWindow);
            });
            if (scene.getWindow() != null) observe(scene.getWindow(), scene.getWindow() instanceof PopupWindow);
        }

        private Point2D recentPoint() {
            return lastPoint != null && System.nanoTime() - lastPointAt < 1_500_000_000L ? lastPoint : null;
        }
    }

    /** Each state owns one additive transform, never the application's bound scale or opacity. */
    private static final class PressFeedback {
        private final Node node;
        private final Scale scale;
        private Animation animation;
        private boolean held, releasing, finished;
        private final ChangeListener<Scene> detached;
        private final ChangeListener<Boolean> disabled;
        private final ChangeListener<Object> itemChanged;
        private final ChangeListener<Number> indexChanged;

        private PressFeedback(Node node) {
            this.node = node;
            var bounds = node.getLayoutBounds();
            scale = new Scale(1, 1, bounds.getCenterX(), bounds.getCenterY());
            detached = (observable, previous, scene) -> { if (previous != null && scene == null) finish(); };
            disabled = (observable, previous, value) -> { if (value) finish(); };
            itemChanged = (observable, previous, value) -> finish();
            indexChanged = (observable, previous, value) -> finish();
            node.getTransforms().add(scale);
            node.sceneProperty().addListener(detached);
            node.disabledProperty().addListener(disabled);
            if (node instanceof Cell<?> cell) cell.itemProperty().addListener(itemChanged);
            if (node instanceof IndexedCell<?> cell) cell.indexProperty().addListener(indexChanged);
        }

        private void press() {
            if (finished || held) return;
            held = true; releasing = false;
            boolean broadSurface = node instanceof Cell<?> || node.getStyleClass().contains("column-header")
                    || node.getStyleClass().contains("title");
            animate(broadSurface ? .995 : .976, Duration.millis(80));
        }

        private void activate() {
            if (finished || releasing) return;
            if (!held) { scale.setX(.988); scale.setY(.988); }
            release();
        }

        private void release() {
            if (finished || releasing) return;
            held = false; releasing = true;
            if (animation != null) animation.stop();
            var timeline = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(scale.xProperty(), scale.getX()), new KeyValue(scale.yProperty(), scale.getY())),
                    new KeyFrame(Duration.millis(105), new KeyValue(scale.xProperty(), 1.004, ARRIVE), new KeyValue(scale.yProperty(), 1.004, ARRIVE)),
                    new KeyFrame(Duration.millis(180), new KeyValue(scale.xProperty(), 1, ARRIVE), new KeyValue(scale.yProperty(), 1, ARRIVE)));
            animation = timeline;
            timeline.setOnFinished(event -> finish());
            timeline.play();
        }

        private void animate(double value, Duration duration) {
            if (animation != null) animation.stop();
            var timeline = new Timeline(new KeyFrame(duration,
                    new KeyValue(scale.xProperty(), value, ARRIVE), new KeyValue(scale.yProperty(), value, ARRIVE)));
            animation = timeline;
            timeline.play();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            if (animation != null) { animation.setOnFinished(null); animation.stop(); }
            node.sceneProperty().removeListener(detached);
            node.disabledProperty().removeListener(disabled);
            if (node instanceof Cell<?> cell) cell.itemProperty().removeListener(itemChanged);
            if (node instanceof IndexedCell<?> cell) cell.indexProperty().removeListener(indexChanged);
            node.getTransforms().remove(scale);
            if (node.getProperties().get(PRESS) == this) node.getProperties().remove(PRESS);
            PRESSED.remove(node);
        }
    }

    /** An observer augments existing lifecycle handlers and never consumes/replays close events. */
    private static final class WindowBinding {
        private final Window window;
        private Scene scene;
        private Parent root;
        private boolean popup, opened, contextStampedForShowSession;
        private WeakReference<Scene> contextScene = new WeakReference<>(null);
        private WeakReference<Window> ghostOwner = new WeakReference<>(null);
        private long contextEpoch;
        private final ChangeListener<Parent> rootChanged = (observable, previous, next) -> watchRoot(next);

        private WindowBinding(Window window, boolean popup) { this.window = window; this.popup = popup; }

        private void start() {
            window.sceneProperty().addListener((observable, previous, next) -> watchScene(next));
            window.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> open());
            window.addEventHandler(WindowEvent.WINDOW_HIDING, event -> exit());
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
                opened = false;
                contextStampedForShowSession = false;
                contextScene.clear();
                ghostOwner.clear();
                contextEpoch = -1;
                WorkspaceMotion.stop(root);
                cancelFeedback(scene);
            });
            window.focusedProperty().addListener((observable, previous, focused) -> {
                if (!focused) cancelFeedback(scene);
            });
            watchScene(window.getScene());
        }

        private void watchScene(Scene next) {
            if (scene == next) return;
            if (scene != null) { scene.rootProperty().removeListener(rootChanged); cancelFeedback(scene); }
            scene = next;
            if (scene != null) { install(scene); scene.rootProperty().addListener(rootChanged); }
            watchRoot(scene == null ? null : scene.getRoot());
        }

        private void watchRoot(Parent next) {
            if (root == next) return;
            WorkspaceMotion.stop(root);
            root = next;
            opened = false;
            if (window.isShowing()) open();
        }

        private void open() {
            if (opened || root == null || scene == null
                    || Boolean.TRUE.equals(scene.getProperties().get(ThemeService.STARTUP_SCENE))) return;
            opened = true;
            // A skin, root or whole Scene refresh is still the same visible report
            // session. Only an actual hide/show can establish a new owner scope.
            if (!contextStampedForShowSession) {
                Window immediateOwner = owner(window);
                while (immediateOwner instanceof PopupWindow popupOwner) immediateOwner = popupOwner.getOwnerWindow();
                Window context = topOwner(immediateOwner);
                Scene ownerScene = context == null ? null : context.getScene();
                if (immediateOwner != null && immediateOwner.getProperties().get(WINDOW_BINDING) instanceof WindowBinding binding
                        && binding.contextScene.get() != null && !binding.currentContext()) ownerScene = null;
                contextScene = new WeakReference<>(ownerScene);
                ghostOwner = new WeakReference<>(immediateOwner);
                contextEpoch = ownerScene == null ? -1 : epoch(ownerScene);
                contextStampedForShowSession = true;
            }
            WorkspaceMotion.appearanceOpaque(root, popup, pivot());
        }

        private Point2D pivot() {
            if (root == null) return null;
            if (window instanceof PopupWindow popupWindow) {
                return localPivot(root, new Point2D(popupWindow.getAnchorX(), popupWindow.getAnchorY()));
            }
            var owner = owner(window);
            if (owner != null && owner.getScene() != null
                    && owner.getScene().getProperties().get(SCENE_BINDING) instanceof SceneBinding binding) {
                return localPivot(root, binding.recentPoint());
            }
            return null;
        }

        private void exit() {
            if (root != null && currentContext() && !ThemeService.isReducedMotion()) {
                ExitGhost.show(ghostOwner.get(), contextScene.get(), root, popup);
            }
            WorkspaceMotion.stop(root);
            cancelFeedback(scene);
        }

        private boolean currentContext() {
            Scene current = contextScene.get();
            return current != null && contextEpoch == epoch(current);
        }
    }

    private static Point2D localPivot(Node node, Point2D screen) {
        if (screen == null) return null;
        var point = node.screenToLocal(screen);
        if (point == null || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) return null;
        var bounds = node.getLayoutBounds();
        return new Point2D(Math.clamp(point.getX(), bounds.getMinX(), bounds.getMaxX()),
                Math.clamp(point.getY(), bounds.getMinY(), bounds.getMaxY()));
    }

    private static Window owner(Window window) {
        if (window instanceof PopupWindow popup) return popup.getOwnerWindow();
        if (window instanceof Stage stage && stage.getOwner() != null) return stage.getOwner();
        // Some legacy Alerts are unowned. A single app top-level is unambiguous;
        // never guess between several windows or attach to another popup's skin.
        Window result = null;
        for (var candidate : Window.getWindows()) if (candidate != window && candidate instanceof Stage stage
                && stage.getOwner() == null && candidate.isShowing() && candidate.getScene() != null
                && !Boolean.TRUE.equals(candidate.getScene().getProperties().get(ThemeService.STARTUP_SCENE))) {
            if (result != null) return null;
            result = candidate;
        }
        return result;
    }

    private static Window topOwner(Window window) {
        for (int depth = 0; window != null && depth < 32; depth++) {
            if (window instanceof PopupWindow popup) { window = popup.getOwnerWindow(); continue; }
            if (window instanceof Stage stage && stage.getOwner() != null) { window = stage.getOwner(); continue; }
            return window;
        }
        return null;
    }

    private static boolean sensitive(Node root) {
        var queue = new ArrayDeque<Node>(); queue.add(root);
        int visited = 0;
        while (!queue.isEmpty()) {
            var node = queue.removeFirst();
            if (++visited > MAX_INSPECTED_NODES || Boolean.TRUE.equals(node.getProperties().get(SENSITIVE))
                    || node instanceof PasswordField || node instanceof TextInputControl text && text.isEditable()
                    || node instanceof Canvas || node.getClass().getName().equals("javafx.scene.web.WebView")
                    || node.getClass().getName().equals("javafx.embed.swing.SwingNode")) return true;
            if (node instanceof Parent parent) queue.addAll(parent.getChildrenUnmodifiable());
        }
        return false;
    }

    /** A short-lived visual copy closes over the owner, while the real report has already hidden. */
    private static final class ExitGhost {
        private final Window owner;
        private final Scene contextScene;
        private final Pane host;
        private final StackPane view;
        private boolean disposed;
        private final EventHandler<WindowEvent> ownerHidden = event -> dispose();
        private final ChangeListener<Scene> detached = (observable, previous, scene) -> {
            if (previous != null && scene == null) dispose();
        };

        private ExitGhost(Window owner, Scene contextScene, Pane host, StackPane view) {
            this.owner = owner; this.contextScene = contextScene; this.host = host; this.view = view;
        }

        private static void show(Window owner, Scene contextScene, Parent source, boolean popup) {
            if (owner == null || !owner.isShowing() || owner.getScene() == null
                    || !(owner.getScene().getRoot() instanceof Pane host)) return;
            Bounds screen = source.localToScreen(source.getBoundsInLocal());
            if (screen == null || !finite(screen.getWidth()) || !finite(screen.getHeight())) return;
            Bounds local = host.screenToLocal(screen);
            if (local == null || !finite(local.getWidth()) || !finite(local.getHeight())) return;
            var content = new StackPane();
            boolean silhouette = sensitive(source);
            if (!silhouette) {
                try {
                    double pixels = source.getBoundsInParent().getWidth() * source.getBoundsInParent().getHeight();
                    if (!Double.isFinite(pixels) || pixels <= 0) silhouette = true;
                    else {
                        double factor = Math.min(1, Math.sqrt(MAX_GHOST_PIXELS / pixels));
                        var parameters = new SnapshotParameters(); parameters.setFill(Color.TRANSPARENT);
                        parameters.setTransform(new Scale(factor, factor));
                        var image = new ImageView(source.snapshot(parameters, null));
                        image.setFitWidth(local.getWidth()); image.setFitHeight(local.getHeight());
                        image.setMouseTransparent(true); image.setPreserveRatio(false);
                        content.getChildren().add(image);
                    }
                } catch (RuntimeException unavailable) { silhouette = true; }
            }
            if (silhouette) {
                boolean dark = source.getStyleClass().contains("theme-dark") || host.getStyleClass().contains("theme-dark");
                var shape = new Rectangle(local.getWidth(), local.getHeight(), dark ? Color.rgb(42, 42, 47, .96) : Color.rgb(255, 255, 255, .96));
                shape.setArcWidth(28); shape.setArcHeight(28);
                shape.setEffect(new DropShadow(18, 0, 4, Color.rgb(0, 0, 0, .16)));
                content.getChildren().setAll(shape);
            }
            content.getProperties().put(GHOST, silhouette ? "SILHOUETTE" : "SNAPSHOT");
            content.setManaged(false); content.setMouseTransparent(true); content.setFocusTraversable(false);
            content.resize(local.getWidth(), local.getHeight());
            content.setPrefSize(local.getWidth(), local.getHeight());
            content.relocate(local.getMinX(), local.getMinY());
            // A rapid sequence of tooltip/menu changes cannot accumulate retained pixels.
            long count = GHOSTS.stream().filter(ghost -> ghost.owner == owner).count();
            if (count >= 2) for (var ghost : List.copyOf(GHOSTS)) if (ghost.owner == owner) { ghost.dispose(); break; }
            var ghost = new ExitGhost(owner, contextScene, host, content);
            GHOSTS.add(ghost);
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, ghost.ownerHidden);
            content.sceneProperty().addListener(ghost.detached);
            host.getChildren().add(content);
            WorkspaceMotion.disappearance(content, popup, null, ghost::dispose);
        }

        private static boolean finite(double value) { return Double.isFinite(value) && value > 0 && value < 16_384; }

        private void dispose() {
            if (disposed) return;
            disposed = true;
            owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, ownerHidden);
            view.sceneProperty().removeListener(detached);
            WorkspaceMotion.stop(view);
            host.getChildren().remove(view);
            view.getChildren().clear();
            GHOSTS.remove(this);
        }
    }

    static long exitGhostCount(Scene scene) { return GHOSTS.stream().filter(ghost -> ghost.host.getScene() == scene).count(); }
    static boolean hasSnapshotGhost(Scene scene) { return GHOSTS.stream().anyMatch(ghost -> ghost.host.getScene() == scene && "SNAPSHOT".equals(ghost.view.getProperties().get(GHOST))); }
    static boolean hasFeedback(Node node) { return node.getProperties().get(PRESS) instanceof PressFeedback; }
}
