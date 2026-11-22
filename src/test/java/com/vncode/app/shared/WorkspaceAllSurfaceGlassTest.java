package com.vncode.app.shared;

import com.vncode.app.config.Database;
import com.vncode.app.ui.workspace.WorkspaceViewport;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.skin.ScrollPaneSkin;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Retains the historical CI suite name while verifying flat surfaces and live controls. */
class WorkspaceAllSurfaceGlassTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final List<Stage> stages = new ArrayList<>();

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (IllegalStateException alreadyStarted) {
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work);
        Platform.runLater(future);
        return future.get(30, TimeUnit.SECONDS);
    }

    private static void settle() throws Exception {
        var done = new CompletableFuture<Void>();
        fx(
                () -> {
                    new AnimationTimer() {
                        int pulses;

                        public void handle(long now) {
                            if (++pulses >= 3) {
                                stop();
                                done.complete(null);
                            }
                        }
                    }.start();
                    Platform.requestNextPulse();
                    return null;
                });
        done.get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString());
        Database.initDatabase();
        previousReduced =
                fx(
                        () -> {
                            ThemeService.initializeAppearance();
                            boolean old = ThemeService.isReducedMotion();
                            ThemeService.setReducedMotion(true);
                            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
                            return old;
                        });
    }

    @AfterEach
    void restore() throws Exception {
        fx(
                () -> {
                    for (var stage : stages) stage.close();
                    WorkspaceInteractionMotion.cancelAll();
                    WorkspaceMotion.cancelAll();
                    ThemeService.setReducedMotion(previousReduced);
                    return null;
                });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private Stage show(Parent root) {
        var stage = new Stage();
        stage.setScene(new Scene(root, 1000, 900));
        stages.add(stage);
        ThemeService.applyTheme(stage.getScene());
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static void noPattern(Region node) {
        if (node.getBackground() != null) {
            assertTrue(node.getBackground().getImages().isEmpty(), "No raster backdrop: " + node);
            assertTrue(
                    node.getBackground().getFills().stream()
                            .noneMatch(f -> f.getFill() instanceof ImagePattern),
                    "No sampled backdrop: " + node);
        }
    }

    private static void opaque(Region node) {
        assertNotNull(node.getBackground(), "Styled surface requires a background: " + node);
        var fill = node.getBackground().getFills().getLast().getFill();
        assertInstanceOf(Color.class, fill);
        assertEquals(1, ((Color) fill).getOpacity(), "Styled surface must be opaque: " + node);
        noPattern(node);
        assertEquals(1, node.getOpacity());
    }

    private static TableView<String> table() {
        var t = new TableView<String>();
        var column = new TableColumn<String, String>("Orders");
        column.setCellValueFactory(value -> new SimpleStringProperty(value.getValue()));
        t.getColumns().add(column);
        t.getItems().setAll("Order A", "Order B", "Order C");
        return t;
    }

    @Test
    void innerFieldsButtonsAndTablesAreOpaqueWhileBusinessActionsStayLive() throws Exception {
        var fixture =
                fx(
                        () -> {
                            var title = new Label("Synthetic products");
                            var text = new TextField("Synthetic article");
                            var action = new Button("Apply");
                            var table = table();
                            var page = new VBox(12, title, new HBox(12, text, action), table);
                            page.getStyleClass().add("workspace-sheet");
                            var root = new VBox(page);
                            root.getStyleClass().add("workspace-shell");
                            var calls = new AtomicInteger();
                            action.setOnAction(e -> calls.incrementAndGet());
                            var handler = action.getOnAction();
                            show(root);
                            return new Object[] {
                                root, page, text, action, table, title, calls, handler
                            };
                        });
        settle();
        fx(
                () -> {
                    var root = (VBox) fixture[0];
                    root.applyCss();
                    root.layout();
                    var page = (VBox) fixture[1];
                    var text = (TextField) fixture[2];
                    var action = (Button) fixture[3];
                    var table = (TableView<?>) fixture[4];
                    var title = (Label) fixture[5];
                    noPattern(root);
                    noPattern(page);
                    for (var r : List.<Region>of(text, action, table)) opaque(r);
                    assertEquals(1, root.getChildren().size());
                    assertEquals(3, page.getChildren().size());
                    assertEquals("Synthetic products", title.getText());
                    assertNull(title.getEffect());
                    assertEquals(1, title.getOpacity());
                    assertEquals("Synthetic article", text.getText());
                    assertEquals(List.of("Order A", "Order B", "Order C"), table.getItems());
                    assertTrue(text.isFocusTraversable());
                    assertTrue(action.isFocusTraversable());
                    assertEquals(javafx.scene.AccessibleRole.BUTTON, action.getAccessibleRole());
                    assertSame(fixture[7], action.getOnAction());
                    action.fire();
                    assertEquals(1, ((AtomicInteger) fixture[6]).get());
                    for (var row : table.lookupAll(".table-row-cell")) {
                        noPattern((Region) row);
                        assertNull(row.getEffect(), "Scrolling rows have no expensive effect");
                    }
                    return null;
                });
    }

    @Test
    void nativeLogicalControlsAndTabsKeepTheirInputsWhenNewContentIsAttached() throws Exception {
        var parts =
                fx(
                        () -> {
                            var tabField = new TextField("First tab");
                            var tabs = new TabPane(new Tab("First", new VBox(tabField)));
                            var scrollField = new TextField("Scrolled form");
                            var scroll = new ScrollPane(new VBox(scrollField));
                            var titleField = new TextField("Expanded form");
                            var title = new TitledPane("Details", new VBox(titleField));
                            title.setAnimated(false);
                            var splitField = new TextField("Split form");
                            var split = new SplitPane(new VBox(splitField));
                            var action = new Button("Refresh");
                            var toolbar = new ToolBar(action);
                            var accordionField = new TextField("Accordion form");
                            var section = new TitledPane("Options", new VBox(accordionField));
                            section.setAnimated(false);
                            var accordion = new Accordion(section);
                            accordion.setExpandedPane(section);
                            var root = new VBox(8, tabs, scroll, title, split, toolbar, accordion);
                            root.getStyleClass().add("workspace-shell");
                            show(root);
                            return new Object[] {
                                root,
                                tabs,
                                tabField,
                                scrollField,
                                titleField,
                                splitField,
                                action,
                                accordionField
                            };
                        });
        settle();
        var next =
                fx(
                        () -> {
                            var field = new TextField("Newly attached tab");
                            var tab = new Tab("Next", new VBox(field));
                            var tabs = (TabPane) parts[1];
                            tabs.getTabs().add(tab);
                            tabs.getSelectionModel().select(tab);
                            ((Parent) parts[0]).applyCss();
                            return field;
                        });
        settle();
        fx(
                () -> {
                    for (int i = 2; i < parts.length; i++) {
                        var node = (Region) parts[i];
                        noPattern(node);
                        assertEquals(1, node.getOpacity());
                    }
                    assertEquals("First tab", ((TextField) parts[2]).getText());
                    assertEquals("Scrolled form", ((TextField) parts[3]).getText());
                    assertEquals("Expanded form", ((TextField) parts[4]).getText());
                    assertEquals("Split form", ((TextField) parts[5]).getText());
                    assertEquals("Refresh", ((Button) parts[6]).getText());
                    assertEquals("Accordion form", ((TextField) parts[7]).getText());
                    noPattern(next);
                    assertEquals("Newly attached tab", next.getText());
                    return null;
                });
    }

    @Test
    void customInlineAndBoundFieldsRetainOwnershipAcrossThemeFlip() throws Exception {
        var parts =
                fx(
                        () -> {
                            var caller = new TextField("Caller-owned");
                            caller.setStyle("-fx-background-color: goldenrod;");
                            var bound = new TextField("Bound");
                            var paint =
                                    new javafx.beans.property.SimpleObjectProperty<>(
                                            Background.fill(Color.SIENNA));
                            bound.backgroundProperty().bind(paint);
                            var ordinary = new TextField("App field");
                            var root = new VBox(10, caller, bound, ordinary);
                            root.getStyleClass().add("workspace-shell");
                            show(root);
                            return new Object[] {root, caller, bound, paint, ordinary};
                        });
        settle();
        fx(
                () -> {
                    assertEquals(Color.GOLDENROD, ThemeServiceTest.paint((Region) parts[1]));
                    assertSame(
                            ((javafx.beans.property.SimpleObjectProperty<?>) parts[3]).get(),
                            ((Region) parts[2]).getBackground());
                    ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                    ((Parent) parts[0]).applyCss();
                    return null;
                });
        settle();
        fx(
                () -> {
                    assertEquals(Color.GOLDENROD, ThemeServiceTest.paint((Region) parts[1]));
                    assertSame(
                            ((javafx.beans.property.SimpleObjectProperty<?>) parts[3]).get(),
                            ((Region) parts[2]).getBackground());
                    noPattern((Region) parts[4]);
                    return null;
                });
    }

    @Test
    void bothThemesKeepNativeWidgetFamiliesReadableAndOperational() throws Exception {
        for (var theme : ThemeService.Theme.values()) {
            var parts =
                    fx(
                            () -> {
                                ThemeService.setCurrentTheme(theme);
                                var choice = new ChoiceBox<String>();
                                choice.getItems().setAll("One", "Two");
                                choice.setValue("One");
                                var combo = new ComboBox<String>();
                                combo.getItems().setAll("One", "Two");
                                combo.setValue("Two");
                                var tree = new TreeView<>(new TreeItem<>("Synthetic tree"));
                                var treeTable =
                                        new TreeTableView<>(new TreeItem<>("Synthetic tree table"));
                                treeTable.getColumns().add(new TreeTableColumn<>("Order"));
                                var list = new ListView<String>();
                                list.getItems().setAll("First", "Second");
                                var types =
                                        List.<Region>of(
                                                new TextField("Field"),
                                                new PasswordField(),
                                                new TextArea("Notes"),
                                                new Button("Apply"),
                                                new ToggleButton("Selected"),
                                                new MenuButton("Actions"),
                                                choice,
                                                combo,
                                                new DatePicker(java.time.LocalDate.of(2026, 1, 1)),
                                                new Spinner<Integer>(1, 10, 2),
                                                table(),
                                                treeTable,
                                                list,
                                                tree,
                                                new TitledPane(
                                                        "Details", new Label("Synthetic detail")),
                                                new ToolBar(new Button("Refresh")));
                                var grid = new TilePane(10, 10);
                                grid.setPrefColumns(4);
                                grid.setPrefTileWidth(230);
                                grid.setPrefTileHeight(135);
                                for (var node : types) {
                                    node.setPrefSize(230, 135);
                                    grid.getChildren().add(node);
                                }
                                var root = new VBox(grid);
                                root.getStyleClass().add("workspace-shell");
                                show(root);
                                return types;
                            });
            settle();
            fx(
                    () -> {
                        for (var node : parts) {
                            noPattern(node);
                            assertEquals(1, node.getOpacity());
                        }
                        assertEquals("One", ((ChoiceBox<?>) parts.get(6)).getValue());
                        assertEquals("Two", ((ComboBox<?>) parts.get(7)).getValue());
                        assertEquals(2, ((Spinner<?>) parts.get(9)).getValue());
                        return null;
                    });
        }
    }

    @Test
    void settingsWrapperAndNewCardsRetainLiveContentAndFlatBackground() throws Exception {
        var parts =
                fx(
                        () -> {
                            var section =
                                    new VBox(
                                            new Label("Appearance"),
                                            new TextField("Synthetic setting"));
                            section.getStyleClass().add("appearance-section");
                            var appearance = new VBox(12, section);
                            appearance.getStyleClass().add("appearance-pane");
                            var later = new VBox(new Label("New panel"));
                            later.setMinSize(200, 80);
                            appearance.getChildren().add(later);
                            var root = new VBox(appearance);
                            root.getStyleClass().add("workspace-shell");
                            show(root);
                            later.getStyleClass().add("card");
                            root.applyCss();
                            root.layout();
                            return new VBox[] {root, appearance, section, later};
                        });
        settle();
        fx(
                () -> {
                    for (var pane : parts) {
                        noPattern(pane);
                        assertEquals(1, pane.getOpacity());
                    }
                    opaque(parts[3]);
                    assertEquals(
                            "New panel", ((Label) parts[3].getChildren().getFirst()).getText());
                    return null;
                });
    }

    @Test
    void reducedNavigationAndDelayedNativeSkinPreserveControlsWithoutBackdropSampling()
            throws Exception {
        var parts =
                fx(
                        () -> {
                            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                            var slot = new StackPane();
                            var root = new VBox(slot);
                            root.getStyleClass().add("workspace-shell");
                            show(root);
                            var field = new TextField("Delayed native content");
                            var custom = new TextField("Caller paint");
                            custom.setStyle("-fx-background-color: goldenrod;");
                            var page = new VBox(10, field, custom);
                            page.getStyleClass().add("workspace-sheet");
                            var scroll =
                                    new ScrollPane(page) {
                                        @Override
                                        protected Skin<?> createDefaultSkin() {
                                            return null;
                                        }
                                    };
                            slot.getChildren().setAll(scroll);
                            assertNull(page.getScene());
                            return new Object[] {root, page, field, custom, scroll};
                        });
        fx(
                () -> {
                    ((ScrollPane) parts[4]).setSkin(new ScrollPaneSkin((ScrollPane) parts[4]));
                    return null;
                });
        settle();
        fx(
                () -> {
                    var root = (Parent) parts[0];
                    root.applyCss();
                    root.layout();
                    var page = (VBox) parts[1];
                    var field = (TextField) parts[2];
                    noPattern(page);
                    noPattern(field);
                    assertEquals("Delayed native content", field.getText());
                    assertEquals(Color.GOLDENROD, ThemeServiceTest.paint((Region) parts[3]));
                    ((ScrollPane) parts[4]).setContent(null);
                    assertNull(page.getScene());
                    return null;
                });
    }

    @Test
    void reducedMotionNavigationAttachesNewViewportWithoutResettingThemeOrData() throws Exception {
        var parts =
                fx(
                        () -> {
                            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                            var host = new StackPane();
                            var root = new VBox(host);
                            root.getStyleClass().add("workspace-shell");
                            VBox.setVgrow(host, Priority.ALWAYS);
                            show(root);
                            var field = new TextField("New route article");
                            var list = new ListView<String>();
                            list.getItems().setAll("A", "B");
                            var page = new VBox(field, list);
                            page.getStyleClass().add("workspace-sheet");
                            new MotionBoundary()
                                    .replace(
                                            host,
                                            new WorkspaceViewport(page),
                                            NavigationContext.SIBLING);
                            return new Object[] {root, page, field, list};
                        });
        settle();
        fx(
                () -> {
                    ((Parent) parts[0]).applyCss();
                    ((Parent) parts[0]).layout();
                    var page = (Region) parts[1];
                    var field = (TextField) parts[2];
                    var list = (ListView<?>) parts[3];
                    assertNotNull(page.getScene());
                    noPattern(page);
                    noPattern(field);
                    noPattern(list);
                    assertTrue(field.isFocusTraversable());
                    assertEquals("New route article", field.getText());
                    assertEquals(List.of("A", "B"), list.getItems());
                    assertEquals(ThemeService.Theme.DARK, ThemeService.getCurrentTheme());
                    return null;
                });
    }
}
