package com.vncode.app.shared;

import javafx.geometry.Insets;
import javafx.scene.layout.*;
import javafx.scene.control.Label;

/** Static placeholders avoid animation load and are used only while an empty view loads. */
public final class LoadingSkeleton extends VBox {
    public LoadingSkeleton(String accessibleText){
        super(16);setId("loadingSkeleton");setMouseTransparent(true);setMaxWidth(Double.MAX_VALUE);setPadding(new Insets(16));setAccessibleText(accessibleText);
        var caption=new Label(accessibleText);caption.getStyleClass().add("text-muted");getChildren().add(caption);
        for(int n=0;n<4;n++){
            var photo=new Region();photo.setMinSize(42,56);photo.setPrefSize(42,56);photo.setMaxSize(42,56);photo.getStyleClass().add("fbo-image-placeholder");
            var lines=new VBox(10);for(int k=0;k<3;k++){var line=new Region();line.setMinHeight(10);line.setPrefHeight(10);line.setMaxWidth(k==2?160:k==1?240:360);line.getStyleClass().add("fbo-image-placeholder");lines.getChildren().add(line);}
            HBox.setHgrow(lines,Priority.ALWAYS);getChildren().add(new HBox(14,photo,lines));
        }
    }
}
