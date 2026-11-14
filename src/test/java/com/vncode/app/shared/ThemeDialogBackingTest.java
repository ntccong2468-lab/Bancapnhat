package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Scene snapshots include the real backing behind rounded, translucent report chrome. */
class ThemeDialogBackingTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private final ArrayList<Stage> stages = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();

    @BeforeAll static void startToolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work); Platform.runLater(future);
        return future.get(20, TimeUnit.SECONDS);
    }

    @BeforeEach void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString());
        Database.initDatabase();
        previousReduced = fx(() -> {
            ThemeService.initializeAppearance();
            boolean previous = ThemeService.isReducedMotion();
            ThemeService.setReducedMotion(true);
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            ThemeService.setGlassEnabled(true);
            ThemeService.setGlassTransparency(55);
            return previous;
        });
    }

    @AfterEach void restore() throws Exception {
        fx(() -> {
            for (var popup : popups) popup.hide();
            for (var stage : stages) stage.close();
            WorkspaceInteractionMotion.cancelAll(); WorkspaceMotion.cancelAll();
            ThemeService.setReducedMotion(previousReduced);
            return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
    }

    private static DialogPane reportPane() {
        var pane = new DialogPane(); pane.setContent(new Label("Synthetic report"));
        pane.getButtonTypes().add(ButtonType.CLOSE);
        return pane;
    }

    private Stage show(Scene scene, StageStyle style) {
        var stage = new Stage(); stage.initStyle(style); stage.setScene(scene);
        ThemeService.applyTheme(scene); stages.add(stage); stage.show();
        scene.getRoot().applyCss(); scene.getRoot().layout();
        return stage;
    }

    private static double brightness(Color value) { return (value.getRed() + value.getGreen() + value.getBlue()) / 3; }

    @Test void aDarkDecoratedReportDoesNotBlendItsGlassFrameAgainstDefaultWhite() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 480, 300);
            var stage = show(scene, StageStyle.DECORATED);
            var pixels = scene.snapshot(null);
            assertTrue(brightness(pixels.getPixelReader().getColor(2, 150)) < .24,
                    "The translucent dark report frame must blend against the dark app ground, not WHITE");
            assertEquals(Color.web("#17181b"), scene.getFill());
            assertEquals(1, stage.getOpacity()); assertEquals(1, scene.getRoot().getOpacity());
            assertEquals(StageStyle.DECORATED, stage.getStyle(), "Keep the real native window decoration");
            return null;
        });
    }

    @Test void appOwnedBackingTracksBothThemesWithoutChangingWindowOpacity() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 480, 300); var stage = show(scene, StageStyle.DECORATED);
            assertEquals(Color.web("#17181b"), scene.getFill());
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(Color.web("#e9ebf0"), scene.getFill());
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            assertEquals(Color.web("#17181b"), scene.getFill());
            assertEquals(1, stage.getOpacity()); assertEquals(1, scene.getRoot().getOpacity());
            return null;
        });
    }

    @Test void initialCustomTransparentAndBoundFillsRemainCallerOwned() throws Exception {
        fx(() -> {
            for (Paint initial : new Paint[]{Color.CORNFLOWERBLUE, Color.TRANSPARENT}) {
                var scene = new Scene(reportPane(), 380, 240, initial); show(scene, StageStyle.DECORATED);
                ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
                ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
                assertEquals(initial, scene.getFill());
            }
            var value = new SimpleObjectProperty<Paint>(Color.WHITE);
            var bound = new Scene(reportPane(), 380, 240); bound.fillProperty().bind(value);
            show(bound, StageStyle.DECORATED);
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(Color.WHITE, bound.getFill()); assertTrue(bound.fillProperty().isBound());
            value.set(Color.DARKORANGE);
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            assertEquals(Color.DARKORANGE, bound.getFill());
            return null;
        });
    }

    @Test void aCallerFillChangeOptsOutEvenWhenItChangesBackToWhite() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 380, 240); show(scene, StageStyle.DECORATED);
            assertEquals(Color.web("#17181b"), scene.getFill());
            scene.setFill(Color.DARKORANGE);
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(Color.DARKORANGE, scene.getFill());
            scene.setFill(Color.WHITE);
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            assertEquals(Color.WHITE, scene.getFill(), "An explicit caller reset must not be reclaimed as a default");
            return null;
        });
    }

    @Test void aReentrantCallerFillChangeDoesNotBecomeOwnedByTheNextTheme() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 380, 240);
            scene.fillProperty().addListener((observable, previous, fill) -> {
                if (Color.web("#17181b").equals(fill)) scene.setFill(Color.DARKORANGE);
            });
            show(scene, StageStyle.DECORATED);
            assertEquals(Color.DARKORANGE, scene.getFill());
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(Color.DARKORANGE, scene.getFill(), "The theme must not reclaim a caller replacement made inside its fill notification");
            return null;
        });
    }

    @Test void bindingAnAlreadyOwnedFillIsPreservedAcrossThemeChanges() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 380, 240); show(scene, StageStyle.DECORATED);
            var value = new SimpleObjectProperty<Paint>(scene.getFill()); scene.fillProperty().bind(value);
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(value.get(), scene.getFill()); assertTrue(scene.fillProperty().isBound());
            scene.fillProperty().unbind();
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            assertEquals(value.get(), scene.getFill(), "Binding ownership does not silently return to the theme");
            return null;
        });
    }

    @Test void ordinaryWorkspacesTransparentStagesAndStartupScenesKeepTheirFill() throws Exception {
        fx(() -> {
            var ordinary = new Scene(new VBox(new Label("Workspace")), 380, 240); show(ordinary, StageStyle.DECORATED);
            assertEquals(Color.WHITE, ordinary.getFill());
            var transparentStage = new Scene(reportPane(), 380, 240); show(transparentStage, StageStyle.TRANSPARENT);
            assertEquals(Color.WHITE, transparentStage.getFill());
            var startup = new Scene(reportPane(), 380, 240);
            startup.getProperties().put(ThemeService.STARTUP_SCENE, Boolean.TRUE); show(startup, StageStyle.DECORATED);
            assertEquals(Color.WHITE, startup.getFill()); assertTrue(startup.getStylesheets().isEmpty());
            return null;
        });
    }

    @Test void popupSceneTransparencyIsNotReplacedWithAnOpaqueReportBacking() throws Exception {
        fx(() -> {
            var owner = show(new Scene(new VBox(new Label("Owner")), 400, 260), StageStyle.DECORATED);
            var popup = new Popup(); popup.getContent().add(reportPane()); popups.add(popup);
            Paint initial = popup.getScene().getFill();
            ThemeService.applyTheme(popup.getScene()); popup.show(owner, owner.getX() + 20, owner.getY() + 40);
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            assertEquals(initial, popup.getScene().getFill());
            return null;
        });
    }

    @Test void leavingTheReportRoleRestoresOnlyTheDefaultFillThatTheThemeOwned() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 380, 240); show(scene, StageStyle.DECORATED);
            assertEquals(Color.web("#17181b"), scene.getFill());
            scene.setRoot(new VBox(new Label("Ordinary workspace")));
            assertEquals(Color.WHITE, scene.getFill());
            scene.setRoot(reportPane());
            assertEquals(Color.web("#17181b"), scene.getFill());
            scene.setFill(Color.DARKORANGE);
            scene.setRoot(new VBox(new Label("Caller surface")));
            assertEquals(Color.DARKORANGE, scene.getFill());
            return null;
        });
    }

    @Test void anExplicitDefaultLookingCallerSurfaceCanOptOutBeforeTheFirstTheme() throws Exception {
        fx(() -> {
            var scene = new Scene(reportPane(), 380, 240);
            scene.getProperties().put("vncode.theme.preserve-scene-fill", Boolean.TRUE);
            show(scene, StageStyle.DECORATED);
            assertEquals(Color.WHITE, scene.getFill());
            ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            assertEquals(Color.WHITE, scene.getFill());
            return null;
        });
    }
}
