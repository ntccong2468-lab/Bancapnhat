package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.StyleOrigin;
import javafx.css.StyleableProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Public JavaFX surfaces must use flat, opaque colors without changing caller-owned backgrounds.
 */
class WorkspaceBackdropMaterialTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private Thread.UncaughtExceptionHandler previousFxHandler;
    private final List<Throwable> callbackErrors = new CopyOnWriteArrayList<>();
    private final List<Stage> stages = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();

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

    private void settle() throws Exception {
        var done = new CompletableFuture<Void>();
        fx(
                () -> {
                    new javafx.animation.AnimationTimer() {
                        int pulses;

                        public void handle(long now) {
                            if (++pulses >= 2) {
                                stop();
                                done.complete(null);
                            }
                        }
                    }.start();
                    Platform.requestNextPulse();
                    return null;
                });
        done.get(10, TimeUnit.SECONDS);
        fx(
                () -> {
                    for (var stage : stages)
                        if (stage.isShowing()) {
                            stage.getScene().getRoot().applyCss();
                            stage.getScene().getRoot().layout();
                        }
                    return null;
                });
    }

    @BeforeEach
    void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString());
        Database.initDatabase();
        previousReduced =
                fx(
                        () -> {
                            previousFxHandler =
                                    Thread.currentThread().getUncaughtExceptionHandler();
                            Thread.currentThread()
                                    .setUncaughtExceptionHandler(
                                            (thread, error) -> callbackErrors.add(error));
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
                    for (var popup : popups) popup.hide();
                    for (var stage : stages) stage.close();
                    WorkspaceInteractionMotion.cancelAll();
                    WorkspaceMotion.cancelAll();
                    ThemeService.setReducedMotion(previousReduced);
                    Thread.currentThread().setUncaughtExceptionHandler(previousFxHandler);
                    return null;
                });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
        assertTrue(
                callbackErrors.isEmpty(),
                () -> "Unexpected JavaFX callback errors: " + callbackErrors);
    }

    private Stage show(Parent root) {
        var stage = new Stage();
        stage.setScene(new Scene(root, 600, 400));
        ThemeService.applyTheme(stage.getScene());
        stages.add(stage);
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static HBox chrome() {
        var chrome = new HBox(new Label("Synthetic shop"));
        chrome.getStyleClass().add("header");
        return chrome;
    }

    private static void noSampledImages(Region r) {
        if (r.getBackground() != null) {
            assertTrue(r.getBackground().getImages().isEmpty(), "No raster wallpaper on " + r);
            assertTrue(
                    r.getBackground().getFills().stream()
                            .noneMatch(f -> f.getFill() instanceof ImagePattern),
                    "No sampled backdrop on " + r);
        }
    }

    private static Color ground(ThemeService.Theme theme) {
        return theme == ThemeService.Theme.DARK ? Color.BLACK : Color.web("#f2f2f2");
    }

    @Test
    void workspaceGroundHasUniformOpaquePixelsInBothThemes() throws Exception {
        for (var theme : ThemeService.Theme.values()) {
            var root =
                    fx(
                            () -> {
                                ThemeService.setCurrentTheme(theme);
                                var page = new VBox();
                                page.getStyleClass()
                                        .addAll(
                                                "card",
                                                "app-page",
                                                "workspace-page",
                                                "workspace-sheet");
                                VBox.setVgrow(page, Priority.ALWAYS);
                                var pane = new VBox(page);
                                pane.getStyleClass().add("workspace-shell");
                                show(pane);
                                return pane;
                            });
            settle();
            fx(
                    () -> {
                        noSampledImages(root);
                        var page = (Region) root.getChildren().getFirst();
                        assertEquals(
                                ground(theme),
                                ThemeServiceTest.paint(page),
                                "Full-page surfaces take priority over the card paint");
                        var pixels = root.snapshot(null, null).getPixelReader();
                        for (int y = 24; y < 380; y += 35)
                            for (int x = 24; x < 580; x += 35)
                                assertEquals(
                                        ground(theme),
                                        pixels.getColor(x, y),
                                        "Flat workspace ground for " + theme);
                        return null;
                    });
        }
    }

    @Test
    void chromeKeepsControlsLiveAndNeverSamplesAnImage() throws Exception {
        var root =
                fx(
                        () -> {
                            var action = new Button("Refresh");
                            var calls = new java.util.concurrent.atomic.AtomicInteger();
                            action.setOnAction(e -> calls.incrementAndGet());
                            var top = new HBox(new Label("Synthetic shop"), action);
                            top.getStyleClass().add("header");
                            var pane = new VBox(top);
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return new Object[] {pane, top, action, calls};
                        });
        settle();
        fx(
                () -> {
                    var top = (HBox) root[1];
                    noSampledImages((Region) root[0]);
                    noSampledImages(top);
                    assertEquals(1, top.getOpacity());
                    assertNull(top.getChildren().getFirst().getEffect());
                    assertEquals(1, top.getChildren().getFirst().getOpacity());
                    ((Button) root[2]).fire();
                    assertEquals(1, ((java.util.concurrent.atomic.AtomicInteger) root[3]).get());
                    return null;
                });
    }

    @Test
    void existingInlineAndBoundBackgroundsRemainCallerOwnedAcrossThemeFlips() throws Exception {
        var bound = new SimpleObjectProperty<>(Background.fill(Color.CORNFLOWERBLUE));
        var root =
                fx(
                        () -> {
                            var a = chrome();
                            a.setBackground(Background.fill(Color.DARKORANGE));
                            var b = chrome();
                            b.backgroundProperty().bind(bound);
                            var c = chrome();
                            c.setStyle("-fx-background-color: crimson;");
                            var pane = new VBox(a, b, c);
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        settle();
        fx(
                () -> {
                    assertSame(bound.get(), ((Region) root.getChildren().get(1)).getBackground());
                    assertEquals(
                            Color.CRIMSON,
                            ThemeServiceTest.paint((Region) root.getChildren().get(2)));
                    ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                    root.applyCss();
                    return null;
                });
        settle();
        fx(
                () -> {
                    assertSame(bound.get(), ((Region) root.getChildren().get(1)).getBackground());
                    assertEquals(
                            Color.CRIMSON,
                            ThemeServiceTest.paint((Region) root.getChildren().get(2)));
                    noSampledImages((Region) root.getChildren().getFirst());
                    return null;
                });
    }

    @Test
    void laterCallerReplacementAndBindingRetainOwnershipAfterResize() throws Exception {
        var root =
                fx(
                        () -> {
                            var pane = new VBox(chrome(), chrome());
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        var bound = new SimpleObjectProperty<>(Background.fill(Color.SIENNA));
        fx(
                () -> {
                    var caller = (Region) root.getChildren().getFirst();
                    caller.setStyle("-fx-background-color: #daa520;");
                    ((Region) root.getChildren().getLast()).backgroundProperty().bind(bound);
                    ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                    root.getScene().getWindow().setWidth(740);
                    return null;
                });
        settle();
        fx(
                () -> {
                    var caller = (Region) root.getChildren().getFirst();
                    assertEquals(Color.GOLDENROD, ThemeServiceTest.paint(caller));
                    assertEquals(
                            StyleOrigin.INLINE,
                            ((StyleableProperty<?>) caller.backgroundProperty()).getStyleOrigin());
                    assertSame(
                            bound.get(), ((Region) root.getChildren().getLast()).getBackground());
                    return null;
                });
    }

    @Test
    void callerAdoptingTheSameBackgroundKeepsItsValueWhenWindowHides() throws Exception {
        var root =
                fx(
                        () -> {
                            var pane = new VBox(chrome());
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        settle();
        fx(
                () -> {
                    var chrome = (Region) root.getChildren().getFirst();
                    var same = chrome.getBackground();
                    chrome.setBackground(same);
                    root.getScene().getWindow().hide();
                    assertSame(
                            same,
                            chrome.getBackground(),
                            "Hiding the window must not replace caller-adopted paint");
                    assertEquals(
                            StyleOrigin.USER,
                            ((StyleableProperty<?>) chrome.backgroundProperty()).getStyleOrigin());
                    return null;
                });
    }

    @Test
    void reentrantCallerBindingWinsAcrossThemeAndWindowChanges() throws Exception {
        var custom = new SimpleObjectProperty<>(Background.fill(Color.DARKORANGE));
        var root =
                fx(
                        () -> {
                            var chrome = chrome();
                            chrome.backgroundProperty()
                                    .addListener(
                                            (observable, before, after) -> {
                                                if (!chrome.backgroundProperty().isBound()
                                                        && after != null)
                                                    chrome.backgroundProperty().bind(custom);
                                            });
                            chrome.setBackground(Background.fill(Color.SKYBLUE));
                            var pane = new VBox(chrome);
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        settle();
        try {
            fx(
                    () -> {
                        var chrome = (Region) root.getChildren().getFirst();
                        assertTrue(chrome.backgroundProperty().isBound());
                        assertSame(custom.get(), chrome.getBackground());
                        ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                        root.getScene().getWindow().setWidth(740);
                        return null;
                    });
            settle();
            fx(
                    () -> {
                        var chrome = (Region) root.getChildren().getFirst();
                        assertTrue(chrome.backgroundProperty().isBound());
                        assertSame(custom.get(), chrome.getBackground());
                        root.getScene().getWindow().hide();
                        assertTrue(chrome.backgroundProperty().isBound());
                        assertSame(custom.get(), chrome.getBackground());
                        return null;
                    });
        } finally {
            fx(
                    () -> {
                        ((Region) root.getChildren().getFirst()).backgroundProperty().unbind();
                        return null;
                    });
        }
    }

    @Test
    void dynamicChromeAndTablesRemainFlatAfterAttachDetachAndThemeFlip() throws Exception {
        var root =
                fx(
                        () -> {
                            var pane = new VBox();
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        var parts =
                fx(
                        () -> {
                            var c = chrome();
                            var table = new TableView<String>();
                            table.getItems().setAll("A", "B");
                            root.getChildren().setAll(c, table);
                            root.applyCss();
                            root.layout();
                            return new Region[] {c, table};
                        });
        settle();
        fx(
                () -> {
                    for (var r : parts) {
                        noSampledImages(r);
                        assertEquals(1, r.getOpacity());
                    }
                    root.getChildren().remove(parts[0]);
                    ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                    root.getChildren().add(0, parts[0]);
                    root.applyCss();
                    root.layout();
                    return null;
                });
        settle();
        fx(
                () -> {
                    for (var r : parts) noSampledImages(r);
                    assertEquals(List.of("A", "B"), ((TableView<?>) parts[1]).getItems());
                    return null;
                });
    }

    @Test
    void popupAndOwnedReportStayOpaqueWithoutSamplingOwner() throws Exception {
        var root =
                fx(
                        () -> {
                            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                            var pane = new VBox();
                            pane.getStyleClass().add("workspace-shell");
                            show(pane);
                            return pane;
                        });
        var surfaces =
                fx(
                        () -> {
                            var report = new DialogPane();
                            report.setContent(new Label("Synthetic report"));
                            var stage = new Stage();
                            stage.initOwner(root.getScene().getWindow());
                            stage.setScene(new Scene(report, 300, 200));
                            stages.add(stage);
                            ThemeService.applyTheme(stage.getScene());
                            stage.show();
                            var menu = new VBox(new Label("Synthetic menu"));
                            var popup = new Popup();
                            popup.getContent().add(menu);
                            popups.add(popup);
                            ThemeService.applyTheme(popup.getScene());
                            popup.show(
                                    root.getScene().getWindow(),
                                    root.getScene().getWindow().getX() + 20,
                                    root.getScene().getWindow().getY() + 40);
                            return new Region[] {report, menu};
                        });
        settle();
        fx(
                () -> {
                    for (var r : surfaces) {
                        r.applyCss();
                        r.layout();
                        noSampledImages(r);
                        assertEquals(1, r.getOpacity());
                    }
                    return null;
                });
    }

    @Test
    void startupSceneKeepsItsOwnStylingAndFill() throws Exception {
        fx(
                () -> {
                    var startup = new VBox(new Label("Startup"));
                    var scene = new Scene(startup, 300, 200, Color.AZURE);
                    scene.getProperties().put(ThemeService.STARTUP_SCENE, true);
                    var stage = new Stage();
                    stage.setScene(scene);
                    stages.add(stage);
                    ThemeService.applyTheme(scene);
                    stage.show();
                    assertTrue(scene.getStylesheets().isEmpty());
                    assertEquals(Color.AZURE, scene.getFill());
                    return null;
                });
    }
}
