package com.tuandev.fbsbarcode.ui.license;

import com.tuandev.fbsbarcode.integration.license.LicenseApiClient.LicenseApiException;
import com.tuandev.fbsbarcode.integration.license.LicenseService;
import com.tuandev.fbsbarcode.integration.license.LicenseState;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.ConfigService;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Dialog kích hoạt/xem trạng thái license. Điểm gọi chính là {@link #ensureLicensed()}
 * trước các thao tác trả phí (mua KIZ).
 */
public class LicenseDialogService {

    private final LicenseService licenseService;
    private final I18nService i18n = I18nService.getInstance();

    public LicenseDialogService() {
        this(LicenseService.getInstance());
    }

    LicenseDialogService(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /** Trả về true nếu license hợp lệ (mở dialog kích hoạt nếu chưa). Gọi trên FX thread. */
    public boolean ensureLicensed() {
        if (licenseService.getState().kizAllowed()) {
            return true;
        }
        showDialog();
        return licenseService.getState().kizAllowed();
    }

    /** Hiển thị dialog trạng thái + nhập key. Gọi trên FX thread. */
    public void showDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(i18n.tr("license.dialog.title"));
        dialog.setHeaderText(null);
        dialog.getDialogPane().setPadding(new Insets(18));
        dialog.getDialogPane().setMinWidth(460);

        ButtonType activateButton =
                new ButtonType(i18n.tr("license.activate"), ButtonBar.ButtonData.OK_DONE);
        ButtonType deactivateButton =
                new ButtonType(i18n.tr("license.deactivate"), ButtonBar.ButtonData.OTHER);
        ButtonType closeButton =
                new ButtonType(i18n.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        boolean hasKey = existingKeyPresent();
        if (hasKey) {
            dialog.getDialogPane().getButtonTypes().addAll(activateButton, deactivateButton, closeButton);
        } else {
            dialog.getDialogPane().getButtonTypes().addAll(activateButton, closeButton);
        }

        Label statusLabel = new Label(statusText(licenseService.getState()));
        statusLabel.setWrapText(true);

        TextField keyField = new TextField();
        keyField.setPromptText(i18n.tr("license.key.prompt"));
        String existingKey = ConfigService.getLicenseKey();
        if (existingKey != null && !existingKey.isBlank()) {
            keyField.setText(existingKey);
        }

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setWrapText(true);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox content =
                new VBox(10, statusLabel, new Label(i18n.tr("license.dialog.label")), keyField, errorLabel);
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        Node activateNode = dialog.getDialogPane().lookupButton(activateButton);
        activateNode.addEventFilter(
                ActionEvent.ACTION,
                event -> {
                    // Kích hoạt là network call — chạy nền, không đóng dialog cho tới khi xong.
                    event.consume();
                    String key = keyField.getText() == null ? "" : keyField.getText().trim();
                    if (key.isEmpty()) {
                        showError(errorLabel, i18n.tr("license.status.not_activated"));
                        return;
                    }
                    activateNode.setDisable(true);
                    errorLabel.setManaged(false);
                    errorLabel.setVisible(false);
                    Task<LicenseState> activateTask =
                            new Task<>() {
                                @Override
                                protected LicenseState call() throws Exception {
                                    return licenseService.activate(key);
                                }
                            };
                    activateTask.setOnSucceeded(
                            e -> {
                                activateNode.setDisable(false);
                                LicenseState state = activateTask.getValue();
                                statusLabel.setText(statusText(state));
                                if (state.kizAllowed()) {
                                    dialog.setResult(activateButton);
                                    dialog.close();
                                } else {
                                    showError(errorLabel, statusText(state));
                                }
                            });
                    activateTask.setOnFailed(
                            e -> {
                                activateNode.setDisable(false);
                                showError(errorLabel, errorText(activateTask.getException()));
                            });
                    AppTaskExecutor.execute(activateTask);
                });

        if (hasKey) {
            Node deactivateNode = dialog.getDialogPane().lookupButton(deactivateButton);
            deactivateNode.addEventFilter(
                    ActionEvent.ACTION,
                    event -> {
                        event.consume();
                        deactivateNode.setDisable(true);
                        Task<LicenseState> task =
                                new Task<>() {
                                    @Override
                                    protected LicenseState call() {
                                        return licenseService.deactivateCurrentDevice();
                                    }
                                };
                        task.setOnSucceeded(
                                e -> {
                                    statusLabel.setText(statusText(task.getValue()));
                                    keyField.clear();
                                    dialog.close();
                                });
                        task.setOnFailed(e -> deactivateNode.setDisable(false));
                        AppTaskExecutor.execute(task);
                    });
        }

        dialog.showAndWait();
    }

    private boolean existingKeyPresent() {
        String key = ConfigService.getLicenseKey();
        return key != null && !key.isBlank();
    }

    private String daysRemaining(long expiresAt) {
        long days = (expiresAt - System.currentTimeMillis()) / (24L * 60 * 60 * 1000);
        return MessageFormat.format(i18n.tr("license.days_remaining"), Math.max(0, days));
    }

    private void showError(Label errorLabel, String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }

    private String errorText(Throwable error) {
        if (error instanceof LicenseApiException apiError) {
            return switch (apiError.code()) {
                case LicenseApiException.CODE_INVALID_LICENSE -> i18n.tr("license.status.invalid");
                case LicenseApiException.CODE_DEVICE_LIMIT_REACHED ->
                        i18n.tr("license.status.device_limit");
                default -> i18n.tr("license.error.failed");
            };
        }
        if (error instanceof IOException) {
            return i18n.tr("license.error.network");
        }
        return i18n.tr("license.error.failed");
    }

    private String statusText(LicenseState state) {
        return switch (state.status()) {
            case NOT_ACTIVATED -> i18n.tr("license.status.not_activated");
            case VALID ->
                    MessageFormat.format(
                            i18n.tr("license.status.valid"), formatDate(state.payload().expiresAt()))
                            + " (" + daysRemaining(state.payload().expiresAt()) + ")";
            case OFFLINE_GRACE ->
                    MessageFormat.format(
                            i18n.tr("license.status.offline"), formatDate(state.payload().expiresAt()));
            case EXPIRED -> {
                String date =
                        state.payload() != null ? formatDate(state.payload().expiresAt()) : "";
                yield MessageFormat.format(i18n.tr("license.status.expired"), date);
            }
            case INVALID -> i18n.tr("license.status.invalid");
            case DEVICE_LIMIT -> i18n.tr("license.status.device_limit");
            case CLOCK_TAMPERED -> i18n.tr("license.status.clock");
            case NETWORK_ERROR -> i18n.tr("license.error.network");
        };
    }

    private String formatDate(long epochMillis) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(i18n.getCurrentLanguage().toLocale())
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
