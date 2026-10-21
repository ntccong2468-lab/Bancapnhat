package com.vncode.app.ui.gs1;

import javafx.scene.control.Dialog;
import javafx.stage.Window;

/** Position GS1 dialogs after JavaFX has measured their content. */
final class Gs1Dialogs {
    private Gs1Dialogs() {}
    static void centerOnOwner(Dialog<?> dialog) {
        dialog.setOnShown(event -> {
            Window owner = dialog.getOwner();
            Window window = dialog.getDialogPane().getScene().getWindow();
            if (owner == null || !owner.isShowing()) return;
            window.setX(owner.getX() + (owner.getWidth() - window.getWidth()) / 2);
            window.setY(owner.getY() + (owner.getHeight() - window.getHeight()) / 2);
        });
    }
}
