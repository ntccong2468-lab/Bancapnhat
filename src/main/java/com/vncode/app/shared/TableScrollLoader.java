package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.input.ScrollEvent;
import java.util.function.BooleanSupplier;

/** Requests one more local page near the end of a virtual table, including scrollbar dragging. */
public final class TableScrollLoader {
    private TableScrollLoader(){}
    public static void install(TableView<?> table,BooleanSupplier canLoad,Runnable load) {
        if(table.getProperties().putIfAbsent(TableScrollLoader.class,Boolean.TRUE)!=null)return;
        Runnable check=()->{
            if(!canLoad.getAsBoolean()||!table.isVisible()||table.getItems().isEmpty())return;
            for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL&&bar.isVisible()&&bar.getMax()>bar.getMin()) {
                if((bar.getValue()-bar.getMin())/(bar.getMax()-bar.getMin())>=0.92)load.run();
                return;
            }
        };
        Runnable attach=()->{
            for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL&&bar.getProperties().putIfAbsent(TableScrollLoader.class,Boolean.TRUE)==null)
                bar.valueProperty().addListener((o,a,b)->Platform.runLater(check));
        };
        table.skinProperty().addListener((o,a,b)->Platform.runLater(attach));
        table.addEventHandler(ScrollEvent.SCROLL,event->Platform.runLater(check));
        table.addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED,event->Platform.runLater(check));
        Platform.runLater(attach);
    }
}
