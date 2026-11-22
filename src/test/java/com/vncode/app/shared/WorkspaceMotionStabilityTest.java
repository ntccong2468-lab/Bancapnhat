package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Geometry and lifecycle regressions observable in real JavaFX pulses, using synthetic data only. */
class WorkspaceMotionStabilityTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final ArrayList<Stage> stages = new ArrayList<>();

    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    @BeforeEach void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString());
        Database.initDatabase();
        previousReduced = fx(() -> {
            ThemeService.initializeAppearance();
            boolean previous = ThemeService.isReducedMotion();
            ThemeService.setReducedMotion(false);
            return previous;
        });
    }

    @AfterEach void restore() throws Exception {
        fx(() -> {
            for (var stage : stages) stage.close();
            WorkspaceMotion.cancelAll();
            WorkspaceInteractionMotion.cancelAll();
            ThemeService.setReducedMotion(previousReduced);
            return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private Stage show(StackPane root) {
        var stage = new Stage();
        stage.setScene(new Scene(root, 500, 320));
        ThemeService.applyTheme(stage.getScene());
        stages.add(stage);
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static void press(javafx.scene.Node node) {
        Event.fireEvent(node, new MouseEvent(node, node, MouseEvent.MOUSE_PRESSED,
                8, 8, 8, 8, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false,
                false, false, true, new PickResult(node, 8, 8)));
    }

    @Test void routesAndTabsKeepTextGeometryAtItsApplicationSizeFromTheFirstFrame() throws Exception {
        fx(() -> {
            var input = new TextField("Synthetic draft");
            var page = new StackPane(input);
            show(new StackPane(page));
            WorkspaceMotion.cancelAll();
            input.requestFocus(); input.selectRange(2, 7);
            for (var context : NavigationContext.values()) {
                var before = page.getBoundsInParent();
                WorkspaceMotion.navigation(page, context);
                assertEquals(before.getWidth(), page.getBoundsInParent().getWidth(), .001, context.toString());
                assertEquals(before.getHeight(), page.getBoundsInParent().getHeight(), .001, context.toString());
                assertEquals("Synthetic draft", input.getText());
                assertEquals(2, input.getSelection().getStart()); assertEquals(7, input.getSelection().getEnd());
                assertSame(input, page.getScene().getFocusOwner());
                WorkspaceMotion.stop(page);
            }
            return null;
        });
    }

    @Test void nativeWindowRootsShowAtFullSizeAndOpacityWithoutAnEntranceScale() throws Exception {
        fx(() -> {
            var root = new StackPane(new TextField("Synthetic dialog draft"));
            show(root); WorkspaceMotion.cancelAll();
            var before = root.getBoundsInParent();
            WorkspaceMotion.appearanceOpaque(root, false, null);
            assertEquals(before.getWidth(), root.getBoundsInParent().getWidth(), .001);
            assertEquals(before.getHeight(), root.getBoundsInParent().getHeight(), .001);
            assertEquals(1, root.getOpacity());
            assertFalse(WorkspaceMotion.isAnimating(root), "Native scene boundaries cannot reveal an undersized page inside their window");
            return null;
        });
    }

    @Test void repeatedSiblingNavigationDoesNotSnapshotTheOperationalPage() throws Exception {
        fx(() -> {
            var host = new StackPane(); show(new StackPane(host));
            try (var boundary = new MotionBoundary()) {
                boundary.replace(host, new StackPane(), NavigationContext.SIBLING);
                host.layout(); WorkspaceMotion.cancelAll();
                var incoming = new StackPane(new Button("Ready"));
                boundary.replace(host, incoming, NavigationContext.SIBLING);
                assertEquals(java.util.List.of(incoming), host.getChildren(),
                        "Repeated sidebar actions need no full-workspace framebuffer");
                assertEquals(0, incoming.getTranslateX()); assertEquals(0, incoming.getTranslateY());
            }
            return null;
        });
    }

    @Test void rapidHierarchyNavigationCannotRecaptureAnOlderRouteThroughATransparentPage() throws Exception {
        fx(() -> {
            var host = new StackPane(); show(new StackPane(host));
            try (var boundary = new MotionBoundary()) {
                var first = new StackPane(); first.setStyle("-fx-background-color: #c02020");
                boundary.replace(host, first, NavigationContext.SIBLING);
                host.applyCss(); host.layout(); WorkspaceMotion.cancelAll();
                var transparent = new StackPane();
                boundary.replace(host, transparent, NavigationContext.FORWARD);
                host.layout();
                var stale = host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                boundary.replace(host, new StackPane(), NavigationContext.BACK);
                var visual = host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                assertNull(stale.getImage());
                assertEquals(0, visual.getImage().getPixelReader().getArgb(250, 160) >>> 24,
                        "Only the current transparent page may enter the next snapshot; the red route has already left");
            }
            return null;
        });
    }

    @Test void aResizeSettlesTheLiveRouteAndDiscardsItsOldSizeBitmap() throws Exception {
        fx(() -> {
            var host = new StackPane(); show(new StackPane(host));
            try (var boundary = new MotionBoundary()) {
                boundary.replace(host, new StackPane(), NavigationContext.SIBLING);
                host.layout(); WorkspaceMotion.cancelAll();
                var incoming = new StackPane(new TextField("Draft survives resize"));
                boundary.replace(host, incoming, NavigationContext.FORWARD);
                var visual = host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                host.resize(620, 370);
                assertNull(visual.getImage(), "A snapshot from the old viewport must not be stretched over the resized route");
                assertEquals(java.util.List.of(incoming), host.getChildren());
                assertFalse(WorkspaceMotion.isAnimating(incoming));
                assertEquals(0, incoming.getTranslateX()); assertEquals(1, incoming.getOpacity());
                assertNull(host.getClip());
            }
            return null;
        });
    }

    @Test void reducedClosingStartsFullyVisibleAndDoesNotBecomeAnEntrance() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);
            var panel = new StackPane(); panel.setOpacity(.9);
            var calls = new AtomicInteger();
            WorkspaceMotion.drawer(panel, false, calls::incrementAndGet);
            assertEquals(.9, panel.getOpacity(), "Closing feedback starts at the existing opacity, rather than fading it back in");
            assertEquals(0, panel.getTranslateX()); assertEquals(0, panel.getTranslateY());
            WorkspaceMotion.cancelAll();
            assertEquals(1, calls.get()); assertEquals(.9, panel.getOpacity());
            return null;
        });
    }

    @Test void virtualizedCellsKeepTheirReadingGeometryWhenPressed() throws Exception {
        fx(() -> {
            var list = new ListView<String>(); list.getItems().setAll("Synthetic first", "Synthetic second");
            show(new StackPane(list));
            var cell = list.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast).filter(value -> "Synthetic first".equals(value.getItem())).findFirst().orElseThrow();
            press(cell);
            assertFalse(WorkspaceInteractionMotion.hasFeedback(cell));
            assertTrue(cell.getTransforms().isEmpty(), "Native row selection must not shrink the row text or cells");
            return null;
        });
    }

    @Test void buttonReleaseNeverGrowsBeyondItsRestingGeometryDuringActualPulses() throws Exception {
        var done = new CompletableFuture<Void>();
        var timer = new AnimationTimer[1];
        var ready = new CompletableFuture<Button>();
        try {
            fx(() -> {
                var button = new Button("Synthetic action");
                show(new StackPane(button)).requestFocus();
                new AnimationTimer() {
                    private int pulses;
                    @Override public void handle(long now) {
                        if (++pulses == 4) { stop(); ready.complete(button); }
                    }
                }.start();
                return null;
            });
            var button = ready.get(5, TimeUnit.SECONDS);
            fx(() -> {
                var calls = new AtomicInteger(); button.setOnAction(event -> calls.incrementAndGet());
                button.fire();
                assertEquals(1, calls.get());
                assertTrue(WorkspaceInteractionMotion.hasFeedback(button));
                timer[0] = new AnimationTimer() {
                    private int sampled;
                    @Override public void handle(long now) {
                        try {
                            sampled++;
                            assertTrue(button.getLocalToParentTransform().getMxx() <= 1.000001,
                                    "A local press can return to rest without a growing rebound");
                            if (!WorkspaceInteractionMotion.hasFeedback(button)) {
                                stop(); assertTrue(sampled > 1, "Observe real feedback pulses, not a focus-loss cancellation");
                                assertEquals(1, calls.get()); done.complete(null);
                            }
                        } catch (Throwable failure) { stop(); done.completeExceptionally(failure); }
                    }
                };
                timer[0].start();
                return null;
            });
            done.get(5, TimeUnit.SECONDS);
        } finally { fx(() -> { if (timer[0] != null) timer[0].stop(); return null; }); }
    }
}
