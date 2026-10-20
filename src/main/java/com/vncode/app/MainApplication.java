package com.vncode.app;

import com.vncode.app.features.print.KizAttachmentCoordinator;
import com.vncode.app.features.finance.FinanceExecutor;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.RegistrationBackgroundLifetime;
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
    private RegistrationBackgroundLifetime registrationBackgroundLifetime;
    private final com.vncode.app.features.gs1.Gs1AutoReminderScheduler gs1Reminders=new com.vncode.app.features.gs1.Gs1AutoReminderScheduler();

    @Override
    public void start(Stage stage) throws IOException {
        stage.getIcons().add(new Image(MainApplication.class.getResourceAsStream("/com/vncode/app/assets/images/logo.png")));
        com.vncode.app.ui.StartupProgress.show(stage,phase->{
            Launcher.prepareDataSession(phase);phase.accept("INITIALIZING_DATA");return HomeController.prepareLocalData();
        },prepared->{
            try{openWorkspace(stage,prepared);}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
        },failure->Launcher.writeStartupLog("startup-data",failure));
    }

    private void openWorkspace(Stage stage,HomeController.PreparedLocalData prepared) throws IOException {
        com.vncode.app.features.license.LicenseRuntime.bootstrap();
        FXMLLoader fxmlLoader = new FXMLLoader(MainApplication.class.getResource("/com/vncode/app/ui/workspace/home-view.fxml"));
        fxmlLoader.setControllerFactory(type->{
            if(type==HomeController.class)return new HomeController(prepared);
            try{return type.getDeclaredConstructor().newInstance();}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        });
        Scene scene = new Scene(fxmlLoader.load());
        homeController = fxmlLoader.getController();
        scene.getStylesheets().add(MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
        ThemeService.applyTheme(scene);
        String testLabel = AppPaths.isZnackRegistrationTestProfile() ? " [ZNACK TEST]" : "";
        stage.setTitle(BuildConfig.getAppName() + testLabel + " v" + BuildConfig.getAppVersion());

        registrationBackgroundLifetime = RegistrationBackgroundLifetime.attach(stage, BuildConfig.getAppName(),
                MainApplication::hasRegistrationWork,
                () -> !hasOtherBackgroundWork() && gs1Reminders.tryStop(),
                MainApplication::showBusyWarning,
                MainApplication::showTrayUnavailableWarning);

        stage.setOnCloseRequest(event -> {
            if (registrationBackgroundLifetime.requestBackground()) {
                event.consume();
                return;
            }
            boolean hasBackgroundWork = hasOtherBackgroundWork() || hasRegistrationWork();
            if(!hasBackgroundWork&&!gs1Reminders.tryStop())hasBackgroundWork=true;
            if (hasBackgroundWork) {
                event.consume();
                showBusyWarning();
            }
        });

        stage.setScene(scene);
        stage.sizeToScene();
        stage.show();
        gs1Reminders.start();
        com.vncode.app.shared.RuntimeDiagnostics.startPulseMonitor();
    }

    private static boolean hasRegistrationWork() {
        return com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.hasPendingJobs()
                || com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.hasPendingJobs()
                || com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.hasActiveJobs()
                || com.vncode.app.integration.znack.registration.OzonCardRegistrationWorkflow.hasActiveJobs();
    }

    private static boolean hasOtherBackgroundWork() {
        return AppTaskExecutor.hasRunningTasks() || com.vncode.app.shared.ShopBatchRegistry.hasWork()
                || KizAttachmentCoordinator.getInstance().hasActiveJobs()
                || com.vncode.app.features.license.LicenseRuntime.hasPendingTasks();
    }

    private static void showBusyWarning() {
        I18nService i18n = I18nService.getInstance();
        AlertService.showWarning(i18n.tr("app.busy.title"), i18n.tr("app.busy.header"), i18n.tr("app.busy.content"));
    }

    private static void showTrayUnavailableWarning() {
        I18nService i18n = I18nService.getInstance();
        AlertService.showWarning(i18n.tr("app.busy.title"),
                i18n.tr("app.background.unavailable.header", "Chưa thể thu nhỏ xuống khay hệ thống"),
                i18n.tr("app.background.unavailable.content", "Hệ thống không hỗ trợ biểu tượng khay hoặc không tạo được biểu tượng. Giữ cửa sổ mở hoặc thu nhỏ xuống thanh tác vụ để việc đăng ký tiếp tục."));
    }

    @Override
    public void stop() {
        if (registrationBackgroundLifetime != null) registrationBackgroundLifetime.close();
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
