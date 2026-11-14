package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** App-owned inner surfaces share a real backdrop; rows, text and business actions stay live. */
class WorkspaceAllSurfaceGlassTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final List<Stage> stages = new ArrayList<>();

    @BeforeAll static void startToolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work); Platform.runLater(future);
        return future.get(30, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        var completed = new CompletableFuture<Void>();
        fx(() -> {
            var timer = new AnimationTimer() {
                private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                private int readyPulses;
                @Override public void handle(long now) {
                    try {
                        readyPulses = condition.getAsBoolean() ? readyPulses + 1 : 0;
                        if (readyPulses >= 2) { stop(); completed.complete(null); }
                        else if (System.nanoTime() >= deadline) {
                            stop(); completed.completeExceptionally(new AssertionError(description));
                        }
                    } catch (Throwable failure) { stop(); completed.completeExceptionally(failure); }
                }
            };
            timer.start(); Platform.requestNextPulse(); return null;
        });
        completed.get(10, TimeUnit.SECONDS);
    }

    @BeforeEach void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
        previousReduced = fx(() -> {
            ThemeService.initializeAppearance(); boolean old = ThemeService.isReducedMotion();
            ThemeService.setReducedMotion(true); ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            ThemeService.setGlassEnabled(true); ThemeService.setGlassTransparency(55); return old;
        });
    }

    @AfterEach void restore() throws Exception {
        fx(() -> { for (var stage : stages) stage.close(); ThemeService.setReducedMotion(previousReduced); return null; });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private Stage show(Parent root) {
        var stage = new Stage(); stage.setScene(new Scene(root, 1000, 900));
        stages.add(stage); ThemeService.applyTheme(stage.getScene()); stage.show(); root.applyCss(); root.layout(); return stage;
    }

    @Test void innerPagesFieldsButtonsAndWorkingTablesUseSharedFrostedMaterial() throws Exception {
        var objects = fx(() -> {
            var title = new Label("Synthetic products"); var text = new TextField("Synthetic article");
            var action = new Button("Apply"); var table = table();
            var page = new VBox(12, title, new HBox(12, text, action), table);
            page.getStyleClass().add("workspace-sheet"); VBox.setVgrow(table, Priority.ALWAYS);
            var root = new VBox(page); root.getStyleClass().add("workspace-shell"); VBox.setVgrow(page, Priority.ALWAYS);
            var calls = new AtomicInteger(); action.setOnAction(event -> calls.incrementAndGet());
            var originalHandler = action.getOnAction(); show(root);
            return new Object[]{root, page, text, action, table, title, calls, originalHandler};
        });
        var root = (VBox) objects[0];
        await(() -> pattern(root) != null, "The shared workspace wallpaper must be ready");
        // Focus and native control CSS may settle one pulse after the workspace ground.
        await(() -> pattern((Region) objects[1]) != null && pattern((Region) objects[2]) != null
                && pattern((Region) objects[3]) != null && pattern((Region) objects[4]) != null,
                "Inner page, field, button and table materials must be ready");
        fx(() -> {
            var page = (VBox) objects[1]; var text = (TextField) objects[2]; var action = (Button) objects[3];
            var table = (TableView<?>) objects[4]; var title = (Label) objects[5];
            var surfaces = List.<Region>of(page, text, action, table);
            assertAll(surfaces.stream().map(surface -> (org.junit.jupiter.api.function.Executable) () -> {
                assertNotNull(pattern(surface), surface.getClass().getSimpleName() + " needs its own shared backdrop material");
                assertEquals(1, surface.getOpacity(), "Material never fades the whole control");
                assertInstanceOf(Color.class, surface.getBackground().getFills().getLast().getFill());
                assertTrue(((Color) surface.getBackground().getFills().getLast().getFill()).getOpacity() < 1,
                        "A frosted surface cannot hide its backdrop behind a solid final paint");
            }));
            var blurred = pattern(page).getImage();
            for (var surface : surfaces) assertSame(blurred, pattern(surface).getImage(), "Reuse one blurred image, not per-control rasters");
            assertNotSame(pattern(root).getImage(), blurred); assertEquals(960, blurred.getWidth()); assertEquals(600, blurred.getHeight());
            assertEquals(1, root.getChildren().size()); assertEquals(3, page.getChildren().size());
            assertEquals("Synthetic products", title.getText()); assertNull(title.getEffect()); assertEquals(1, title.getOpacity());
            assertEquals("Synthetic article", text.getText()); assertEquals(List.of("Order A", "Order B", "Order C"), table.getItems());
            assertSame(objects[7], action.getOnAction()); action.fire(); assertEquals(1, ((AtomicInteger) objects[6]).get());
            for (Node row : table.lookupAll(".table-row-cell")) {
                assertNull(pattern((Region) row), "Virtualized rows share their table material rather than capture individual images");
                assertNull(row.getEffect(), "Scrolling rows do not carry blur/shadow effects");
            }
            return null;
        });
    }

    @Test void materialReachesPublicLogicalContentInsideControlsAndNewTabs() throws Exception {
        var content = fx(() -> {
            var tabField = new TextField("First tab"); var first = new Tab("First", new VBox(tabField));
            var tabs = new TabPane(first); tabs.setPrefHeight(100);
            var scrollField = new TextField("Scrolled form"); var scroll = new ScrollPane(new VBox(scrollField)); scroll.setPrefHeight(100);
            var titledField = new TextField("Expanded form"); var titled = new TitledPane("Details", new VBox(titledField)); titled.setAnimated(false);
            var splitField = new TextField("Split form"); var split = new SplitPane(new VBox(splitField)); split.setPrefHeight(100);
            var toolAction = new Button("Refresh"); var toolbar = new ToolBar(toolAction);
            var accordionField = new TextField("Accordion form"); var section = new TitledPane("Options", new VBox(accordionField)); section.setAnimated(false);
            var accordion = new Accordion(section); accordion.setExpandedPane(section);
            var root = new VBox(8, tabs, scroll, titled, split, toolbar, accordion); root.getStyleClass().add("workspace-shell"); show(root);
            return new Object[]{root, tabs, tabField, scrollField, titledField, splitField, toolAction, accordionField};
        });
        for (int i = 2; i < content.length; i++) {
            var surface = (Region) content[i]; await(() -> pattern(surface) != null, "Logical form/control content needs material: " + i);
        }
        var next = fx(() -> {
            var field = new TextField("Newly attached tab"); var tab = new Tab("Next", new VBox(field));
            var tabs = (TabPane) content[1]; tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab); return field;
        });
        await(() -> pattern(next) != null, "A tab added after scene installation must receive the shared material");
        fx(() -> { assertEquals("Newly attached tab", next.getText()); assertEquals(1, next.getOpacity()); return null; });
    }

    @Test void innerSurfaceOptOutsAndGlassOffPreserveCallerOwnership() throws Exception {
        var data = fx(() -> {
            var caller = new TextField("Caller-owned"); caller.setStyle("-fx-background-color: goldenrod;");
            var bound = new TextField("Bound"); var paint = new javafx.beans.property.SimpleObjectProperty<>(Background.fill(Color.SIENNA));
            bound.backgroundProperty().bind(paint);
            var ordinary = new TextField("App material"); var root = new VBox(10, caller, bound, ordinary);
            root.getStyleClass().add("workspace-shell"); show(root); return new Object[]{root, caller, bound, paint, ordinary};
        });
        var ordinary = (TextField) data[4]; await(() -> pattern(ordinary) != null, "The ordinary field must receive material");
        fx(() -> {
            assertNull(pattern((Region) data[1])); assertEquals(Color.GOLDENROD, ((Region) data[1]).getBackground().getFills().getLast().getFill());
            assertSame(((javafx.beans.property.SimpleObjectProperty<?>) data[3]).get(), ((Region) data[2]).getBackground());
            ThemeService.setGlassEnabled(false); return null;
        });
        await(() -> pattern(ordinary) == null, "Disabling glass restores the regular field paint");
        fx(() -> { assertNull(pattern((Region) data[1])); assertSame(((javafx.beans.property.SimpleObjectProperty<?>) data[3]).get(), ((Region) data[2]).getBackground()); return null; });
    }

    @Test void bothThemesCoverNativeWidgetFamiliesWithoutAllocatingPerWidgetImages() throws Exception {
        Set<Image> images = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var theme : ThemeService.Theme.values()) {
            var fixture = fx(() -> {
                ThemeService.setCurrentTheme(theme);
                var choice = new ChoiceBox<String>(); choice.getItems().setAll("One", "Two"); choice.setValue("One");
                var combo = new ComboBox<String>(); combo.getItems().setAll("One", "Two"); combo.setValue("Two");
                var tree = new TreeView<>(new TreeItem<>("Synthetic tree"));
                var treeTable = new TreeTableView<>(new TreeItem<>("Synthetic tree table"));
                treeTable.getColumns().add(new TreeTableColumn<>("Order"));
                var list = new ListView<String>(); list.getItems().setAll("First", "Second");
                var types = List.<Region>of(new TextField("Field"), new PasswordField(), new TextArea("Notes"),
                        new Button("Apply"), new ToggleButton("Selected"), new MenuButton("Actions"), choice, combo,
                        new DatePicker(java.time.LocalDate.of(2026, 1, 1)), new Spinner<Integer>(1, 10, 2),
                        table(), treeTable, list, tree, new TitledPane("Details", new Label("Synthetic detail")),
                        new ToolBar(new Button("Refresh")));
                var grid = new TilePane(10, 10); grid.setPrefColumns(4); grid.setPrefTileWidth(230); grid.setPrefTileHeight(135);
                for (var type : types) { type.setPrefSize(230, 135); grid.getChildren().add(type); }
                var root = new VBox(grid); root.getStyleClass().add("workspace-shell"); show(root);
                return new Object[]{root, types};
            });
            @SuppressWarnings("unchecked") var types = (List<Region>) fixture[1];
            for (Region type : types) await(() -> pattern(type) != null, theme + " missing material on " + type.getClass().getSimpleName());
            fx(() -> {
                var root = (VBox) fixture[0]; images.add(pattern(root).getImage());
                for (Region type : types) {
                    images.add(pattern(type).getImage()); assertEquals(1, type.getOpacity());
                    assertInstanceOf(Color.class, type.getBackground().getFills().getLast().getFill());
                    assertTrue(((Color) type.getBackground().getFills().getLast().getFill()).getOpacity() < 1,
                            theme + " must expose the backdrop on " + type.getClass().getSimpleName());
                }
                assertEquals("One", ((ChoiceBox<?>) types.get(6)).getValue());
                assertEquals("Two", ((ComboBox<?>) types.get(7)).getValue());
                assertEquals(2, ((Spinner<?>) types.get(9)).getValue()); return null;
            });
        }
        assertEquals(4, images.size(), "Every native widget reuses the two theme grounds and their blurred variants");
    }

    @Test void transparentSettingsWrapperStaysClearWhileItsSectionsAndNewCardsFloat() throws Exception {
        var fixture = fx(() -> {
            var section = new VBox(new Label("Appearance"), new TextField("Synthetic setting")); section.getStyleClass().add("appearance-section");
            var appearance = new VBox(12, section); appearance.getStyleClass().add("appearance-pane");
            var later = new VBox(new Label("New panel")); later.setMinSize(200, 80); appearance.getChildren().add(later);
            var root = new VBox(appearance); root.getStyleClass().add("workspace-shell"); show(root);
            return new VBox[]{root, appearance, section, later};
        });
        await(() -> pattern(fixture[2]) != null, "The Settings section must float independently");
        fx(() -> {
            assertNull(pattern(fixture[1]), "Never paint another full rectangle around Settings");
            assertTrue(fixture[1].getBackground() == null || fixture[1].getBackground().getFills().stream()
                    .allMatch(fill -> fill.getFill() instanceof Color color && color.getOpacity() == 0));
            fixture[3].getStyleClass().add("card"); fixture[0].applyCss(); fixture[0].layout(); return null;
        });
        await(() -> pattern(fixture[3]) != null, "A panel tagged after installation must acquire material");
        fx(() -> { assertEquals(1, fixture[3].getChildren().size()); assertEquals("New panel", ((Label) fixture[3].getChildren().getFirst()).getText()); return null; });
    }

    private static TableView<String> table() {
        var table = new TableView<String>(); var column = new TableColumn<String, String>("Orders");
        column.setCellValueFactory(value -> new SimpleStringProperty(value.getValue())); table.getColumns().add(column);
        table.getItems().setAll("Order A", "Order B", "Order C"); return table;
    }

    private static ImagePattern pattern(Region region) {
        return region.getBackground() == null ? null : region.getBackground().getFills().stream().map(fill -> fill.getFill())
                .filter(ImagePattern.class::isInstance).map(ImagePattern.class::cast).findFirst().orElse(null);
    }
}
