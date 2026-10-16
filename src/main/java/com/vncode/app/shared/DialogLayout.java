package com.vncode.app.shared;

import javafx.scene.control.DialogPane;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** Bounds the dialog before showing; it leaves the existing content and button bar intact. */
public final class DialogLayout {
    private DialogLayout() {}
    public static void install(DialogPane pane){
        if(pane.getProperties().putIfAbsent(DialogLayout.class,Boolean.TRUE)!=null)return;
        Runnable fit=()->fit(pane);
        pane.prefWidthProperty().addListener((o,a,b)->fit.run());
        pane.prefHeightProperty().addListener((o,a,b)->fit.run());
        pane.contentProperty().addListener((o,a,b)->fit.run());
        pane.sceneProperty().addListener((o,a,b)->{if(b!=null){b.windowProperty().addListener((v,old,window)->watch(window,fit));watch(b.getWindow(),fit);}});
        if(pane.getScene()!=null)watch(pane.getScene().getWindow(),fit);
        fit.run();
    }
    private static void watch(Window window,Runnable fit){
        if(window==null)return;
        window.addEventHandler(WindowEvent.WINDOW_SHOWING,event->fit.run());
        fit.run();
    }
    private static void fit(DialogPane pane){
        var screen=Screen.getPrimary().getVisualBounds();double width=screen.getWidth()-40,height=screen.getHeight()-80;
        var window=pane.getScene()==null?null:pane.getScene().getWindow();
        if(window instanceof Stage stage&&stage.getOwner()!=null){
            var owner=stage.getOwner();
            if(owner.getWidth()>480)width=Math.min(width,owner.getWidth()-32);
            if(owner.getHeight()>360)height=Math.min(height,owner.getHeight()-60);
        }
        pane.setMinSize(0,0);pane.setMaxSize(width,height);
        if(pane.getPrefWidth()>width)pane.setPrefWidth(width);
        if(pane.getPrefHeight()>height)pane.setPrefHeight(height);
        if(pane.getContent() instanceof Region content)content.setMinSize(0,0);
    }
}
