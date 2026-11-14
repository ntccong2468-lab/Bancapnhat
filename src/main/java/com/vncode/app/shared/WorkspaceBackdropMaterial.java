package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.StyleOrigin;
import javafx.css.StyleableProperty;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Paint;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Shared, data-free app backdrop. Only wallpaper pixels are blurred; live content is never captured. */
public final class WorkspaceBackdropMaterial {
    private static final String SCOPE = WorkspaceBackdropMaterial.class.getName() + ".scope";
    private static final String ATTACH = WorkspaceBackdropMaterial.class.getName() + ".attach";
    private static final int WIDTH = 960, HEIGHT = 600;
    private static final Map<ThemeService.Theme, CompletableFuture<Wallpaper>> WALLPAPERS = new EnumMap<>(ThemeService.Theme.class);
    private static final Set<String> MATERIAL_PANELS = Set.of(
            "workspace-glass-header", "workspace-glass-sidebar", "glass-surface", "glass-overlay", "glass-toolbar", "glass-sidebar",
            "context-menu", "date-picker-popup", "tooltip", "workspace-sheet", "card", "surface", "copy-card", "news-card",
            "guide-section", "gtin-inventory-card", "supply-detail", "detail-drawer", "appearance-section", "workspace-overlay",
            "workspace-article", "finance-kpi-card", "task-status-pill", "info-banner", "workspace-batch-footer",
            "selection-action-bar", "products-bulk");

    private WorkspaceBackdropMaterial() { }

    /** Does not replace roots, children, effects, event handlers, or application background bindings. */
    public static void install(Parent root, ThemeService.Theme theme, boolean enabled) {
        if (root == null) return;
        if (!root.getProperties().containsKey(ATTACH)) {
            root.getProperties().put(ATTACH, Boolean.TRUE);
            root.sceneProperty().addListener((observable, previous, scene) -> {
                if (scene != null) install(scene.getRoot(), ThemeService.getCurrentTheme(), ThemeService.isGlassEnabled());
            });
        }
        Scene scene = root.getScene();
        if (scene == null) return;
        Scope scope = (Scope) scene.getProperties().get(SCOPE);
        if (scope == null && !Boolean.TRUE.equals(scene.getProperties().get(ThemeService.STARTUP_SCENE))) {
            scope = new Scope(scene); scene.getProperties().put(SCOPE, scope); scope.start();
        }
        if (scope != null) { scope.theme = theme; scope.enabled = enabled; scope.request(); }
    }

    private static synchronized CompletableFuture<Wallpaper> wallpaper(ThemeService.Theme theme) {
        // Exactly two themes, each retaining one original and one genuinely blurred image.
        return WALLPAPERS.computeIfAbsent(theme, selected -> CompletableFuture.supplyAsync(() -> generate(selected)));
    }

    private static final class Scope {
        private final Scene scene;
        private final Map<Region, Surface> surfaces = new IdentityHashMap<>();
        private final Set<Parent> observed = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<Runnable> unwatch = new ArrayList<>();
        private final InvalidationListener geometry = ignored -> request();
        private final javafx.event.EventHandler<WindowEvent> shown = ignored -> { rebuild(); request(); };
        private final javafx.event.EventHandler<WindowEvent> hidden = ignored -> { clearTree(); watchGround(null); };
        private Window window;
        private Parent groundRoot;
        private Window groundWindow;
        private Scene groundScene;
        private final javafx.beans.value.ChangeListener<Parent> groundChanged = (o, a, b) -> request();
        private ThemeService.Theme theme = ThemeService.Theme.DARK;
        private ThemeService.Theme waitingFor;
        private boolean enabled, pending, rebuildRequested;

        private Scope(Scene scene) { this.scene = scene; }

        private void start() {
            scene.widthProperty().addListener(geometry); scene.heightProperty().addListener(geometry);
            scene.rootProperty().addListener((observable, previous, root) -> { clearTree(); rebuild(); request(); });
            scene.windowProperty().addListener((observable, previous, next) -> watchWindow(next));
            watchWindow(scene.getWindow()); rebuild();
        }

        private void watchWindow(Window next) {
            if (window != null) {
                window.removeEventHandler(WindowEvent.WINDOW_SHOWN, shown); window.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
                window.xProperty().removeListener(geometry); window.yProperty().removeListener(geometry);
            }
            window = next;
            if (window != null) {
                window.addEventHandler(WindowEvent.WINDOW_SHOWN, shown); window.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
                window.xProperty().addListener(geometry); window.yProperty().addListener(geometry);
            }
            request();
        }

        private void request() {
            if (pending) return;
            pending = true;
            Platform.runLater(() -> { pending = false; refresh(); });
        }

        private void rebuild() { scan(scene.getRoot()); }

        private void scan(Node node) {
            if (!(node instanceof Parent parent) || !observed.add(parent)) return;
            if (node instanceof Region region && role(region, scene) != 0) {
                surfaces.computeIfAbsent(region, key -> new Surface(this, key));
            }
            InvalidationListener classes = ignored -> { rebuildRequested = true; request(); };
            parent.getStyleClass().addListener(classes);
            unwatch.add(() -> parent.getStyleClass().removeListener(classes));
            // Logical content only. Never walk virtualized rows, control skins or product thumbnails.
            if (parent instanceof ScrollPane scroll) {
                content(scroll.contentProperty());
                scroll.hvalueProperty().addListener(geometry); scroll.vvalueProperty().addListener(geometry);
                unwatch.add(() -> { scroll.hvalueProperty().removeListener(geometry); scroll.vvalueProperty().removeListener(geometry); });
                return;
            }
            if (parent instanceof TitledPane titled) {
                content(titled.contentProperty());
                InvalidationListener expansion = ignored -> { rebuildRequested = true; request(); };
                titled.expandedProperty().addListener(expansion); unwatch.add(() -> titled.expandedProperty().removeListener(expansion));
                return;
            }
            if (parent instanceof Accordion accordion) { nodes(accordion.getPanes()); return; }
            if (parent instanceof SplitPane split) { nodes(split.getItems()); return; }
            if (parent instanceof ToolBar toolbar) { nodes(toolbar.getItems()); return; }
            if (parent instanceof ButtonBar buttons) { nodes(buttons.getButtons()); return; }
            if (parent instanceof TabPane tabs) {
                InvalidationListener selection = ignored -> { rebuildRequested = true; request(); };
                tabs.selectionModelProperty().addListener(selection);
                unwatch.add(() -> tabs.selectionModelProperty().removeListener(selection));
                var model = tabs.getSelectionModel();
                if (model != null) {
                    model.selectedItemProperty().addListener(selection);
                    unwatch.add(() -> model.selectedItemProperty().removeListener(selection));
                    var selected = model.getSelectedItem();
                    if (selected != null) content(selected.contentProperty());
                }
                ListChangeListener<Tab> changed = ignored -> { rebuildRequested = true; request(); };
                tabs.getTabs().addListener(changed); unwatch.add(() -> tabs.getTabs().removeListener(changed));
                return;
            }
            if (parent instanceof DialogPane dialog) {
                content(dialog.contentProperty()); content(dialog.headerProperty()); content(dialog.expandableContentProperty());
                // DialogPane is a logical Pane, not a Control skin. Its public children
                // include the existing button bar and app-owned floating title chrome.
            }
            if (parent instanceof Control) return;
            parent.layoutBoundsProperty().addListener(geometry);
            parent.layoutXProperty().addListener(geometry); parent.layoutYProperty().addListener(geometry);
            unwatch.add(() -> {
                parent.layoutBoundsProperty().removeListener(geometry);
                parent.layoutXProperty().removeListener(geometry); parent.layoutYProperty().removeListener(geometry);
            });
            ListChangeListener<Node> children = change -> {
                while (change.next()) {
                    if (change.wasRemoved()) rebuildRequested = true;
                    if (change.wasAdded()) for (Node added : change.getAddedSubList()) scan(added);
                }
                request();
            };
            parent.getChildrenUnmodifiable().addListener(children);
            unwatch.add(() -> parent.getChildrenUnmodifiable().removeListener(children));
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) scan(child);
        }

        private void content(ObservableValue<? extends Node> property) {
            scan(property.getValue());
            javafx.beans.value.ChangeListener<Node> listener = (observable, previous, next) -> {
                if (previous != null) rebuildRequested = true;
                scan(next); request();
            };
            property.addListener(listener); unwatch.add(() -> property.removeListener(listener));
        }

        private void nodes(ObservableList<? extends Node> nodes) {
            for (Node node : List.copyOf(nodes)) scan(node);
            ListChangeListener<Node> changed = change -> {
                while (change.next()) {
                    if (change.wasRemoved()) rebuildRequested = true;
                    if (change.wasAdded()) for (Node next : change.getAddedSubList()) scan(next);
                }
                request();
            };
            nodes.addListener(changed); unwatch.add(() -> nodes.removeListener(changed));
        }

        private void clearTree() {
            for (Runnable cleanup : unwatch) cleanup.run(); unwatch.clear(); observed.clear();
            for (Surface surface : List.copyOf(surfaces.values())) surface.dispose(); surfaces.clear();
        }
        private void release() { for (Surface surface : List.copyOf(surfaces.values())) surface.release(); }

        private void watchGround(Parent next) {
            if (groundRoot == next) return;
            if (groundRoot != null) groundRoot.layoutBoundsProperty().removeListener(geometry);
            if (groundScene != null) {
                groundScene.widthProperty().removeListener(geometry); groundScene.heightProperty().removeListener(geometry);
                groundScene.rootProperty().removeListener(groundChanged);
            }
            if (groundWindow != null) { groundWindow.xProperty().removeListener(geometry); groundWindow.yProperty().removeListener(geometry); }
            groundRoot = next; groundScene = next == null ? null : next.getScene();
            groundWindow = groundScene == null ? null : groundScene.getWindow();
            if (groundRoot != null) groundRoot.layoutBoundsProperty().addListener(geometry);
            if (groundScene != null) {
                groundScene.widthProperty().addListener(geometry); groundScene.heightProperty().addListener(geometry);
                groundScene.rootProperty().addListener(groundChanged);
            }
            if (groundWindow != null) { groundWindow.xProperty().addListener(geometry); groundWindow.yProperty().addListener(geometry); }
        }

        private void refresh() {
            if (window == null || !window.isShowing()
                    || Boolean.TRUE.equals(scene.getProperties().get(ThemeService.STARTUP_SCENE))) { release(); watchGround(null); return; }
            if (rebuildRequested) { rebuildRequested = false; clearTree(); rebuild(); }
            if (!enabled) { release(); watchGround(null); return; }
            watchGround(ground(scene));
            var future = wallpaper(theme);
            if (!future.isDone()) {
                if (waitingFor != theme) {
                    waitingFor = theme; var weak = new WeakReference<>(this);
                    future.whenComplete((image, error) -> Platform.runLater(() -> {
                        Scope scope = weak.get(); if (scope != null) { scope.waitingFor = null; scope.request(); }
                    }));
                }
                return;
            }
            if (future.isCompletedExceptionally()) { release(); return; }
            Wallpaper images = future.join();
            for (var entry : List.copyOf(surfaces.entrySet())) {
                if (entry.getKey().getScene() != scene) { entry.getValue().dispose(); surfaces.remove(entry.getKey()); }
                else entry.getValue().refresh(images);
            }
        }
    }

    private static int role(Region region, Scene scene) {
        var classes = region.getStyleClass();
        if (classes.contains("workspace-shell")) return 1;
        // The Settings page is a transparent layout wrapper; only its sections float.
        if (classes.contains("appearance-pane")) return 0;
        if (region instanceof DialogPane || region instanceof ButtonBase || region instanceof MenuButton
                || region instanceof TextInputControl || region instanceof ComboBoxBase<?> || region instanceof ChoiceBox<?> || region instanceof Spinner<?>
                || region instanceof TableView<?> || region instanceof TreeTableView<?> || region instanceof ListView<?>
                || region instanceof TreeView<?> || region instanceof TitledPane || region instanceof ToolBar) return 2;
        for (String type : classes) if (MATERIAL_PANELS.contains(type)) return 2;
        return 0;
    }

    private static final class Surface {
        private final Scope scope;
        private final Region region;
        private Background base, owned;
        private StyleOrigin origin;
        private boolean writing, callerOwned;
        private final javafx.beans.value.ChangeListener<Background> backgroundChanged;
        private final javafx.beans.value.ChangeListener<Scene> sceneChanged;
        private Image image;
        private double x, y, width, height;

        private Surface(Scope scope, Region region) {
            this.scope = scope; this.region = region;
            StyleOrigin initial = property().getStyleOrigin();
            callerOwned = region.backgroundProperty().isBound() || initial == StyleOrigin.USER || initial == StyleOrigin.INLINE;
            backgroundChanged = (observable, previous, value) -> { if (!writing) scope.request(); };
            sceneChanged = (observable, previous, scene) -> { if (scene != scope.scene) release(); else scope.request(); };
            region.backgroundProperty().addListener(backgroundChanged);
            region.widthProperty().addListener(scope.geometry); region.heightProperty().addListener(scope.geometry);
            region.layoutXProperty().addListener(scope.geometry); region.layoutYProperty().addListener(scope.geometry);
            region.styleProperty().addListener(scope.geometry); region.getStyleClass().addListener(scope.geometry);
            region.sceneProperty().addListener(sceneChanged);
        }

        private void dispose() {
            release(); region.backgroundProperty().removeListener(backgroundChanged); region.sceneProperty().removeListener(sceneChanged);
            region.widthProperty().removeListener(scope.geometry); region.heightProperty().removeListener(scope.geometry);
            region.layoutXProperty().removeListener(scope.geometry); region.layoutYProperty().removeListener(scope.geometry);
            region.styleProperty().removeListener(scope.geometry); region.getStyleClass().removeListener(scope.geometry);
        }

        @SuppressWarnings("unchecked") private StyleableProperty<Background> property() {
            return (StyleableProperty<Background>) region.backgroundProperty();
        }

        private void refresh(Wallpaper wallpaper) {
            if (callerOwned) return;
            var property = property(); var current = region.getBackground();
            if (region.backgroundProperty().isBound()) { callerOwned = true; owned = null; return; }
            StyleOrigin selected = property.getStyleOrigin();
            // A caller can explicitly set the very same Background instance, changing only its origin.
            if (selected == StyleOrigin.USER || selected == StyleOrigin.INLINE) { callerOwned = true; owned = null; return; }
            if (current != owned) {
                base = current; origin = selected; owned = null;
            }
            int role = role(region, scope.scene);
            if (role == 0 || base == null || base.getFills().isEmpty() || !base.getImages().isEmpty()
                    || base.getFills().stream().noneMatch(fill -> visible(fill.getFill()))) { release(); return; }
            Parent ground = ground(scope.scene);
            double w = ground.getLayoutBounds().getWidth(), h = ground.getLayoutBounds().getHeight();
            if (w <= 0 || h <= 0 || region.getWidth() <= 0 || region.getHeight() <= 0) return;
            Point2D point;
            if (ground.getScene() == scope.scene) {
                point = ground.sceneToLocal(region.localToScene(0, 0));
            } else {
                Point2D screen = region.localToScreen(0, 0); point = screen == null ? null : ground.screenToLocal(screen);
            }
            if (point == null) return;
            double nextX = -point.getX(), nextY = -point.getY();
            Image nextImage = role == 1 ? wallpaper.original : wallpaper.blurred;
            if (owned != null && nextImage == image && x == nextX && y == nextY && width == w && height == h) return;
            image = nextImage; x = nextX; y = nextY; width = w; height = h;
            var fills = new ArrayList<>(base.getFills()); var shape = fills.getLast();
            var backdrop = new BackgroundFill(new ImagePattern(image, x, y, width, height, false), shape.getRadii(), shape.getInsets());
            // Preserve the original material tint as the final fill, above the blurred background.
            fills.add(role == 1 ? fills.size() : Math.max(0, fills.size() - 1), backdrop);
            owned = new Background(fills, base.getImages()); write(owned);
        }

        private void release() {
            if (owned == null) return;
            StyleOrigin selected = property().getStyleOrigin();
            if (region.backgroundProperty().isBound() || selected == StyleOrigin.USER || selected == StyleOrigin.INLINE) {
                callerOwned = true; owned = null; image = null; return;
            }
            if (region.getBackground() == owned) write(base);
            owned = null; image = null;
        }

        private void write(Background next) {
            writing = true;
            try { property().applyStyle(origin, next); }
            finally { writing = false; }
            if (region.backgroundProperty().isBound() || region.getBackground() != next) {
                callerOwned = true; owned = null; image = null;
            }
        }
    }

    private static boolean visible(Paint paint) {
        if (paint instanceof Color color) return color.getOpacity() > 0;
        if (paint instanceof LinearGradient gradient) return gradient.getStops().stream().anyMatch(stop -> stop.getColor().getOpacity() > 0);
        if (paint instanceof RadialGradient gradient) return gradient.getStops().stream().anyMatch(stop -> stop.getColor().getOpacity() > 0);
        return true;
    }

    private static Parent ground(Scene scene) {
        Window window = scene.getWindow(); Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (window != null && seen.add(window)) {
            Window owner = window instanceof PopupWindow popup ? popup.getOwnerWindow()
                    : window instanceof Stage stage ? stage.getOwner() : null;
            if (owner == null || owner.getScene() == null || !owner.isShowing()) break;
            window = owner;
        }
        return window != null && window.getScene() != null ? window.getScene().getRoot() : scene.getRoot();
    }

    private record Wallpaper(Image original, Image blurred) { }

    private static Wallpaper generate(ThemeService.Theme theme) {
        int[] pixels = new int[WIDTH * HEIGHT]; boolean light = theme == ThemeService.Theme.LIGHT;
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            double u = (double) x / (WIDTH - 1), v = (double) y / (HEIGHT - 1);
            double field = Math.exp(-((u - .20) * (u - .20) / .42 + (v - .22) * (v - .22) / .38));
            double fold = v - (.77 - .54 * u + .065 * Math.sin(u * 7));
            double ribbon = Math.exp(-fold * fold / .0018);
            double shade = Math.exp(-((u - .85) * (u - .85) / .24 + (v - .76) * (v - .76) / .28));
            double r = light ? .80 + .14 * field + .09 * ribbon - .12 * shade : .070 + .045 * field + .12 * ribbon + .025 * shade;
            double g = light ? .86 + .10 * field + .07 * ribbon - .10 * shade : .090 + .085 * field + .17 * ribbon + .045 * shade;
            double b = light ? .94 + .055 * field + .035 * ribbon - .055 * shade : .135 + .14 * field + .23 * ribbon + .075 * shade;
            pixels[y * WIDTH + x] = 0xff000000 | channel(r) << 16 | channel(g) << 8 | channel(b);
        }
        Image original = image(pixels); int[] blurred = pixels;
        // Three separable box passes approximate a Gaussian kernel, applied to wallpaper pixels only.
        for (int pass = 0; pass < 3; pass++) blurred = blur(blur(blurred, true, 10), false, 10);
        return new Wallpaper(original, image(blurred));
    }

    private static int channel(double value) { return (int) Math.round(Math.clamp(value, 0, 1) * 255); }

    private static Image image(int[] pixels) {
        var image = new WritableImage(WIDTH, HEIGHT);
        image.getPixelWriter().setPixels(0, 0, WIDTH, HEIGHT, PixelFormat.getIntArgbPreInstance(), pixels, 0, WIDTH);
        return image;
    }

    private static int[] blur(int[] source, boolean horizontal, int radius) {
        int[] target = new int[source.length]; int length = horizontal ? WIDTH : HEIGHT, lines = horizontal ? HEIGHT : WIDTH;
        int kernel = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int red = 0, green = 0, blue = 0;
            for (int p = -radius; p <= radius; p++) {
                int pixel = source[index(horizontal, line, Math.clamp(p, 0, length - 1))];
                red += pixel >> 16 & 255; green += pixel >> 8 & 255; blue += pixel & 255;
            }
            for (int p = 0; p < length; p++) {
                target[index(horizontal, line, p)] = 0xff000000 | red / kernel << 16 | green / kernel << 8 | blue / kernel;
                int remove = source[index(horizontal, line, Math.clamp(p - radius, 0, length - 1))];
                int add = source[index(horizontal, line, Math.clamp(p + radius + 1, 0, length - 1))];
                red += (add >> 16 & 255) - (remove >> 16 & 255);
                green += (add >> 8 & 255) - (remove >> 8 & 255); blue += (add & 255) - (remove & 255);
            }
        }
        return target;
    }

    private static int index(boolean horizontal, int line, int point) { return horizontal ? line * WIDTH + point : point * WIDTH + line; }
}
