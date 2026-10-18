package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import java.util.*;

/** Shared glass paints and high-contrast foregrounds; whole-window opacity stays intact. */
public final class ThemeService {
    private static final String GLASS_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/liquid-glass.css")).toExternalForm();
    private static final String BASE_CSS = Objects.requireNonNull(ThemeService.class.getResource("/com/vncode/app/styles/theme.css")).toExternalForm();
    private static final Set<Parent> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final String ORIGINAL_STYLE = ThemeService.class.getName()+".originalStyle";
    private static boolean observingWindows;
    public static final int DEFAULT_TRANSPARENCY = 55;
    private static final String GLASS_STYLE_VERSION = "blue-glass-1";

    public enum Theme {
        DARK("dark"), LIGHT("light");
        private final String key;
        Theme(String key) { this.key=key; }
        public String getKey() { return key; }
        public static Theme fromKey(String key) { return "dark".equalsIgnoreCase(key)?DARK:LIGHT; }
    }
    /** Apply the approved blue reference once on upgrade; later theme choices remain the user's. */
    public static void initializeAppearance() {
        if (!GLASS_STYLE_VERSION.equals(ConfigService.getConfigValue("glass_style_version"))) {
            ConfigService.setConfigValue("app_theme",Theme.LIGHT.getKey());
            ConfigService.setConfigValue("glass_style_version",GLASS_STYLE_VERSION);
        }
    }
    public static Theme getCurrentTheme() { return Theme.fromKey(ConfigService.getConfigValue("app_theme")); }
    public static void setCurrentTheme(Theme theme) { switchTheme(theme.getKey()); }
    public static int getGlassTransparency() {
        try { return bounded(Double.parseDouble(ConfigService.getConfigValue("glass_transparency"))); }
        catch (RuntimeException invalid) { return DEFAULT_TRANSPARENCY; }
    }
    private static int bounded(double value) { return Double.isFinite(value)?(int)Math.round(Math.clamp(value,0,100)):DEFAULT_TRANSPARENCY; }
    public static void setGlassTransparency(double value) {
        int amount=bounded(value);
        ConfigService.setConfigValue("glass_transparency",Integer.toString(amount));
        previewGlassTransparency(amount);
    }
    /** Slider previews perform no SQL writes and never change Node/Window opacity. */
    public static void previewGlassTransparency(double value) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(()->previewGlassTransparency(value)); return; }
        var theme=getCurrentTheme();int amount=bounded(value);
        for(var root:List.copyOf(ROOTS)) material(root,theme,amount);
    }
    public static void applyTheme(Scene scene) {
        if(scene==null)return;
        UiFonts.install();observeWindows();
        if(!scene.getStylesheets().contains(BASE_CSS))scene.getStylesheets().add(BASE_CSS);
        if(!scene.getStylesheets().contains(GLASS_CSS))scene.getStylesheets().add(GLASS_CSS);
        if(!scene.getProperties().containsKey(ThemeService.class)) {
            scene.getProperties().put(ThemeService.class,Boolean.TRUE);
            scene.rootProperty().addListener((observable,oldRoot,newRoot)->applyTheme(scene));
        }
        material(scene.getRoot(),getCurrentTheme(),getGlassTransparency());
    }
    public static void applyTheme(DialogPane pane) {
        if(pane==null)return;
        UiFonts.install();observeWindows();
        if(!pane.getStylesheets().contains(BASE_CSS))pane.getStylesheets().add(BASE_CSS);
        if(!pane.getStylesheets().contains(GLASS_CSS))pane.getStylesheets().add(GLASS_CSS);
        material(pane,getCurrentTheme(),getGlassTransparency());
    }
    private static void observeWindows() {
        if(observingWindows||!Platform.isFxApplicationThread())return;
        observingWindows=true;
        Window.getWindows().addListener((ListChangeListener<Window>)changes->{
            while(changes.next())if(changes.wasAdded())for(var window:changes.getAddedSubList()){
                observeWindow(window);
            }
        });
        for(var window:List.copyOf(Window.getWindows()))observeWindow(window);
    }
    private static void observeWindow(Window window){
        if(!window.getProperties().containsKey(ThemeService.class)){
            window.getProperties().put(ThemeService.class,Boolean.TRUE);
            window.sceneProperty().addListener((observable,previous,scene)->applyTheme(scene));
        }
        if(window.getScene()!=null)applyTheme(window.getScene());
    }
    private static void material(Parent root,Theme theme,int amount) {
        if(root==null)return;
        ROOTS.add(root);
        if(!root.getStyleClass().contains("liquid-glass"))root.getStyleClass().add("liquid-glass");
        String selected=theme==Theme.LIGHT?"theme-light":"theme-dark";
        if(!root.getStyleClass().contains(selected))root.getStyleClass().add(selected);
        root.getStyleClass().remove(theme==Theme.LIGHT?"theme-dark":"theme-light");
        root.getProperties().putIfAbsent(ORIGINAL_STYLE,root.getStyle());
        // The left end is clear water; increasing the slider adds the frosted material.
        // Keep alpha in paints, never on a parent Node (which also dims its text).
        double alpha=.005+amount*.00995;
        String tint=theme==Theme.LIGHT?"199,226,246":"22,48,75";
        String elevated=theme==Theme.LIGHT?"226,241,252":"33,64,94";
        String ink=theme==Theme.LIGHT?"0,5,12":"249,252,255";
        double frost=amount/100.0;
        String paint=String.format(Locale.ROOT,"-glass-surface:rgba(%s,%.3f);-glass-elevated:rgba(%s,%.3f);-glass-row:rgba(%s,%.3f);-glass-button:rgba(%s,%.3f);-glass-highlight:rgba(255,255,255,%.3f);-glass-control-highlight:rgba(255,255,255,%.3f);-glass-control-shadow:rgba(20,94,158,%.3f);-glass-selected:rgba(%s,%.3f);-text-primary:rgb(%s);",tint,alpha,elevated,Math.min(1,alpha+.045*frost),tint,alpha*.55,elevated,Math.min(1,alpha+.035*frost),.015+(theme==Theme.LIGHT?.36:.14)*frost,.025+.22*frost,.005+.12*frost,theme==Theme.LIGHT?"54,117,190":"110,180,242",.035+.22*frost,ink);
        root.setStyle(root.getProperties().get(ORIGINAL_STYLE)+";"+paint);
        root.getProperties().put("glass.transparency",amount);
    }
    public static void switchTheme(String themeName) {
        ConfigService.setConfigValue("app_theme",Theme.fromKey(themeName).getKey());
        previewGlassTransparency(getGlassTransparency());
    }
}
