package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Public integration contract: these tests compile against the pre-motion app too. */
class WorkspaceGlobalMotionContractTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final ArrayList<Stage> windows = new ArrayList<>();

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
            for (var window : windows) window.close();
            windows.clear();
            WorkspaceMotion.cancelAll();
            ThemeService.setReducedMotion(true);
            ThemeService.setReducedMotion(previousReduced);
            return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private Stage show(StackPane root) {
        var stage = new Stage();
        var scene = new Scene(root, 480, 320);
        ThemeService.applyTheme(scene);
        stage.setScene(scene);
        windows.add(stage);
        stage.show();
        return stage;
    }

    private static void press(Button button) {
        Event.fireEvent(button, new MouseEvent(button, button, MouseEvent.MOUSE_PRESSED,
                8, 8, 8, 8, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false,
                false, false, true, new PickResult(button, 8, 8)));
    }

    @Test void aButtonPressHasLocalFeedbackWithoutConsumingOrRefiringItsBusinessAction() throws Exception {
        fx(() -> {
            var calls = new AtomicInteger();
            var observed = new AtomicInteger();
            var button = new Button("Synthetic action");
            var applicationTransform = new Rotate(2);
            button.getTransforms().add(applicationTransform);
            button.scaleXProperty().bind(new javafx.beans.property.SimpleDoubleProperty(.91));
            button.setOnAction(event -> calls.incrementAndGet());
            var root = new StackPane(button);
            show(root);
            root.addEventHandler(ActionEvent.ACTION, event -> observed.incrementAndGet());
            press(button);
            assertTrue(button.getTransforms().stream().anyMatch(Scale.class::isInstance),
                    "Every themed scene must provide additive feedback for its real action buttons");
            assertTrue(button.getTransforms().contains(applicationTransform));
            assertEquals(.91, button.getScaleX(), "Application-bound transforms remain owned by the application");
            assertEquals(0, calls.get(), "Feedback cannot submit the action on press");
            button.fire();
            assertEquals(1, calls.get(), "One real action remains one business call");
            assertEquals(1, observed.get(), "The action still bubbles to its real handlers");
            return null;
        });
    }

    @Test void reportStagesOpenAtFullSizeAndKeepImmediateHiddenCleanup() throws Exception {
        fx(() -> {
            var owner = show(new StackPane());
            var root = new StackPane(new Button("Synthetic report"));
            var report = new Stage();
            report.initOwner(owner);
            var scene = new Scene(root, 360, 240);
            ThemeService.applyTheme(scene);
            report.setScene(scene);
            windows.add(report);
            var shown = new AtomicInteger();
            var hidden = new AtomicInteger();
            report.setOnShown(event -> shown.incrementAndGet());
            report.setOnHidden(event -> hidden.incrementAndGet());
            report.show();
            assertFalse(WorkspaceMotion.isAnimating(root), "Dense native report roots show at full size from their first frame");
            assertTrue(root.getTransforms().isEmpty()); assertEquals(1, root.getOpacity());
            assertEquals(1, shown.get());
            report.hide();
            assertFalse(report.isShowing(), "Animation cannot delay the real window hide");
            assertEquals(1, hidden.get(), "The existing report cleanup must run immediately and once");
            return null;
        });
    }

    @Test void controlsAddedAfterThemeInstallationReceiveFeedbackWithoutARescan() throws Exception {
        fx(() -> {
            var contents = new VBox();
            show(new StackPane(contents));
            var button = new Button("Added later");
            contents.getChildren().add(button);
            press(button);
            assertTrue(button.getTransforms().stream().anyMatch(Scale.class::isInstance),
                    "Dialogs and filter groups create new controls after their scene has already been themed");
            return null;
        });
    }
}
