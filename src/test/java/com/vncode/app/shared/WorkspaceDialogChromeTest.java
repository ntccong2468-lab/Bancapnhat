package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDialogChromeTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private Thread.UncaughtExceptionHandler previousHandler;
    private final java.util.List<Throwable> callbackErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ArrayList<Dialog<?>> dialogs = new ArrayList<>();
    private final ArrayList<Stage> nativeStages = new ArrayList<>();
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
    @BeforeEach void setup() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir"); System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
        fx(() -> {
            previousHandler = Thread.currentThread().getUncaughtExceptionHandler(); Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> callbackErrors.add(failure));
            ThemeService.initializeAppearance(); previousReduced = ThemeService.isReducedMotion(); ThemeService.setReducedMotion(true); return null;
        });
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for (var dialog : dialogs) { dialog.setOnCloseRequest(null); dialog.close(); }
            for (var stage : nativeStages) stage.close();
            ThemeService.setReducedMotion(previousReduced); Thread.currentThread().setUncaughtExceptionHandler(previousHandler); return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir"); else System.setProperty("vncode.appdata.dir", previousProfile);
        assertTrue(callbackErrors.isEmpty(), () -> "Unexpected JavaFX callbacks: " + callbackErrors);
    }
    private <R> Dialog<R> dialog() { var dialog = new Dialog<R>(); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialogs.add(dialog); return dialog; }

    @Test void anAppReportHasTransparentOuterCornersWithoutReplacingItsDialogPane() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); var pane = dialog.getDialogPane(); var header = new Label("Original header"); var content = new Label("Synthetic report");
            pane.setHeader(header); pane.setContent(content); AlertService.applyTheme(dialog); dialog.show(); pane.applyCss(); pane.layout();
            assertEquals(StageStyle.TRANSPARENT, ((Stage) pane.getScene().getWindow()).getStyle(), "App-owned reports need rounded floating outer chrome");
            assertEquals(Color.TRANSPARENT, pane.getScene().getFill(), "The Scene must expose the rounded outer corners");
            assertSame(pane, dialog.getDialogPane()); assertSame(pane, pane.getScene().getRoot()); assertSame(header, pane.getHeader()); assertSame(content, pane.getContent());
            assertTrue(pane.getScene().snapshot(null).getPixelReader().getColor(0, 0).getOpacity() < .1, "Rounded app window corners must actually be transparent pixels");
            return null;
        });
    }

    @Test void closeUsesTheExistingVetoResultConverterAndLifecycleHandlers() throws Exception {
        fx(() -> {
            var dialog = this.<String>dialog(); var requests = new AtomicInteger(); var shown = new AtomicInteger(); var hidden = new AtomicInteger();
            dialog.setResultConverter(type -> type == ButtonType.CLOSE ? "closed-by-original-converter" : "other");
            dialog.setOnCloseRequest(event -> { if (requests.incrementAndGet() == 1) event.consume(); });
            dialog.setOnShown(event -> shown.incrementAndGet()); dialog.setOnHidden(event -> hidden.incrementAndGet());
            AlertService.applyTheme(dialog); dialog.show(); var close = close(dialog);
            close.fire(); assertTrue(dialog.isShowing()); assertEquals(1, requests.get()); assertEquals(0, hidden.get());
            close.fire(); assertFalse(dialog.isShowing()); assertEquals(2, requests.get()); assertEquals(1, shown.get()); assertEquals(1, hidden.get());
            assertEquals("closed-by-original-converter", dialog.getResult()); return null;
        });
    }

    @Test void showAndWaitRetainsOwnerModalityAndOriginalButtonResult() throws Exception {
        fx(() -> {
            var owner = new Stage(); owner.setScene(new Scene(new VBox(), 500, 350)); nativeStages.add(owner); owner.show();
            var dialog = this.<String>dialog(); dialog.initOwner(owner); dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setResultConverter(type -> type == ButtonType.CLOSE ? "cancelled" : "unexpected");
            AlertService.applyTheme(dialog); dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> Platform.runLater(() -> close(dialog).fire()));
            assertEquals("cancelled", dialog.showAndWait().orElseThrow()); assertSame(owner, dialog.getOwner()); assertEquals(Modality.WINDOW_MODAL, dialog.getModality()); return null;
        });
    }

    @Test void titleUpdatesAndExistingHeaderContentAndButtonsRemainLive() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); var pane = dialog.getDialogPane(); var original = new Button("Original action"); var called = new AtomicInteger(); original.setOnAction(event -> called.incrementAndGet());
            var content = new VBox(original); var header = new Label("Header"); pane.setContent(content); pane.setHeader(header);
            var closeType = pane.getButtonTypes().getFirst(); AlertService.applyTheme(dialog); AlertService.applyTheme(dialog);
            dialog.setTitle("New title"); dialog.show(); pane.applyCss(); pane.layout();
            assertEquals("New title", ((Label) pane.lookup(".workspace-window-title")).getText()); assertSame(content, pane.getContent()); assertSame(header, pane.getHeader());
            assertSame(closeType, pane.getButtonTypes().getFirst()); assertEquals(1, pane.getChildren().stream().filter(node -> node.getStyleClass().contains("workspace-window-chrome")).count());
            original.fire(); assertEquals(1, called.get()); assertTrue(dialog.isShowing()); assertFalse(close(dialog).isDefaultButton()); assertFalse(close(dialog).isCancelButton());
            assertFalse(close(dialog).getAccessibleText().isBlank()); return null;
        });
    }

    @Test void explicitReportDimensionsKeepTheirContentAreaAndDoNotGrowOnReopen() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); var pane = dialog.getDialogPane(); pane.setContent(new Label("Synthetic content")); pane.setPrefSize(400, 300);
            AlertService.applyTheme(dialog); dialog.show(); pane.applyCss(); pane.layout();
            assertEquals(424, pane.getPrefWidth()); assertEquals(350, pane.getPrefHeight());
            assertTrue(pane.getContent().getBoundsInParent().getMinY() >= 56, "The unmanaged titlebar must not overlap the content");
            var chrome = (Region) pane.lookup(".workspace-window-chrome"); assertEquals(32, chrome.getHeight()); assertEquals(16, chrome.getLayoutY());
            dialog.close(); dialog.show(); assertEquals(424, pane.getPrefWidth()); assertEquals(350, pane.getPrefHeight()); return null;
        });
    }

    @Test void onlyResizableDialogsAcceptEdgeResizeAndStayInsideTheScreen() throws Exception {
        fx(() -> {
            for (boolean resizable : new boolean[]{false, true}) {
                var dialog = this.<ButtonType>dialog(); dialog.setResizable(resizable); dialog.getDialogPane().setPrefSize(400, 300); AlertService.applyTheme(dialog); dialog.show();
                var pane = dialog.getDialogPane(); pane.applyCss(); pane.layout(); var stage = (Stage) pane.getScene().getWindow(); double width = stage.getWidth();
                var point = pane.localToScreen(pane.getWidth() - 12, pane.getHeight() / 2);
                mouse(pane, MouseEvent.MOUSE_PRESSED, pane.getWidth() - 12, pane.getHeight() / 2, point.getX(), point.getY());
                mouse(pane, MouseEvent.MOUSE_DRAGGED, pane.getWidth() - 12, pane.getHeight() / 2, point.getX() + 80, point.getY());
                mouse(pane, MouseEvent.MOUSE_RELEASED, pane.getWidth() - 12, pane.getHeight() / 2, point.getX() + 80, point.getY());
                if (resizable) assertTrue(stage.getWidth() > width + 40); else assertEquals(width, stage.getWidth());
                var screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).getFirst().getVisualBounds();
                assertTrue(stage.getWidth() <= screen.getWidth()); assertTrue(stage.getHeight() <= screen.getHeight()); dialog.close();
            }
            return null;
        });
    }

    @Test void paneReplacementKeepsTheSameDialogAndDoesNotRetainOldChrome() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); var old = dialog.getDialogPane(); AlertService.applyTheme(dialog);
            var next = new DialogPane(); next.getButtonTypes().add(ButtonType.CLOSE); next.setContent(new Label("Replacement")); dialog.setDialogPane(next); AlertService.applyTheme(dialog); dialog.show();
            assertSame(next, next.getScene().getRoot()); assertTrue(old.getChildren().stream().noneMatch(node -> node.getStyleClass().contains("workspace-window-chrome")));
            assertEquals(1, next.getChildren().stream().filter(node -> node.getStyleClass().contains("workspace-window-chrome")).count()); assertNotNull(close(dialog)); return null;
        });
    }

    @Test void returningToAPreviouslyUsedPaneDoesNotGrowItsDimensions() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); var first = dialog.getDialogPane(); first.setPrefSize(400, 300); AlertService.applyTheme(dialog);
            dialog.show(); dialog.close(); var second = new DialogPane(); second.getButtonTypes().add(ButtonType.CLOSE);
            dialog.setDialogPane(second); assertEquals(400, first.getPrefWidth()); assertEquals(300, first.getPrefHeight());
            dialog.setDialogPane(first); dialog.show(); assertEquals(424, first.getPrefWidth()); assertEquals(350, first.getPrefHeight()); return null;
        });
    }

    @Test void aCallerSceneFillChangeAfterInstallSurvivesShowAndReopen() throws Exception {
        fx(() -> {
            var dialog = this.<ButtonType>dialog(); AlertService.applyTheme(dialog); var scene = dialog.getDialogPane().getScene();
            scene.setFill(Color.CORNFLOWERBLUE); dialog.show(); assertEquals(Color.CORNFLOWERBLUE, scene.getFill());
            dialog.close(); dialog.show(); assertEquals(Color.CORNFLOWERBLUE, scene.getFill()); return null;
        });
    }

    @Test void ordinaryStagesAndPaneOnlyThemingKeepNativeDecorations() throws Exception {
        fx(() -> {
            var stage = new Stage(); stage.setScene(new Scene(new VBox(), 300, 200)); nativeStages.add(stage); ThemeService.applyTheme(stage.getScene()); stage.show(); assertEquals(StageStyle.DECORATED, stage.getStyle());
            var dialog = this.<ButtonType>dialog(); AlertService.applyTheme(dialog.getDialogPane()); dialog.show(); assertEquals(StageStyle.DECORATED, ((Stage) dialog.getDialogPane().getScene().getWindow()).getStyle());
            assertNull(dialog.getDialogPane().lookup(".workspace-window-chrome")); return null;
        });
    }

    @Test void customSceneFillAndBoundCallerPaddingStayNative() throws Exception {
        fx(() -> {
            var custom = this.<ButtonType>dialog(); custom.getDialogPane().getScene().setFill(Color.CORNFLOWERBLUE); AlertService.applyTheme(custom);
            custom.show(); assertEquals(StageStyle.DECORATED, ((Stage) custom.getDialogPane().getScene().getWindow()).getStyle()); assertEquals(Color.CORNFLOWERBLUE, custom.getDialogPane().getScene().getFill());
            var bound = this.<ButtonType>dialog(); var padding = new SimpleObjectProperty<>(new Insets(18)); bound.getDialogPane().paddingProperty().bind(padding);
            try { AlertService.applyTheme(bound); bound.show(); assertEquals(StageStyle.DECORATED, ((Stage) bound.getDialogPane().getScene().getWindow()).getStyle()); assertTrue(bound.getDialogPane().paddingProperty().isBound()); }
            finally { bound.getDialogPane().paddingProperty().unbind(); }
            return null;
        });
    }

    private static Button close(Dialog<?> dialog) { return (Button) dialog.getDialogPane().lookup(".workspace-window-close"); }
    private static void mouse(Region target, javafx.event.EventType<MouseEvent> type, double x, double y, double screenX, double screenY) {
        target.fireEvent(new MouseEvent(type, x, y, screenX, screenY, MouseButton.PRIMARY, 1, false, false, false, false,
                type != MouseEvent.MOUSE_RELEASED, false, false, true, false, true, new PickResult(target, x, y)));
    }
}
