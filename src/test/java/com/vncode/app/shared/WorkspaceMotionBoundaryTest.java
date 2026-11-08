package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMotionBoundaryTest {
    @TempDir Path profile;
    private boolean savedReduced;

    @BeforeAll static void start() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    @BeforeEach void initialize() throws Exception {
        System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
        savedReduced = fx(() -> { ThemeService.initializeAppearance(); return ThemeService.isReducedMotion(); });
    }
    @AfterEach void restore() throws Exception {
        fx(() -> { WorkspaceMotion.cancelAll(); ThemeService.setReducedMotion(savedReduced); return null; });
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void interruptionRestoresApplicationTransformsAndNeverTouchesSharedChrome() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(false);
            var header = new StackPane(); var sidebar = new StackPane();
            var first = new StackPane(); var second = new StackPane();
            first.setTranslateX(3); first.setTranslateY(4); first.setOpacity(.9);
            var host = new StackPane(header, sidebar, first, second); new Scene(host);
            var boundary = new MotionBoundary();
            boundary.show(first, NavigationContext.FORWARD);
            assertTrue(WorkspaceMotion.isAnimating(first));
            boundary.show(second, NavigationContext.BACK);
            assertFalse(WorkspaceMotion.isAnimating(first));
            assertEquals(3, first.getTranslateX()); assertEquals(4, first.getTranslateY()); assertEquals(.9, first.getOpacity());
            assertFalse(WorkspaceMotion.isAnimating(header)); assertFalse(WorkspaceMotion.isAnimating(sidebar));
            boundary.close();
            assertFalse(WorkspaceMotion.isAnimating(second));
            assertEquals(0, second.getTranslateX()); assertEquals(0, second.getTranslateY()); assertEquals(1, second.getOpacity());
            return null;
        });
    }

    @Test void removalCancelsWithoutRunningAStaleCompletion() throws Exception {
        fx(() -> {
            var panel = new StackPane(); var host = new StackPane(panel); new Scene(host);
            var completions = new AtomicInteger();
            WorkspaceMotion.drawer(panel, false, completions::incrementAndGet);
            host.getChildren().remove(panel);
            assertFalse(WorkspaceMotion.isAnimating(panel)); assertEquals(0, completions.get());
            assertEquals(0, panel.getTranslateX()); assertEquals(1, panel.getOpacity());
            return null;
        });
    }

    @Test void reducedMotionKeepsFeedbackWithoutSpatialTransformsAndFinishesClosingState() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);
            var panel = new StackPane(); var completions = new AtomicInteger();
            WorkspaceMotion.navigation(panel, NavigationContext.FORWARD);
            assertEquals(0, panel.getTranslateX()); assertEquals(0, panel.getTranslateY());
            assertTrue(panel.getTransforms().isEmpty()); assertTrue(panel.getOpacity() > 0);
            WorkspaceMotion.drawer(panel, false, completions::incrementAndGet);
            WorkspaceMotion.cancelAll();
            assertEquals(1, completions.get()); assertEquals(1, panel.getOpacity());
            assertFalse(WorkspaceMotion.isAnimating(panel));
            return null;
        });
    }

    @Test void tabsCancelTheirPreviousContentAndLeaveRowsUnanimated() throws Exception {
        fx(() -> {
            var first = new StackPane(); var table = new TableView<String>();
            table.getItems().add("synthetic"); var second = new StackPane(table);
            var tabs = new TabPane(new Tab("First", first), new Tab("Second", second));
            new Scene(new StackPane(tabs)); WorkspaceMotion.install(tabs);
            tabs.getSelectionModel().select(1);
            assertTrue(WorkspaceMotion.isAnimating(second)); assertFalse(WorkspaceMotion.isAnimating(table));
            tabs.getSelectionModel().select(0);
            assertFalse(WorkspaceMotion.isAnimating(second)); assertTrue(WorkspaceMotion.isAnimating(first));
            assertEquals(0, first.getTranslateX()); assertEquals(0, first.getTranslateY());
            WorkspaceMotion.cancelAll(); return null;
        });
    }

    @Test void reducedDrawerNaturallyCompletesOnceAndRestoresBaseline() throws Exception {
        var completed = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var panel = new StackPane[1]; var stage = new Stage[1];
        try {
            fx(() -> {
                ThemeService.setReducedMotion(true);
                panel[0] = new StackPane(); panel[0].setOpacity(.9); panel[0].setTranslateX(2);
                stage[0] = new Stage(); stage[0].setScene(new Scene(new StackPane(panel[0]), 320, 200)); stage[0].show();
                WorkspaceMotion.drawer(panel[0], false, () -> { calls.incrementAndGet(); completed.complete(null); });
                return null;
            });
            completed.get(5, TimeUnit.SECONDS);
            fx(() -> {
                assertEquals(1, calls.get()); assertFalse(WorkspaceMotion.isAnimating(panel[0]));
                assertEquals(.9, panel[0].getOpacity()); assertEquals(2, panel[0].getTranslateX());
                WorkspaceMotion.stop(panel[0]); assertEquals(1, calls.get()); return null;
            });
        } finally { fx(() -> { if (stage[0] != null) stage[0].close(); return null; }); }
    }

    @Test void hidingPopupRemovesItsTemporaryTransformWithoutReplacingOwnerHandlers() throws Exception {
        fx(() -> {
            var owner = new Stage(); owner.setScene(new Scene(new StackPane(), 320, 200)); owner.show();
            var popup = new Popup(); popup.getContent().add(new StackPane());
            var hidden = new AtomicInteger(); popup.setOnHidden(event -> hidden.incrementAndGet());
            try {
                WorkspaceMotion.install(popup.getScene()); popup.show(owner, 20, 20);
                var root = popup.getScene().getRoot();
                assertTrue(WorkspaceMotion.isAnimating(root));
                popup.hide();
                assertFalse(WorkspaceMotion.isAnimating(root)); assertTrue(root.getTransforms().isEmpty());
                assertEquals(1, root.getOpacity()); assertEquals(1, hidden.get());
            } finally { popup.hide(); owner.close(); }
            return null;
        });
    }
}
