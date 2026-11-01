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
        // Scroll events and scrollbar value changes may arrive together for one gesture.
        // Inspect the final position once on the next FX turn instead of queuing a
        // lookup for every event in the burst.
        boolean[] checkQueued={false};
        Runnable scheduleCheck=()->{
            if(checkQueued[0])return;
            checkQueued[0]=true;
            Platform.runLater(()->{checkQueued[0]=false;check.run();});
        };
        Runnable attach=()->{
            for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL&&bar.getProperties().putIfAbsent(TableScrollLoader.class,Boolean.TRUE)==null)
                bar.valueProperty().addListener((o,a,b)->scheduleCheck.run());
        };
        table.skinProperty().addListener((o,a,b)->Platform.runLater(attach));
        table.addEventHandler(ScrollEvent.SCROLL,event->scheduleCheck.run());
        table.addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED,event->scheduleCheck.run());
        Platform.runLater(attach);
    }
}
