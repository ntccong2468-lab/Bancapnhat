package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.FriendlyErrorService;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.ui.controls.CategoryFilterMenu;
import com.tuandev.fbsbarcode.ui.license.LicenseDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackInsufficientFundsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackMissingDocumentsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackOperatorTermsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackSuzAuthDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackKizInventoryActionService;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.List;

public class KizMappingController {
    @FXML private Label titleLabel, emptyStateLabel;
    @FXML private TextField searchField;
    @FXML private MenuButton categoryFilterButton;
    @FXML private Button refreshButton;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private TableView<ZnackGtinInventorySummary> gtinTable;
    @FXML private TableColumn<ZnackGtinInventorySummary,String> gtinColumn, nameColumn, mappingColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,Number> availableColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,String> pipelineColumn, errorColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,ZnackGtinInventorySummary> actionsColumn;

    private final KizMappingRepository mappingRepository = new KizMappingRepository();
    private final ZnackKizInventoryActionService inventoryActions = new ZnackKizInventoryActionService();
    private CategoryFilterMenu categoryFilter;
    private List<ZnackGtinInventorySummary> summaries = List.of();
    private Shop shop;
    private ZnackRepository znackRepository;
    private Timeline refreshTimer;
    private javafx.animation.RotateTransition refreshSpin;
    private boolean loading;
    private boolean syncing;
    private long shopGeneration;

    @FXML
    private void initialize() {
        gtinColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().gtin()));
        nameColumn.setCellValueFactory(v -> new SimpleStringProperty(value(v.getValue().productName())));
        mappingColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().mappingRuleCount() == 0
                ? tr("kiz_mapping.status.unmapped")
                : v.getValue().mappingRuleCount() + " " + tr("kiz_mapping.status.rules")));
        availableColumn.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().available()));
        pipelineColumn.setCellValueFactory(v -> new SimpleStringProperty(localizeStatus(first(
                v.getValue().latestPipelineStage(), v.getValue().latestOrderStatus()))));
        mappingColumn.setCellFactory(column -> statusCell("badge-green", "badge-gray"));
        pipelineColumn.setCellFactory(column -> statusCell("badge-warning", "badge-gray"));
        errorColumn.setCellValueFactory(v -> {
            ZnackGtinInventorySummary summary = v.getValue();
            String status = first(summary.latestPipelineStage(), summary.latestOrderStatus());
            return new SimpleStringProperty(
                    ZnackErrorMessages.displayForPipeline(status, summary.latestError()));
        });
        errorColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String display, boolean empty) {
                super.updateItem(display, empty);
                boolean hasError = !empty && display != null && !display.isBlank();
                setText(hasError ? display : null);
                setWrapText(hasError);
                setUnderline(hasError);
                setCursor(hasError ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
                setTooltip(hasError ? new Tooltip(tr("report.button")) : null);
                setOnMouseClicked(hasError ? event -> {
                    ZnackGtinInventorySummary row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null && shop != null) {
                        com.tuandev.fbsbarcode.ui.report.ErrorReportDialog.show(
                                shop.getName(), row.gtin(), "PURCHASE_PIPELINE", row.latestError());
                    }
                } : null);
            }
        });
        actionsColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        actionsColumn.setCellFactory(column -> new ActionsCell());
        refreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), event -> {
            if (shop != null && !loading) refresh();
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        searchField.textProperty().addListener((ignored, old, value) -> applyFilter());
        categoryFilter = new CategoryFilterMenu(categoryFilterButton, this::applyFilter);
        if (refreshButton.getGraphic() != null) {
            refreshSpin = new javafx.animation.RotateTransition(
                    javafx.util.Duration.millis(800), refreshButton.getGraphic());
            refreshSpin.setByAngle(360);
            refreshSpin.setCycleCount(javafx.animation.Animation.INDEFINITE);
            refreshSpin.setInterpolator(javafx.animation.Interpolator.LINEAR);
        }
        applyTranslations();
        setLoading(false);
    }

    public void setShop(Shop selected) {
        shopGeneration++;
        shop = selected;
        syncing = false;
        summaries = List.of();
        searchField.clear();
        categoryFilter.rebuild(List.of());
        znackRepository = selected == null ? null : new ZnackRepository(new ShopContext(selected.getId(), selected.getName()));
        setLoading(false);
        if (selected == null) refreshTimer.stop(); else refreshTimer.play();
        applyTranslations();
        refresh();
        if (znackRepository != null) {
            ZnackRepository currentRepository = znackRepository;
            Settings currentSettings = currentRepository.getSettings();
            runBackground(() -> ZnackPurchaseCoordinator.create(currentRepository).resume(currentSettings));
        }
    }

    public void syncOnOpen() {
        refresh();
        // Auto-sync only the first time a shop's mapping page is opened in this session (and only when a
        // verified signature is configured). Subsequent opens just show the cached data; the user re-syncs
        // manually with the Refresh button when needed.
        if (znackRepository == null || shop == null) return;
        if (!hasVerifiedSignature(znackRepository.getSettings())) return;
        if (ZnackGtinAutoSync.shouldAutoSync(shop.getId())) requestSync(false);
    }

    public void applyTranslations() {
        boolean ozon = shop != null && shop.getMarketplace() == Marketplace.OZON;
        titleLabel.setText(tr(ozon ? "ozon.mapping.title" : "kiz_mapping.title"));
        searchField.setPromptText(tr("kiz_mapping.search_gtin"));
        categoryFilter.setTexts(tr("znack.filter.button"), tr("znack.filter.no_category"), tr("znack.filter.clear"));
        refreshButton.setTooltip(new Tooltip(tr("kiz_mapping.refresh")));
        refreshButton.setAccessibleText(tr("kiz_mapping.refresh"));
        gtinColumn.setText(tr("znack.field.gtin"));
        nameColumn.setText(tr("znack.field.name"));
        mappingColumn.setText(tr(ozon ? "ozon.mapping.column.mapping" : "kiz_mapping.column.mapping"));
        availableColumn.setText(tr("kiz_mapping.column.available"));
        pipelineColumn.setText(tr("kiz_mapping.column.pipeline"));
        errorColumn.setText(tr("kiz_mapping.column.error"));
        actionsColumn.setText(tr("kiz_mapping.column.actions"));
        emptyStateLabel.setText(tr("kiz_mapping.empty_gtin"));
    }

    @FXML
    private void onRefresh() {
        requestSync(true);
    }

    private void requestSync(boolean showErrors) {
        if (znackRepository == null || syncing) return;
        long generation = shopGeneration;
        int shopId = shop.getId();
        ZnackRepository current = znackRepository;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override protected List<ZnackGtinInventorySummary> call() throws Exception {
                try {
                    syncProducts(current);
                    return mappingRepository.findGtinSummaries(shopId);
                } catch (Exception error) {
                    current.log("GTIN_SYNC", null, "ERROR", error.getMessage(), null);
                    throw error;
                }
            }
        };
        syncing = true;
        setLoadingState();
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != shopId) return;
            syncing = false;
            summaries = task.getValue();
            categoryFilter.rebuild(summaries.stream().map(ZnackGtinInventorySummary::category).toList());
            applyFilter();
            setLoadingState();
            ZnackInsufficientFundsDialogService.promptIfNeeded(current, summaries, this::refresh);
            ZnackOperatorTermsDialogService.promptIfNeeded(current, summaries);
            ZnackSuzAuthDialogService.promptIfNeeded(current, summaries);
            ZnackMissingDocumentsDialogService.promptIfNeeded(current, summaries);
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            syncing = false;
            setLoadingState();
            if (showErrors) AlertService.showError(friendlyError(task.getException()));
            refresh();
        });
        AppTaskExecutor.execute(task);
    }

    public void refresh() {
        if (loading) return;
        if (shop == null) {
            summaries = List.of();
            gtinTable.getItems().clear();
            updateEmpty();
            return;
        }
        int shopId = shop.getId();
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override protected List<ZnackGtinInventorySummary> call() {
                return mappingRepository.findGtinSummaries(shopId);
            }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (generation == shopGeneration && shop != null && shop.getId() == shopId) {
                summaries = task.getValue();
                categoryFilter.rebuild(summaries.stream().map(ZnackGtinInventorySummary::category).toList());
                applyFilter();
                setLoading(false);
                ZnackInsufficientFundsDialogService.promptIfNeeded(
                        currentRepository, summaries, this::refresh);
                ZnackOperatorTermsDialogService.promptIfNeeded(currentRepository, summaries);
                ZnackMissingDocumentsDialogService.promptIfNeeded(currentRepository, summaries);
            }
        });
        task.setOnFailed(e -> {
            if (generation == shopGeneration) {
                setLoading(false);
                AlertService.showError(task.getException().getMessage());
            }
        });
        AppTaskExecutor.execute(task);
    }

    private void showMapping(ZnackGtinInventorySummary summary) {
        if (shop == null || summary == null) return;
        int shopId = shop.getId();
        long generation = shopGeneration;
        KizGtinMappingEditor.Host host = new KizGtinMappingEditor.Host() {
            @Override public boolean isCurrent() {
                return generation == shopGeneration && shop != null && shop.getId() == shopId;
            }

            @Override public void busy(boolean busy) {
                if (generation == shopGeneration) setLoading(busy);
            }

            @Override public void saved() {
                if (generation == shopGeneration) refresh();
            }

            @Override public void error(Throwable error) {
                if (generation == shopGeneration) AlertService.showError(friendlyError(error));
            }
        };
        if (shop.getMarketplace() == Marketplace.OZON) {
            new OzonGtinMappingEditor().open(shopId, summary.gtin(), host);
        } else {
            new KizGtinMappingEditor().open(shopId, summary.gtin(), host);
        }
    }

    private void showBuy(ZnackGtinInventorySummary summary) {
        if (!new LicenseDialogService().ensureLicensed()) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("1");
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("kiz_mapping.buy.title"));
        dialog.setHeaderText(summary.gtin());
        dialog.setContentText(tr("znack.field.quantity"));
        dialog.showAndWait().ifPresent(text -> {
            try {
                int quantity = Integer.parseInt(text.trim());
                if (quantity <= 0) {
                    AlertService.showError(tr("kiz_mapping.buy.positive"));
                    return;
                }
                ZnackRepository currentRepository = znackRepository;
                ZnackPurchaseCoordinator currentCoordinator = ZnackPurchaseCoordinator.create(currentRepository);
                Settings currentSettings = currentRepository.getSettings();
                runTask(() -> {
                    currentCoordinator.enqueue(currentSettings, summary.gtin(), quantity,
                            java.util.UUID.randomUUID().toString());
                    return null;
                });
            } catch (NumberFormatException e) {
                AlertService.showError(tr("kiz_mapping.buy.positive"));
            }
        });
    }

    private void retryIntroduction(ZnackGtinInventorySummary summary) {
        if (znackRepository == null || summary == null) return;
        ZnackRepository currentRepository = znackRepository;
        ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(currentRepository);
        Settings currentSettings = currentRepository.getSettings();
        runTask(() -> {
            coordinator.retryIntroduction(currentSettings, summary.gtin());
            return null;
        });
    }

    private void exportKiz(ZnackGtinInventorySummary summary) {
        long generation = shopGeneration;
        inventoryActions.export(gtinTable, shop, summary,
                busy -> { if (generation == shopGeneration) setLoading(busy); },
                () -> { if (generation == shopGeneration) refresh(); },
                error -> { if (generation == shopGeneration) AlertService.showError(friendlyError(error)); });
    }

    private void archiveFailedKiz(ZnackGtinInventorySummary summary) {
        long generation = shopGeneration;
        inventoryActions.archiveFailed(gtinTable, shop, summary,
                busy -> { if (generation == shopGeneration) setLoading(busy); },
                () -> { if (generation == shopGeneration) refresh(); },
                error -> { if (generation == shopGeneration) AlertService.showError(friendlyError(error)); });
    }

    private void runTask(ThrowingSupplier action) {
        long generation = shopGeneration;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { return action.get(); }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            refresh();
        });
        task.setOnFailed(e -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            AlertService.showError(friendlyError(task.getException()));
            refresh();
        });
        AppTaskExecutor.execute(task);
    }

    private void runBackground(Runnable action) {
        long generation = shopGeneration;
        Task<Void> task = new Task<>() {
            @Override protected Void call() { action.run(); return null; }
        };
        task.setOnSucceeded(e -> {
            if (generation == shopGeneration) refresh();
        });
        AppTaskExecutor.execute(task);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        setLoadingState();
    }

    private void setLoadingState() {
        boolean active = loading || syncing;
        loadingIndicator.setVisible(active);
        loadingIndicator.setManaged(active);
        refreshButton.setDisable(active || shop == null);
        if (refreshSpin != null) {
            if (active) {
                if (refreshSpin.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                    refreshSpin.playFromStart();
                }
            } else {
                refreshSpin.stop();
                if (refreshButton.getGraphic() != null) refreshButton.getGraphic().setRotate(0);
            }
        }
    }

    private void updateEmpty() {
        boolean empty = gtinTable.getItems().isEmpty();
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private void applyFilter() {
        String query = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        gtinTable.getItems().setAll(summaries.stream()
                .filter(summary -> categoryFilter.matches(summary.category()))
                .filter(summary -> query.isEmpty() || summary.matchesSearch(query))
                .toList());
        updateEmpty();
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private String friendlyError(Throwable error) {
        return FriendlyErrorService.format(error);
    }

    private String localizeStatus(String status) {
        return status == null || status.isBlank() ? "" : I18nService.getInstance()
                .tr("znack.status_value." + status.toLowerCase(java.util.Locale.ROOT), status);
    }

    private TableCell<ZnackGtinInventorySummary, String> statusCell(String activeClass, String emptyClass) {
        return new TableCell<>() {
            private final Label chip = new Label();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                    return;
                }
                chip.setText(item);
                chip.getStyleClass().setAll("badge", item.equals(tr("kiz_mapping.status.unmapped")) ? emptyClass : activeClass);
                setGraphic(chip);
            }
        };
    }

    private final class ActionsCell extends TableCell<ZnackGtinInventorySummary, ZnackGtinInventorySummary> {
        private final Button mapping = new Button();
        private final Button retry = new Button();
        private final Button buy = new Button();
        private final Button export = new Button();
        private final Button archive = new Button();
        private final HBox box = new HBox(6, mapping, retry, export, archive, buy);
        private final Tooltip buyTooltip = new Tooltip(tr("kiz_mapping.action.buy"));
        private final Tooltip technicalGtinTooltip = new Tooltip(tr("supply.gtin_inventory.error.technical_gtin"));

        private ActionsCell() {
            icon(mapping, "fth-link", tr("kiz_mapping.action.mapping"));
            icon(retry, "fth-rotate-ccw", tr("kiz_mapping.action.retry_introduction"));
            icon(buy, "fth-plus", tr("kiz_mapping.action.buy"));
            icon(export, "fth-download", tr("kiz_mapping.action.export"));
            icon(archive, "fth-trash-2", tr("kiz_mapping.action.delete_failed"));
            mapping.setOnAction(e -> showMapping(getItem()));
            retry.setOnAction(e -> retryIntroduction(getItem()));
            export.setOnAction(e -> exportKiz(getItem()));
            archive.setOnAction(e -> archiveFailedKiz(getItem()));
            buy.setOnAction(e -> showBuy(getItem()));
        }

        private void icon(Button button, String iconLiteral, String tooltip) {
            button.getStyleClass().add("btn-icon");
            button.setGraphic(new org.kordamp.ikonli.javafx.FontIcon(iconLiteral));
            button.setTooltip(new Tooltip(tooltip));
            button.setAccessibleText(tooltip);
        }

        @Override protected void updateItem(ZnackGtinInventorySummary item, boolean empty) {
            super.updateItem(item, empty);
            boolean technical = !empty && item != null && GtinNormalizer.isTechnicalRange(item.gtin());
            buy.setDisable(technical);
            buy.setTooltip(technical ? technicalGtinTooltip : buyTooltip);
            boolean introductionFailed = !empty && item != null
                    && item.discardable() > 0
                    && "INTRODUCTION_FAILED".equalsIgnoreCase(value(item.latestPipelineStage()));
            retry.setVisible(introductionFailed);
            retry.setManaged(introductionFailed);
            export.setDisable(empty || item == null || item.available() <= 0);
            archive.setDisable(empty || item == null || item.discardable() <= 0);
            setGraphic(empty || item == null ? null : box);
        }
    }

    @FunctionalInterface private interface ThrowingSupplier {
        Void get() throws Exception;
    }

    private void syncProducts(ZnackRepository repository) throws Exception {
        Settings settings = repository.getSettings();
        ZnackSignatureProvider signer = settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                ? ZnackSignatureProvider.unconfigured()
                : new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        new ZnackProductService(api, new ZnackAuthService(api, signer), repository).sync(settings);
        ZnackPurchaseCoordinator.create(repository).resumeEligibleIntroductionsAsync(settings);
    }

    private boolean hasVerifiedSignature(Settings settings) {
        return settings != null && settings.signerCertificate() != null && !settings.signerCertificate().isBlank()
                && settings.signerTestedAt() != null;
    }
}
