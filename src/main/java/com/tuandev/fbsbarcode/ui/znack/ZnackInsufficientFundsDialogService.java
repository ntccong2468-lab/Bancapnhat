package com.tuandev.fbsbarcode.ui.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Displays one actionable warning for each persisted KIZ purchase rejected for low balance. */
public final class ZnackInsufficientFundsDialogService {
    private static final Set<String> PROMPTED_PIPELINES = ConcurrentHashMap.newKeySet();

    private ZnackInsufficientFundsDialogService() {
    }

    public static void promptIfNeeded(ZnackRepository repository,
                                      List<ZnackGtinInventorySummary> summaries,
                                      Runnable refresh) {
        if (repository == null || summaries == null || summaries.isEmpty()) return;
        if (!Platform.isFxApplicationThread()) {
            List<ZnackGtinInventorySummary> snapshot = List.copyOf(summaries);
            Platform.runLater(() -> promptIfNeeded(repository, snapshot, refresh));
            return;
        }
        candidate(repository, summaries).ifPresent(summary -> show(repository, summary, refresh));
    }

    private static Optional<ZnackGtinInventorySummary> candidate(
            ZnackRepository repository, List<ZnackGtinInventorySummary> summaries) {
        return summaries.stream()
                .filter(summary -> summary.latestPipelineId() != null)
                .filter(summary -> "FAILED".equalsIgnoreCase(summary.latestPipelineStage()))
                .filter(summary -> ZnackErrorMessages.isInsufficientFunds(summary.latestError()))
                .filter(summary -> !PROMPTED_PIPELINES.contains(
                        promptKey(repository, summary.latestPipelineId())))
                .findFirst();
    }

    private static void show(ZnackRepository repository, ZnackGtinInventorySummary summary,
                             Runnable refresh) {
        long pipelineId = summary.latestPipelineId();
        String promptKey = promptKey(repository, pipelineId);
        if (!PROMPTED_PIPELINES.add(promptKey)) return;

        I18nService i18n = I18nService.getInstance();
        ButtonType retry = new ButtonType(i18n.tr("znack.insufficient_funds.retry"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(i18n.tr("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, i18n.tr("znack.insufficient_funds.content"), retry, close);
        AlertService.applyTheme(alert);
        alert.setTitle(i18n.tr("znack.insufficient_funds.title"));
        alert.setHeaderText(i18n.tr("znack.insufficient_funds.header") + "\nGTIN: " + summary.gtin());

        if (alert.showAndWait().filter(retry::equals).isEmpty()) return;
        retry(repository, pipelineId, promptKey, refresh);
    }

    private static void retry(ZnackRepository repository, long pipelineId, String promptKey,
                              Runnable refresh) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(repository);
                coordinator.retryInsufficientFunds(repository.getSettings(), pipelineId);
                return null;
            }
        };
        task.setOnSucceeded(event -> run(refresh));
        task.setOnFailed(event -> {
            PROMPTED_PIPELINES.remove(promptKey);
            Throwable error = task.getException();
            String message = error == null ? "" : error.getMessage();
            if (!ZnackErrorMessages.isInsufficientFunds(message)) {
                AlertService.showError(ZnackErrorMessages.display(message));
            }
            run(refresh);
        });
        AppTaskExecutor.execute(task);
    }

    private static String promptKey(ZnackRepository repository, long pipelineId) {
        return repository.shop().shopId() + ":" + pipelineId;
    }

    private static void run(Runnable action) {
        if (action != null) action.run();
    }
}
