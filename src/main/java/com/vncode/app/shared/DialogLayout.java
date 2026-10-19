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
        if(window instanceof Stage stage&&stage.getOwner()!=null&&window.getProperties().putIfAbsent(DialogLayout.class,Boolean.TRUE)==null){
            var owner=stage.getOwner();
            javafx.beans.value.ChangeListener<Number> resized=(o,a,b)->fit.run();
            owner.widthProperty().addListener(resized);owner.heightProperty().addListener(resized);
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN,event->{owner.widthProperty().removeListener(resized);owner.heightProperty().removeListener(resized);window.getProperties().remove(DialogLayout.class);});
        }
        fit.run();
    }
    private static void fit(DialogPane pane){
        var window=pane.getScene()==null?null:pane.getScene().getWindow();
        var screens=window==null?java.util.List.of(Screen.getPrimary()):Screen.getScreensForRectangle(window.getX(),window.getY(),Math.max(1,window.getWidth()),Math.max(1,window.getHeight()));
        var screen=(screens.isEmpty()?Screen.getPrimary():screens.getFirst()).getVisualBounds();double width=screen.getWidth()-40,height=screen.getHeight()-80;
        if(window instanceof Stage stage&&stage.getOwner()!=null){
            var owner=stage.getOwner();
            if(owner.getWidth()>0)width=Math.min(width,Math.max(160,owner.getWidth()-32));
            if(owner.getHeight()>0)height=Math.min(height,Math.max(120,owner.getHeight()-60));
        }
        pane.setMinSize(0,0);pane.setMaxSize(width,height);
        if(pane.getPrefWidth()>width)pane.setPrefWidth(width);
        if(pane.getPrefHeight()>height)pane.setPrefHeight(height);
        if(pane.getContent() instanceof Region content){
            content.setMinSize(0,0);
            if(!(content instanceof javafx.scene.control.ScrollPane)&&content.prefHeight(width)>Math.max(40,height-160)){
                var scroll=new javafx.scene.control.ScrollPane(content);scroll.setFitToWidth(true);scroll.setMinSize(0,0);scroll.setMaxHeight(Math.max(40,height-160));scroll.setPrefViewportHeight(Math.max(40,height-160));pane.setContent(scroll);
            }else if(content instanceof javafx.scene.control.ScrollPane scroll)scroll.setMaxHeight(Math.max(40,height-160));
        }
        if(window!=null&&window.isShowing()){
            if(window.getHeight()>height+36)window.setHeight(height+36);
            if(window.getWidth()>width+20)window.setWidth(width+20);
        }
    }
}
