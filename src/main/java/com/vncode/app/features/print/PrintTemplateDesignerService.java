package com.vncode.app.features.print;

import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.ui.print.PrintTemplateDesignerController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class PrintTemplateDesignerService {
    public void showDialog() {
        FXMLLoader loader = FxmlViewLoader.loader(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        Scene scene = new Scene(FxmlViewLoader.load(loader));
        scene.getStylesheets().add(java.util.Objects.requireNonNull(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css")).toExternalForm());
        ThemeService.applyTheme(scene);

        Stage stage = new Stage();
        stage.setTitle(I18nService.getInstance().tr("template.window_title"));
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.showAndWait();
    }
}
