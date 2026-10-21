package com.vncode.app.ui.znackregistration;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** FX-thread status overlay. A previous completion timer cannot remove a newer task. */
final class RegistrationStatusPill {
    private RegistrationStatusPill() {}
    static HBox show(StackPane host,String id,String message,boolean running) {
        return show(host,id,message,running,!running);
    }
    static HBox show(StackPane host,String id,String message,boolean running,boolean dismiss) {
        host.getChildren().removeIf(node->id.equals(node.getId()));
        var label=new Label(message);label.setWrapText(true);label.setMinWidth(0);
        label.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(160,host.widthProperty().subtract(80)));
        var pill=new HBox(10);pill.setId(id);pill.setAlignment(Pos.CENTER);
        if(running){var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);pill.getChildren().add(spinner);}
        pill.getChildren().add(label);pill.getStyleClass().add("task-status-pill");
        pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);
        StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));
        host.getChildren().add(pill);
        if(dismiss)dismissAfter(host,pill,Duration.seconds(8));
        return pill;
    }
    static void dismissAfter(StackPane host,HBox pill,Duration delay) {
        var timer=new PauseTransition(delay);timer.setOnFinished(event->host.getChildren().remove(pill));
        timer.play();
    }
}
