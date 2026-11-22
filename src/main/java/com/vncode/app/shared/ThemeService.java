package com.vncode.app.shared;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.css.Styleable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PopupControl;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared desktop appearance for scenes, dialogs and popups. */
public final class ThemeService {
    private static final String BASE_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/theme.css")).toExternalForm();
    private static final String CONTROLS_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/workspace-controls.css")).toExternalForm();
    private static final Set<Parent> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Runnable> APPEARANCE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final String DIALOG_BACKING = ThemeService.class.getName() + ".dialogBacking";
    private static boolean observingWindows;
    private static boolean observingSystemPreferences;
    private static volatile Boolean reducedMotionPreference;
    private static volatile boolean systemReducedMotion;
    private static volatile Theme systemTheme = Theme.LIGHT;
    public static final String STARTUP_SCENE = "vncode.startup.progress";
    /** Opt out before applying a theme when an intentionally white caller fill looks like the default. */
    public static final String PRESERVE_SCENE_FILL = "vncode.theme.preserve-scene-fill";

    private ThemeService() { }

    /** Effective light/dark appearance, retained for existing callers. */
    public enum Theme {
        DARK("dark"), LIGHT("light");
        private final String key;
        Theme(String key) { this.key = key; }
        public String getKey() { return key; }
        public static Theme fromKey(String key) { return "light".equalsIgnoreCase(key) ? LIGHT : DARK; }
    }

    /** The saved choice is separate from the effective appearance when following the OS. */
    public enum ThemeMode {
        DARK("dark"), LIGHT("light"), SYSTEM("system");
        private final String key;
        ThemeMode(String key) { this.key = key; }
        public String getKey() { return key; }
        public static ThemeMode fromKey(String key) {
            if ("system".equalsIgnoreCase(key)) return SYSTEM;
            return "light".equalsIgnoreCase(key) ? LIGHT : DARK;
        }
    }

    /** Reload preferences at application startup without replacing an existing user choice. */
    public static void initializeAppearance() {
        reducedMotionPreference = Boolean.parseBoolean(ConfigService.getConfigValue("ui_reduced_motion"));
        refreshAppearance();
    }

    public static ThemeMode getThemeMode() {
        return ThemeMode.fromKey(ConfigService.getConfigValue("app_theme"));
    }

    public static Theme getCurrentTheme() {
        return switch (getThemeMode()) {
            case LIGHT -> Theme.LIGHT;
            case DARK -> Theme.DARK;
            case SYSTEM -> currentSystemTheme();
        };
    }

    public static void setCurrentTheme(Theme theme) {
        switchTheme(Objects.requireNonNull(theme).getKey());
    }

    /** OS accessibility preferences always take priority over optional application motion. */
    public static boolean isReducedMotion() {
        if (reducedMotionPreference == null) {
            synchronized (ThemeService.class) {
                if (reducedMotionPreference == null) {
                    reducedMotionPreference = Boolean.parseBoolean(ConfigService.getConfigValue("ui_reduced_motion"));
                }
            }
        }
        observeSystemPreferences();
        return Boolean.TRUE.equals(reducedMotionPreference) || systemReducedMotion;
    }

    public static void setReducedMotion(boolean reduced) {
        ConfigService.setConfigValue("ui_reduced_motion", Boolean.toString(reduced));
        reducedMotionPreference = reduced;
        refreshAppearance();
    }

    public static void applyTheme(Scene scene) {
        if (scene == null) return;
        if (Boolean.TRUE.equals(scene.getProperties().get(STARTUP_SCENE))) {
            if (scene.getProperties().get(DIALOG_BACKING) instanceof DialogBacking backing) backing.releaseOwned();
            return;
        }
        UiFonts.install();
        observeSystemPreferences();
        observeWindows();
        if (!scene.getStylesheets().contains(BASE_CSS)) scene.getStylesheets().add(BASE_CSS);
        if (!scene.getStylesheets().contains(CONTROLS_CSS)) scene.getStylesheets().add(CONTROLS_CSS);
        if (!scene.getProperties().containsKey(ThemeService.class)) {
            scene.getProperties().put(ThemeService.class, Boolean.TRUE);
            scene.rootProperty().addListener((observable, oldRoot, newRoot) -> applyTheme(scene));
        }
        applyAppearance(scene.getRoot(), getCurrentTheme());
        WorkspaceMotion.install(scene);
        WorkspaceInteractionMotion.install(scene);
    }

    /** App-owned dialog presentation, installed before its first show. */
    public static void applyThemeDialog(Dialog<?> dialog) {
        if (dialog == null) return;
        WorkspaceDialogChrome.install(dialog);
        applyTheme(dialog.getDialogPane());
    }

    public static void applyTheme(DialogPane pane) {
        if (pane == null) return;
        UiFonts.install();
        observeSystemPreferences();
        observeWindows();
        if (!pane.getStylesheets().contains(BASE_CSS)) pane.getStylesheets().add(BASE_CSS);
        if (!pane.getStylesheets().contains(CONTROLS_CSS)) pane.getStylesheets().add(CONTROLS_CSS);
        applyAppearance(pane, getCurrentTheme());
        WorkspaceMotion.install(pane);
        WorkspaceInteractionMotion.install(pane);
    }

    public static void addAppearanceListener(Runnable listener) {
        APPEARANCE_LISTENERS.add(Objects.requireNonNull(listener));
    }

    public static void removeAppearanceListener(Runnable listener) {
        APPEARANCE_LISTENERS.remove(listener);
    }

    private static void refreshAppearance() {
        onFxThread(() -> {
            observeSystemPreferences();
            if (isReducedMotion()) {
                WorkspaceMotion.cancelAll();
                WorkspaceInteractionMotion.cancelAll();
            }
            Theme theme = getCurrentTheme();
            for (Parent root : List.copyOf(ROOTS)) applyAppearance(root, theme);
            for (Runnable listener : APPEARANCE_LISTENERS) listener.run();
        });
    }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else {
            try { Platform.runLater(action); }
            catch (IllegalStateException toolkitNotStarted) {
                // Startup preferences are applied when the first scene is installed.
            }
        }
    }

    private static Theme currentSystemTheme() {
        try {
            systemTheme = Platform.getPreferences().getColorScheme() == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
        } catch (IllegalStateException toolkitNotStarted) {
            // The first themed scene observes the OS once JavaFX is ready.
        }
        return systemTheme;
    }

    private static void observeSystemPreferences() {
        if (observingSystemPreferences || !Platform.isFxApplicationThread()) return;
        var preferences = Platform.getPreferences();
        observingSystemPreferences = true;
        systemTheme = preferences.getColorScheme() == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
        systemReducedMotion = preferences.isReducedMotion();
        preferences.colorSchemeProperty().addListener((observable, previous, selected) -> {
            systemTheme = selected == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
            if (getThemeMode() == ThemeMode.SYSTEM) refreshAppearance();
        });
        preferences.reducedMotionProperty().addListener((observable, previous, reduced) -> {
            systemReducedMotion = reduced;
            refreshAppearance();
        });
    }

    private static void observeWindows() {
        if (observingWindows || !Platform.isFxApplicationThread()) return;
        observingWindows = true;
        Window.getWindows().addListener((ListChangeListener<Window>) changes -> {
            while (changes.next()) {
                if (changes.wasAdded()) for (Window window : changes.getAddedSubList()) observeWindow(window);
            }
        });
        for (Window window : List.copyOf(Window.getWindows())) observeWindow(window);
    }

    private static void observeWindow(Window window) {
        if (!window.getProperties().containsKey(ThemeService.class)) {
            window.getProperties().put(ThemeService.class, Boolean.TRUE);
            window.sceneProperty().addListener((observable, previous, scene) -> applyTheme(scene));
        }
        if (window.getScene() != null) applyTheme(window.getScene());
    }

    private static void applyAppearance(Parent root, Theme theme) {
        if (root == null) return;
        ROOTS.add(root);
        applyThemeClasses(root, theme);
        root.getStyleClass().remove("reference-dark");
        Scene scene = root.getScene();
        dialogBacking(scene, theme);
        WorkspaceRoundedControlClip.install(root);
        if (scene != null && scene.getRoot() == root && scene.getWindow() instanceof PopupControl popup) {
            applyThemeClasses(popup, theme);
            // Context menu skins copy their classes on creation.
            var skin = popup.getSkin();
            if (skin != null && skin.getNode() != null) {
                applyThemeClasses(skin.getNode(), theme);
            }
        }
    }

    private static void applyThemeClasses(Styleable node, Theme theme) {
        selectClass(node, "workspace-theme", true);
        selectClass(node, "theme-light", theme == Theme.LIGHT);
        selectClass(node, "theme-dark", theme == Theme.DARK);
    }

    private static void dialogBacking(Scene scene, Theme theme) {
        if (scene == null) return;
        Object existing = scene.getProperties().get(DIALOG_BACKING);
        if (existing instanceof DialogBacking backing) {
            backing.refresh(theme);
        } else if (scene.getRoot() instanceof DialogPane
                && !Boolean.TRUE.equals(scene.getProperties().get(STARTUP_SCENE))) {
            var backing = new DialogBacking(scene);
            scene.getProperties().put(DIALOG_BACKING, backing);
            backing.start(theme);
        }
    }

    /** Match the uncovered corners of decorated dialogs to the app ground.
     * Preserve fills supplied by callers, including bindings. */
    private static final class DialogBacking {
        // Matches the semantic -bg-primary palette in both shipped stylesheets.
        private static final Color DARK_GROUND = Color.BLACK;
        private static final Color LIGHT_GROUND = Color.web("#f2f2f2");
        private final Scene scene;
        private final Paint original;
        private Paint owned;
        private boolean callerOwned, writing;

        private DialogBacking(Scene scene) {
            this.scene = scene;
            original = scene.getFill();
            callerOwned = scene.fillProperty().isBound() || !Color.WHITE.equals(original)
                    || Boolean.TRUE.equals(scene.getProperties().get(PRESERVE_SCENE_FILL));
        }

        private void start(Theme theme) {
            scene.fillProperty().addListener((observable, previous, fill) -> {
                if (!writing) { callerOwned = true; owned = null; }
            });
            scene.windowProperty().addListener((observable, previous, window) -> refresh(getCurrentTheme()));
            refresh(theme);
        }

        private void refresh(Theme theme) {
            if (scene.fillProperty().isBound()
                    || Boolean.TRUE.equals(scene.getProperties().get(PRESERVE_SCENE_FILL))) {
                callerOwned = true; owned = null;
                return;
            }
            boolean eligible = scene.getRoot() instanceof DialogPane
                    && scene.getWindow() instanceof Stage stage && stage.getStyle() != StageStyle.TRANSPARENT
                    && !Boolean.TRUE.equals(scene.getProperties().get(STARTUP_SCENE));
            if (!eligible) { releaseOwned(); return; }
            if (callerOwned) return;
            if (owned == null && !Color.WHITE.equals(scene.getFill())) { callerOwned = true; return; }
            owned = theme == Theme.LIGHT ? LIGHT_GROUND : DARK_GROUND;
            write(owned);
        }

        private void releaseOwned() {
            if (owned == null) return;
            if (scene.fillProperty().isBound()) { callerOwned = true; owned = null; return; }
            if (Objects.equals(scene.getFill(), owned)) write(original);
            owned = null;
        }

        private void write(Paint fill) {
            writing = true;
            try { scene.setFill(fill); }
            finally { writing = false; }
            // A caller listener can replace or bind the fill reentrantly during our notification.
            if (scene.fillProperty().isBound() || !Objects.equals(scene.getFill(), fill)) {
                callerOwned = true; owned = null;
            }
        }
    }

    private static void selectClass(Styleable root, String styleClass, boolean selected) {
        if (selected) {
            if (!root.getStyleClass().contains(styleClass)) root.getStyleClass().add(styleClass);
        } else root.getStyleClass().remove(styleClass);
    }

    public static void switchTheme(String themeName) {
        ConfigService.setConfigValue("app_theme", ThemeMode.fromKey(themeName).getKey());
        refreshAppearance();
    }
}
