package com.vncode.app;

import com.vncode.app.features.print.KizAttachmentCoordinator;
import com.vncode.app.features.finance.FinanceExecutor;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.ui.workspace.HomeController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;


import java.io.IOException;

public class MainApplication extends Application {
    private HomeController homeController;
    private final com.vncode.app.features.gs1.Gs1AutoReminderScheduler gs1Reminders=new com.vncode.app.features.gs1.Gs1AutoReminderScheduler();

    @Override
    public void start(Stage stage) throws IOException {
        com.vncode.app.features.license.LicenseRuntime.bootstrap();
        FXMLLoader fxmlLoader = new FXMLLoader(MainApplication.class.getResource("/com/vncode/app/ui/workspace/home-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load());
        homeController = fxmlLoader.getController();
        scene.getStylesheets().add(MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
        ThemeService.applyTheme(scene);
        String testLabel = AppPaths.isZnackRegistrationTestProfile() ? " [ZNACK TEST]" : "";
        stage.setTitle(BuildConfig.getAppName() + testLabel + " v" + BuildConfig.getAppVersion());

        Image appIcon = new Image(MainApplication.class.getResourceAsStream("/com/vncode/app/assets/images/logo.png"));
        stage.getIcons().add(appIcon);
        stage.setOnCloseRequest(event -> {
            boolean hasBackgroundWork = AppTaskExecutor.hasRunningTasks() || com.vncode.app.shared.ShopBatchRegistry.hasWork() || KizAttachmentCoordinator.getInstance().hasActiveJobs()
                    || com.vncode.app.features.license.LicenseRuntime.hasPendingTasks()
                    || com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.hasActiveJobs()
                    || com.vncode.app.integration.znack.registration.OzonCardRegistrationWorkflow.hasActiveJobs();
            if(!hasBackgroundWork&&!gs1Reminders.tryStop())hasBackgroundWork=true;
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
        gs1Reminders.start();
        com.vncode.app.shared.RuntimeDiagnostics.startPulseMonitor();
    }

    @Override
    public void stop() {
        gs1Reminders.close();
        com.vncode.app.shared.RuntimeDiagnostics.stopPulseMonitor();
        if (homeController != null) {
            homeController.dispose();
        }
        WbSupplyWorkflow.shutdownImageLoader();
        AppTaskExecutor.shutdown();
        FinanceExecutor.shutdown();
        com.vncode.app.features.license.LicenseRuntime.close();
    }
}
