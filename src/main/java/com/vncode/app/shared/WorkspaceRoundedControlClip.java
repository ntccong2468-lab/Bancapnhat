package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

import java.util.*;

/** Contains virtualized skin paint within its control's public rounded surface. */
public final class WorkspaceRoundedControlClip {
    private static final String SCOPE = WorkspaceRoundedControlClip.class.getName() + ".scope";
    private static final String CALLER_OWNED = WorkspaceRoundedControlClip.class.getName() + ".caller-owned";

    private WorkspaceRoundedControlClip() { }

    /** Installs once per scene root. It never reads or changes skin children. */
    public static void install(Parent root) {
        if (root == null) return;
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> install(root)); return; }
        Parent anchor = root.getScene() == null ? root : root.getScene().getRoot();
        if (anchor != root) uninstall(root);
        Scope scope = (Scope) anchor.getProperties().get(SCOPE);
        if (scope == null) {
            scope = new Scope(anchor); anchor.getProperties().put(SCOPE, scope); scope.start();
        } else scope.refresh();
    }

    /** Releases only clips still owned by this helper; caller clips are retained. */
    public static void uninstall(Parent root) {
        if (root == null) return;
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> uninstall(root)); return; }
        if (root.getProperties().remove(SCOPE) instanceof Scope scope) scope.close();
    }

    private static final class Scope {
        private final Parent root;
        private final Map<Region, Clip> controls = new IdentityHashMap<>();
        private final List<Runnable> unwatchTree = new ArrayList<>();
        private final ChangeListener<Scene> sceneChanged = (o, a, b) -> watchScene(b);
        private final ChangeListener<Window> windowChanged = (o, a, b) -> watchWindow(b);
        private final ChangeListener<Boolean> showingChanged = (o, a, b) -> refresh();
        private Scene scene;
        private Window window;
        private boolean pending, closed;
        private long revision;

        private Scope(Parent root) { this.root = root; }
        private void start() { root.sceneProperty().addListener(sceneChanged); watchScene(root.getScene()); }
        private void watchScene(Scene next) {
            if (scene != null) scene.windowProperty().removeListener(windowChanged);
            scene = next;
            if (scene != null && scene.getRoot() != root) {
                Parent anchor = scene.getRoot();
                uninstall(root); install(anchor); return;
            }
            if (scene != null) scene.windowProperty().addListener(windowChanged);
            watchWindow(scene == null ? null : scene.getWindow());
        }
        private void watchWindow(Window next) {
            if (window != null) window.showingProperty().removeListener(showingChanged);
            window = next;
            if (window != null) window.showingProperty().addListener(showingChanged);
            refresh();
        }
        private boolean visible() { return !closed && scene != null && window != null && window.isShowing(); }
        private void request() {
            if (closed || pending) return;
            pending = true;
            Platform.runLater(() -> { pending = false; if (!closed) refresh(); });
        }
        private void refresh() {
            if (closed) return;
            long pass = ++revision;
            clearTree();
            if (!visible()) {
                for (Clip clip : List.copyOf(controls.values())) clip.close();
                controls.clear(); return;
            }
            Set<Region> found = Collections.newSetFromMap(new IdentityHashMap<>());
            scan(root, found);
            for (Region previous : List.copyOf(controls.keySet())) if (!found.contains(previous)) controls.remove(previous).close();
            for (Region control : found) {
                // Showing listeners can hide/reopen the window during a clip
                // assignment. A stale outer pass must not repopulate its tree.
                if (pass != revision || !visible()) return;
                controls.computeIfAbsent(control, node -> new Clip(this, node)).update();
            }
        }
        private void scan(Node node, Set<Region> found) {
            if (node == null) return;
            if (node instanceof TableView<?> || node instanceof TreeTableView<?> || node instanceof ListView<?> || node instanceof TreeView<?>) {
                found.add((Region) node); return;
            }
            if (node instanceof DialogPane dialog) {
                scan(dialog.getHeader(), found); scan(dialog.getContent(), found);
                watchContent(dialog.contentProperty()); watchContent(dialog.headerProperty()); return;
            }
            if (node instanceof ScrollPane scroll) { scan(scroll.getContent(), found); watchContent(scroll.contentProperty()); return; }
            if (node instanceof TitledPane titled) { scan(titled.getContent(), found); watchContent(titled.contentProperty()); return; }
            if (node instanceof TabPane tabs) {
                for (Tab tab : tabs.getTabs()) { scan(tab.getContent(), found); watchContent(tab.contentProperty()); }
                ListChangeListener<Tab> listener = ignored -> request(); tabs.getTabs().addListener(listener);
                unwatchTree.add(() -> tabs.getTabs().removeListener(listener)); return;
            }
            if (node instanceof SplitPane split) {
                for (Node item : split.getItems()) scan(item, found);
                ListChangeListener<Node> listener = ignored -> request(); split.getItems().addListener(listener);
                unwatchTree.add(() -> split.getItems().removeListener(listener)); return;
            }
            if (node instanceof Accordion accordion) {
                for (TitledPane pane : accordion.getPanes()) scan(pane, found);
                ListChangeListener<TitledPane> listener = ignored -> request(); accordion.getPanes().addListener(listener);
                unwatchTree.add(() -> accordion.getPanes().removeListener(listener)); return;
            }
            if (node instanceof ToolBar toolbar) {
                for (Node item : toolbar.getItems()) scan(item, found);
                ListChangeListener<Node> listener = ignored -> request(); toolbar.getItems().addListener(listener);
                unwatchTree.add(() -> toolbar.getItems().removeListener(listener)); return;
            }
            if (!(node instanceof Parent parent) || node instanceof Control) return;
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) scan(child, found);
            ListChangeListener<Node> listener = ignored -> request(); parent.getChildrenUnmodifiable().addListener(listener);
            unwatchTree.add(() -> parent.getChildrenUnmodifiable().removeListener(listener));
        }
        private void watchContent(javafx.beans.value.ObservableValue<Node> property) {
            ChangeListener<Node> listener = (o, a, b) -> request(); property.addListener(listener);
            unwatchTree.add(() -> property.removeListener(listener));
        }
        private void clearTree() { for (Runnable remove : unwatchTree) remove.run(); unwatchTree.clear(); }
        private void close() {
            if (closed) return;
            closed = true; clearTree();
            for (Clip clip : List.copyOf(controls.values())) clip.close(); controls.clear();
            root.sceneProperty().removeListener(sceneChanged);
            if (scene != null) scene.windowProperty().removeListener(windowChanged);
            if (window != null) window.showingProperty().removeListener(showingChanged);
            scene = null; window = null;
        }
    }

    private static final class Clip {
        private final Scope scope;
        private final Region control;
        private final Rectangle shape = new Rectangle();
        private int writing;
        private long writeGeneration;
        private boolean callerOwned, closed;
        private final InvalidationListener changed = ignored -> update();
        private final ChangeListener<Scene> sceneChanged = (o, a, b) -> update();
        private final ChangeListener<Node> clipChanged = (o, a, b) -> { if (writing == 0 && b != shape) takeOver(); };

        private Clip(Scope scope, Region control) {
            this.scope = scope; this.control = control;
            callerOwned = Boolean.TRUE.equals(control.getProperties().get(CALLER_OWNED))
                    || control.clipProperty().isBound() || control.getClip() != null;
            if (callerOwned) control.getProperties().put(CALLER_OWNED, Boolean.TRUE);
            shape.setMouseTransparent(true);
            control.widthProperty().addListener(changed); control.heightProperty().addListener(changed);
            control.backgroundProperty().addListener(changed); control.borderProperty().addListener(changed);
            control.sceneProperty().addListener(sceneChanged); control.clipProperty().addListener(clipChanged);
        }
        private void takeOver() {
            callerOwned = true; control.getProperties().put(CALLER_OWNED, Boolean.TRUE);
        }
        private void update() {
            if (closed || callerOwned) return;
            if (control.clipProperty().isBound() || control.getClip() != null && control.getClip() != shape) { takeOver(); return; }
            if (!scope.visible() || control.getScene() != scope.scene) { release(); return; }
            double width = control.getWidth(), height = control.getHeight();
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) { release(); return; }
            double[] radius = radii(control, width, height);
            if (radius == null || radius[0] <= 0 || radius[1] <= 0) { release(); return; }
            shape.setWidth(width); shape.setHeight(height);
            shape.setArcWidth(Math.min(width, radius[0] * 2)); shape.setArcHeight(Math.min(height, radius[1] * 2));
            if (control.getClip() == null) write(shape);
        }
        private void release() {
            if (!callerOwned && !control.clipProperty().isBound() && control.getClip() == shape) write(null);
        }
        private void write(Node value) {
            long generation = ++writeGeneration;
            writing++;
            try { control.setClip(value); }
            finally { writing--; }
            // A caller listener can replace/clear the clip during setClip, even
            // without changing its final value again after this write returns.
            if (!closed && generation == writeGeneration
                    && (control.clipProperty().isBound() || control.getClip() != value)) takeOver();
        }
        private void close() {
            if (closed) return;
            closed = true; release();
            control.widthProperty().removeListener(changed); control.heightProperty().removeListener(changed);
            control.backgroundProperty().removeListener(changed); control.borderProperty().removeListener(changed);
            control.sceneProperty().removeListener(sceneChanged); control.clipProperty().removeListener(clipChanged);
        }
    }

    private static double[] radii(Region control, double width, double height) {
        double[] result = {0, 0};
        if (control.getBackground() != null) for (var fill : control.getBackground().getFills()) if (!merge(result, fill.getRadii(), width, height)) return null;
        if (control.getBorder() != null) for (var stroke : control.getBorder().getStrokes()) if (!merge(result, stroke.getRadii(), width, height)) return null;
        return result;
    }
    /** The shared table/list surface uses uniform corners; asymmetric caller shapes are left alone. */
    private static boolean merge(double[] result, CornerRadii r, double width, double height) {
        double left = resolve(r.getTopLeftHorizontalRadius(), r.isTopLeftHorizontalRadiusAsPercentage(), width);
        double top = resolve(r.getTopLeftVerticalRadius(), r.isTopLeftVerticalRadiusAsPercentage(), height);
        if (left != resolve(r.getTopRightHorizontalRadius(), r.isTopRightHorizontalRadiusAsPercentage(), width)
                || left != resolve(r.getBottomLeftHorizontalRadius(), r.isBottomLeftHorizontalRadiusAsPercentage(), width)
                || left != resolve(r.getBottomRightHorizontalRadius(), r.isBottomRightHorizontalRadiusAsPercentage(), width)
                || top != resolve(r.getTopRightVerticalRadius(), r.isTopRightVerticalRadiusAsPercentage(), height)
                || top != resolve(r.getBottomLeftVerticalRadius(), r.isBottomLeftVerticalRadiusAsPercentage(), height)
                || top != resolve(r.getBottomRightVerticalRadius(), r.isBottomRightVerticalRadiusAsPercentage(), height)) return false;
        result[0] = Math.max(result[0], left); result[1] = Math.max(result[1], top);
        return true;
    }
    private static double resolve(double radius, boolean percent, double dimension) { return percent ? radius * dimension : radius; }
}
