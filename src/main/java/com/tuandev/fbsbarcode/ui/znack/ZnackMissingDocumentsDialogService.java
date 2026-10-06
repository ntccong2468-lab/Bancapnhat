package com.tuandev.fbsbarcode.ui.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchasePipelineState;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shows one compact, non-repeating warning for KIZ pipelines waiting for GTIN documents. */
public final class ZnackMissingDocumentsDialogService {
    private static final Set<String> PROMPTED_PIPELINES = ConcurrentHashMap.newKeySet();

    private ZnackMissingDocumentsDialogService() {
    }

    public static void promptIfNeeded(ZnackRepository repository,
                                      List<ZnackGtinInventorySummary> summaries) {
        if (repository == null || summaries == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> promptIfNeeded(repository, List.of()));
            return;
        }

        List<ZnackPurchasePipelineState> waiting = new ArrayList<>(
                repository.findWaitingIntroductionDocumentPipelines());
        repository.findSkippedIntroductionPipelines().stream()
                .filter(pipeline -> pipeline.stage()
                        == PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS)
                .forEach(waiting::add);
        List<Candidate> candidates = waiting.stream()
                .filter(pipeline -> !PROMPTED_PIPELINES.contains(promptKey(
                        repository, pipeline.id())))
                .map(pipeline -> {
                    Product product = repository.findProduct(pipeline.gtin()).orElse(null);
                    return new Candidate(pipeline.id(), pipeline.gtin(),
                            product == null ? "" : product.productName());
                })
                .toList();
        if (candidates.isEmpty()) return;

        candidates.forEach(candidate -> PROMPTED_PIPELINES.add(promptKey(
                repository, candidate.pipelineId())));
        show(candidates);
    }

    private static void show(List<Candidate> candidates) {
        I18nService i18n = I18nService.getInstance();
        StringBuilder content = new StringBuilder(i18n.tr("znack.missing_documents.intro"));
        content.append("\n\n");
        for (Candidate candidate : candidates) {
            content.append("\u2022 GTIN ")
                    .append(oneLine(candidate.gtin(), "-"))
                    .append(" \u2014 ")
                    .append(oneLine(candidate.productName(),
                            i18n.tr("supply.gtin_inventory.unnamed")))
                    .append('\n');
        }
        content.append("\n").append(i18n.tr("znack.missing_documents.action"));

        TextArea details = new TextArea(content.toString());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefColumnCount(72);
        details.setPrefRowCount(Math.min(16, candidates.size() + 7));

        ButtonType close = new ButtonType(i18n.tr("common.close"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", close);
        AlertService.applyTheme(alert);
        alert.setTitle(i18n.tr("znack.missing_documents.title"));
        alert.setHeaderText(i18n.tr("znack.missing_documents.header"));
        alert.getDialogPane().setContent(details);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.showAndWait();
    }

    private static String promptKey(ZnackRepository repository, long pipelineId) {
        return repository.shop().shopId() + ":" + pipelineId;
    }

    private static String oneLine(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().replaceAll("\\s+", " ");
    }

    private record Candidate(long pipelineId, String gtin, String productName) {
    }
}
