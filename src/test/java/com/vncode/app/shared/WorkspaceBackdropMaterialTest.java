package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.StyleOrigin;
import javafx.css.StyleableProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Stage;
import javafx.stage.Popup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX pixels and public backgrounds, without snapshots of seller content as a material source. */
class WorkspaceBackdropMaterialTest {
    @TempDir Path profile;
    private String previousProfile;
    private boolean previousReduced;
    private Thread.UncaughtExceptionHandler previousFxHandler;
    private final java.util.List<Throwable> callbackErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ArrayList<Stage> stages = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();

    @BeforeAll static void startToolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work); Platform.runLater(future);
        return future.get(30, TimeUnit.SECONDS);
    }

    private static void settle() throws Exception { for (int i = 0; i < 4; i++) fx(() -> null); }

    private void settleCss() throws Exception {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        fx(() -> {
            new javafx.animation.AnimationTimer() {
                private int pulses;
                @Override public void handle(long now) { if (++pulses >= 2) { stop(); completed.complete(null); } }
            }.start();
            Platform.requestNextPulse(); return null;
        });
        completed.get(10, TimeUnit.SECONDS);
        fx(() -> {
            for (var stage : stages) if (stage.isShowing()) { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
            return null;
        });
    }

    private static void awaitMaterial(javafx.scene.layout.Region region) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (fx(() -> region.getBackground() != null && region.getBackground().getFills().stream()
                    .anyMatch(fill -> fill.getFill() instanceof ImagePattern))) return;
            Thread.sleep(15);
        }
    }

    @BeforeEach void initialize() throws Exception {
        previousProfile = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
        previousReduced = fx(() -> {
            previousFxHandler = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> callbackErrors.add(error));
            ThemeService.initializeAppearance(); boolean before = ThemeService.isReducedMotion();
            ThemeService.setReducedMotion(true); ThemeService.setCurrentTheme(ThemeService.Theme.LIGHT);
            ThemeService.setGlassEnabled(true); ThemeService.setGlassTransparency(55); return before;
        });
    }

    @AfterEach void restore() throws Exception {
        fx(() -> {
            for (var popup : popups) popup.hide();
            for (var stage : stages) stage.close();
            ThemeService.setReducedMotion(previousReduced);
            Thread.currentThread().setUncaughtExceptionHandler(previousFxHandler); return null;
        });
        if (previousProfile == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousProfile);
        assertTrue(callbackErrors.isEmpty(), () -> "Unexpected JavaFX callback errors: " + callbackErrors);
    }

    private Stage show(Parent root) {
        var scene = new Scene(root, 600, 400); var stage = new Stage(); stage.setScene(scene);
        ThemeService.applyTheme(scene); stages.add(stage); stage.show(); root.applyCss(); root.layout(); return stage;
    }

    @Test void theActualWorkspaceGroundContainsVisibleBackdropDepthInBothThemes() throws Exception {
        for (var theme : ThemeService.Theme.values()) {
            var root = fx(() -> {
                ThemeService.setCurrentTheme(theme); var pane = new VBox();
                pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
            });
            awaitMaterial(root); settle();
            fx(() -> {
                var pixels = root.snapshot(null, null).getPixelReader();
                var first = pixels.getColor(24, 24); double largest = 0;
                for (int y = 24; y < 380; y += 35) for (int x = 24; x < 580; x += 35) {
                    var other = pixels.getColor(x, y);
                    largest = Math.max(largest, distance(first, other));
                }
                assertTrue(largest > .07, "A real " + theme + " workspace ground must have visible material depth, not a uniform paint");
                return null;
            });
        }
    }

    @Test void chromeSamplesABackgroundImageWhileItsLiveControlsRemainUnchanged() throws Exception {
        var root = fx(() -> {
            var label = new Label("Synthetic shop"); var action = new Button("Refresh");
            var chrome = new HBox(label, action); chrome.getStyleClass().add("workspace-glass-header");
            var pane = new VBox(chrome); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
        });
        awaitMaterial((HBox) root.getChildren().getFirst()); settle();
        fx(() -> {
            var chrome = (HBox) root.getChildren().getFirst();
            assertTrue(chrome.getBackground().getFills().stream().anyMatch(fill -> fill.getFill() instanceof ImagePattern),
                    "Chrome needs an actual sampled, blurred backdrop below its tint");
            assertInstanceOf(Color.class, chrome.getBackground().getFills().getLast().getFill(), "Keep the saved alpha tint contract");
            assertEquals(1, root.getChildren().size()); assertEquals(2, chrome.getChildren().size());
            assertEquals(1, chrome.getOpacity()); assertEquals(1, chrome.getChildren().getFirst().getOpacity());
            assertNull(chrome.getChildren().getFirst().getEffect(), "Never blur live text");
            var controlEffect = chrome.getChildren().getLast().getEffect();
            assertTrue(controlEffect == null || controlEffect instanceof javafx.scene.effect.DropShadow,
                    "A floating control may have an outer shadow, never a foreground blur");
            if (controlEffect instanceof javafx.scene.effect.DropShadow shadow) assertNull(shadow.getInput());
            return null;
        });
    }

    @Test void blurSoftensOnlyTheSharedWallpaperAndAllRegionsReuseBoundedImages() throws Exception {
        Set<Image> images = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var theme : ThemeService.Theme.values()) {
            var root = fx(() -> {
                ThemeService.setCurrentTheme(theme); var a = chrome(); var b = chrome();
                var pane = new VBox(a, b); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
            });
            awaitMaterial(root); awaitMaterial((Region) root.getChildren().getFirst()); settle();
            fx(() -> {
                var original = pattern(root).getImage(); var first = pattern((Region) root.getChildren().getFirst()).getImage();
                assertNotSame(original, first, "The material must use the blurred source, not the original wallpaper");
                assertSame(first, pattern((Region) root.getChildren().getLast()).getImage(), "No per-region bitmap allocation");
                assertTrue(edgeEnergy(first) < edgeEnergy(original) * .90, "The actual blur kernel must soften wallpaper edges");
                for (var image : new Image[]{original, first}) {
                    assertEquals(960, image.getWidth()); assertEquals(600, image.getHeight()); assertFalse(image.isError()); images.add(image);
                }
                return null;
            });
        }
        assertEquals(4, images.size(), "Only original+blurred images per theme are retained");
    }

    @Test void samplingUsesRootCoordinatesAndPreservesCssOriginAndRoundedShape() throws Exception {
        var root = fx(() -> { var pane = new VBox(chrome()); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial((Region) root.getChildren().getFirst()); settle();
        fx(() -> {
            var chrome = (Region) root.getChildren().getFirst(); var fills = chrome.getBackground().getFills();
            var tint = fills.getLast(); var sampled = fills.get(fills.size() - 2);
            assertInstanceOf(ImagePattern.class, sampled.getFill());
            assertEquals(tint.getInsets(), sampled.getInsets()); assertEquals(tint.getRadii(), sampled.getRadii());
            assertEquals(StyleOrigin.AUTHOR, ((StyleableProperty<?>) chrome.backgroundProperty()).getStyleOrigin());
            var point = root.sceneToLocal(chrome.localToScene(0, 0)); var material = pattern(chrome);
            assertEquals(-point.getX(), material.getX(), .001); assertEquals(-point.getY(), material.getY(), .001);
            assertEquals(root.getWidth(), material.getWidth(), .001); assertEquals(root.getHeight(), material.getHeight(), .001);
            return null;
        });
    }

    @Test void existingCustomInlineAndBoundBackgroundsAreNeverClaimed() throws Exception {
        var custom = Background.fill(Color.DARKORANGE); var bound = new SimpleObjectProperty<>(Background.fill(Color.CORNFLOWERBLUE));
        var root = fx(() -> {
            var a = chrome(); a.setBackground(custom); var b = chrome(); b.backgroundProperty().bind(bound);
            var c = chrome(); c.setStyle("-fx-background-color: crimson;");
            var pane = new VBox(a, b, c); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
        });
        awaitMaterial(root); settle();
        fx(() -> {
            // Existing author CSS can supersede USER paints; the material helper must not claim this surface.
            assertNull(pattern((Region) root.getChildren().get(0)));
            assertSame(bound.get(), ((Region) root.getChildren().get(1)).getBackground());
            assertEquals(Color.CRIMSON, ((Region) root.getChildren().get(2)).getBackground().getFills().getLast().getFill());
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK); return null;
        });
        settle();
        fx(() -> { assertNull(pattern((Region) root.getChildren().get(0))); assertSame(bound.get(), ((Region) root.getChildren().get(1)).getBackground()); return null; });
    }

    @Test void callerBackgroundRemainsExcludedWhenCssPrecedesThemeRescan() throws Exception {
        var root = fx(() -> {
            var caller = chrome(); caller.setBackground(Background.fill(Color.DARKORANGE));
            var pane = new VBox(caller, chrome()); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
        });
        var caller = (Region) root.getChildren().getFirst();
        var ordinary = (Region) root.getChildren().getLast();
        awaitMaterial(ordinary); settleCss(); awaitMaterial(ordinary); settle();
        fx(() -> {
            // Native author CSS legitimately supersedes USER paint, while its material exclusion remains.
            assertEquals(StyleOrigin.AUTHOR, ((StyleableProperty<?>) caller.backgroundProperty()).getStyleOrigin());
            assertNull(pattern(caller)); assertNotNull(pattern(ordinary));
            ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
            // Model the Windows ordering: CSS can resolve before the queued material-tree rebuild.
            root.applyCss(); root.layout(); return null;
        });
        settleCss(); awaitMaterial(ordinary); settle();
        fx(() -> {
            assertNull(pattern(caller), "Theme rescans must retain the original caller-owned background exclusion");
            assertNotNull(pattern(ordinary), "Ordinary author surfaces must still receive the new theme material");
            return null;
        });
    }

    @Test void aLaterCallerReplacementOrBindingRetainsOwnership() throws Exception {
        var root = fx(() -> { var pane = new VBox(chrome(), chrome()); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial((Region) root.getChildren().getFirst()); settle();
        var bound = new SimpleObjectProperty<>(Background.fill(Color.SIENNA));
        var custom = fx(() -> {
            // AUTHOR theme CSS supersedes USER setBackground at the next pulse, even on the old runtime.
            // A caller-owned inline background is the persistent public CSS contract being protected here.
            var caller = (Region) root.getChildren().getFirst(); caller.setStyle("-fx-background-color: #daa520;"); root.applyCss();
            ((Region) root.getChildren().getLast()).backgroundProperty().bind(bound);
            return caller.getBackground();
        });
        fx(() -> { ThemeService.setCurrentTheme(ThemeService.Theme.DARK); root.getScene().getWindow().setWidth(740); return null; });
        settleCss();
        fx(() -> {
            var caller = (Region) root.getChildren().getFirst();
            // JavaFX may reconstruct an equal CSS Background; identity belongs to the bound case only.
            assertEquals(custom, caller.getBackground()); assertEquals(Color.GOLDENROD, caller.getBackground().getFills().getLast().getFill());
            assertNull(pattern(caller)); assertSame(bound.get(), ((Region) root.getChildren().getLast()).getBackground());
            assertEquals(StyleOrigin.INLINE, ((StyleableProperty<?>) caller.backgroundProperty()).getStyleOrigin()); return null;
        });
    }

    @Test void aCallerSettingTheSameBackgroundInstanceKeepsItsValueWhenWindowHides() throws Exception {
        var root = fx(() -> { var pane = new VBox(chrome()); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial((Region) root.getChildren().getFirst()); settle();
        fx(() -> {
            var chrome = (Region) root.getChildren().getFirst(); var same = chrome.getBackground(); chrome.setBackground(same);
            // WINDOW_HIDDEN cleanup is synchronous. Native AUTHOR CSS may supersede USER on a later pulse.
            root.getScene().getWindow().hide();
            assertSame(same, chrome.getBackground(), "Helper cleanup must respect the same-instance USER adoption");
            assertEquals(StyleOrigin.USER, ((StyleableProperty<?>) chrome.backgroundProperty()).getStyleOrigin()); return null;
        });
    }

    @Test void aReentrantCallerBindingDuringCompositionWins() throws Exception {
        var custom = new SimpleObjectProperty<>(Background.fill(Color.DARKORANGE));
        var root = fx(() -> {
            var chrome = chrome();
            chrome.backgroundProperty().addListener((observable, before, after) -> {
                if (!chrome.backgroundProperty().isBound() && after != null
                        && after.getFills().stream().anyMatch(fill -> fill.getFill() instanceof ImagePattern)) chrome.backgroundProperty().bind(custom);
            });
            var pane = new VBox(chrome); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
        });
        try {
            awaitMaterial(root); settleCss();
            fx(() -> { assertSame(custom.get(), ((Region) root.getChildren().getFirst()).getBackground()); root.getScene().getWindow().setWidth(740); return null; });
            settleCss();
            fx(() -> {
                var chrome = (Region) root.getChildren().getFirst(); assertTrue(chrome.backgroundProperty().isBound()); assertSame(custom.get(), chrome.getBackground());
                root.getScene().getWindow().hide(); assertTrue(chrome.backgroundProperty().isBound()); assertSame(custom.get(), chrome.getBackground()); return null;
            });
        } finally {
            fx(() -> { ((Region) root.getChildren().getFirst()).backgroundProperty().unbind(); return null; });
        }
    }

    @Test void glassOffAndWindowHideRestoreOnlyOurBackgrounds() throws Exception {
        var root = fx(() -> { var pane = new VBox(chrome()); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial(root); awaitMaterial((Region) root.getChildren().getFirst()); settle();
        fx(() -> { ThemeService.setGlassEnabled(false); return null; }); settle();
        fx(() -> { assertNull(pattern(root)); assertNull(pattern((Region) root.getChildren().getFirst())); ThemeService.setGlassEnabled(true); return null; });
        awaitMaterial(root); settle();
        fx(() -> { root.getScene().getWindow().hide(); assertNull(pattern(root)); assertNull(pattern((Region) root.getChildren().getFirst())); return null; });
        fx(() -> { ((Stage) root.getScene().getWindow()).show(); return null; }); awaitMaterial(root);
        fx(() -> { assertNotNull(pattern(root)); return null; });
    }

    @Test void dynamicChromeIsCoveredAndDetachedChromeIsReleased() throws Exception {
        var root = fx(() -> { var pane = new VBox(); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial(root);
        var chrome = fx(() -> { var added = chrome(); root.getChildren().add(added); root.applyCss(); root.layout(); return added; });
        awaitMaterial(chrome);
        fx(() -> { root.getChildren().remove(chrome); return null; }); settle();
        fx(() -> { assertNull(pattern(chrome)); root.getChildren().add(chrome); root.applyCss(); root.layout(); return null; });
        awaitMaterial(chrome);
        fx(() -> { assertEquals(1, chrome.getBackground().getFills().stream().filter(fill -> fill.getFill() instanceof ImagePattern).count()); return null; });
    }

    @Test void anOwnedReportAndPopupSampleTheirExplicitOwnerAndFollowItsResize() throws Exception {
        var root = fx(() -> { var pane = new VBox(); pane.getStyleClass().add("workspace-shell"); show(pane); return pane; });
        awaitMaterial(root);
        var report = fx(() -> {
            var pane = new DialogPane(); pane.setContent(new Label("Synthetic report"));
            var stage = new Stage(); stage.initOwner(root.getScene().getWindow()); stage.setScene(new Scene(pane, 300, 200));
            stages.add(stage); ThemeService.applyTheme(stage.getScene()); stage.show(); pane.applyCss(); pane.layout(); return pane;
        });
        var popupPane = fx(() -> {
            var pane = new VBox(new Label("Synthetic menu")); pane.getStyleClass().add("glass-overlay");
            var popup = new Popup(); popup.getContent().add(pane); popups.add(popup);
            popup.show(root.getScene().getWindow(), root.getScene().getWindow().getX() + 75, root.getScene().getWindow().getY() + 90);
            ThemeService.applyTheme(popup.getScene()); pane.applyCss(); pane.layout(); return pane;
        });
        awaitMaterial(report); awaitMaterial(popupPane); settle();
        var old = fx(() -> {
            assertSame(pattern(report).getImage(), pattern(popupPane).getImage());
            assertEquals(root.getWidth(), pattern(report).getWidth(), .001); assertEquals(root.getWidth(), pattern(popupPane).getWidth(), .001);
            double value = pattern(report).getWidth(); root.getScene().getWindow().setWidth(800); return value;
        });
        for (int i = 0; i < 30; i++) { settle(); if (fx(() -> pattern(report).getWidth() != old)) break; Thread.sleep(10); }
        fx(() -> {
            assertNotEquals(old, pattern(report).getWidth()); assertEquals(root.getWidth(), pattern(report).getWidth(), .001);
            assertEquals(root.getWidth(), pattern(popupPane).getWidth(), .001); return null;
        });
    }

    @Test void workingTablesHaveFrostedBackgroundsWithoutBlurringTextAndStartupStaysSeparate() throws Exception {
        var root = fx(() -> {
            var table = new TableView<String>(); table.getColumns().add(new TableColumn<>("Orders"));
            var pane = new VBox(chrome(), table); pane.getStyleClass().add("workspace-shell"); show(pane); return pane;
        });
        awaitMaterial((Region) root.getChildren().getLast()); settleCss();
        fx(() -> {
            // The user's later all-glass brief supersedes the earlier opaque-table appearance.
            var table = (TableView<?>) root.getChildren().getLast(); assertNotNull(pattern(table));
            assertEquals(1, table.getOpacity(), "Frosted paint must not fade the table or its text");
            assertTrue(table.getEffect() == null || table.getEffect() instanceof javafx.scene.effect.DropShadow,
                    "Only an outer floating shadow is allowed; never a foreground blur");
            var tint = assertInstanceOf(Color.class, table.getBackground().getFills().getLast().getFill());
            assertTrue(tint.getOpacity() > 0 && tint.getOpacity() < 1, "The working surface now has an actual translucent tint");
            for (var row : table.lookupAll(".table-row-cell")) { assertEquals(1, row.getOpacity()); assertNull(row.getEffect()); }
            var startup = new VBox(); startup.getStyleClass().add("workspace-shell"); var scene = new Scene(startup, 300, 200);
            scene.getProperties().put(ThemeService.STARTUP_SCENE, true); var stage = new Stage(); stage.setScene(scene); stages.add(stage);
            ThemeService.applyTheme(scene); stage.show(); startup.applyCss(); startup.layout(); assertNull(pattern(startup)); return null;
        });
    }

    private static HBox chrome() { var chrome = new HBox(new Label("Synthetic shop")); chrome.getStyleClass().add("workspace-glass-header"); return chrome; }
    private static ImagePattern pattern(Region region) {
        return region.getBackground() == null ? null : region.getBackground().getFills().stream().map(fill -> fill.getFill())
                .filter(ImagePattern.class::isInstance).map(ImagePattern.class::cast).findFirst().orElse(null);
    }

    private static double edgeEnergy(Image image) {
        var reader = image.getPixelReader(); double total = 0;
        for (int y = 1; y < image.getHeight(); y += 3) for (int x = 1; x < image.getWidth(); x += 3) {
            double a = reader.getColor(x, y).getBlue() - reader.getColor(x - 1, y).getBlue();
            double b = reader.getColor(x, y).getBlue() - reader.getColor(x, y - 1).getBlue(); total += a * a + b * b;
        }
        return total;
    }

    private static double distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
    }
}
