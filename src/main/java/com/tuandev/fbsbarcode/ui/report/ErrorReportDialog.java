package com.tuandev.fbsbarcode.ui.report;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.integration.license.DeviceFingerprint;
import com.tuandev.fbsbarcode.integration.license.ReportApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.ConfigService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * Dialog hiển thị lỗi ở dạng chữ dễ đọc cho người dùng, kèm nút "Báo cáo" để gửi lỗi
 * (kèm cửa hàng + license) về server cho admin.
 */
public final class ErrorReportDialog {

    private final I18nService i18n = I18nService.getInstance();

    private ErrorReportDialog() {}

    /** Mở dialog. Gọi trên FX thread. */
    public static void show(String shopName, String entity, String action, String rawError) {
        new ErrorReportDialog().open(shopName, entity, action, rawError);
    }

    private void open(String shopName, String entity, String action, String rawError) {
        String message = ZnackErrorMessages.display(rawError);
        String errorCode = ZnackErrorMessages.errorCode(rawError);

        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(i18n.tr("report.dialog.title"));
        dialog.setHeaderText(null);
        dialog.getDialogPane().setPadding(new Insets(16));
        dialog.getDialogPane().setMinWidth(480);

        ButtonType reportButton =
                new ButtonType(i18n.tr("report.button"), ButtonBar.ButtonData.OK_DONE);
        ButtonType closeButton =
                new ButtonType(i18n.tr("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(reportButton, closeButton);

        TextArea messageArea = new TextArea(message);
        messageArea.setEditable(false);
        messageArea.setWrapText(true);
        messageArea.setPrefRowCount(4);

        Label statusLabel = new Label(i18n.tr("report.hint"));
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("muted");

        VBox content =
                new VBox(
                        10,
                        new Label(errorCode.isBlank()
                                ? i18n.tr("report.dialog.label")
                                : i18n.tr("report.dialog.label") + " (mã " + errorCode + ")"),
                        messageArea,
                        statusLabel);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);

        javafx.scene.Node reportNode = dialog.getDialogPane().lookupButton(reportButton);
        reportNode.addEventFilter(
                ActionEvent.ACTION,
                event -> {
                    // Gửi báo cáo là network call — không đóng dialog cho tới khi xong.
                    event.consume();
                    reportNode.setDisable(true);
                    statusLabel.setText(i18n.tr("report.sending"));
                    ReportApiClient.Report report =
                            new ReportApiClient.Report(
                                    ConfigService.getLicenseKey(),
                                    DeviceFingerprint.get(),
                                    shopName,
                                    action,
                                    entity,
                                    errorCode,
                                    message,
                                    BuildConfig.getAppVersion());
                    Task<Void> task =
                            new Task<>() {
                                @Override
                                protected Void call() throws Exception {
                                    new ReportApiClient().send(report);
                                    return null;
                                }
                            };
                    task.setOnSucceeded(e -> statusLabel.setText(i18n.tr("report.sent")));
                    task.setOnFailed(
                            e -> {
                                reportNode.setDisable(false);
                                statusLabel.setText(i18n.tr("report.failed"));
                            });
                    AppTaskExecutor.execute(task);
                });

        dialog.showAndWait();
    }
}
