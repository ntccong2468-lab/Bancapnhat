package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Popup;
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

class WorkspaceInteractionMotionTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final ArrayList<Stage> windows = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();

    @BeforeAll static void startToolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work);
        Platform.runLater(future);
        return future.get(15, TimeUnit.SECONDS);
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
            for (var popup : popups) popup.hide();
            for (int i = windows.size() - 1; i >= 0; i--) windows.get(i).close();
            WorkspaceInteractionMotion.cancelAll();
            WorkspaceMotion.cancelAll();
            ThemeService.setReducedMotion(previousReduced);
            return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private Stage show(StackPane root, Stage owner) {
        var stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setScene(new Scene(root, owner == null ? 640 : 360, owner == null ? 440 : 240));
        ThemeService.applyTheme(stage.getScene());
        windows.add(stage);
        stage.show();
        return stage;
    }

    private static void press(javafx.scene.Node node) {
        Event.fireEvent(node, new MouseEvent(node, node, MouseEvent.MOUSE_PRESSED,
                8, 8, 8, 8, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false,
                false, false, true, new PickResult(node, 8, 8)));
    }

    @Test void repeatedPressHasOneOwnedTransformAndDisablingRestoresOnlyThatTransform() throws Exception {
        fx(() -> {
            var button = new Button("Action"); var rotation = new Rotate(3);
            button.getTransforms().add(rotation);
            show(new StackPane(button), null);
            press(button); press(button);
            assertEquals(1, button.getTransforms().stream().filter(Scale.class::isInstance).count());
            button.setDisable(true);
            assertFalse(WorkspaceInteractionMotion.hasFeedback(button));
            assertEquals(java.util.List.of(rotation), button.getTransforms());
            press(button);
            assertFalse(WorkspaceInteractionMotion.hasFeedback(button));
            return null;
        });
    }

    @Test void editableTextAndSlidersKeepTheirInputAndDragGeometry() throws Exception {
        fx(() -> {
            var text = new TextField("Synthetic text"); text.selectRange(2, 7);
            var slider = new Slider(0, 100, 38);
            show(new StackPane(new javafx.scene.layout.VBox(text, slider)), null);
            press(text); press(slider);
            assertFalse(WorkspaceInteractionMotion.hasFeedback(text));
            assertFalse(WorkspaceInteractionMotion.hasFeedback(slider));
            assertTrue(text.getTransforms().isEmpty()); assertTrue(slider.getTransforms().isEmpty());
            assertEquals(38, slider.getValue());
            assertEquals("Synthetic text", text.getText());
            return null;
        });
    }

    @Test void sceneRootReplacementCancelsTheOldControlAndCoversTheNewRoot() throws Exception {
        fx(() -> {
            var first = new Button("First"); var stage = show(new StackPane(first), null);
            press(first); assertTrue(WorkspaceInteractionMotion.hasFeedback(first));
            var second = new Button("Second"); stage.getScene().setRoot(new StackPane(second));
            assertFalse(WorkspaceInteractionMotion.hasFeedback(first));
            assertTrue(first.getTransforms().isEmpty());
            press(second); assertTrue(WorkspaceInteractionMotion.hasFeedback(second));
            return null;
        });
    }

    @Test void nativeCellsKeepTheirReadingGeometryWhenPressedAndRecycled() throws Exception {
        fx(() -> {
            var list = new ListView<String>(); list.getItems().setAll("First", "Second");
            list.setCellFactory(ignored -> new RecycledCell());
            show(new StackPane(list), null); list.applyCss(); list.layout();
            var cell = list.lookupAll(".list-cell").stream().filter(RecycledCell.class::isInstance)
                    .map(RecycledCell.class::cast).filter(value -> "First".equals(value.getItem())).findFirst().orElseThrow();
            press(cell); assertFalse(WorkspaceInteractionMotion.hasFeedback(cell));
            assertTrue(cell.getTransforms().isEmpty());
            cell.reuse("Reused");
            assertFalse(WorkspaceInteractionMotion.hasFeedback(cell));
            assertTrue(cell.getTransforms().isEmpty());
            return null;
        });
    }

    private static final class RecycledCell extends ListCell<String> {
        void reuse(String value) { updateItem(value, false); }
        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty); setText(empty ? "" : value);
        }
    }

    @Test void aReadOnlyReportClosesImmediatelyAndItsVisualCopyDoesNotReceiveInput() throws Exception {
        fx(() -> {
            var owner = show(new StackPane(), null);
            var report = show(new StackPane(new Label("Synthetic report")), owner);
            var hidden = new AtomicInteger(); report.setOnHidden(event -> hidden.incrementAndGet());
            report.hide();
            assertFalse(report.isShowing()); assertEquals(1, hidden.get());
            assertEquals(1, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            assertTrue(WorkspaceInteractionMotion.hasSnapshotGhost(owner.getScene()));
            var ghost = ((StackPane) owner.getScene().getRoot()).getChildren().getLast();
            assertTrue(ghost.isMouseTransparent()); assertFalse(ghost.isManaged()); assertFalse(ghost.isFocusTraversable());
            owner.hide();
            assertEquals(0, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            return null;
        });
    }

    @Test void aReportWithAnEditableSearchOrPasswordClosesWithGeometryWithoutRetainingItsContents() throws Exception {
        fx(() -> {
            var owner = show(new StackPane(), null);
            var password = new PasswordField(); password.setText("synthetic-private-value");
            var report = show(new StackPane(new javafx.scene.layout.VBox(new TextField("Search"), password)), owner);
            var hidden = new AtomicInteger(); report.setOnHidden(event -> { password.clear(); hidden.incrementAndGet(); });
            report.hide();
            assertFalse(report.isShowing()); assertEquals(1, hidden.get()); assertEquals("", password.getText());
            assertEquals(1, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            assertFalse(WorkspaceInteractionMotion.hasSnapshotGhost(owner.getScene()));
            return null;
        });
    }

    @Test void reducedMotionImmediatelyRemovesCurrentFeedbackAndClosingCopies() throws Exception {
        fx(() -> {
            var button = new Button("Action"); var owner = show(new StackPane(button), null);
            var report = show(new StackPane(new Label("Report")), owner);
            report.hide(); press(button);
            assertEquals(1, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            assertTrue(WorkspaceInteractionMotion.hasFeedback(button));
            ThemeService.setReducedMotion(true);
            assertEquals(0, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            assertFalse(WorkspaceInteractionMotion.hasFeedback(button)); assertTrue(button.getTransforms().isEmpty());
            press(button); assertFalse(WorkspaceInteractionMotion.hasFeedback(button));
            return null;
        });
    }

    @Test void aShopContextChangeInvalidatesOpenAndAlreadyClosingReportCopies() throws Exception {
        fx(() -> {
            var owner = show(new StackPane(), null);
            var first = show(new StackPane(new Label("First shop report")), owner);
            var second = show(new StackPane(new Label("Another report")), owner);
            first.hide();
            assertEquals(1, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            WorkspaceInteractionMotion.invalidateOwner(owner.getScene());
            assertEquals(0, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()));
            second.hide();
            assertEquals(0, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()), "Old modeless report cannot paint over a new shop");
            return null;
        });
    }

    @Test void replacingAVisibleReportRootOrSceneCannotReauthorizeItsOldShopSession() throws Exception {
        fx(() -> {
            var owner = show(new StackPane(), null);
            for (boolean replaceScene : new boolean[]{false, true}) {
                var report = show(new StackPane(new Label("Old shop report")), owner);
                WorkspaceInteractionMotion.invalidateOwner(owner.getScene());
                var replacement = new StackPane(new Label("Same report, refreshed root"));
                if (replaceScene) report.setScene(new Scene(replacement, 360, 240));
                else report.getScene().setRoot(replacement);
                report.hide();
                assertEquals(0, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()),
                        "A root/Scene refresh is not a new owner authorization: scene=" + replaceScene);
                report.show();
                report.hide();
                assertEquals(1, WorkspaceInteractionMotion.exitGhostCount(owner.getScene()),
                        "A genuinely new showing session may bind the current owner context");
                WorkspaceInteractionMotion.cancelAll();
            }
            return null;
        });
    }

    @Test void anOpenPopupPreservesUserLifecycleHandlersAndRestoresItsRootOnHide() throws Exception {
        fx(() -> {
            var owner = show(new StackPane(), null);
            var popup = new Popup(); popup.getContent().add(new StackPane(new Label("Menu"))); popups.add(popup);
            var shown = new AtomicInteger(); var hidden = new AtomicInteger();
            popup.setOnShown(event -> shown.incrementAndGet()); popup.setOnHidden(event -> hidden.incrementAndGet());
            ThemeService.applyTheme(popup.getScene());
            popup.show(owner, owner.getX() + 10, owner.getY() + 50);
            var root = popup.getScene().getRoot();
            assertFalse(WorkspaceMotion.isAnimating(root)); assertEquals(1, root.getOpacity());
            assertTrue(root.getTransforms().isEmpty()); assertEquals(1, shown.get());
            popup.hide();
            assertFalse(WorkspaceMotion.isAnimating(root)); assertTrue(root.getTransforms().isEmpty());
            assertEquals(1, hidden.get());
            return null;
        });
    }

    @Test void closingVisualCopyNaturallyCleansUpWithoutKeepingTheReportAlive() throws Exception {
        var completed = new CompletableFuture<Long>();
        var scene = fx(() -> {
            var owner = show(new StackPane(), null);
            var report = show(new StackPane(new Label("Report")), owner);
            report.hide();
            assertFalse(report.isShowing());
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(650));
            pause.setOnFinished(event -> completed.complete(WorkspaceInteractionMotion.exitGhostCount(owner.getScene())));
            pause.play();
            return owner.getScene();
        });
        assertEquals(0L, completed.get(5, TimeUnit.SECONDS).longValue());
        assertEquals(0L, fx(() -> WorkspaceInteractionMotion.exitGhostCount(scene)).longValue());
    }
}
