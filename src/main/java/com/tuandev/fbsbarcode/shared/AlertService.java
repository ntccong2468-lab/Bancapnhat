package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.MainApplication;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Optional;

public final class AlertService {
    private static final String THEME_CSS = "/com/tuandev/fbsbarcode/styles/theme.css";

    private AlertService() {
    }

    public static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        applyTheme(alert);
        alert.setTitle(I18nService.getInstance().tr("alert.error.title"));
        alert.setHeaderText(I18nService.getInstance().tr("alert.error.header"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    /** Shows diagnostics in full and keeps the dialog open while its Copy button is used. */
    public static void showDetailedError(String summary, String details) {
        I18nService i18n = I18nService.getInstance();
        Dialog<Void> dialog = new Dialog<>();
        applyTheme(dialog);
        dialog.setTitle(i18n.tr("alert.error.title"));
        dialog.setHeaderText(i18n.tr("alert.error.header"));

        Label summaryLabel = new Label(summary == null || summary.isBlank()
                ? i18n.tr("alert.error.header") : summary);
        summaryLabel.setWrapText(true);
        summaryLabel.setMaxWidth(Double.MAX_VALUE);

        TextArea detailsArea = new TextArea(details == null ? "" : details);
        detailsArea.setEditable(false);
        detailsArea.setWrapText(false);
        detailsArea.setPrefColumnCount(90);
        detailsArea.setPrefRowCount(22);

        VBox content = new VBox(10, summaryLabel, detailsArea);
        content.setPrefWidth(760);
        dialog.getDialogPane().setContent(content);

        ButtonType copyType = new ButtonType(i18n.tr("common.copy"), ButtonBar.ButtonData.OTHER);
        ButtonType closeType = new ButtonType(i18n.tr("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(copyType, closeType);
        Button copyButton = (Button) dialog.getDialogPane().lookupButton(copyType);
        copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(detailsArea.getText());
            Clipboard.getSystemClipboard().setContent(clipboardContent);
            event.consume();
        });
        dialog.showAndWait();
    }

    public static void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        alert.showAndWait();
    }

    public static void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        alert.showAndWait();
    }

    public static Optional<ButtonType> showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        return alert.showAndWait();
    }

    public static void applyTheme(Dialog<?> dialog) {
        if (dialog != null) {
            applyTheme(dialog.getDialogPane());
        }
    }

    public static void applyTheme(Alert alert) {
        if (alert != null) {
            applyTheme(alert.getDialogPane());
        }
    }

    public static void applyTheme(DialogPane pane) {
        if (pane == null) {
            return;
        }
        String stylesheet = Objects.requireNonNull(MainApplication.class.getResource(THEME_CSS)).toExternalForm();
        if (!pane.getStylesheets().contains(stylesheet)) {
            pane.getStylesheets().add(stylesheet);
        }
        if (!pane.getStyleClass().contains("app-dialog-pane")) {
            pane.getStyleClass().add("app-dialog-pane");
        }
        ThemeService.applyTheme(pane);
    }
}
