package com.tuandev.fbsbarcode.ui.znack;

import com.tuandev.fbsbarcode.features.print.ZnackKizExportService;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.awt.Desktop;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Shared JavaFX actions for safely exporting or archiving GTIN-level KIZ inventory. */
public final class ZnackKizInventoryActionService {
    private final ZnackKizExportService exportService = new ZnackKizExportService();
    private final ZnackGtinInventoryService inventoryService = new ZnackGtinInventoryService();
    private final Set<String> inFlight = new HashSet<>();

    public void export(Node owner, Shop shop, ZnackGtinInventorySummary summary,
                       Consumer<Boolean> busy, Runnable changed, Consumer<Throwable> failed) {
        if (shop == null || summary == null || summary.available() <= 0) return;
        I18nService i18n = I18nService.getInstance();
        TextInputDialog quantityDialog = new TextInputDialog("1");
        AlertService.applyTheme(quantityDialog);
        quantityDialog.setTitle(i18n.tr("kiz_export.title"));
        quantityDialog.setHeaderText(summary.gtin() + "\n" + safe(summary.productName()));
        quantityDialog.setContentText(i18n.tr("kiz_export.quantity"));
        quantityDialog.showAndWait().ifPresent(text -> {
            int quantity;
            try {
                quantity = Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                AlertService.showError(i18n.tr("kiz_export.positive"));
                return;
            }
            if (quantity <= 0) {
                AlertService.showError(i18n.tr("kiz_export.positive"));
                return;
            }
            if (quantity > summary.available()) {
                AlertService.showError(i18n.tr("kiz_export.not_enough") + " " + summary.available());
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle(i18n.tr("kiz_export.save_title"));
            chooser.setInitialFileName("KIZ-" + summary.gtin() + "-" + quantity + ".pdf");
            File initialDirectory = AppPaths.preferredDownloadsDirectory();
            if (initialDirectory != null) chooser.setInitialDirectory(initialDirectory);
            chooser.getExtensionFilters().setAll(
                    new FileChooser.ExtensionFilter(i18n.tr("filechooser.pdf"), "*.pdf"));
            File target = chooser.showSaveDialog(window(owner));
            if (target != null) runExport(shop, summary, quantity, target, busy, changed, failed);
        });
    }

    public void archiveFailed(Node owner, Shop shop, ZnackGtinInventorySummary summary,
                              Consumer<Boolean> busy, Runnable changed, Consumer<Throwable> failed) {
        if (shop == null || summary == null || summary.discardable() <= 0) return;
        I18nService i18n = I18nService.getInstance();
        if (AlertService.showConfirmation(
                i18n.tr("kiz_archive.title"),
                i18n.tr("kiz_archive.header"),
                i18n.tr("kiz_archive.content") + " " + summary.discardable())
                .filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        String key = key(shop, summary, "archive");
        if (!inFlight.add(key)) return;
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                return inventoryService.archiveDiscardable(shop.getId(), shop.getName(), summary.gtin());
            }
        };
        busy.accept(true);
        task.setOnSucceeded(event -> finish(key, busy, changed));
        task.setOnFailed(event -> finishFailed(key, busy, failed, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void runExport(Shop shop, ZnackGtinInventorySummary summary, int quantity, File target,
                           Consumer<Boolean> busy, Runnable changed, Consumer<Throwable> failed) {
        String key = key(shop, summary, "export");
        if (!inFlight.add(key)) return;
        Task<ZnackKizExportService.ExportResult> task = new Task<>() {
            @Override protected ZnackKizExportService.ExportResult call() throws Exception {
                return exportService.export(shop.getId(), summary.gtin(), quantity, target);
            }
        };
        busy.accept(true);
        task.setOnSucceeded(event -> {
            finish(key, busy, changed);
            openExport(task.getValue().file());
        });
        task.setOnFailed(event -> finishFailed(key, busy, failed, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void finish(String key, Consumer<Boolean> busy, Runnable changed) {
        inFlight.remove(key);
        busy.accept(false);
        changed.run();
    }

    private void finishFailed(String key, Consumer<Boolean> busy, Consumer<Throwable> failed, Throwable error) {
        inFlight.remove(key);
        busy.accept(false);
        failed.accept(error);
    }

    private static void openExport(File file) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException();
            }
            Desktop.getDesktop().open(file);
        } catch (Exception error) {
            AlertService.showWarning(
                    I18nService.getInstance().tr("kiz_export.open_failed.title"),
                    I18nService.getInstance().tr("kiz_export.open_failed.header"),
                    file == null ? "" : file.getAbsolutePath());
        }
    }

    private static Window window(Node owner) {
        return owner == null || owner.getScene() == null ? null : owner.getScene().getWindow();
    }

    private static String key(Shop shop, ZnackGtinInventorySummary summary, String action) {
        return shop.getId() + ":" + summary.gtin() + ":" + action;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
