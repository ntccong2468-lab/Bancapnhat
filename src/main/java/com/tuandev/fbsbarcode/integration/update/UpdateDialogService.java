package com.tuandev.fbsbarcode.integration.update;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.awt.Desktop;
import java.net.URI;

public class UpdateDialogService {

    public UpdateChoice showDialog(UpdateInfo info) {
        I18nService i18n = I18nService.getInstance();
        Dialog<UpdateChoice> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(i18n.tr("update.dialog.title"));
        dialog.setHeaderText(i18n.tr("update.dialog.header"));

        VBox content = new VBox(12);
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(4, 0, 4, 0));
        content.setPrefWidth(520);

        Label versionLabel = new Label(String.format(
                i18n.tr("update.dialog.version"),
                BuildConfig.getAppVersion(), info.getVersion()
        ));
        versionLabel.getStyleClass().add("update-version-label");

        Label dateLabel = new Label(i18n.tr("update.dialog.release_date") + " " + safeValue(info.getReleaseDate()));
        dateLabel.getStyleClass().add("muted-label");

        Label dataNote = new Label(i18n.tr("update.dialog.data_note"));
        dataNote.setWrapText(true);
        dataNote.getStyleClass().addAll("info-banner", "update-data-note");

        Label changelogHeader = new Label(i18n.tr("update.dialog.changelog"));
        changelogHeader.getStyleClass().add("section-title");

        TextArea changelogArea = new TextArea(info.getDisplayChangelog());
        changelogArea.setEditable(false);
        changelogArea.setWrapText(true);
        changelogArea.setPrefRowCount(10);
        changelogArea.setFocusTraversable(false);
        changelogArea.getStyleClass().add("update-changelog-area");

        Label sourceLabel = new Label(i18n.tr("update.dialog.source") + " " + safeValue(info.getBestDownloadUrl()));
        sourceLabel.setWrapText(true);
        sourceLabel.getStyleClass().add("muted-label");

        content.getChildren().addAll(versionLabel, dateLabel, dataNote, changelogHeader, changelogArea, sourceLabel);
        dialog.getDialogPane().setContent(content);

        ButtonType downloadBtn = new ButtonType(i18n.tr("update.dialog.download"), ButtonBar.ButtonData.OK_DONE);
        ButtonType skipBtn = new ButtonType(i18n.tr("update.dialog.skip"), ButtonBar.ButtonData.OTHER);
        ButtonType laterBtn = new ButtonType(i18n.tr("update.dialog.later"), ButtonBar.ButtonData.CANCEL_CLOSE);

        if (info.isMandatory()) {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn, skipBtn, laterBtn);
        }

        dialog.setResultConverter(button -> {
            if (button == downloadBtn) return UpdateChoice.DOWNLOAD;
            if (button == skipBtn) return UpdateChoice.SKIP;
            return UpdateChoice.LATER;
        });

        return dialog.showAndWait().orElse(UpdateChoice.LATER);
    }

    public Dialog<Void> showDownloadProgressDialog(UpdateInfo info, Task<?> task) {
        I18nService i18n = I18nService.getInstance();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(i18n.tr("update.progress.title"));
        dialog.setHeaderText(i18n.tr("update.progress.header") + " " + safeValue(info.getVersion()));
        AlertService.applyTheme(dialog);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);

        Node cancelButton = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelButton != null) {
            cancelButton.setDisable(true);
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);
        }

        ProgressBar progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefWidth(420);
        progressBar.setPrefHeight(14);
        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.getStyleClass().add("update-progress-bar");

        Label percentLabel = new Label();
        percentLabel.getStyleClass().add("update-progress-percent");
        percentLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            double progress = task.getProgress();
            if (progress < 0) {
                return i18n.tr("update.progress.preparing");
            }
            return String.format("%.0f%%", Math.max(0, Math.min(100, progress * 100)));
        }, task.progressProperty()));

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);
        messageLabel.getStyleClass().add("muted-label");
        messageLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            String message = task.getMessage();
            return message == null || message.isBlank()
                    ? i18n.tr("update.progress.downloading")
                    : message;
        }, task.messageProperty()));

        VBox content = new VBox(12, progressBar, percentLabel, messageLabel);
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(4, 0, 4, 0));
        dialog.getDialogPane().setContent(content);
        dialog.setOnCloseRequest(event -> event.consume());
        return dialog;
    }

    public enum UpdateChoice {
        DOWNLOAD,
        SKIP,
        LATER
    }

    public static void openDownloadUrl(UpdateInfo info) {
        String url = info.getBestDownloadUrl();
        if (url != null && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception e) {
                AlertService.showError(I18nService.getInstance().tr("update.dialog.open_browser_failed") + " " + url);
            }
        }
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return I18nService.getInstance().tr("common.not_available");
        }
        return value;
    }
}
