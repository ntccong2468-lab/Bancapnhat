package com.tuandev.fbsbarcode.shared;

import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThemeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeService.class);
    
    public enum Theme {
        DARK("dark"),
        LIGHT("light");

        private final String key;

        Theme(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static Theme fromKey(String key) {
            for (Theme t : values()) {
                if (t.key.equalsIgnoreCase(key)) {
                    return t;
                }
            }
            return DARK; // Default
        }
    }

    public static Theme getCurrentTheme() {
        String val = ConfigService.getConfigValue("app_theme");
        if (val == null || val.isBlank()) {
            return Theme.DARK; // default
        }
        return Theme.fromKey(val);
    }

    public static void setCurrentTheme(Theme theme) {
        ConfigService.setConfigValue("app_theme", theme.getKey());
        switchTheme(theme.getKey());
    }

    public static void applyTheme(Scene scene) {
        if (scene == null) return;
        Parent root = scene.getRoot();
        if (root != null) {
            root.getStyleClass().removeAll("theme-light", "theme-dark");
            Theme current = getCurrentTheme();
            if (current == Theme.LIGHT) {
                root.getStyleClass().add("theme-light");
            } else {
                root.getStyleClass().add("theme-dark");
            }
        }
    }

    public static void applyTheme(DialogPane pane) {
        if (pane == null) return;
        pane.getStyleClass().removeAll("theme-light", "theme-dark");
        Theme current = getCurrentTheme();
        if (current == Theme.LIGHT) {
            pane.getStyleClass().add("theme-light");
        } else {
            pane.getStyleClass().add("theme-dark");
        }
    }

    public static void switchTheme(String themeName) {
        ConfigService.setConfigValue("app_theme", themeName);
        Theme theme = Theme.fromKey(themeName);
        
        // Apply to all open windows/scenes
        try {
            for (Window window : Window.getWindows()) {
                if (window.getScene() != null) {
                    applyTheme(window.getScene());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to dynamically switch theme for windows", e);
        }
    }
}
