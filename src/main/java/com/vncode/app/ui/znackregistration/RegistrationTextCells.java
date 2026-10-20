package com.vncode.app.ui.znackregistration;

import javafx.scene.control.*;

/** Status explanations stay readable at laptop widths instead of disappearing into ellipses. */
final class RegistrationTextCells {
    static <T> void wrap(TableColumn<T,String> column){
        column.setCellFactory(ignored->new TableCell<>(){
            private final Label text=new Label();
            {text.setWrapText(true);text.maxWidthProperty().bind(column.widthProperty().subtract(16));text.prefWidthProperty().bind(column.widthProperty().subtract(16));}
            protected void updateItem(String value,boolean empty){super.updateItem(value,empty);text.setText(empty?"":value);setText(null);setGraphic(empty?null:text);setTooltip(empty||value==null||value.isBlank()?null:new Tooltip(value));}
        });
    }
}
