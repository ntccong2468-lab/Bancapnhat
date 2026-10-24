package com.vncode.app.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Fit the decorated window to the work area, including a taskbar on any edge. */
public final class WorkspaceWindowBounds {
    private WorkspaceWindowBounds() { }

    public static void install(Stage stage) {
        if(stage.getProperties().putIfAbsent(WorkspaceWindowBounds.class,Boolean.TRUE)!=null)return;
        var debounce = new PauseTransition(Duration.millis(80));
        debounce.setOnFinished(event -> fit(stage));
        javafx.beans.InvalidationListener changed = ignored -> debounce.playFromStart();
        stage.xProperty().addListener(changed);
        stage.yProperty().addListener(changed);
        stage.widthProperty().addListener(changed);
        stage.heightProperty().addListener(changed);
        stage.maximizedProperty().addListener(changed);
        javafx.collections.ListChangeListener<Screen> screensChanged = ignored -> debounce.playFromStart();
        Screen.getScreens().addListener(screensChanged);
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, event -> {
            debounce.stop();
            Screen.getScreens().removeListener(screensChanged);
        });
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, event -> {
            Screen.getScreens().removeListener(screensChanged);
            Screen.getScreens().addListener(screensChanged);
            fit(stage);
        });
        fit(stage);
    }

    private static void fit(Stage stage) {
        if (!stage.isShowing() || stage.isIconified() || stage.isFullScreen() || stage.isMaximized()) return;
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        var screen = screens.stream().max(java.util.Comparator.comparingDouble(value ->
                intersection(value.getBounds(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())))
                .orElse(Screen.getPrimary());
        var area = screen.getVisualBounds();
        var fitted = fit(area, stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        if (stage.getMinWidth() > area.getWidth()) stage.setMinWidth(area.getWidth());
        if (stage.getMinHeight() > area.getHeight()) stage.setMinHeight(area.getHeight());
        if (Math.abs(stage.getWidth() - fitted.getWidth()) > .5) stage.setWidth(fitted.getWidth());
        if (Math.abs(stage.getHeight() - fitted.getHeight()) > .5) stage.setHeight(fitted.getHeight());
        if (Math.abs(stage.getX() - fitted.getMinX()) > .5) stage.setX(fitted.getMinX());
        if (Math.abs(stage.getY() - fitted.getMinY()) > .5) stage.setY(fitted.getMinY());
    }

    static Rectangle2D fit(Rectangle2D area, double x, double y, double width, double height) {
        double w = Math.min(area.getWidth(), Math.max(1, width));
        double h = Math.min(area.getHeight(), Math.max(1, height));
        return new Rectangle2D(Math.max(area.getMinX(), Math.min(x, area.getMaxX() - w)),
                Math.max(area.getMinY(), Math.min(y, area.getMaxY() - h)), w, h);
    }

    private static double intersection(Rectangle2D screen, double x, double y, double width, double height) {
        return Math.max(0, Math.min(screen.getMaxX(), x + width) - Math.max(screen.getMinX(), x))
                * Math.max(0, Math.min(screen.getMaxY(), y + height) - Math.max(screen.getMinY(), y));
    }
}
