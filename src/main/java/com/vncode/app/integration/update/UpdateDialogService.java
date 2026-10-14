package com.vncode.app.integration.update;

import com.vncode.app.BuildConfig;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.I18nService;
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
        return createDialog(info).showAndWait().orElse(UpdateChoice.LATER);
    }
    public Dialog<UpdateChoice> createDialog(UpdateInfo info) {
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
        boolean verified=new UpdateInstallerService().supportsInAppInstall(info);
        Label verification=new Label(i18n.tr(verified?"update.verified":"update.unverified"));verification.setWrapText(true);

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

        content.getChildren().addAll(versionLabel, dateLabel, dataNote,verification,changelogHeader,changelogArea, sourceLabel);
        dialog.getDialogPane().setContent(content);

        ButtonType downloadBtn = new ButtonType(i18n.tr(verified?"update.dialog.download":"update.open_download_page"), ButtonBar.ButtonData.OK_DONE);
        ButtonType downloadOnlyBtn = new ButtonType(i18n.tr("update.download_only"), ButtonBar.ButtonData.OTHER);
        ButtonType skipBtn = new ButtonType(i18n.tr("update.dialog.skip"), ButtonBar.ButtonData.OTHER);
        ButtonType laterBtn = new ButtonType(i18n.tr("update.dialog.later"), ButtonBar.ButtonData.CANCEL_CLOSE);

        if (info.isMandatory()) {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn,downloadOnlyBtn,laterBtn);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn,downloadOnlyBtn,skipBtn,laterBtn);
        }

        dialog.setResultConverter(button -> {
            if (button == downloadBtn) return UpdateChoice.DOWNLOAD;
            if (button == downloadOnlyBtn) return UpdateChoice.DOWNLOAD_ONLY;
            if (button == skipBtn) return UpdateChoice.SKIP;
            return UpdateChoice.LATER;
        });

        return dialog;
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
        if(cancelButton!=null)cancelButton.setDisable(false);

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
        dialog.setOnCloseRequest(event -> {if(task.isRunning())task.cancel(true);});
        return dialog;
    }

    public enum UpdateChoice {
        DOWNLOAD,
        DOWNLOAD_ONLY,
        SKIP,
        LATER
    }

    public static void openDownloadUrl(UpdateInfo info) {
        String url = info.getBestDownloadUrl();
        if (url != null && Desktop.isDesktopSupported()) {
            try {
                URI uri=new URI(url);
                if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("UPDATE_URL_INVALID");
                Desktop.getDesktop().browse(uri);
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
