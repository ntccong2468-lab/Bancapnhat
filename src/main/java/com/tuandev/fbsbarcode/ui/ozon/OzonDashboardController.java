package com.tuandev.fbsbarcode.ui.ozon;

import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.ozon.OzonPostingSortingService;
import com.tuandev.fbsbarcode.features.shop.ShopOperationCoordinator;
import com.tuandev.fbsbarcode.features.ozon.OzonProductVariant;
import com.tuandev.fbsbarcode.features.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.features.supply.OrderSortPreferenceService;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.ozon.OzonBatchPrintReadiness;
import com.tuandev.fbsbarcode.integration.ozon.OzonCatalogRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonPrintBundleService;
import com.tuandev.fbsbarcode.integration.ozon.OzonPrintReadiness;
import com.tuandev.fbsbarcode.integration.ozon.OzonPrintReadinessService;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonShipService;
import com.tuandev.fbsbarcode.integration.ozon.OzonSyncWorkflow;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinAutoSync;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackProductService;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackSafety;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.FriendlyErrorService;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.ui.kizmapping.KizGtinMappingEditor;
import com.tuandev.fbsbarcode.ui.kizmapping.OzonGtinMappingEditor;
import com.tuandev.fbsbarcode.ui.kizmapping.OzonKizPolicyEditor;
import com.tuandev.fbsbarcode.ui.license.LicenseDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackInsufficientFundsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackMissingDocumentsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackOperatorTermsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackSuzAuthDialogService;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

/** JavaFX-first Ozon FBS workspace aligned with the existing WB packing workflow. */
public final class OzonDashboardController {
    private static final Set<String> ACTIVE_PURCHASE_STAGES = Set.of(
            "QUEUED", "VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER", "POLLING_ORDER", "DOWNLOADING_CODES",
            "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION", "POLLING_INTRODUCTION");

    private final OzonPostingRepository postings = new OzonPostingRepository();
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonSyncWorkflow syncWorkflow = new OzonSyncWorkflow();
    private final OzonShipService shipService = new OzonShipService();
    private final OzonPrintBundleService printBundleService = new OzonPrintBundleService();
    private final OzonPrintReadinessService printReadinessService = new OzonPrintReadinessService();
    private final FboProductImageService imageService = new FboProductImageService();
    private final OzonPostingSortingService sortingService = new OzonPostingSortingService();
    private final OrderSortPreferenceService sortPreferences = new OrderSortPreferenceService();
    private final KizMappingRepository gtinRepository = new KizMappingRepository();
    private final Set<String> selectedPostingNumbers = new LinkedHashSet<>();
    private final Set<String> purchasesStarting = new HashSet<>();
    private final CheckBox selectAllCheckBox = new CheckBox();

    @FXML private Label titleLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Button refreshButton;
    @FXML private TabPane orderStatusTabs;
    @FXML private Tab newOrdersTab;
    @FXML private Tab packingOrdersTab;
    @FXML private Tab deliveringOrdersTab;
    @FXML private HBox selectionActionBar;
    @FXML private Label selectedCountLabel;
    @FXML private Button moveToPackingButton;
    @FXML private Label emptyNewOrdersLabel;
    @FXML private Label emptyPackingOrdersLabel;
    @FXML private Label emptyDeliveringOrdersLabel;
    @FXML private TableView<OzonPostingDto> newOrdersTable;
    @FXML private TableColumn<OzonPostingDto, Boolean> newOrderSelectTC;
    @FXML private TableColumn<OzonPostingDto, OzonPostingDto> newOrderImageTC;
    @FXML private TableColumn<OzonPostingDto, String> newOrderNumberTC;
    @FXML private TableColumn<OzonPostingDto, String> newOrderItemsTC;
    @FXML private TableView<OzonPostingDto> packingOrdersTable;
    @FXML private TableColumn<OzonPostingDto, OzonPostingDto> packingOrderImageTC;
    @FXML private TableColumn<OzonPostingDto, String> packingOrderNumberTC;
    @FXML private TableColumn<OzonPostingDto, String> packingOrderItemsTC;
    @FXML private TableColumn<OzonPostingDto, Void> packingLabelTC;
    @FXML private TableView<OzonPostingDto> deliveringOrdersTable;
    @FXML private TableColumn<OzonPostingDto, OzonPostingDto> deliveringOrderImageTC;
    @FXML private TableColumn<OzonPostingDto, String> deliveringOrderNumberTC;
    @FXML private TableColumn<OzonPostingDto, String> deliveringOrderItemsTC;
    @FXML private TableColumn<OzonPostingDto, String> deliveringOrderStatusTC;
    @FXML private CheckBox sortByProductCheckBox;
    @FXML private CheckBox sortByArticleCheckBox;
    @FXML private CheckBox sortByColorCheckBox;
    @FXML private CheckBox sortBySizeCheckBox;
    @FXML private Button printAllButton;
    @FXML private Label gtinInventoryTitleLabel;
    @FXML private Label gtinInventoryEmptyLabel;
    @FXML private ProgressIndicator gtinInventoryLoading;
    @FXML private Button gtinInventoryRefreshButton;
    @FXML private Button kizPolicyButton;
    @FXML private TextField gtinSearchField;
    @FXML private VBox gtinInventoryList;

    private Shop shop;
    private boolean busy;
    private Integer busyShopId;
    private long requestToken;
    private Map<String, String> productImageUrls = Map.of();
    private List<OzonProductDto> catalogProducts = List.of();
    private List<OzonPostingDto> packingOrdersRaw = List.of();
    private boolean updatingSortControls;
    private BiConsumer<Integer, Boolean> onBusy = (ignoredShop, ignoredBusy) -> { };
    private ZnackRepository znackRepository;
    private List<ZnackGtinInventorySummary> gtinSummaries = List.of();
    private Timeline gtinRefreshTimer;
    private javafx.animation.RotateTransition gtinRefreshSpin;
    private boolean gtinLoading;
    private boolean gtinSyncing;
    private boolean gtinSyncPending;
    private boolean pendingSyncShowsErrors;
    private long shopGeneration;

    @FXML
    private void initialize() {
        setupTables();
        setupSorting();
        setupGtinInventory();
        orderStatusTabs.getSelectionModel().select(newOrdersTab);
        orderStatusTabs.getSelectionModel().selectedItemProperty().addListener((ignored, oldTab, selectedTab) -> {
            if (selectedTab == packingOrdersTab) refreshPostings();
        });
        applyTranslations();
        clear();
    }

    public void setShop(Shop shop, boolean syncRemote) {
        Shop selected = shop != null && shop.getMarketplace() == Marketplace.OZON ? shop : null;
        boolean sameContext = sameShopContext(this.shop, selected);
        if (!sameContext) {
            requestToken++;
            setBusy(false);
            selectedPostingNumbers.clear();
        }
        this.shop = selected;
        if (!sameContext) setGtinShop(selected);
        if (this.shop == null) {
            clear();
            return;
        }
        loadLocal();
        syncGtinInventoryOnOpen();
        if (syncRemote) sync();
    }

    public void sync() {
        Shop selected = shop;
        if (selected == null || busy) return;
        long token = ++requestToken;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(selected.getId(), () -> {
                    syncWorkflow.checkConnection(selected);
                    syncWorkflow.syncOverview(selected);
                    return null;
                });
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.sync_done"));
            loadLocal();
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    public void setOnBusy(BiConsumer<Integer, Boolean> onBusy) {
        this.onBusy = onBusy == null ? (ignoredShop, ignoredBusy) -> { } : onBusy;
    }

    public void showPackingQueue() {
        orderStatusTabs.getSelectionModel().select(newOrdersTab);
        loadLocal();
        refreshPostings();
    }

    private void refreshPostings() {
        Shop selected = shop;
        if (selected == null || busy) return;
        long token = ++requestToken;
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(
                        selected.getId(), () -> syncWorkflow.syncCurrentPostings(selected));
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            loadLocal();
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("ozon.dashboard.title"));
        refreshButton.setText(i18n.tr("ozon.dashboard.refresh"));
        updateTabTitles();
        moveToPackingButton.setText(i18n.tr("ozon.dashboard.move_to_packing"));
        printAllButton.setText(i18n.tr("ozon.dashboard.print_all"));
        sortByProductCheckBox.setText(i18n.tr("ozon.dashboard.sort.product"));
        sortByArticleCheckBox.setText(i18n.tr("ozon.dashboard.sort.article"));
        sortByColorCheckBox.setText(i18n.tr("ozon.dashboard.sort.color"));
        sortBySizeCheckBox.setText(i18n.tr("ozon.dashboard.sort.size"));
        gtinInventoryTitleLabel.setText(i18n.tr("supply.gtin_inventory.title"));
        gtinInventoryEmptyLabel.setText(i18n.tr("supply.gtin_inventory.empty"));
        gtinInventoryRefreshButton.setTooltip(new Tooltip(i18n.tr("supply.gtin_inventory.refresh")));
        String kizPolicyTooltip = i18n.tr("ozon.kiz_policy.tooltip");
        kizPolicyButton.setText(null);
        kizPolicyButton.setAccessibleText(kizPolicyTooltip);
        kizPolicyButton.setTooltip(new Tooltip(kizPolicyTooltip));
        gtinSearchField.setPromptText(i18n.tr("kiz_mapping.search_gtin"));
        emptyNewOrdersLabel.setText(i18n.tr("ozon.dashboard.empty.new"));
        emptyPackingOrdersLabel.setText(i18n.tr("ozon.dashboard.empty.packing"));
        emptyDeliveringOrdersLabel.setText(i18n.tr("ozon.dashboard.empty.delivering"));
        newOrdersTable.setPlaceholder(new Label(i18n.tr("ozon.dashboard.empty.new")));
        packingOrdersTable.setPlaceholder(new Label(i18n.tr("ozon.dashboard.empty.packing")));
        deliveringOrdersTable.setPlaceholder(new Label(i18n.tr("ozon.dashboard.empty.delivering")));
        newOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        packingOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        deliveringOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        newOrderItemsTC.setText(i18n.tr("ozon.dashboard.col.items"));
        packingOrderItemsTC.setText(i18n.tr("ozon.dashboard.col.items"));
        deliveringOrderItemsTC.setText(i18n.tr("ozon.dashboard.col.items"));
        deliveringOrderStatusTC.setText(i18n.tr("ozon.dashboard.col.status"));
        newOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        packingOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        deliveringOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        packingLabelTC.setText("");
        updateSelectionState();
        renderGtinSummaries(gtinSummaries);
        newOrdersTable.refresh();
        packingOrdersTable.refresh();
        deliveringOrdersTable.refresh();
    }

    @FXML private void onRefresh() { sync(); }

    @FXML private void onSelectAll() {
        if (selectAllCheckBox.isSelected()) {
            newOrdersTable.getItems().stream().map(OzonPostingDto::postingNumber).forEach(selectedPostingNumbers::add);
        } else {
            newOrdersTable.getItems().stream().map(OzonPostingDto::postingNumber).forEach(selectedPostingNumbers::remove);
        }
        newOrdersTable.refresh();
        updateSelectionState();
    }

    @FXML private void onMoveToPacking() {
        Shop selected = shop;
        List<String> postingNumbers = new ArrayList<>(selectedPostingNumbers);
        if (selected == null || postingNumbers.isEmpty() || busy) return;
        long token = ++requestToken;
        Task<BatchTransitionResult> task = new Task<>() {
            @Override protected BatchTransitionResult call() {
                List<String> completed = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                for (String postingNumber : postingNumbers) {
                    try {
                        shipService.ship(selected, postingNumber, true);
                        completed.add(postingNumber);
                    } catch (Exception exception) {
                        failed.add(postingNumber + " (" + safeFailure(exception) + ")");
                    }
                }
                return new BatchTransitionResult(postingNumbers.size(), List.copyOf(completed), List.copyOf(failed));
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            BatchTransitionResult result = task.getValue();
            selectedPostingNumbers.removeAll(result.completed());
            statusLabel.setText(batchResultText(result));
            loadLocal();
            if (!result.completed().isEmpty()) orderStatusTabs.getSelectionModel().select(packingOrdersTab);
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    @FXML private void onPrintAll() {
        Shop selected = shop;
        List<OzonPostingDto> queue = List.copyOf(packingOrdersTable.getItems());
        if (selected == null || queue.isEmpty() || busy) return;
        List<String> postingNumbers = queue.stream().map(OzonPostingDto::postingNumber).toList();
        long token = ++requestToken;
        Task<OzonBatchPrintReadiness> task = new Task<>() {
            @Override protected OzonBatchPrintReadiness call() throws Exception {
                for (String postingNumber : postingNumbers) {
                    syncWorkflow.refreshPosting(selected, postingNumber);
                }
                return printReadinessService.inspectAll(selected, postingNumbers);
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            OzonBatchPrintReadiness readiness = task.getValue();
            loadLocal();
            if (!readiness.ready()) {
                String reason = batchReadinessBlockedText(readiness);
                statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"));
                AlertService.showWarning(I18nService.getInstance().tr("ozon.dashboard.readiness.title"),
                        I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"), reason);
                return;
            }
            chooseBatchTargetAndExport(selected, postingNumbers);
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void downloadLabel(OzonPostingDto posting) {
        Shop selected = shop;
        if (posting == null || selected == null || busy || !canPrintLabel(posting)) return;
        long token = ++requestToken;
        Task<OzonPrintReadiness> task = new Task<>() {
            @Override protected OzonPrintReadiness call() throws Exception {
                syncWorkflow.refreshPosting(selected, posting.postingNumber());
                return printReadinessService.inspect(selected, posting.postingNumber());
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            OzonPrintReadiness readiness = task.getValue();
            loadLocal();
            if (!readiness.ready()) {
                String reason = readinessBlockedText(readiness);
                statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"));
                AlertService.showWarning(I18nService.getInstance().tr("ozon.dashboard.readiness.title"),
                        I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"), reason);
                return;
            }
            chooseSingleTargetAndExport(selected, posting);
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void chooseSingleTargetAndExport(Shop selected, OzonPostingDto posting) {
        File target = choosePdf("OZON-" + safeFilename(posting.postingNumber()) + ".pdf");
        if (target == null) return;
        File picking = pickingTarget(target);
        runPrintTask(selected, new Task<>() {
            @Override protected PrintOutput call() throws Exception {
                printBundleService.export(selected, posting.postingNumber(), target, picking);
                return new PrintOutput(I18nService.getInstance().tr("ozon.dashboard.print_one_done"),
                        List.of(target, picking), List.of(posting.postingNumber()));
            }
        });
    }

    private void chooseBatchTargetAndExport(Shop selected, List<String> postingNumbers) {
        File target = choosePdf("OZON-FBS-" + LocalDate.now() + ".pdf");
        if (target == null) return;
        File picking = pickingTarget(target);
        runPrintTask(selected, new Task<>() {
            @Override protected PrintOutput call() throws Exception {
                OzonPrintBundleService.BatchExportResult result = printBundleService.exportAll(
                        selected, postingNumbers, target, picking);
                return new PrintOutput(MessageFormat.format(
                        I18nService.getInstance().tr("ozon.dashboard.print_all_done"), result.postingCount()),
                        List.of(target, picking), postingNumbers);
            }
        });
    }

    private File choosePdf(String initialName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18nService.getInstance().tr("ozon.dashboard.print_label"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18nService.getInstance().tr("filechooser.pdf"), "*.pdf"));
        chooser.setInitialFileName(initialName);
        File downloads = AppPaths.preferredDownloadsDirectory();
        if (downloads != null) chooser.setInitialDirectory(downloads);
        return chooser.showSaveDialog(packingOrdersTable.getScene() == null
                ? null : packingOrdersTable.getScene().getWindow());
    }

    private void runPrintTask(Shop selected, Task<PrintOutput> task) {
        long token = ++requestToken;
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            PrintOutput output = task.getValue();
            statusLabel.setText(output.message());
            loadLocal();
            refreshGtinInventory();
            try {
                openExportedFiles(output.files());
            } catch (IOException exception) {
                AlertService.showWarning(I18nService.getInstance().tr("ozon.dashboard.open_files.title"),
                        I18nService.getInstance().tr("ozon.dashboard.open_files.failed"),
                        exception.getMessage());
            }
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void finishFailure(long token, Shop selected, Throwable failure) {
        if (token != requestToken || !isCurrent(selected)) return;
        setBusy(false);
        statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.error"));
        AlertService.showError(failure == null || failure.getMessage() == null
                ? I18nService.getInstance().tr("ozon.dashboard.error") : failure.getMessage());
        loadLocal();
        refreshGtinInventory();
    }

    private void loadLocal() {
        Shop selected = shop;
        if (selected == null) {
            newOrdersTable.getItems().clear();
            packingOrdersTable.getItems().clear();
            deliveringOrdersTable.getItems().clear();
            packingOrdersRaw = List.of();
            updateEmptyStates();
            updatePrintAllState();
            updateTabTitles();
            return;
        }
        String selectedNew = selectedPostingNumber(newOrdersTable);
        String selectedPacking = selectedPostingNumber(packingOrdersTable);
        String selectedDelivering = selectedPostingNumber(deliveringOrdersTable);
        List<OzonPostingDto> newOrders = postings.findByStatus(selected.getId(), "awaiting_packaging", 500, 0);
        List<OzonPostingDto> packingOrders = postings.findByStatus(selected.getId(), "awaiting_deliver", 500, 0);
        List<OzonPostingDto> deliveringOrders = postings.findByStatus(selected.getId(), "delivering", 500, 0);
        catalogProducts = catalog.findAll(selected.getId());
        productImageUrls = catalogProducts.stream()
                .filter(product -> !product.sku().isBlank() && !product.primaryImageUrl().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        OzonProductDto::sku, OzonProductDto::primaryImageUrl, (first, ignored) -> first));
        newOrdersTable.getItems().setAll(newOrders);
        packingOrdersRaw = List.copyOf(packingOrders);
        packingOrdersTable.getItems().setAll(
                sortingService.sort(packingOrdersRaw, getSortOptions(), catalogProducts));
        deliveringOrdersTable.getItems().setAll(deliveringOrders);
        Set<String> availableNewOrders = newOrders.stream().map(OzonPostingDto::postingNumber)
                .collect(java.util.stream.Collectors.toSet());
        selectedPostingNumbers.retainAll(availableNewOrders);
        selectPosting(newOrdersTable, selectedNew);
        selectPosting(packingOrdersTable, selectedPacking);
        selectPosting(deliveringOrdersTable, selectedDelivering);
        updateEmptyStates();
        updateSelectionState();
        updatePrintAllState();
        updateTabTitles();
    }

    private void updateTabTitles() {
        I18nService i18n = I18nService.getInstance();
        newOrdersTab.setText(i18n.tr("ozon.dashboard.tab.new") + " (" + newOrdersTable.getItems().size() + ")");
        packingOrdersTab.setText(i18n.tr("ozon.dashboard.tab.packing") + " (" + packingOrdersTable.getItems().size() + ")");
        deliveringOrdersTab.setText(i18n.tr("ozon.dashboard.tab.delivering") + " ("
                + deliveringOrdersTable.getItems().size() + ")");
    }

    private void updateEmptyStates() {
        setEmptyState(emptyNewOrdersLabel, false);
        setEmptyState(emptyPackingOrdersLabel, false);
        setEmptyState(emptyDeliveringOrdersLabel, false);
    }

    private static void setEmptyState(Label label, boolean empty) {
        label.setVisible(empty);
        label.setManaged(empty);
    }

    private void setupTables() {
        selectAllCheckBox.setOnAction(event -> onSelectAll());
        newOrderSelectTC.setGraphic(selectAllCheckBox);
        newOrderSelectTC.setCellValueFactory(data ->
                new ReadOnlyObjectWrapper<>(selectedPostingNumbers.contains(data.getValue().postingNumber())));
        newOrderSelectTC.setCellFactory(column -> selectionCell());
        configureImageColumn(newOrderImageTC);
        configureImageColumn(packingOrderImageTC);
        configureImageColumn(deliveringOrderImageTC);
        configureOrderColumns(newOrderNumberTC, newOrderItemsTC);
        configureOrderColumns(packingOrderNumberTC, packingOrderItemsTC);
        configureOrderColumns(deliveringOrderNumberTC, deliveringOrderItemsTC);
        deliveringOrderStatusTC.setCellValueFactory(data ->
                new SimpleStringProperty(statusText(data.getValue().status())));
        packingLabelTC.setCellFactory(column -> labelCell());
        for (TableView<OzonPostingDto> table : List.of(newOrdersTable, packingOrdersTable, deliveringOrdersTable)) {
            table.setSortPolicy(ignored -> false);
            table.setRowFactory(ignored -> new TableRow<>() {
                @Override protected void updateItem(OzonPostingDto posting, boolean empty) {
                    super.updateItem(posting, empty);
                    double height = empty || posting == null ? Region.USE_COMPUTED_SIZE : orderRowHeight(posting);
                    setMinHeight(height);
                    setPrefHeight(height);
                }
            });
            table.getColumns().forEach(column -> {
                column.setSortable(false);
                column.setReorderable(false);
            });
        }
    }

    private void configureOrderColumns(TableColumn<OzonPostingDto, String> orderColumn,
            TableColumn<OzonPostingDto, String> itemsColumn) {
        orderColumn.setCellValueFactory(data -> new SimpleStringProperty(orderText(data.getValue())));
        itemsColumn.setCellValueFactory(data -> new SimpleStringProperty(itemsText(data.getValue())));
        itemsColumn.setCellFactory(column -> new OrderItemsCell());
    }

    private void configureImageColumn(TableColumn<OzonPostingDto, OzonPostingDto> imageColumn) {
        imageColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final VBox images = new VBox(6);
            private String currentPostingNumber;
            {
                images.setAlignment(Pos.CENTER);
            }
            @Override protected void updateItem(OzonPostingDto posting, boolean empty) {
                super.updateItem(posting, empty);
                if (empty || posting == null) {
                    currentPostingNumber = null;
                    images.getChildren().clear();
                    setGraphic(null);
                    return;
                }
                currentPostingNumber = posting.postingNumber();
                images.getChildren().clear();
                for (OzonPostingItemDto item : posting.items()) {
                    images.getChildren().add(productImage(posting.postingNumber(), item));
                }
                setGraphic(images);
            }

            private StackPane productImage(String postingNumber, OzonPostingItemDto item) {
                ImageView imageView = new ImageView();
                imageView.setFitWidth(42);
                imageView.setFitHeight(50);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.setVisible(false);
                StackPane placeholder = new StackPane();
                placeholder.setPrefSize(42, 50);
                placeholder.setMaxSize(42, 50);
                placeholder.getStyleClass().add("image-placeholder");
                Label quantityBadge = new Label("× " + item.quantity());
                quantityBadge.getStyleClass().addAll("badge", "badge-warning");
                quantityBadge.setMouseTransparent(true);
                StackPane.setAlignment(quantityBadge, Pos.BOTTOM_RIGHT);
                StackPane container = new StackPane(placeholder, imageView, quantityBadge);
                container.setPrefSize(46, 52);

                String requestedUrl = productImageUrl(item);
                if (!requestedUrl.isBlank()) {
                    imageService.loadImage(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                        if (!Objects.equals(currentPostingNumber, postingNumber)
                                || !images.getChildren().contains(container)
                                || bytes == null || bytes.length == 0) return;
                        imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                        imageView.setVisible(true);
                        placeholder.setVisible(false);
                    }));
                }
                return container;
            }
        });
    }

    private String productImageUrl(OzonPostingItemDto item) {
        if (item == null || item.sku().isBlank()) return "";
        return value(productImageUrls.get(item.sku()));
    }

    private TableCell<OzonPostingDto, Boolean> selectionCell() {
        return new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    OzonPostingDto posting = getTableRow() == null ? null : getTableRow().getItem();
                    if (posting == null) return;
                    if (checkBox.isSelected()) selectedPostingNumbers.add(posting.postingNumber());
                    else selectedPostingNumbers.remove(posting.postingNumber());
                    updateSelectionState();
                });
            }
            @Override protected void updateItem(Boolean selected, boolean empty) {
                super.updateItem(selected, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                checkBox.setSelected(Boolean.TRUE.equals(selected));
                checkBox.setDisable(busy);
                setGraphic(checkBox);
            }
        };
    }

    private TableCell<OzonPostingDto, Void> labelCell() {
        return new TableCell<>() {
            private final Button printButton = new Button();
            {
                printButton.getStyleClass().addAll("button", "btn-icon", "fbo-row-print-button");
                printButton.setGraphic(new FontIcon("fth-printer"));
                printButton.setOnAction(event -> {
                    OzonPostingDto posting = getTableRow() == null ? null : getTableRow().getItem();
                    if (posting != null) downloadLabel(posting);
                });
            }
            @Override protected void updateItem(Void ignored, boolean empty) {
                super.updateItem(ignored, empty);
                OzonPostingDto posting = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || posting == null) {
                    setGraphic(null);
                    return;
                }
                String label = I18nService.getInstance().tr("ozon.dashboard.print_label");
                printButton.setText(null);
                printButton.setTooltip(new Tooltip(label));
                printButton.setAccessibleText(label);
                printButton.setDisable(busy || !canPrintLabel(posting));
                setAlignment(Pos.CENTER);
                setGraphic(printButton);
            }
        };
    }

    private void setupSorting() {
        setSortOptions(sortPreferences.load());
        sortByProductCheckBox.selectedProperty().addListener((ignored, oldValue, newValue) -> sortChanged());
        sortByArticleCheckBox.selectedProperty().addListener((ignored, oldValue, newValue) -> sortChanged());
        sortByColorCheckBox.selectedProperty().addListener((ignored, oldValue, newValue) -> sortChanged());
        sortBySizeCheckBox.selectedProperty().addListener((ignored, oldValue, newValue) -> sortChanged());
    }

    private void sortChanged() {
        if (updatingSortControls) return;
        sortPreferences.save(getSortOptions());
        String selected = selectedPostingNumber(packingOrdersTable);
        packingOrdersTable.getItems().setAll(
                sortingService.sort(packingOrdersRaw, getSortOptions(), catalogProducts));
        selectPosting(packingOrdersTable, selected);
    }

    private OrderSortOptions getSortOptions() {
        return new OrderSortOptions(sortByProductCheckBox.isSelected(), sortByArticleCheckBox.isSelected(),
                sortByColorCheckBox.isSelected(), sortBySizeCheckBox.isSelected());
    }

    private void setSortOptions(OrderSortOptions options) {
        OrderSortOptions safe = options == null ? OrderSortOptions.defaultOptions() : options;
        updatingSortControls = true;
        try {
            sortByProductCheckBox.setSelected(safe.bySubject());
            sortByArticleCheckBox.setSelected(safe.byArticle());
            sortByColorCheckBox.setSelected(safe.byColor());
            sortBySizeCheckBox.setSelected(safe.bySize());
        } finally {
            updatingSortControls = false;
        }
    }

    private void updateSelectionState() {
        int selectedVisible = (int) newOrdersTable.getItems().stream()
                .filter(posting -> selectedPostingNumbers.contains(posting.postingNumber())).count();
        int visible = newOrdersTable.getItems().size();
        selectAllCheckBox.setIndeterminate(selectedVisible > 0 && selectedVisible < visible);
        selectAllCheckBox.setSelected(visible > 0 && selectedVisible == visible);
        selectedCountLabel.setText(MessageFormat.format(
                I18nService.getInstance().tr("ozon.dashboard.selected_count"), selectedVisible));
        boolean hasSelection = selectedVisible > 0;
        selectionActionBar.setVisible(hasSelection);
        selectionActionBar.setManaged(hasSelection);
        moveToPackingButton.setDisable(busy || shop == null || !hasSelection);
    }

    private void updatePrintAllState() {
        printAllButton.setDisable(busy || shop == null || packingOrdersTable.getItems().isEmpty());
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) {
            busyShopId = shop == null ? null : shop.getId();
            if (busyShopId != null) onBusy.accept(busyShopId, true);
        } else if (busyShopId != null) {
            onBusy.accept(busyShopId, false);
            busyShopId = null;
        }
        loadingIndicator.setVisible(busy);
        loadingIndicator.setManaged(busy);
        refreshButton.setDisable(busy || shop == null);
        newOrdersTable.setDisable(busy);
        packingOrdersTable.setDisable(busy);
        deliveringOrdersTable.setDisable(busy);
        selectAllCheckBox.setDisable(busy);
        kizPolicyButton.setDisable(busy || gtinLoading || gtinSyncing || shop == null);
        updateSelectionState();
        updatePrintAllState();
    }

    private void clear() {
        selectedPostingNumbers.clear();
        productImageUrls = Map.of();
        catalogProducts = List.of();
        packingOrdersRaw = List.of();
        newOrdersTable.getItems().clear();
        packingOrdersTable.getItems().clear();
        deliveringOrdersTable.getItems().clear();
        statusLabel.setText("");
        setBusy(false);
        updateEmptyStates();
        updatePrintAllState();
        updateTabTitles();
    }

    private void setupGtinInventory() {
        if (gtinInventoryRefreshButton.getGraphic() != null) {
            gtinRefreshSpin = new javafx.animation.RotateTransition(
                    javafx.util.Duration.millis(800), gtinInventoryRefreshButton.getGraphic());
            gtinRefreshSpin.setByAngle(360);
            gtinRefreshSpin.setCycleCount(javafx.animation.Animation.INDEFINITE);
            gtinRefreshSpin.setInterpolator(javafx.animation.Interpolator.LINEAR);
        }
        gtinRefreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), event -> {
            if (shop != null && !gtinLoading && !gtinSyncing) refreshGtinInventory();
        }));
        gtinRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        gtinSearchField.textProperty().addListener((ignored, oldValue, newValue) -> renderGtinSummaries(gtinSummaries));
        renderGtinSummaries(List.of());
        setGtinLoading(false);
    }

    private void setGtinShop(Shop selected) {
        shopGeneration++;
        purchasesStarting.clear();
        gtinSummaries = List.of();
        gtinSearchField.clear();
        gtinSyncPending = false;
        pendingSyncShowsErrors = false;
        gtinSyncing = false;
        znackRepository = selected == null ? null : new ZnackRepository(new ShopContext(selected.getId(), selected.getName()));
        setGtinLoading(false);
        if (selected == null) gtinRefreshTimer.stop();
        else gtinRefreshTimer.play();
        refreshGtinInventory();
    }

    private void syncGtinInventoryOnOpen() {
        refreshGtinInventory();
        if (shop == null || znackRepository == null || !hasVerifiedSignature(znackRepository.getSettings())) return;
        if (ZnackGtinAutoSync.shouldAutoSync(shop.getId())) requestGtinSync(false);
    }

    @FXML private void onRefreshGtinInventory() { requestGtinSync(true); }

    @FXML private void onKizPolicy() {
        if (shop == null) return;
        long generation = shopGeneration;
        int shopId = shop.getId();
        new OzonKizPolicyEditor().open(shopId, new KizGtinMappingEditor.Host() {
            @Override public boolean isCurrent() {
                return generation == shopGeneration && shop != null && shop.getId() == shopId;
            }
            @Override public void busy(boolean value) {
                if (generation == shopGeneration) setGtinLoading(value);
            }
            @Override public void saved() {
                if (generation == shopGeneration) loadLocal();
            }
            @Override public void error(Throwable error) {
                if (generation == shopGeneration) AlertService.showError(friendlyError(error));
            }
        });
    }

    private void requestGtinSync(boolean showErrors) {
        if (znackRepository == null) return;
        if (gtinLoading || gtinSyncing) {
            gtinSyncPending = true;
            pendingSyncShowsErrors |= showErrors;
            return;
        }
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                syncProducts(currentRepository);
                return null;
            }
        };
        setGtinSyncing(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) return;
            setGtinSyncing(false);
            refreshGtinInventory();
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            setGtinSyncing(false);
            if (showErrors) AlertService.showError(friendlyError(task.getException()));
            refreshGtinInventory();
        });
        AppTaskExecutor.execute(task);
    }

    private void refreshGtinInventory() {
        if (gtinLoading) return;
        if (shop == null) {
            renderGtinSummaries(List.of());
            setGtinLoading(false);
            return;
        }
        int shopId = shop.getId();
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override protected List<ZnackGtinInventorySummary> call() {
                return gtinRepository.findGtinSummaries(shopId);
            }
        };
        setGtinLoading(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != shopId) return;
            renderGtinSummaries(task.getValue());
            setGtinLoading(false);
            startPendingGtinSync();
            ZnackInsufficientFundsDialogService.promptIfNeeded(
                    currentRepository, task.getValue(), this::refreshGtinInventory);
            ZnackOperatorTermsDialogService.promptIfNeeded(currentRepository, task.getValue());
            ZnackSuzAuthDialogService.promptIfNeeded(currentRepository, task.getValue());
            ZnackMissingDocumentsDialogService.promptIfNeeded(currentRepository, task.getValue());
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            setGtinLoading(false);
            AlertService.showError(friendlyError(task.getException()));
            startPendingGtinSync();
        });
        AppTaskExecutor.execute(task);
    }

    private void renderGtinSummaries(List<ZnackGtinInventorySummary> summaries) {
        gtinSummaries = summaries == null ? List.of() : List.copyOf(summaries);
        gtinInventoryList.getChildren().clear();
        String query = gtinSearchField.getText() == null ? "" : gtinSearchField.getText().trim().toLowerCase(Locale.ROOT);
        for (ZnackGtinInventorySummary summary : gtinSummaries) {
            if (query.isEmpty() || summary.matchesSearch(query)) gtinInventoryList.getChildren().add(createGtinCard(summary));
        }
        updateGtinEmpty();
    }

    private VBox createGtinCard(ZnackGtinInventorySummary summary) {
        Label code = new Label(value(summary.gtin()));
        code.getStyleClass().add("gtin-code");
        Label name = new Label(summary.productName() == null || summary.productName().isBlank()
                ? tr("supply.gtin_inventory.unnamed") : summary.productName());
        name.getStyleClass().add("gtin-name");
        name.setWrapText(true);
        VBox identity = new VBox(2, code, name);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Button mapping = new Button();
        mapping.getStyleClass().add("btn-icon");
        mapping.setGraphic(new FontIcon("fth-link"));
        mapping.setTooltip(new Tooltip(tr("kiz_mapping.action.mapping")));
        mapping.setAccessibleText(tr("kiz_mapping.action.mapping"));
        mapping.setOnAction(event -> showMapping(summary));

        Button buy = new Button();
        buy.getStyleClass().addAll("btn-primary", "gtin-buy-button");
        buy.setGraphic(new FontIcon("fth-plus"));
        boolean technicalGtin = GtinNormalizer.isTechnicalRange(summary.gtin());
        buy.setTooltip(new Tooltip(technicalGtin
                ? tr("supply.gtin_inventory.error.technical_gtin") : tr("supply.gtin_inventory.buy")));
        buy.setAccessibleText(tr("supply.gtin_inventory.buy"));
        buy.setDisable(technicalGtin || purchasesStarting.contains(summary.gtin()));
        buy.setOnAction(event -> showBuy(summary));

        HBox header = new HBox(8, identity, mapping, buy);
        if ("INTRODUCTION_FAILED".equalsIgnoreCase(value(summary.latestPipelineStage()))) {
            Button retry = new Button();
            retry.getStyleClass().add("btn-icon");
            retry.setGraphic(new FontIcon("fth-rotate-ccw"));
            retry.setTooltip(new Tooltip(tr("kiz_mapping.action.retry_introduction")));
            retry.setAccessibleText(tr("kiz_mapping.action.retry_introduction"));
            retry.setDisable(purchasesStarting.contains(summary.gtin()));
            retry.setOnAction(event -> startRetryIntroduction(summary.gtin()));
            header.getChildren().add(header.getChildren().indexOf(buy), retry);
        }
        header.setAlignment(Pos.CENTER_LEFT);

        Label availableCaption = new Label(tr("supply.gtin_inventory.available"));
        availableCaption.getStyleClass().add("gtin-count-caption");
        Label available = new Label(String.valueOf(summary.available()));
        available.getStyleClass().addAll("badge", "badge-green");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusRow = new HBox(6, availableCaption, available, spacer);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        String status = first(summary.latestPipelineStage(), summary.latestOrderStatus());
        if (!status.isBlank()) {
            Label statusChip = new Label(localizeStatus(status));
            statusChip.getStyleClass().addAll("badge", statusClass(status));
            statusChip.setMaxWidth(150);
            statusChip.setTooltip(new Tooltip(statusChip.getText()));
            statusRow.getChildren().add(statusChip);
        }
        VBox card = new VBox(8, header, statusRow);
        card.getStyleClass().add("gtin-inventory-card");
        String displayError = ZnackErrorMessages.displayForPipeline(status, summary.latestError());
        if (!displayError.isBlank()) {
            Label detail = new Label(displayError);
            detail.getStyleClass().add("text-muted");
            detail.setWrapText(true);
            card.getChildren().add(detail);
            Tooltip.install(card, new Tooltip(summary.latestError()));
        }
        return card;
    }

    private void showMapping(ZnackGtinInventorySummary summary) {
        if (shop == null) return;
        long generation = shopGeneration;
        int shopId = shop.getId();
        new OzonGtinMappingEditor().open(shopId, summary.gtin(), new KizGtinMappingEditor.Host() {
            @Override public boolean isCurrent() {
                return generation == shopGeneration && shop != null && shop.getId() == shopId;
            }
            @Override public void busy(boolean value) {
                if (generation == shopGeneration) setGtinLoading(value);
            }
            @Override public void saved() {
                if (generation == shopGeneration) refreshGtinInventory();
            }
            @Override public void error(Throwable error) {
                if (generation == shopGeneration) AlertService.showError(friendlyError(error));
            }
        });
    }

    private void showBuy(ZnackGtinInventorySummary summary) {
        if (znackRepository == null || shop == null || !new LicenseDialogService().ensureLicensed()) return;
        TextInputDialog dialog = new TextInputDialog("1");
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("supply.gtin_inventory.buy_title"));
        dialog.setHeaderText(summary.gtin() + "\n" + value(summary.productName()));
        dialog.setContentText(tr("znack.field.quantity"));
        dialog.showAndWait().ifPresent(text -> {
            int quantity;
            try {
                quantity = Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                AlertService.showError(tr("kiz_mapping.buy.positive"));
                return;
            }
            if (quantity <= 0) {
                AlertService.showError(tr("kiz_mapping.buy.positive"));
                return;
            }
            startPurchase(summary.gtin(), quantity);
        });
    }

    private void startPurchase(String gtin, int quantity) {
        if (znackRepository == null || !purchasesStarting.add(gtin)) {
            AlertService.showError(tr("supply.gtin_inventory.error.pipeline_active"));
            return;
        }
        renderGtinSummaries(gtinSummaries);
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(currentRepository);
        Settings settings = currentRepository.getSettings();
        Task<Long> task = new Task<>() {
            @Override protected Long call() throws Exception {
                return coordinator.enqueue(settings, gtin, quantity, java.util.UUID.randomUUID().toString());
            }
        };
        task.setOnSucceeded(event -> finishPurchaseStart(generation, gtin, null));
        task.setOnFailed(event -> finishPurchaseStart(generation, gtin, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void startRetryIntroduction(String gtin) {
        if (znackRepository == null || !purchasesStarting.add(gtin)) return;
        renderGtinSummaries(gtinSummaries);
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(currentRepository);
        Settings settings = currentRepository.getSettings();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                coordinator.retryIntroduction(settings, gtin);
                return null;
            }
        };
        task.setOnSucceeded(event -> finishPurchaseStart(generation, gtin, null));
        task.setOnFailed(event -> finishPurchaseStart(generation, gtin, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void finishPurchaseStart(long generation, String gtin, Throwable error) {
        if (generation != shopGeneration) return;
        purchasesStarting.remove(gtin);
        if (error != null) AlertService.showError(friendlyError(error));
        refreshGtinInventory();
    }

    private void setGtinLoading(boolean loading) {
        gtinLoading = loading;
        updateGtinBusyState();
    }

    private void setGtinSyncing(boolean syncing) {
        gtinSyncing = syncing;
        updateGtinBusyState();
    }

    private void updateGtinBusyState() {
        boolean value = gtinLoading || gtinSyncing;
        gtinInventoryLoading.setVisible(value);
        gtinInventoryLoading.setManaged(value);
        gtinInventoryRefreshButton.setDisable(value || shop == null);
        kizPolicyButton.setDisable(value || busy || shop == null);
        if (gtinRefreshSpin != null) {
            if (value) {
                if (gtinRefreshSpin.getStatus() != javafx.animation.Animation.Status.RUNNING) gtinRefreshSpin.playFromStart();
            } else {
                gtinRefreshSpin.stop();
                if (gtinInventoryRefreshButton.getGraphic() != null) gtinInventoryRefreshButton.getGraphic().setRotate(0);
            }
        }
        updateGtinEmpty();
    }

    private void updateGtinEmpty() {
        boolean empty = !gtinLoading && !gtinSyncing && gtinInventoryList.getChildren().isEmpty();
        gtinInventoryEmptyLabel.setVisible(empty);
        gtinInventoryEmptyLabel.setManaged(empty);
    }

    private void startPendingGtinSync() {
        if (!gtinSyncPending || gtinLoading || gtinSyncing || znackRepository == null) return;
        boolean showErrors = pendingSyncShowsErrors;
        gtinSyncPending = false;
        pendingSyncShowsErrors = false;
        requestGtinSync(showErrors);
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

    private static boolean hasVerifiedSignature(Settings settings) {
        return settings != null && settings.signerCertificate() != null && !settings.signerCertificate().isBlank()
                && settings.signerTestedAt() != null;
    }

    private boolean isActivePipeline(String stage) {
        return stage != null && ACTIVE_PURCHASE_STAGES.contains(stage.toUpperCase(Locale.ROOT));
    }

    private String statusClass(String status) {
        String normalized = status.toUpperCase(Locale.ROOT);
        if ("FAILED".equals(normalized) || "CANCELLED".equals(normalized)
                || "INTRODUCTION_FAILED".equals(normalized)) return "badge-red";
        if ("WAITING_INTRODUCTION_DOCUMENTS".equals(normalized)) return "badge-warning";
        return isActivePipeline(normalized) ? "badge-warning" : "badge-green";
    }

    private String localizeStatus(String status) {
        return status == null || status.isBlank() ? "" : tr("znack.status_value." + status.toLowerCase(Locale.ROOT), status);
    }

    private String friendlyError(Throwable error) {
        return FriendlyErrorService.format(error);
    }

    private static String batchReadinessBlockedText(OzonBatchPrintReadiness readiness) {
        List<String> reasons = new ArrayList<>();
        readiness.postings().stream().filter(value -> !value.ready()).limit(8)
                .forEach(value -> reasons.add(value.postingNumber() + ": " + readinessBlockedText(value)));
        readiness.gtinAvailability().stream().filter(value -> !value.sufficient())
                .forEach(value -> reasons.add(MessageFormat.format(
                        I18nService.getInstance().tr("ozon.dashboard.readiness.availability"),
                        value.gtin(), value.required(), value.available())));
        return reasons.isEmpty() ? I18nService.getInstance().tr("ozon.dashboard.readiness.blocked")
                : String.join("\n", reasons);
    }

    private static String readinessSummary(OzonPrintReadiness readiness) {
        I18nService i18n = I18nService.getInstance();
        if (!readiness.postingAvailable()) return i18n.tr("ozon.dashboard.readiness.posting_missing");
        if (!readiness.unsupportedRequirements().isEmpty()) {
            return MessageFormat.format(i18n.tr("ozon.dashboard.readiness.unsupported"),
                    String.join(", ", readiness.unsupportedRequirements()));
        }
        if (!readiness.missingSkus().isEmpty()) {
            return MessageFormat.format(i18n.tr("ozon.dashboard.readiness.missing_mapping"),
                    String.join(", ", readiness.missingSkus()));
        }
        if (readiness.requiredKiz() == 0) return i18n.tr("ozon.dashboard.readiness.not_required");
        if (readiness.ready()) return MessageFormat.format(i18n.tr("ozon.dashboard.readiness.ready"), readiness.requiredKiz());
        return MessageFormat.format(i18n.tr("ozon.dashboard.readiness.insufficient"), availabilityText(readiness));
    }

    private static String readinessBlockedText(OzonPrintReadiness readiness) {
        if ("REJECTED".equals(readiness.preparationStage())) {
            return I18nService.getInstance().tr("ozon.dashboard.readiness.rejected");
        }
        return readinessSummary(readiness);
    }

    private static String availabilityText(OzonPrintReadiness readiness) {
        return readiness.gtinAvailability().stream().filter(value -> !value.sufficient())
                .map(value -> MessageFormat.format(
                        I18nService.getInstance().tr("ozon.dashboard.readiness.availability"),
                        value.gtin(), value.required(), value.available()))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String orderText(OzonPostingDto posting) {
        String order = posting.orderNumber().isBlank() ? posting.postingNumber() : posting.orderNumber();
        return order.equals(posting.postingNumber()) ? order : order + "\n" + posting.postingNumber();
    }

    static String itemsText(OzonPostingDto posting) {
        if (posting.items().isEmpty()) return "-";
        return posting.items().stream().map(OzonDashboardController::itemText)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static double orderRowHeight(OzonPostingDto posting) {
        int itemRows = posting == null ? 0 : posting.items().size();
        return 76.0 + Math.max(0, itemRows - 1) * 64.0;
    }

    private static String itemText(OzonPostingItemDto item) {
        String name = item.name().isBlank() ? "SKU " + item.sku() : item.name();
        return name + " × " + item.quantity();
    }

    private static String statusText(String status) {
        return I18nService.getInstance().tr("ozon.dashboard.status_value." + status, status);
    }

    private static String batchResultText(BatchTransitionResult result) {
        String text = MessageFormat.format(I18nService.getInstance().tr("ozon.dashboard.move_result"),
                result.completed().size(), result.requested());
        return result.failed().isEmpty() ? text : text + " • " + result.failed().getFirst();
    }

    private static boolean canPrintLabel(OzonPostingDto posting) {
        return posting != null && switch (posting.status().toLowerCase(Locale.ROOT)) {
            case "awaiting_deliver", "delivering", "delivered" -> true;
            default -> false;
        };
    }

    private static String selectedPostingNumber(TableView<OzonPostingDto> table) {
        OzonPostingDto selected = table.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.postingNumber();
    }

    private static void selectPosting(TableView<OzonPostingDto> table, String postingNumber) {
        if (postingNumber == null) return;
        table.getItems().stream().filter(posting -> Objects.equals(posting.postingNumber(), postingNumber))
                .findFirst().ifPresent(value -> table.getSelectionModel().select(value));
    }

    private boolean isCurrent(Shop selected) {
        return shop != null && selected != null && shop.getId() == selected.getId();
    }

    private static boolean sameShopContext(Shop first, Shop second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.getId() == second.getId() && first.getMarketplace() == second.getMarketplace()
                && Objects.equals(first.getClientId(), second.getClientId())
                && Objects.equals(first.getApiKey(), second.getApiKey());
    }

    private static String safeFilename(String value) {
        String safe = value == null ? "posting" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "posting" : safe;
    }

    private static String safeFailure(Throwable failure) {
        String message = failure == null ? "error" : failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        String safe = message.replaceAll("\\p{Cntrl}", " ").strip();
        return safe.length() <= 100 ? safe : safe.substring(0, 100);
    }

    private static File pickingTarget(File labelTarget) {
        String name = labelTarget.getName();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        return new File(labelTarget.getAbsoluteFile().getParentFile(), base + "-picking.pdf");
    }

    private static void openExportedFiles(List<File> files) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException(I18nService.getInstance().tr("ozon.dashboard.open_files.unsupported"));
        }
        for (File file : files) {
            if (file == null || !file.isFile()) {
                throw new IOException(I18nService.getInstance().tr("ozon.dashboard.open_files.missing"));
            }
            Desktop.getDesktop().open(file);
        }
    }

    private String tr(String key) { return I18nService.getInstance().tr(key); }
    private String tr(String key, String fallback) { return I18nService.getInstance().tr(key, fallback); }
    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? value(fallback) : preferred;
    }
    private static String value(String value) { return value == null ? "" : value; }

    private final class OrderItemsCell extends TableCell<OzonPostingDto, String> {
        private final VBox box = new VBox(8);
        OrderItemsCell() {
            box.setAlignment(Pos.CENTER_LEFT);
        }
        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            OzonPostingDto posting = getTableRow() == null ? null : getTableRow().getItem();
            if (empty || posting == null || posting.items().isEmpty()) {
                box.getChildren().clear();
                setGraphic(null);
                return;
            }
            box.getChildren().clear();
            for (OzonPostingItemDto item : posting.items()) {
                box.getChildren().add(itemDetails(posting, item));
            }
            setAccessibleText(itemsText(posting));
            setGraphic(box);
        }

        private VBox itemDetails(OzonPostingDto posting, OzonPostingItemDto item) {
            OzonProductVariant variant = OzonProductVariant.from(item, findCatalogProduct(item));
            Label title = new Label(item.name().isBlank() ? "SKU " + item.sku() : item.name());
            title.getStyleClass().add("text-bold");
            title.setWrapText(true);
            Label quantityBadge = new Label("× " + item.quantity());
            quantityBadge.getStyleClass().addAll("badge", "badge-warning");
            HBox titleRow = new HBox(8, title, quantityBadge);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.ALWAYS);
            Label metadata = new Label();
            metadata.getStyleClass().add("text-muted");
            metadata.setText(tr("ozon.dashboard.item.article") + ": " + first(variant.article(), item.sku()));
            List<String> details = new ArrayList<>();
            if (!variant.color().isBlank()) details.add(tr("ozon.dashboard.item.color") + ": " + variant.color());
            if (!variant.size().isBlank()) details.add(tr("ozon.dashboard.item.size") + ": " + variant.size());
            Label variants = new Label(String.join(" • ", details));
            variants.getStyleClass().add("text-muted");
            variants.setVisible(!details.isEmpty());
            variants.setManaged(!details.isEmpty());
            VBox itemBox = new VBox(3, titleRow, metadata, variants);
            itemBox.setAlignment(Pos.CENTER_LEFT);
            return itemBox;
        }
    }

    private OzonProductDto findCatalogProduct(OzonPostingItemDto item) {
        if (item == null) return null;
        return catalogProducts.stream().filter(product ->
                (!item.productId().isBlank() && item.productId().equals(product.productId()))
                        || (!item.sku().isBlank() && item.sku().equals(product.sku()))
                        || (!item.offerId().isBlank() && item.offerId().equals(product.offerId())))
                .findFirst().orElse(null);
    }

    private record BatchTransitionResult(int requested, List<String> completed, List<String> failed) { }
    private record PrintOutput(String message, List<File> files, List<String> postingNumbers) {
        private PrintOutput {
            message = message == null ? "" : message;
            files = files == null ? List.of() : List.copyOf(files);
            postingNumbers = postingNumbers == null ? List.of() : List.copyOf(postingNumbers);
        }
    }
}
