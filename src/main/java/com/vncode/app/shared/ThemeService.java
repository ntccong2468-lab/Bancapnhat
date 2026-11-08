package com.vncode.app.shared;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.css.Styleable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PopupControl;
import javafx.stage.Window;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared desktop appearance. Material density changes paints, never text or window opacity. */
public final class ThemeService {
    private static final String BASE_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/theme.css")).toExternalForm();
    private static final String CONTROLS_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/workspace-controls.css")).toExternalForm();
    private static final Set<Parent> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Runnable> APPEARANCE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final String ORIGINAL_STYLE = ThemeService.class.getName() + ".originalStyle";
    private static boolean observingWindows;
    private static boolean observingSystemPreferences;
    private static volatile Boolean reducedMotionPreference;
    private static volatile boolean systemReducedMotion;
    private static volatile Theme systemTheme = Theme.LIGHT;
    public static final int DEFAULT_TRANSPARENCY = 55;
    public static final String STARTUP_SCENE = "vncode.startup.progress";

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

    public static boolean isGlassEnabled() {
        String saved = ConfigService.getConfigValue("glass_enabled");
        return saved == null || Boolean.parseBoolean(saved);
    }

    public static void setGlassEnabled(boolean enabled) {
        ConfigService.setConfigValue("glass_enabled", Boolean.toString(enabled));
        refreshAppearance();
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

    public static int getGlassTransparency() {
        try { return bounded(Double.parseDouble(ConfigService.getConfigValue("glass_transparency"))); }
        catch (RuntimeException invalid) { return DEFAULT_TRANSPARENCY; }
    }

    private static int bounded(double value) {
        return Double.isFinite(value) ? (int) Math.round(Math.clamp(value, 0, 100)) : DEFAULT_TRANSPARENCY;
    }

    public static void setGlassTransparency(double value) {
        int amount = bounded(value);
        ConfigService.setConfigValue("glass_transparency", Integer.toString(amount));
        refreshAppearance();
    }

    /** Slider previews perform no SQL writes and never change Node/Window opacity. */
    public static void previewGlassTransparency(double value) {
        if (!Platform.isFxApplicationThread()) {
            onFxThread(() -> previewGlassTransparency(value));
            return;
        }
        Theme theme = getCurrentTheme();
        int amount = bounded(value);
        boolean glass = isGlassEnabled();
        for (Parent root : List.copyOf(ROOTS)) material(root, theme, amount, glass);
    }

    public static void applyTheme(Scene scene) {
        if (scene == null || Boolean.TRUE.equals(scene.getProperties().get(STARTUP_SCENE))) return;
        UiFonts.install();
        observeSystemPreferences();
        observeWindows();
        if (!scene.getStylesheets().contains(BASE_CSS)) scene.getStylesheets().add(BASE_CSS);
        if (!scene.getStylesheets().contains(CONTROLS_CSS)) scene.getStylesheets().add(CONTROLS_CSS);
        if (!scene.getProperties().containsKey(ThemeService.class)) {
            scene.getProperties().put(ThemeService.class, Boolean.TRUE);
            scene.rootProperty().addListener((observable, oldRoot, newRoot) -> applyTheme(scene));
        }
        material(scene.getRoot(), getCurrentTheme(), getGlassTransparency(), isGlassEnabled());
        WorkspaceMotion.install(scene);
    }

    public static void applyTheme(DialogPane pane) {
        if (pane == null) return;
        UiFonts.install();
        observeSystemPreferences();
        observeWindows();
        if (!pane.getStylesheets().contains(BASE_CSS)) pane.getStylesheets().add(BASE_CSS);
        if (!pane.getStylesheets().contains(CONTROLS_CSS)) pane.getStylesheets().add(CONTROLS_CSS);
        material(pane, getCurrentTheme(), getGlassTransparency(), isGlassEnabled());
        WorkspaceMotion.install(pane);
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
            if (isReducedMotion()) WorkspaceMotion.cancelAll();
            previewGlassTransparency(getGlassTransparency());
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

    private static void material(Parent root, Theme theme, int amount, boolean glass) {
        if (root == null) return;
        ROOTS.add(root);
        if (!root.getStyleClass().contains("workspace-theme")) root.getStyleClass().add("workspace-theme");
        selectClass(root, "liquid-glass", glass);
        selectClass(root, "glass-disabled", !glass);
        selectClass(root, "theme-light", theme == Theme.LIGHT);
        selectClass(root, "theme-dark", theme == Theme.DARK);
        root.getStyleClass().remove("reference-dark");
        root.getProperties().putIfAbsent(ORIGINAL_STYLE, root.getStyle());
        // Retain the saved slider scale. Only chrome/overlay CSS consumes these paints.
        double alpha = glass ? .005 + amount * .00995 : 1;
        double frost = glass ? amount / 100.0 : 1;
        String tint = theme == Theme.LIGHT ? "255,255,255" : "38,38,42";
        String elevated = theme == Theme.LIGHT ? "255,255,255" : "48,48,53";
        String ink = theme == Theme.LIGHT ? "36,37,42" : "241,241,244";
        String selection = theme == Theme.LIGHT ? "36,37,42" : "241,241,244";
        String paint = String.format(Locale.ROOT,
                "-glass-surface:rgba(%s,%.3f);-glass-elevated:rgba(%s,%.3f);-glass-row:rgba(%s,%.3f);"
                        + "-glass-button:rgba(%s,%.3f);-glass-highlight:rgba(255,255,255,%.3f);"
                        + "-glass-control-highlight:rgba(255,255,255,%.3f);-glass-control-shadow:rgba(0,0,0,%.3f);"
                        + "-glass-selected:rgba(%s,%.3f);-text-primary:rgb(%s);",
                tint, alpha, elevated, Math.min(1, alpha + .045 * frost), tint, alpha * .55,
                elevated, Math.min(1, alpha + .035 * frost), .015 + (theme == Theme.LIGHT ? .36 : .14) * frost,
                .025 + .22 * frost, .005 + .12 * frost, selection, .035 + .12 * frost, ink);
        root.setStyle(root.getProperties().get(ORIGINAL_STYLE) + ";" + paint);
        root.getProperties().put("glass.transparency", amount);
        root.getProperties().put("glass.enabled", glass);
        // PopupControl's CSS bridge inherits from its owner, outside the popup scene
        // graph. Theme the public control too so a same-turn theme change cannot
        // leave its skin using the owner's previously resolved lookup colours.
        Scene scene = root.getScene();
        if (scene != null && scene.getRoot() == root && scene.getWindow() instanceof PopupControl popup) {
            if (!popup.getStyleClass().contains("workspace-theme")) popup.getStyleClass().add("workspace-theme");
            selectClass(popup, "liquid-glass", glass);
            selectClass(popup, "glass-disabled", !glass);
            selectClass(popup, "theme-light", theme == Theme.LIGHT);
            selectClass(popup, "theme-dark", theme == Theme.DARK);
            popup.getProperties().putIfAbsent(ORIGINAL_STYLE, popup.getStyle());
            popup.setStyle(popup.getProperties().get(ORIGINAL_STYLE) + ";" + paint);
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
