package com.vncode.app.shared;

import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import org.kordamp.ikonli.javafx.FontIcon;

/** Explicit app-owned Dialog chrome; ordinary Stages and OS-owned windows are never converted. */
public final class WorkspaceDialogChrome {
    private static final Object KEY = WorkspaceDialogChrome.class;
    private static final double SHADOW = 12, TITLE_X = 30, TITLE_Y = 16, TITLE_HEIGHT = 32;
    private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    private WorkspaceDialogChrome() { }

    /** Must be requested before the first show. Existing Dialog/result/lifecycle handlers remain authoritative. */
    public static void install(Dialog<?> dialog) {
        if (dialog == null || dialog.getDialogPane().getProperties().containsKey(KEY) || dialog.isShowing()) return;
        DialogPane pane = dialog.getDialogPane(); Scene scene = pane.getScene();
        if (scene == null || !(scene.getWindow() instanceof Stage stage) || scene.fillProperty().isBound() || pane.paddingProperty().isBound()) return;
        if (stage.getStyle() != StageStyle.DECORATED && stage.getStyle() != StageStyle.TRANSPARENT) return;
        // An explicit custom Scene fill remains the caller's window rather than becoming transparent.
        if (!Color.WHITE.equals(scene.getFill()) && !Color.TRANSPARENT.equals(scene.getFill())) return;
        try { dialog.initStyle(StageStyle.TRANSPARENT); }
        catch (IllegalStateException alreadyShown) { return; }
        var chrome = new Chrome(dialog); chrome.attach(pane);
        dialog.dialogPaneProperty().addListener((observable, previous, next) -> chrome.attach(next));
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> chrome.beforeShow());
        // Dialog.showAndWait fires DIALOG_SHOWN before the native Stage is actually shown/positioned.
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> chrome.fit());
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> chrome.stopGesture());
    }

    private static final class Chrome {
        private final Dialog<?> dialog;
        private final HBox bar = new HBox(8);
        private final Label title = new Label();
        private final Button close = new Button();
        private DialogPane pane;
        private Cursor originalCursor;
        private Cursor ownedCursor;
        private Gesture gesture;
        private double compensatedWidth = Double.NaN, compensatedHeight = Double.NaN;
        private double originalWidth = Double.NaN, originalHeight = Double.NaN;
        private Scene fillScene;
        private boolean callerFill, writingFill;
        private final javafx.beans.value.ChangeListener<Paint> fillChanged = (observable, previous, fill) -> { if (!writingFill) callerFill = true; };
        private final javafx.beans.value.ChangeListener<Scene> sceneChanged = (observable, previous, scene) -> { if (scene != null) transparent(scene); };
        private final javafx.beans.InvalidationListener layout = ignored -> positionBar();
        private final EventHandler<MouseEvent> moved = this::mouseMoved;
        private final EventHandler<MouseEvent> pressed = this::resizePressed;
        private final EventHandler<MouseEvent> dragged = this::resizeDragged;
        private final EventHandler<MouseEvent> released = event -> stopGesture();
        private final EventHandler<MouseEvent> exited = event -> { if (gesture == null) restoreCursor(); };

        private Chrome(Dialog<?> dialog) {
            this.dialog = dialog;
            bar.setManaged(false); bar.setPickOnBounds(true); bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("workspace-window-chrome");
            title.getStyleClass().add("workspace-window-title"); title.textProperty().bind(dialog.titleProperty());
            title.setMouseTransparent(true); title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
            close.getStyleClass().addAll("btn-icon", "workspace-window-close"); close.setGraphic(new FontIcon("fth-x"));
            close.setAccessibleText(I18nService.getInstance().tr("common.close")); close.setOnAction(event -> dialog.close());
            bar.getChildren().addAll(title, close);
            bar.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY || interactive(event.getTarget())) return;
                Stage stage = stage(); if (stage == null) return;
                if (event.getClickCount() == 2 && dialog.isResizable()) { stage.setMaximized(!stage.isMaximized()); event.consume(); return; }
                if (!stage.isMaximized()) gesture = new Gesture(0, event.getScreenX(), event.getScreenY(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                event.consume();
            });
            bar.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
                Stage stage = stage(); if (stage == null || gesture == null || gesture.edges != 0) return;
                var bounds = screen(event.getScreenX(), event.getScreenY());
                double x = gesture.x + event.getScreenX() - gesture.pointerX, y = gesture.y + event.getScreenY() - gesture.pointerY;
                stage.setX(Math.clamp(x, bounds.getMinX() - stage.getWidth() + 80, bounds.getMaxX() - 80));
                stage.setY(Math.clamp(y, bounds.getMinY(), bounds.getMaxY() - TITLE_HEIGHT - SHADOW)); event.consume();
            });
            bar.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> stopGesture());
        }

        private void attach(DialogPane next) {
            if (pane != null) {
                releaseDimensions();
                stopGesture(); pane.getChildren().remove(bar); pane.getProperties().remove(KEY); pane.getStyleClass().remove("floating-dialog-pane");
                pane.widthProperty().removeListener(layout); pane.heightProperty().removeListener(layout);
                pane.sceneProperty().removeListener(sceneChanged);
                pane.removeEventFilter(MouseEvent.MOUSE_MOVED, moved); pane.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
                pane.removeEventFilter(MouseEvent.MOUSE_DRAGGED, dragged); pane.removeEventFilter(MouseEvent.MOUSE_RELEASED, released); pane.removeEventFilter(MouseEvent.MOUSE_EXITED, exited);
            }
            pane = next; compensatedWidth = Double.NaN; compensatedHeight = Double.NaN;
            if (pane == null) return;
            pane.getProperties().put(KEY, this); pane.getStyleClass().add("floating-dialog-pane"); originalCursor = pane.getCursor();
            pane.getChildren().add(bar); pane.widthProperty().addListener(layout); pane.heightProperty().addListener(layout);
            pane.addEventFilter(MouseEvent.MOUSE_MOVED, moved); pane.addEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
            pane.addEventFilter(MouseEvent.MOUSE_DRAGGED, dragged); pane.addEventFilter(MouseEvent.MOUSE_RELEASED, released); pane.addEventFilter(MouseEvent.MOUSE_EXITED, exited);
            pane.sceneProperty().addListener(sceneChanged);
            if (pane.getScene() != null) transparent(pane.getScene()); positionBar();
        }

        private void transparent(Scene scene) {
            if (fillScene != scene) {
                if (fillScene != null) fillScene.fillProperty().removeListener(fillChanged);
                fillScene = scene;
                callerFill = scene.fillProperty().isBound() || (!Color.WHITE.equals(scene.getFill()) && !Color.TRANSPARENT.equals(scene.getFill()));
                scene.fillProperty().addListener(fillChanged);
            }
            if (scene.fillProperty().isBound()) { callerFill = true; return; }
            if (callerFill) return;
            scene.getProperties().put(ThemeService.PRESERVE_SCENE_FILL, Boolean.TRUE);
            writingFill = true;
            try { scene.setFill(Color.TRANSPARENT); }
            finally { writingFill = false; }
            if (scene.fillProperty().isBound() || !Color.TRANSPARENT.equals(scene.getFill())) callerFill = true;
        }

        private void beforeShow() {
            if (pane == null) return;
            if (pane.getScene() != null) transparent(pane.getScene());
            // Explicit old dimensions described content plus 18px padding. Keep that content work area.
            if (!pane.prefWidthProperty().isBound() && pane.getPrefWidth() > 0 && pane.getPrefWidth() != compensatedWidth) {
                originalWidth = pane.getPrefWidth(); compensatedWidth = originalWidth + 24; pane.setPrefWidth(compensatedWidth);
            }
            if (!pane.prefHeightProperty().isBound() && pane.getPrefHeight() > 0 && pane.getPrefHeight() != compensatedHeight) {
                originalHeight = pane.getPrefHeight(); compensatedHeight = originalHeight + 50; pane.setPrefHeight(compensatedHeight);
            }
            positionBar();
        }

        private void releaseDimensions() {
            if (!pane.prefWidthProperty().isBound() && pane.getPrefWidth() == compensatedWidth) pane.setPrefWidth(originalWidth);
            if (!pane.prefHeightProperty().isBound() && pane.getPrefHeight() == compensatedHeight) pane.setPrefHeight(originalHeight);
        }

        private void positionBar() { if (pane != null) bar.resizeRelocate(TITLE_X, TITLE_Y, Math.max(0, pane.getWidth() - TITLE_X * 2), TITLE_HEIGHT); }
        private Stage stage() { return pane != null && pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage ? stage : null; }
        private void fit() {
            Stage stage = stage(); if (stage == null) return;
            var bounds = screen(stage.getX() + stage.getWidth() / 2, stage.getY() + stage.getHeight() / 2);
            stage.setWidth(Math.min(stage.getWidth(), Math.max(1, bounds.getWidth() - 24)));
            stage.setHeight(Math.min(stage.getHeight(), Math.max(1, bounds.getHeight() - 24)));
            stage.setX(Math.clamp(stage.getX(), bounds.getMinX(), Math.max(bounds.getMinX(), bounds.getMaxX() - stage.getWidth())));
            stage.setY(Math.clamp(stage.getY(), bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - stage.getHeight())));
        }

        private int edges(MouseEvent event) {
            if (!dialog.isResizable()) return 0; Stage stage = stage(); if (stage == null || stage.isMaximized()) return 0;
            Point2D point = pane.sceneToLocal(event.getSceneX(), event.getSceneY()); double x = point.getX(), y = point.getY(); int edges = 0;
            if (Math.abs(x - SHADOW) <= 5) edges |= LEFT; if (Math.abs(x - (pane.getWidth() - SHADOW)) <= 5) edges |= RIGHT;
            if (Math.abs(y - SHADOW) <= 5) edges |= TOP; if (Math.abs(y - (pane.getHeight() - SHADOW)) <= 5) edges |= BOTTOM;
            return edges;
        }

        private void mouseMoved(MouseEvent event) {
            if (gesture != null || pane.cursorProperty().isBound()) return;
            if (interactive(event.getTarget())) { restoreCursor(); return; }
            int edges = edges(event); Cursor cursor = switch (edges) {
                case LEFT, RIGHT -> Cursor.H_RESIZE; case TOP, BOTTOM -> Cursor.V_RESIZE;
                case LEFT | TOP, RIGHT | BOTTOM -> Cursor.NW_RESIZE; case RIGHT | TOP, LEFT | BOTTOM -> Cursor.NE_RESIZE; default -> null;
            };
            if (cursor == null) restoreCursor(); else {
                if (ownedCursor == null || pane.getCursor() != ownedCursor) originalCursor = pane.getCursor();
                ownedCursor = cursor; pane.setCursor(cursor);
            }
        }

        private void resizePressed(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY || event.isConsumed() || interactive(event.getTarget())) return;
            int edges = edges(event); Stage stage = stage(); if (edges == 0 || stage == null) return;
            gesture = new Gesture(edges, event.getScreenX(), event.getScreenY(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()); event.consume();
        }

        private void resizeDragged(MouseEvent event) {
            Stage stage = stage(); if (stage == null || gesture == null || gesture.edges == 0) return;
            var bounds = screen(event.getScreenX(), event.getScreenY()); double dx = event.getScreenX() - gesture.pointerX, dy = event.getScreenY() - gesture.pointerY;
            double maxW = Math.min(bounds.getWidth() - 24, stage.getMaxWidth()), maxH = Math.min(bounds.getHeight() - 24, stage.getMaxHeight());
            double minW = Math.min(maxW, Math.max(160, stage.getMinWidth())), minH = Math.min(maxH, Math.max(120, stage.getMinHeight()));
            double width = Math.clamp(gesture.width + ((gesture.edges & RIGHT) != 0 ? dx : (gesture.edges & LEFT) != 0 ? -dx : 0), minW, maxW);
            double height = Math.clamp(gesture.height + ((gesture.edges & BOTTOM) != 0 ? dy : (gesture.edges & TOP) != 0 ? -dy : 0), minH, maxH);
            double x = (gesture.edges & LEFT) != 0 ? gesture.x + gesture.width - width : gesture.x;
            double y = (gesture.edges & TOP) != 0 ? gesture.y + gesture.height - height : gesture.y;
            stage.setWidth(width); stage.setHeight(height);
            stage.setX(Math.clamp(x, bounds.getMinX(), bounds.getMaxX() - width)); stage.setY(Math.clamp(y, bounds.getMinY(), bounds.getMaxY() - height)); event.consume();
        }

        private void stopGesture() { gesture = null; restoreCursor(); }
        private void restoreCursor() { if (pane != null && ownedCursor != null && !pane.cursorProperty().isBound() && pane.getCursor() == ownedCursor) pane.setCursor(originalCursor); ownedCursor = null; }
    }

    private static boolean interactive(Object target) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null; current = current.getParent()) if (current instanceof ButtonBase) return true;
        return false;
    }
    private static Rectangle2D screen(double x, double y) { var screens = Screen.getScreensForRectangle(x, y, 1, 1); return (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds(); }
    private record Gesture(int edges, double pointerX, double pointerY, double x, double y, double width, double height) { }
}
