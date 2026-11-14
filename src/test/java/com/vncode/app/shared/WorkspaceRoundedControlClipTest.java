package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Native skin containment, ownership and input-state checks with disposable synthetic data. */
class WorkspaceRoundedControlClipTest {
    @TempDir Path profile;
    private String previous;
    private static final Color GROUND = Color.rgb(101, 37, 89);

    @BeforeAll static void toolkit() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
        fx(() -> { Platform.setImplicitExit(false); return null; });
    }
    @BeforeEach void profile() {
        previous = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) if (window instanceof Stage stage) {
                if (stage.getScene() != null) WorkspaceRoundedControlClip.uninstall(stage.getScene().getRoot());
                stage.close();
            }
            ThemeService.setReducedMotion(false); return null;
        });
        if (previous == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previous);
    }

    @Test void populatedNativeSkinsStayInsideBottomCornersAfterScrollingAndResize() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);
            var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for (var theme : ThemeService.Theme.values()) for (int kind = 0; kind < 4; kind++) for (int count : new int[]{0, 2, 48}) {
                ThemeService.setCurrentTheme(theme);
                Control control = control(kind, count); control.setPrefSize(320, 180); control.relocate(24, 24);
                var root = new Pane(control); root.setStyle("-fx-background-color: rgb(101,37,89);");
                var stage = stage(root, 440, 300);
                try {
                    root.applyCss(); root.layout();
                    var effect = control.getEffect(); double padding = control.getPadding().getTop();
                    WorkspaceRoundedControlClip.install(root);
                    control.requestFocus(); select(control, count > 1 ? 1 : -1);
                    if (count == 48) scroll(control, 47);
                    root.layout();
                    String context = theme + " " + control.getClass().getSimpleName() + " rows=" + count;
                    WritableImage image = root.snapshot(null, null);
                    Color left = pixel(root, image, control, 2, control.getHeight() - 3);
                    Color right = pixel(root, image, control, control.getWidth() - 3, control.getHeight() - 3);
                    checks.add(() -> assertEquals(GROUND, left, "Native skin must reveal the ground outside the bottom-left arc: " + context));
                    checks.add(() -> assertEquals(GROUND, right, "Native skin must reveal the ground outside the bottom-right arc: " + context));
                    assertNotEquals(GROUND, pixel(root, image, control, control.getWidth() / 2, control.getHeight() - 12), "Working body remains painted");
                    capture(root, "corners-" + theme.getKey() + "-" + kind + "-" + count + ".png");
                    assertSame(effect, control.getEffect(), "Containment does not alter material effects");
                    assertEquals(padding, control.getPadding().getTop(), "Containment does not alter layout");
                    assertSame(control, stage.getScene().getFocusOwner());
                    if (count > 1) assertEquals(1, selected(control), "Selection survives clipping and scrolling");
                    if (count == 48) {
                        var bar = control.lookupAll(".scroll-bar").stream().filter(n -> n instanceof ScrollBar scroll
                                && scroll.getOrientation() == Orientation.VERTICAL && scroll.isVisible()).map(n -> (ScrollBar) n).findFirst().orElseThrow();
                        assertTrue(bar.getValue() > bar.getMin(), "Public scroll-to still moves the native viewport");
                        var center = control.sceneToLocal(bar.localToScene(bar.getWidth() / 2, bar.getHeight() / 2));
                        assertTrue(control.contains(center), "The scrollbar center remains inside the control's hit area");
                        if (control.getClip() != null) assertTrue(control.getClip().contains(center), "The scrollbar center remains inside the rounded hit area");
                    }
                    control.setPrefSize(360, 220); root.requestLayout(); root.layout();
                    image = root.snapshot(null, null);
                    Color resized = pixel(root, image, control, control.getWidth() - 3, control.getHeight() - 3);
                    checks.add(() -> assertEquals(GROUND, resized, "Rounded containment follows resize: " + context));
                } finally { WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            }
            assertAll(checks); return null;
        });
    }

    @Test void callerClipsAndBindingsIncludingSameOwnedInstanceAreNeverReplacedOrCleared() throws Exception {
        fx(() -> {
            Control existing = control(0, 2), bound = control(1, 2), later = control(2, 2), same = control(3, 2);
            var original = new Rectangle(260, 140); existing.setClip(original);
            var binding = new SimpleObjectProperty<Node>(); bound.clipProperty().bind(binding);
            var root = new HBox(existing, bound, later, same); var stage = stage(root, 1100, 250);
            try {
                root.applyCss(); root.layout(); WorkspaceRoundedControlClip.install(root);
                assertSame(original, existing.getClip()); assertTrue(bound.clipProperty().isBound()); assertNull(bound.getClip());
                var replacement = new Rectangle(250, 130); later.setClip(replacement);
                var owned = same.getClip(); assertNotNull(owned);
                var sameBinding = new SimpleObjectProperty<Node>(owned); same.clipProperty().bind(sameBinding);
                WorkspaceRoundedControlClip.install(root);
                stage.hide(); WorkspaceRoundedControlClip.uninstall(root);
                assertSame(original, existing.getClip()); assertSame(replacement, later.getClip());
                assertTrue(bound.clipProperty().isBound()); assertNull(bound.getClip());
                assertTrue(same.clipProperty().isBound()); assertSame(owned, same.getClip());
                same.clipProperty().unbind(); bound.clipProperty().unbind();
                stage.show(); root.applyCss(); root.layout(); WorkspaceRoundedControlClip.install(root);
                assertSame(owned, same.getClip(), "Binding takeover persists after unbinding and reinstallation");
                assertSame(replacement, later.getClip());
            } finally { bound.clipProperty().unbind(); same.clipProperty().unbind(); WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            return null;
        });
    }

    @Test void embeddedStraightAndAsymmetricCallerSurfacesAreLeftAlone() throws Exception {
        fx(() -> {
            Control embedded = control(0, 2), asymmetric = control(1, 2);
            var sheet = new VBox(embedded); sheet.getStyleClass().add("workspace-sheet");
            asymmetric.setStyle("-fx-background-radius: 20 4 12 6;");
            var root = new HBox(sheet, asymmetric); var stage = stage(root, 700, 260);
            try {
                root.applyCss(); root.layout(); WorkspaceRoundedControlClip.install(root);
                assertNull(embedded.getClip(), "The owning workspace sheet supplies the embedded table's outer frame");
                assertNull(asymmetric.getClip(), "A uniform helper must not approximate a caller's asymmetric surface");
            } finally { WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            return null;
        });
    }

    @Test void reentrantCallerClearingTheHelperClipKeepsOwnershipAcrossResizeAndRefresh() throws Exception {
        fx(() -> {
            Control control = control(0, 2); var root = new StackPane(control); var stage = stage(root, 440, 260);
            try {
                root.applyCss(); root.layout();
                assertNotNull(control.getClip(), "The normal theme hook installs containment before this fixture adds listeners");
                // Reset through the public API so this regression observes a
                // first write, rather than an already-installed idempotent scope.
                WorkspaceRoundedControlClip.uninstall(root); assertNull(control.getClip());
                var writes = new java.util.concurrent.atomic.AtomicInteger();
                control.clipProperty().addListener((observable, previous, next) -> {
                    if (next != null && writes.getAndIncrement() == 0) control.setClip(null);
                });
                WorkspaceRoundedControlClip.install(root); assertNull(control.getClip());
                control.resize(360, 200); WorkspaceRoundedControlClip.install(root);
                assertNull(control.getClip(), "An explicit reentrant null is caller ownership, not permission to reacquire");
                assertEquals(1, writes.get(), "No second helper write may follow the caller's takeover");
            } finally { WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            return null;
        });
    }

    @Test void logicalDynamicContentAndWindowLifecycleReleaseAndReattachOwnedClips() throws Exception {
        var state = fx(() -> {
            var tabs = new TabPane(); var list = control(1, 2); var tab = new Tab("Synthetic", list); tabs.getTabs().add(tab);
            var titled = new TitledPane("Synthetic", tabs); titled.setExpanded(true); titled.setAnimated(false);
            var split = new SplitPane(titled); var scroll = new ScrollPane(split); scroll.setFitToWidth(true); scroll.setFitToHeight(true);
            var dialog = new DialogPane(); dialog.setContent(scroll); var root = new StackPane(dialog); var stage = stage(root, 700, 420);
            root.applyCss(); root.layout(); WorkspaceRoundedControlClip.install(root); assertNotNull(list.getClip());
            tabs.getTabs().remove(tab); assertNull(list.getClip(), "Detached control releases its clip immediately");
            return new Object[]{root, stage, tabs, tab, list};
        });
        fx(() -> { ((TabPane) state[2]).getTabs().add((Tab) state[3]); return null; });
        fx(() -> null);
        fx(() -> {
            Parent root = (Parent) state[0]; Stage stage = (Stage) state[1]; Control list = (Control) state[4];
            root.applyCss(); root.layout(); assertNotNull(list.getClip(), "Reattached logical content is discovered");
            stage.hide(); assertNull(list.getClip(), "Hidden window releases owned clips and tree listeners");
            stage.show(); root.applyCss(); root.layout(); assertNotNull(list.getClip(), "Reopened window reinstalls containment");
            WorkspaceRoundedControlClip.uninstall(root); assertNull(list.getClip()); stage.close(); return null;
        });
    }

    @Test void dynamicAccordionPanesAndToolbarItemsUseOnlyTheirPublicContentLists() throws Exception {
        var state = fx(() -> {
            var accordion = new Accordion(); var toolbar = new ToolBar();
            var root = new VBox(accordion, toolbar); var stage = stage(root, 760, 550);
            root.applyCss(); root.layout(); WorkspaceRoundedControlClip.install(root);
            Control table = control(0, 2), list = control(1, 2); table.setPrefSize(340, 180); list.setPrefSize(340, 180);
            var pane = new TitledPane("Synthetic", table); pane.setAnimated(false); accordion.getPanes().add(pane); accordion.setExpandedPane(pane);
            toolbar.getItems().add(list);
            return new Object[]{root, stage, accordion, toolbar, pane, table, list};
        });
        fx(() -> null);
        fx(() -> {
            var root = (Parent) state[0]; root.applyCss(); root.layout();
            assertNotNull(((Control) state[5]).getClip(), "New Accordion pane content is discovered");
            assertNotNull(((Control) state[6]).getClip(), "New ToolBar item content is discovered");
            ((Stage) state[1]).hide(); assertNull(((Control) state[5]).getClip()); assertNull(((Control) state[6]).getClip());
            ((Stage) state[1]).show(); root.applyCss(); root.layout();
            assertNotNull(((Control) state[5]).getClip()); assertNotNull(((Control) state[6]).getClip());
            ((Accordion) state[2]).getPanes().clear(); ((ToolBar) state[3]).getItems().clear();
            return null;
        });
        fx(() -> null);
        fx(() -> {
            assertNull(((Control) state[5]).getClip(), "Removed Accordion content releases its clip");
            assertNull(((Control) state[6]).getClip(), "Removed ToolBar content releases its clip");
            WorkspaceRoundedControlClip.uninstall((Parent) state[0]); ((Stage) state[1]).close(); return null;
        });
    }

    @Test void installationBeforeSceneAttachmentTransfersToTheActualSceneRoot() throws Exception {
        fx(() -> {
            Control list = control(1, 2); var page = new VBox(list);
            WorkspaceRoundedControlClip.install(page);
            var root = new StackPane(page); var stage = stage(root, 440, 300);
            try {
                root.applyCss(); root.layout();
                assertNotNull(list.getClip(), "Unattached themed content transfers ownership to its later scene root");
                WorkspaceRoundedControlClip.uninstall(root); assertNull(list.getClip());
            } finally { WorkspaceRoundedControlClip.uninstall(page); WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            return null;
        });
    }

    @Test void reentrantWindowHideReleasesAnOwnedClipWithoutClaimingCallerOwnership() throws Exception {
        fx(() -> {
            Control first = control(0, 2), second = control(1, 2);
            var root = new HBox(first, second); var stage = stage(root, 720, 280);
            try {
                root.applyCss(); root.layout();
                assertNotNull(first.getClip(), "The normal theme hook already installed the first control");
                assertNotNull(second.getClip(), "The normal theme hook already installed the second control");
                WorkspaceRoundedControlClip.uninstall(root); assertNull(first.getClip()); assertNull(second.getClip());
                var hideOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
                for (Control control : List.of(first, second)) control.clipProperty().addListener((observable, previous, next) -> {
                    if (next != null && hideOnce.getAndSet(false)) stage.hide();
                });
                WorkspaceRoundedControlClip.install(root);
                assertFalse(stage.isShowing()); assertNull(first.getClip()); assertNull(second.getClip());
                stage.show(); root.applyCss(); root.layout();
                assertNotNull(first.getClip(), "The helper's own nested release is not a caller takeover");
                assertNotNull(second.getClip(), "All controls reattach after a reentrant window hide");
            } finally { WorkspaceRoundedControlClip.uninstall(root); stage.close(); }
            return null;
        });
    }

    private static Control control(int kind, int count) {
        var rows = java.util.stream.IntStream.range(0, count).mapToObj(n -> "Synthetic " + n).toList();
        if (kind == 0) {
            var table = new TableView<String>(); var column = new TableColumn<String, String>("Synthetic header"); column.setPrefWidth(210);
            column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));
            table.getColumns().add(column); table.getItems().setAll(rows); return table;
        }
        if (kind == 1) { var list = new ListView<String>(); list.getItems().setAll(rows); return list; }
        var item = new TreeItem<>("Synthetic root"); item.setExpanded(true); rows.forEach(row -> item.getChildren().add(new TreeItem<>(row)));
        if (kind == 2) {
            var table = new TreeTableView<String>(item); table.setShowRoot(false);
            var column = new TreeTableColumn<String, String>("Synthetic header"); column.setPrefWidth(210);
            column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().getValue())); table.getColumns().add(column); return table;
        }
        var tree = new TreeView<String>(item); tree.setShowRoot(false); return tree;
    }
    private static void select(Control control, int row) {
        if (control instanceof TableView<?> table) table.getSelectionModel().select(row);
        else if (control instanceof ListView<?> list) list.getSelectionModel().select(row);
        else if (control instanceof TreeTableView<?> table) table.getSelectionModel().select(row);
        else if (control instanceof TreeView<?> tree) tree.getSelectionModel().select(row);
    }
    private static int selected(Control control) {
        if (control instanceof TableView<?> table) return table.getSelectionModel().getSelectedIndex();
        if (control instanceof ListView<?> list) return list.getSelectionModel().getSelectedIndex();
        if (control instanceof TreeTableView<?> table) return table.getSelectionModel().getSelectedIndex();
        return ((TreeView<?>) control).getSelectionModel().getSelectedIndex();
    }
    private static void scroll(Control control, int row) {
        if (control instanceof TableView<?> table) table.scrollTo(row);
        else if (control instanceof ListView<?> list) list.scrollTo(row);
        else if (control instanceof TreeTableView<?> table) table.scrollTo(row);
        else if (control instanceof TreeView<?> tree) tree.scrollTo(row);
    }
    private static Stage stage(Parent root, int width, int height) {
        var stage = new Stage(); stage.setScene(new Scene(root, width, height)); ThemeService.applyTheme(stage.getScene()); stage.show(); return stage;
    }
    private static Color pixel(Parent root, WritableImage image, Node node, double x, double y) {
        var point = root.sceneToLocal(node.localToScene(x, y)); return image.getPixelReader().getColor((int) Math.floor(point.getX()), (int) Math.floor(point.getY()));
    }
    private static void capture(Parent root, String name) throws Exception {
        String output = System.getProperty("vncode.rounded.capture.dir"); if (output == null) return;
        Path directory = Path.of(output); Files.createDirectories(directory); var image = root.snapshot(null, null);
        var pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(pixels, "png", directory.resolve(name).toFile());
    }
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); Platform.runLater(task); return task.get(45, TimeUnit.SECONDS);
    }
}
