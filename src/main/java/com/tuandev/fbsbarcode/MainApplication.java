package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator;
import com.tuandev.fbsbarcode.features.finance.FinanceExecutor;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.shared.ThemeService;
import com.tuandev.fbsbarcode.ui.workspace.HomeController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;


import java.io.IOException;

public class MainApplication extends Application {
    private HomeController homeController;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApplication.class.getResource("/com/tuandev/fbsbarcode/ui/workspace/home-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load());
        homeController = fxmlLoader.getController();
        scene.getStylesheets().add(MainApplication.class.getResource("/com/tuandev/fbsbarcode/styles/theme.css").toExternalForm());
        ThemeService.applyTheme(scene);
        String testLabel = AppPaths.isZnackRegistrationTestProfile() ? " [ZNACK TEST]" : "";
        stage.setTitle("WCode" + testLabel + " v" + BuildConfig.getAppVersion() + " (Zalo: 0335407670)");

        Image appIcon = new Image(MainApplication.class.getResourceAsStream("/com/tuandev/fbsbarcode/assets/images/logo.png"));
        stage.getIcons().add(appIcon);
        stage.setOnCloseRequest(event -> {
            boolean hasBackgroundWork = AppTaskExecutor.hasRunningTasks() || KizAttachmentCoordinator.getInstance().hasActiveJobs();
            if (hasBackgroundWork) {
                event.consume();
                I18nService i18n = I18nService.getInstance();
                AlertService.showWarning(
                        i18n.tr("app.busy.title"),
                        i18n.tr("app.busy.header"),
                        i18n.tr("app.busy.content")
                );
            }
        });

        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (homeController != null) {
            homeController.dispose();
        }
        WbSupplyWorkflow.shutdownImageLoader();
        AppTaskExecutor.shutdown();
        FinanceExecutor.shutdown();
    }
}
