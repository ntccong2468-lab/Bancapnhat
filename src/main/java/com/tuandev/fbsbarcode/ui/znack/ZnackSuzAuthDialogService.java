package com.tuandev.fbsbarcode.ui.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shows non-repeating guidance after retries cannot authenticate the shop's UOT/SUZ connection. */
public final class ZnackSuzAuthDialogService {
    private static final Set<String> PROMPTED_SHOPS = ConcurrentHashMap.newKeySet();

    private ZnackSuzAuthDialogService() {
    }

    public static void promptIfNeeded(ZnackRepository repository,
                                      List<ZnackGtinInventorySummary> summaries) {
        if (repository == null || summaries == null || summaries.isEmpty()) return;
        if (!Platform.isFxApplicationThread()) {
            List<ZnackGtinInventorySummary> snapshot = List.copyOf(summaries);
            Platform.runLater(() -> promptIfNeeded(repository, snapshot));
            return;
        }
        ZnackGtinInventorySummary candidate = summaries.stream()
                .filter(summary -> summary.latestPipelineId() != null)
                .filter(summary -> "FAILED".equalsIgnoreCase(summary.latestPipelineStage()))
                .filter(summary -> ZnackErrorMessages.isSuzAuthError(summary.latestError()))
                .findFirst()
                .orElse(null);
        String shopKey = String.valueOf(repository.shop().shopId());
        if (candidate == null) {
            PROMPTED_SHOPS.remove(shopKey);
        } else if (PROMPTED_SHOPS.add(shopKey)) {
            show(candidate);
        }
    }

    private static void show(ZnackGtinInventorySummary summary) {
        I18nService i18n = I18nService.getInstance();
        ButtonType close = new ButtonType(i18n.tr("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.ERROR, "", close);
        AlertService.applyTheme(alert);
        alert.setTitle(i18n.tr("znack.suz_auth.title"));
        alert.setHeaderText(i18n.tr("znack.suz_auth.header"));
        alert.setContentText(i18n.tr("znack.suz_auth.content"));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.showAndWait();
    }
}
