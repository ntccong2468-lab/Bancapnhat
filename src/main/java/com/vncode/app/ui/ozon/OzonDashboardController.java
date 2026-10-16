package com.vncode.app.ui.ozon;

import com.vncode.app.features.fbo.FboProductImageService;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.features.ozon.OzonPostingSortingService;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.features.ozon.OzonProductVariant;
import com.vncode.app.features.supply.OrderSortOptions;
import com.vncode.app.features.supply.OrderSortPreferenceService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonBatchPrintReadiness;
import com.vncode.app.integration.ozon.OzonCatalogRepository;
import com.vncode.app.integration.ozon.OzonPostingDto;
import com.vncode.app.integration.ozon.OzonPostingItemDto;
import com.vncode.app.integration.ozon.OzonPostingRepository;
import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.ui.kiz.ShipmentKizPane;
import com.vncode.app.integration.ozon.OzonPrintBundleService;
import com.vncode.app.integration.ozon.OzonPrintReadiness;
import com.vncode.app.integration.ozon.OzonPrintReadinessService;
import com.vncode.app.integration.ozon.OzonProductDto;
import com.vncode.app.integration.ozon.OzonShipService;
import com.vncode.app.integration.ozon.OzonSyncWorkflow;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackErrorMessages;
import com.vncode.app.integration.znack.ZnackGtinAutoSync;
import com.vncode.app.integration.znack.ZnackGtinInventorySummary;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.ZnackProductService;
import com.vncode.app.integration.znack.ZnackPurchaseCoordinator;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.ZnackSafety;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.FriendlyErrorService;
import com.vncode.app.shared.I18nService;
import com.vncode.app.ui.kizmapping.KizGtinMappingEditor;
import com.vncode.app.ui.kizmapping.OzonGtinMappingEditor;
import com.vncode.app.ui.kizmapping.OzonKizPolicyEditor;
import com.vncode.app.ui.znack.ZnackInsufficientFundsDialogService;
import com.vncode.app.ui.znack.ZnackMissingDocumentsDialogService;
import com.vncode.app.ui.znack.ZnackOperatorTermsDialogService;
import com.vncode.app.ui.znack.ZnackSuzAuthDialogService;
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
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

/** JavaFX-first Ozon FBS workspace aligned with the existing WB packing workflow. */
public final class OzonDashboardController {
    private static final double ITEM_HEIGHT = 116;
    private static final double ITEM_GAP = 4;
    private static final Set<String> ACTIVE_PURCHASE_STAGES = Set.of(
            "QUEUED", "VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER", "POLLING_ORDER", "DOWNLOADING_CODES",
            "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION", "POLLING_INTRODUCTION");

    private final OzonPostingRepository postings = new OzonPostingRepository();
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonSyncWorkflow syncWorkflow;
    public OzonDashboardController(){this(new OzonSyncWorkflow());}
    public OzonDashboardController(OzonSyncWorkflow syncWorkflow){this.syncWorkflow=java.util.Objects.requireNonNull(syncWorkflow);}
    private final OzonShipService shipService = new OzonShipService();
    private final ShipmentKizPane shipmentKizPane=new ShipmentKizPane();
    @FXML private VBox shipmentKizHost;
    @FXML private Tab shipmentKizTab;
    private final OzonPrintReadinessService printReadinessService = new OzonPrintReadinessService();
    private FboProductImageService imageService = new FboProductImageService();
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
    @FXML private FlowPane selectionActionBar;
    @FXML private Button clearSearchButton;
    @FXML private TableColumn<OzonPostingDto, String> newOrderShipmentTC, packingOrderShipmentTC, deliveringOrderShipmentTC;
    @FXML private TableColumn<OzonPostingDto, OzonPostingDto> newOrderPriceTC, packingOrderPriceTC, deliveringOrderPriceTC;
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
    @FXML private Button selectFilteredButton,clearBatchSelectionButton;
    private final java.util.concurrent.atomic.AtomicLong progressPublishedAt=new java.util.concurrent.atomic.AtomicLong();
    @FXML private Label gtinInventoryTitleLabel;
    @FXML private Label gtinInventoryEmptyLabel;
    @FXML private ProgressIndicator gtinInventoryLoading;
    @FXML private Button gtinInventoryRefreshButton;
    @FXML private Button kizPolicyButton;
    @FXML private TextField gtinSearchField;
    @FXML private javafx.scene.control.ListView<ZnackGtinInventorySummary> gtinInventoryList;

    @FXML private TextField postingSearch;
    @FXML private Button previousPageButton,nextPageButton;
    @FXML private Label pageStatusLabel,workflowLabel;
    @FXML private javafx.scene.control.TitledPane gtinInventoryPanel,batchResultsPanel;
    @FXML private javafx.scene.control.TextArea batchResultsArea;
    private final com.vncode.app.shared.ShopReadCoordinator reads=com.vncode.app.shared.ShopReadCoordinator.shared();
    private java.util.function.Function<com.vncode.app.features.ozon.OzonFbsQuery,com.vncode.app.features.ozon.OzonFbsPage> fbsReader=new com.vncode.app.features.ozon.OzonFbsSnapshotService()::load;
    private final Map<String,Integer> offsets=new java.util.HashMap<>();
    private final Map<String,Long> totals=new java.util.HashMap<>();
    private final javafx.animation.PauseTransition postingDebounce=new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private Map<String,OzonProductDto> productsBySku=Map.of();private Map<String,String> verifiedGtinsBySku=Map.of();
    private long pageGeneration;private boolean disposed;
    void setImageService(FboProductImageService images){imageService=java.util.Objects.requireNonNull(images);}
    public void setFbsReader(java.util.function.Function<com.vncode.app.features.ozon.OzonFbsQuery,com.vncode.app.features.ozon.OzonFbsPage> reader){this.fbsReader=Objects.requireNonNull(reader);}
    public int currentPageLimit(){return 100;}
    private Shop shop;
    private boolean busy;
    private Integer busyShopId;
    private long requestToken;
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
        statusLabel.visibleProperty().bind(statusLabel.textProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        setupTables();
        setupSorting();
        setupGtinInventory();
        shipmentKizHost.getChildren().setAll(shipmentKizPane);
        shipmentKizPane.setOnSelectionChanged(source->{gtinInventoryPanel.setVisible(source.purchased());gtinInventoryPanel.setManaged(source.purchased());});
        gtinInventoryList.setCellFactory(list->new javafx.scene.control.ListCell<>(){
            @Override protected void updateItem(ZnackGtinInventorySummary row,boolean empty){super.updateItem(row,empty);setText(null);setGraphic(empty||row==null?null:createGtinCard(row));}
        });
        orderStatusTabs.getSelectionModel().select(newOrdersTab);
        orderStatusTabs.getSelectionModel().selectedItemProperty().addListener((ignored, oldTab, selectedTab) -> updatePaging());
        postingDebounce.setOnFinished(e->{offsets.clear();loadLocal();});
        postingSearch.textProperty().addListener((o,a,b)->postingDebounce.playFromStart());
        gtinInventoryPanel.expandedProperty().addListener((o,a,b)->{if(b)refreshGtinInventory();});
        applyTranslations();
        clear();
    }

    public void setShop(Shop shop, boolean syncRemote) {
        Shop selected = shop != null && shop.getMarketplace() == Marketplace.OZON ? shop : null;
        boolean sameContext = sameShopContext(this.shop, selected);
        if (!sameContext) {
            requestToken++;
            setBusy(false);
            selectedPostingNumbers.clear();offsets.clear();totals.clear();pageGeneration++;
            productsBySku=Map.of();verifiedGtinsBySku=Map.of();catalogProducts=List.of();
            newOrdersTable.getItems().clear();packingOrdersTable.getItems().clear();deliveringOrdersTable.getItems().clear();
        }
        this.shop = selected;
        if(!sameContext){batchResultsArea.clear();batchResultsPanel.setVisible(false);batchResultsPanel.setManaged(false);}
        if (!sameContext) {setGtinShop(selected);shipmentKizPane.setContext(selected,"OZON:FBS");}
        if (this.shop == null) {
            clear();
            return;
        }
        loadLocal();
        if(com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))setBusy(true);
        if(gtinInventoryPanel.isExpanded())refreshGtinInventory();
        if (syncRemote) sync();
    }

    public void sync() {
        if(shop!=null&&com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_QUEUE)){
            com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.CATALOG);loadLocal();return;
        }
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishFailure(token, selected, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    public void setOnBusy(BiConsumer<Integer, Boolean> onBusy) {
        this.onBusy = onBusy == null ? (ignoredShop, ignoredBusy) -> { } : onBusy;
    }

    public void showPackingQueue() {
        orderStatusTabs.getSelectionModel().select(newOrdersTab);
        loadLocal();
    }

    private void refreshPostings() {
        if(shop!=null&&com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_QUEUE)){loadLocal();return;}
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishFailure(token, selected, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        String clearSearch = i18n.tr("ozon.fbs.clear_search");
        clearSearchButton.setAccessibleText(clearSearch);
        clearSearchButton.setTooltip(new Tooltip(clearSearch));
        for (var column : List.of(newOrderShipmentTC, packingOrderShipmentTC, deliveringOrderShipmentTC))
            column.setText(i18n.tr("ozon.dashboard.col.shipment"));
        for (var column : List.of(newOrderPriceTC, packingOrderPriceTC, deliveringOrderPriceTC))
            column.setText(i18n.tr("packing.col.price"));
        postingSearch.setPromptText(i18n.tr("ozon.fbs.search"));previousPageButton.setText(i18n.tr("ozon.fbs.previous"));nextPageButton.setText(i18n.tr("ozon.fbs.next"));workflowLabel.setText(i18n.tr("ozon.fbs.workflow"));gtinInventoryPanel.setText(i18n.tr("supply.gtin_inventory.title"));
        shipmentKizTab.setText(i18n.tr("kiz.shipment.title"));shipmentKizPane.applyTranslations();
        batchResultsPanel.setText(i18n.tr("ozon.fbs.results"));titleLabel.setText(i18n.tr("ozon.dashboard.title"));
        refreshButton.setText(i18n.tr("ozon.dashboard.refresh"));
        updateTabTitles();
        moveToPackingButton.setText(i18n.tr("ozon.dashboard.move_to_packing"));
        printAllButton.setText(i18n.tr("ozon.dashboard.print_all"));
        selectFilteredButton.setText(i18n.tr("ozon.batch.select_filtered"));clearBatchSelectionButton.setText(i18n.tr("ozon.batch.clear_selection"));
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
    @FXML private void onClearSearch() { postingSearch.clear(); }

    @FXML private void onSelectAll() {
        if (selectAllCheckBox.isSelected()) {
            newOrdersTable.getItems().stream().map(OzonPostingDto::postingNumber).forEach(selectedPostingNumbers::add);
        } else {
            newOrdersTable.getItems().stream().map(OzonPostingDto::postingNumber).forEach(selectedPostingNumbers::remove);
        }
        newOrdersTable.refresh();
        updateSelectionState();
    }

    @FXML private void onClearBatchSelection(){selectedPostingNumbers.clear();newOrdersTable.refresh();updateSelectionState();}
    @FXML private void onSelectFiltered(){
        Shop selected=shop;if(selected==null||busy)return;String text=postingSearch.getText();String sort=sortFields();long token=++requestToken;long epoch=pageGeneration;
        Task<List<String>> task=new Task<>(){@Override protected List<String> call(){return postings.freezeFilteredNumbers(new com.vncode.app.features.ozon.OzonFbsQuery(selected.getId(),"awaiting_packaging",text,sort,0,100),10000);}};
        setBusy(true);task.setOnSucceeded(e->{if(token!=requestToken||!isCurrent(selected))return;setBusy(false);if(epoch!=pageGeneration||!text.equals(postingSearch.getText()))return;
            selectedPostingNumbers.clear();selectedPostingNumbers.addAll(task.getValue());newOrdersTable.refresh();updateSelectionState();});
        java.util.function.Consumer<Throwable> failed=error->finishFailure(token,selected,error);task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }

    @FXML private void onMoveToPacking() {
        Shop selected = shop;
        List<String> postingNumbers = new ArrayList<>(selectedPostingNumbers);
        if (selected == null || postingNumbers.isEmpty() || busy) return;
        var dialog=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        if(newOrdersTable.getScene()!=null&&newOrdersTable.getScene().getWindow()!=null)dialog.initOwner(newOrdersTable.getScene().getWindow());
        AlertService.applyTheme(dialog);dialog.setTitle(tr("ozon.fbs.confirm_title"));dialog.setHeaderText(selected.getName()+" · "+postingNumbers.size());dialog.setContentText(tr("ozon.fbs.confirm_body")+"\n"+String.join(", ",postingNumbers.stream().limit(20).toList())+(postingNumbers.size()>20?" …":""));
        var accepted=new javafx.scene.control.ButtonType(tr("ozon.fbs.confirm_accept"),javafx.scene.control.ButtonBar.ButtonData.OK_DONE);var cancelled=new javafx.scene.control.ButtonType(tr("common.cancel"),javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);dialog.getButtonTypes().setAll(accepted,cancelled);if(dialog.showAndWait().orElse(cancelled)!=accepted)return;
        if(disposed||!isCurrent(selected)||busy)return;
        var lease=com.vncode.app.shared.ShopBatchRegistry.claim(selected,"ozon-pack").orElse(null);if(lease==null){statusLabel.setText(tr("runtime.queue_full"));return;}
        long token = ++requestToken;
        Task<BatchTransitionResult> task = new Task<>() {
            @Override protected BatchTransitionResult call() {
                lease.requireOwner(selected);
                List<String> completed = new ArrayList<>();
                List<String> failed = new ArrayList<>();List<String> pending=new ArrayList<>();
                int processed=0;for (String postingNumber : postingNumbers) {
                    if(lease.stopping()||Thread.currentThread().isInterrupted())break;
                    lease.requireOwner(selected);
                    com.vncode.app.integration.listing.ListingIdentity.require(selected);
                    try {
                        shipService.ship(selected, postingNumber, true);
                        completed.add(postingNumber);
                    } catch (Exception exception) {
                        if(reconciliationRequired(exception))pending.add(postingNumber);else failed.add(postingNumber + " (" + safeFailure(exception) + ")");
                    }
                    reportProgress(token,selected,++processed,postingNumbers.size());
                }
                return new BatchTransitionResult(postingNumbers.size(), List.copyOf(completed), List.copyOf(failed),List.copyOf(pending));
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            BatchTransitionResult result = task.getValue();
            selectedPostingNumbers.removeAll(result.completed());
            statusLabel.setText(batchResultText(result));
            var lines=new ArrayList<String>();result.completed().forEach(id->lines.add(id+" · "+tr("ozon.fbs.packaged")));result.pending().forEach(id->lines.add(id+" · "+tr("ozon.fbs.pending")));result.failed().forEach(id->lines.add(id+" · "+tr("ozon.fbs.failed")));batchResultsArea.setText(String.join("\n",lines));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!result.pending().isEmpty()||!result.failed().isEmpty());
            loadLocal();
            if (!result.completed().isEmpty()) orderStatusTabs.getSelectionModel().select(packingOrdersTab);
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);finishFailure(token, selected, rejection);};
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    @FXML private void onPrintAll() {
        Shop selected = shop;
        if (selected == null || packingOrdersTable.getItems().isEmpty() || busy) return;
        KizSourceSelection sources=selectedKizSources();if(sources==null)return;
        String text=postingSearch.getText(),sort=sortFields();long epoch=pageGeneration;
        long token = ++requestToken;
        Task<List<String>> task=new Task<>(){@Override protected List<String> call(){return postings.freezeFilteredNumbers(new com.vncode.app.features.ozon.OzonFbsQuery(selected.getId(),"awaiting_deliver",text,sort,0,100),10000);}};
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            if(epoch!=pageGeneration||!text.equals(postingSearch.getText())||!sort.equals(sortFields()))return;
            var numbers=task.getValue();if(numbers.isEmpty())return;
            var confirm=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,MessageFormat.format(tr("ozon.batch.confirm_print"),numbers.size()),javafx.scene.control.ButtonType.YES,javafx.scene.control.ButtonType.NO);
            AlertService.applyTheme(confirm);confirm.setHeaderText(selected.getName());if(packingOrdersTable.getScene()!=null)confirm.initOwner(packingOrdersTable.getScene().getWindow());
            if(confirm.showAndWait().orElse(javafx.scene.control.ButtonType.NO)!=javafx.scene.control.ButtonType.YES)return;
            if(disposed||!isCurrent(selected)||busy||epoch!=pageGeneration||!text.equals(postingSearch.getText())||!sort.equals(sortFields()))return;
            checkBatchReadinessAndExport(selected,numbers,sources);
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishFailure(token, selected, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private KizSourceSelection selectedKizSources(){
        try{var source=shipmentKizPane.snapshot();if(!source.anySelected()){statusLabel.setText(tr("kiz.shipment.select_source"));return null;}return source;}
        catch(IllegalStateException loading){statusLabel.setText(com.vncode.app.features.kiz.KizErrorMessages.message("KIZ_SOURCE_LOADING"));return null;}
    }

    private void checkBatchReadinessAndExport(Shop selected,List<String> postingNumbers,KizSourceSelection sources){
        long token=++requestToken;Task<OzonBatchPrintReadiness> task=new Task<>(){@Override protected OzonBatchPrintReadiness call()throws Exception{new com.vncode.app.integration.ozon.OzonStockReplenishment().forPrint(selected,postingNumbers,sources);return printReadinessService.inspectAll(selected,postingNumbers,sources);}};
        setBusy(true);task.setOnSucceeded(e->{if(token!=requestToken||!isCurrent(selected))return;setBusy(false);var readiness=task.getValue();
            batchResultsArea.setText(readiness.postings().stream().map(row->row.postingNumber()+" · "+readinessSummary(row)).collect(java.util.stream.Collectors.joining("\n")));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!readiness.ready());
            if(!readiness.ready()) {
                statusLabel.setText(tr("ozon.dashboard.readiness.blocked"));
                if(readiness.readyPostingNumbers().isEmpty()){AlertService.showWarning(tr("ozon.dashboard.readiness.title"),tr("ozon.dashboard.readiness.blocked"),batchReadinessBlockedText(readiness));return;}
                var consent=new Alert(Alert.AlertType.CONFIRMATION,MessageFormat.format(tr("ozon.batch.confirm_ready_subset"),readiness.readyPostingNumbers().size(),postingNumbers.size())+"\n\n"+batchReadinessBlockedText(readiness),ButtonType.YES,ButtonType.NO);
                AlertService.applyTheme(consent);consent.setHeaderText(selected.getName());
                if(consent.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES)return;
                if(disposed||!isCurrent(selected)||busy||token!=requestToken)return;
                chooseBatchTargetAndExport(selected,readiness.readyPostingNumbers(),sources);return;
            }
            chooseBatchTargetAndExport(selected,postingNumbers,sources);});
        java.util.function.Consumer<Throwable> failed=error->finishFailure(token,selected,error);task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }

    private void reportProgress(long token,Shop selected,int completed,int total){
        long now=System.nanoTime(),previous=progressPublishedAt.get();if(completed<total&&now-previous<java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250))return;
        if(!progressPublishedAt.compareAndSet(previous,now))return;
        Platform.runLater(()->{if(!disposed&&busy&&token==requestToken&&isCurrent(selected))statusLabel.setText(MessageFormat.format(tr("ozon.batch.progress"),completed,total));});
    }

    private void downloadLabel(OzonPostingDto posting) {
        Shop selected = shop;
        if (posting == null || selected == null || busy || !canPrintLabel(posting)) return;
        KizSourceSelection sources=selectedKizSources();if(sources==null)return;
        long token = ++requestToken;
        Task<OzonPrintReadiness> task = new Task<>() {
            @Override protected OzonPrintReadiness call() throws Exception {
                syncWorkflow.refreshPosting(selected, posting.postingNumber());
                new com.vncode.app.integration.ozon.OzonStockReplenishment().forPrint(selected,List.of(posting.postingNumber()),sources);
                return printReadinessService.inspect(selected, posting.postingNumber(),sources);
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            OzonPrintReadiness readiness = task.getValue();
            batchResultsArea.setText(readiness.postingNumber()+" · "+readinessSummary(readiness));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!readiness.ready());
            loadLocal();
            if (!readiness.ready()) {
                String reason = readinessBlockedText(readiness);
                statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"));
                AlertService.showWarning(I18nService.getInstance().tr("ozon.dashboard.readiness.title"),
                        I18nService.getInstance().tr("ozon.dashboard.readiness.blocked"), reason);
                return;
            }
            chooseSingleTargetAndExport(selected, posting,sources);
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishFailure(token, selected, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void chooseSingleTargetAndExport(Shop selected, OzonPostingDto posting,KizSourceSelection sources) {
        File target = choosePdf("OZON-" + safeFilename(posting.postingNumber()) + ".pdf");
        if (target == null) return;
        File picking = pickingTarget(target);
        runPrintTask(selected,target,picking,List.of(posting.postingNumber()),progress->{
            new OzonPrintBundleService(sources).export(selected,posting.postingNumber(),target,picking);
            return new PrintOutput(tr("ozon.dashboard.print_one_done"),List.of(target,picking),List.of(posting.postingNumber()));
        });
    }

    private void chooseBatchTargetAndExport(Shop selected,List<String> postingNumbers,KizSourceSelection sources){
        File target=choosePdf("OZON-"+selected.getId()+"-FBS-"+LocalDate.now()+".pdf");if(target==null)return;
        File picking=pickingTarget(target);
        runPrintTask(selected,target,picking,postingNumbers,progress->{
            var result=new OzonPrintBundleService(sources).exportAll(selected,postingNumbers,target,picking,progress);
            return new PrintOutput(MessageFormat.format(tr("ozon.dashboard.print_all_done"),result.postingCount()),List.of(target,picking),postingNumbers);
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

    private void runPrintTask(Shop selected,File labelTarget,File pickingTarget,List<String> numbers,PrintWork work) {
        Task<PrintOutput> task=new Task<>(){
            protected PrintOutput call()throws Exception{
                var report=new com.vncode.app.integration.ozon.OzonFbsPrintReport(selected,numbers);
                try{
                    final long[] published={0};
                    var result=work.run(count->{updateProgress(count,numbers.size());long now=System.nanoTime();if(count==numbers.size()||now-published[0]>250_000_000L){published[0]=now;updateMessage(MessageFormat.format(tr("ozon.batch.progress"),count,numbers.size()));}});
                    report.finish(selected,true);return result;
                }catch(Exception failed){try{report.finish(selected,false);}catch(RuntimeException storage){failed.addSuppressed(storage);}throw failed;}
            }
        };
        if(disposed||!isCurrent(selected)||busy)return;
        var lease=com.vncode.app.shared.ShopBatchRegistry.claim(selected,"ozon-print",List.of(labelTarget.toPath(),pickingTarget.toPath())).orElse(null);if(lease==null){statusLabel.setText(tr("ozon.batch.target_busy"));return;}
        long token = ++requestToken;
        setBusy(true);
        task.messageProperty().addListener((ignored,oldValue,newValue)->{
            if(!disposed&&busy&&token==requestToken&&isCurrent(selected)&&newValue!=null&&!newValue.isBlank())statusLabel.setText(newValue);
        });
        task.setOnSucceeded(event -> {
            lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            PrintOutput output = task.getValue();
            statusLabel.setText(output.message());
            batchResultsArea.setText(output.postingNumbers().stream().map(n->n+" · "+tr("ozon.fbs.pdf_created_local_kiz")).collect(java.util.stream.Collectors.joining("\n")));
            batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);
            loadLocal();
            refreshGtinInventory();
            Task<Void> open=new Task<>(){@Override protected Void call()throws IOException{openExportedFiles(output.files());return null;}};
            open.setOnFailed(e->{if(!disposed&&isCurrent(selected))AlertService.showWarning(tr("ozon.dashboard.open_files.title"),tr("ozon.dashboard.open_files.failed"),tr("ozon.dashboard.open_files.unsupported"));});
            AppTaskExecutor.execute(open,error->{});
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);if(!disposed&&isCurrent(selected)){batchResultsArea.setText(numbers.stream().map(n->n+" · "+tr("ozon.fbs.batch_incomplete")).collect(java.util.stream.Collectors.joining("\n")));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(true);}finishFailure(token, selected, rejection);};
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    @FunctionalInterface private interface PrintWork{PrintOutput run(java.util.function.IntConsumer progress)throws Exception;}

    private void finishFailure(long token, Shop selected, Throwable failure) {
        if (token != requestToken || !isCurrent(selected)) return;
        setBusy(false);
        statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.error"));
        AlertService.showError(com.vncode.app.shared.FriendlyErrorService.format(failure));
        loadLocal();
        refreshGtinInventory();
    }

    public void refreshLocalData(){if(!disposed&&!busy)loadLocal();}
    private void loadLocal() {
        Shop selected=shop;if(disposed||selected==null){newOrdersTable.getItems().clear();packingOrdersTable.getItems().clear();deliveringOrdersTable.getItems().clear();updateTabTitles();updatePaging();return;}
        long epoch=++pageGeneration,started=System.nanoTime();String text=postingSearch.getText();String sort=sortFields();var frozenOffsets=Map.copyOf(offsets);
        var owner=selected;String scope=com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner)+"|"+text+"|"+sort+"|"+frozenOffsets;
        reads.submitLocal(new com.vncode.app.shared.ShopReadCoordinator.Key(selected.getId(),"ozon-fbs-page",scope),()->{
            var pages=new java.util.LinkedHashMap<String,com.vncode.app.features.ozon.OzonFbsPage>();
            for(String status:List.of("awaiting_packaging","awaiting_deliver","delivering"))pages.put(status,fbsReader.apply(new com.vncode.app.features.ozon.OzonFbsQuery(owner.getId(),status,text,sort,frozenOffsets.getOrDefault(status,0),100)));
            return pages;
        }).whenComplete((pages,error)->Platform.runLater(()->{
            if(disposed||epoch!=pageGeneration||!isCurrent(owner))return;
            if(error!=null){statusLabel.setText(tr("products.read_failed"));return;}
            var previousProducts=productsBySku;var previousGtins=verifiedGtinsBySku;
            var products=new java.util.HashMap<String,OzonProductDto>();pages.values().forEach(page->products.putAll(page.productsBySku()));var gtins=new java.util.HashMap<String,String>();pages.values().forEach(page->gtins.putAll(page.verifiedGtinsBySku()));verifiedGtinsBySku=Map.copyOf(gtins);productsBySku=Map.copyOf(products);catalogProducts=List.copyOf(products.values());
            var enrichedSkus=new java.util.HashSet<String>();enrichedSkus.addAll(previousProducts.keySet());enrichedSkus.addAll(productsBySku.keySet());enrichedSkus.addAll(previousGtins.keySet());enrichedSkus.addAll(verifiedGtinsBySku.keySet());
            enrichedSkus.removeIf(sku->java.util.Objects.equals(previousProducts.get(sku),productsBySku.get(sku))&&java.util.Objects.equals(previousGtins.get(sku),verifiedGtinsBySku.get(sku)));
            java.util.function.BiPredicate<OzonPostingDto,OzonPostingDto> unchanged=(old,value)->old.equals(value)&&value.items().stream().noneMatch(item->enrichedSkus.contains(item.sku()));
            var newSelection=selectedPostingNumber(newOrdersTable);var packingSelection=selectedPostingNumber(packingOrdersTable);var deliveringSelection=selectedPostingNumber(deliveringOrdersTable);
            com.vncode.app.shared.TableRows.merge(newOrdersTable.getItems(),pages.get("awaiting_packaging").postings(),OzonPostingDto::postingNumber,unchanged);packingOrdersRaw=pages.get("awaiting_deliver").postings();com.vncode.app.shared.TableRows.merge(packingOrdersTable.getItems(),packingOrdersRaw,OzonPostingDto::postingNumber,unchanged);com.vncode.app.shared.TableRows.merge(deliveringOrdersTable.getItems(),pages.get("delivering").postings(),OzonPostingDto::postingNumber,unchanged);
            pages.forEach((status,page)->totals.put(status,page.total()));selectPosting(newOrdersTable,newSelection);selectPosting(packingOrdersTable,packingSelection);selectPosting(deliveringOrdersTable,deliveringSelection);
            updateEmptyStates();updateSelectionState();updatePrintAllState();updateTabTitles();updatePaging();
            com.vncode.app.shared.RuntimeDiagnostics.afterNextPulse("ozon-fbs-render",started);
        }));
    }
    private String sortFields(){var fields=new ArrayList<String>();var options=getSortOptions();if(options.bySubject())fields.add("product");if(options.byArticle())fields.add("article");if(options.byColor())fields.add("color");if(options.bySize())fields.add("size");return String.join(",",fields);}
    private String activeStatus(){return orderStatusTabs.getSelectionModel().getSelectedItem()==packingOrdersTab?"awaiting_deliver":orderStatusTabs.getSelectionModel().getSelectedItem()==deliveringOrdersTab?"delivering":"awaiting_packaging";}
    @FXML private void onPreviousPage(){String status=activeStatus();offsets.put(status,Math.max(0,offsets.getOrDefault(status,0)-100));loadLocal();}
    @FXML private void onNextPage(){String status=activeStatus();if(offsets.getOrDefault(status,0)+100<totals.getOrDefault(status,0L)){offsets.put(status,offsets.getOrDefault(status,0)+100);loadLocal();}}
    private void updatePaging(){if(pageStatusLabel==null)return;String status=activeStatus();long total=totals.getOrDefault(status,0L);int offset=offsets.getOrDefault(status,0);pageStatusLabel.setText((total==0?0:offset+1)+"–"+Math.min(offset+100,total)+" / "+total);previousPageButton.setDisable(shop==null||offset==0);nextPageButton.setDisable(shop==null||offset+100>=total);}
    public void dispose(){shipmentKizPane.dispose();disposed=true;pageGeneration++;requestToken++;shopGeneration++;postingDebounce.stop();if(gtinRefreshTimer!=null)gtinRefreshTimer.stop();if(gtinRefreshSpin!=null)gtinRefreshSpin.stop();productsBySku=Map.of();verifiedGtinsBySku=Map.of();catalogProducts=List.of();}

    private void updateTabTitles() {
        I18nService i18n = I18nService.getInstance();
        newOrdersTab.setText(i18n.tr("ozon.dashboard.tab.new") + " (" + totals.getOrDefault("awaiting_packaging",0L) + ")");
        packingOrdersTab.setText(i18n.tr("ozon.dashboard.tab.packing") + " (" + totals.getOrDefault("awaiting_deliver",0L) + ")");
        deliveringOrdersTab.setText(i18n.tr("ozon.dashboard.tab.delivering") + " ("
                + totals.getOrDefault("delivering",0L) + ")");
    }

    private void updateEmptyStates() {
        setEmptyState(emptyNewOrdersLabel,newOrdersTable.getItems().isEmpty());setEmptyState(emptyPackingOrdersLabel,packingOrdersTable.getItems().isEmpty());setEmptyState(emptyDeliveringOrdersLabel,deliveringOrdersTable.getItems().isEmpty());
    }

    private static void setEmptyState(Label label, boolean empty) {
        label.setVisible(empty);
        label.setManaged(empty);
    }

    private void setupTables() {
        for (var column : List.of(newOrderShipmentTC, packingOrderShipmentTC, deliveringOrderShipmentTC))
            column.setCellValueFactory(data -> new SimpleStringProperty(shipmentText(data.getValue())));
        for (var column : List.of(newOrderPriceTC, packingOrderPriceTC, deliveringOrderPriceTC))
            configurePriceColumn(column);
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
        deliveringOrderStatusTC.setCellFactory(column->new TableCell<>(){
            protected void updateItem(String text,boolean empty){super.updateItem(text,empty);setText(empty?null:text);var posting=getTableRow()==null?null:getTableRow().getItem();setTooltip(empty||posting==null?null:new Tooltip(text+"\n"+posting.status()));}
        });
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
            private final VBox images = new VBox(ITEM_GAP);
            private String currentPostingNumber;
            {
                images.setAlignment(Pos.TOP_CENTER);
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
                StackPane thumbnail = new StackPane(placeholder, imageView, quantityBadge);
                thumbnail.setMinSize(46, 52); thumbnail.setPrefSize(46, 52); thumbnail.setMaxSize(46, 52);
                StackPane container = new StackPane(thumbnail);
                container.setMinSize(46, ITEM_HEIGHT);
                container.setPrefSize(46, ITEM_HEIGHT);
                container.setMaxSize(46, ITEM_HEIGHT);
                container.setAlignment(Pos.TOP_CENTER);

                long imageEpoch=shopGeneration;int imageOwner=shop==null?0:shop.getId();String requestedUrl = productImageUrl(item);
                if (!requestedUrl.isBlank()) {
                    imageService.loadThumbnailBytes(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                        if (disposed||imageEpoch!=shopGeneration||shop==null||shop.getId()!=imageOwner||!Objects.equals(currentPostingNumber, postingNumber)
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
        var product=findCatalogProduct(item);return product==null?"":value(product.primaryImageUrl());
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
        offsets.clear();loadLocal();
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
                I18nService.getInstance().tr("ozon.dashboard.selected_count"), selectedPostingNumbers.size()));
        boolean hasSelection = !selectedPostingNumbers.isEmpty();
        selectionActionBar.setVisible(hasSelection);
        selectionActionBar.setManaged(hasSelection);
        moveToPackingButton.setDisable(busy || shop == null || !hasSelection);
        selectFilteredButton.setDisable(busy||shop==null);clearBatchSelectionButton.setDisable(busy||shop==null||!hasSelection);
    }

    private void updatePrintAllState() {
        printAllButton.setDisable(busy || shop == null || packingOrdersTable.getItems().isEmpty());
    }

    private void setBusy(boolean busy) {
        this.busy = busy;shipmentKizPane.setLocked(busy);
        if (busy) {
            busyShopId = shop == null ? null : shop.getId();
            if (busyShopId != null) onBusy.accept(busyShopId, true);
        } else if (busyShopId != null) {
            if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(busyShopId))onBusy.accept(busyShopId, false);
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
        catalogProducts = List.of();productsBySku=Map.of();verifiedGtinsBySku=Map.of();totals.clear();offsets.clear();
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
        gtinRefreshTimer.stop();
        if(selected!=null&&gtinInventoryPanel.isExpanded())refreshGtinInventory();
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration) return;
            setGtinSyncing(false);
            if (showErrors) AlertService.showError(friendlyError(rejection));
            refreshGtinInventory();
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration) return;
            setGtinLoading(false);
            AlertService.showError(friendlyError(rejection));
            startPendingGtinSync();
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void renderGtinSummaries(List<ZnackGtinInventorySummary> summaries) {
        gtinSummaries = summaries == null ? List.of() : List.copyOf(summaries);
        gtinInventoryList.getItems().clear();
        String query = gtinSearchField.getText() == null ? "" : gtinSearchField.getText().trim().toLowerCase(Locale.ROOT);
        for (ZnackGtinInventorySummary summary : gtinSummaries) {
            if (query.isEmpty() || summary.matchesSearch(query)) gtinInventoryList.getItems().add(summary);
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
        if (znackRepository == null || shop == null) return;
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishPurchaseStart(generation, gtin, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
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
        java.util.function.Consumer<Throwable> taskFailure = rejection -> finishPurchaseStart(generation, gtin, rejection);
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
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
        boolean empty = !gtinLoading && !gtinSyncing && gtinInventoryList.getItems().isEmpty();
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
        var sourceShop=new com.vncode.app.features.shop.ShopRepository().findById(repository.shop().shopId());
        if(sourceShop==null)throw new IllegalStateException("SHOP_CREDENTIALS_UNAVAILABLE");
        new com.vncode.app.features.sync.ZnackCatalogSyncService().refresh(sourceShop,false,p->{});
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
        if(readiness.missingSkus().stream().anyMatch(s->s.startsWith("KIZ_CATEGORY_STOCK: "))){
            return i18n.tr("kiz.error.category_shortage")+" · SKU: "+readiness.missingSkus().stream().map(s->s.replace("KIZ_CATEGORY_STOCK: ","")).collect(java.util.stream.Collectors.joining(", "));
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
        return ITEM_HEIGHT + 8 + Math.max(0, itemRows - 1) * (ITEM_HEIGHT + ITEM_GAP);
    }

    static String shipmentText(OzonPostingDto posting) {
        if (posting.shipmentAt().isBlank()) return "—";
        try {
            return java.time.OffsetDateTime.parse(posting.shipmentAt()).format(
                    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy\nHH:mm XXX"));
        } catch (java.time.format.DateTimeParseException invalid) {
            return posting.shipmentAt();
        }
    }

    private void configurePriceColumn(TableColumn<OzonPostingDto, OzonPostingDto> column) {
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(OzonPostingDto posting, boolean empty) {
                super.updateItem(posting, empty);
                if (empty || posting == null) { setGraphic(null); return; }
                var prices = new VBox(ITEM_GAP);
                for (var item : posting.items()) {
                    var price = new Label(item.price() + " " + item.currencyCode());
                    price.setTooltip(new Tooltip(price.getText()));
                    price.setMinHeight(ITEM_HEIGHT); price.setPrefHeight(ITEM_HEIGHT);
                    price.setMaxHeight(ITEM_HEIGHT); price.setAlignment(Pos.TOP_LEFT);
                    prices.getChildren().add(price);
                }
                setAlignment(Pos.TOP_LEFT); setGraphic(prices);
            }
        });
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
        if(!result.pending().isEmpty())text+=" • "+I18nService.getInstance().tr("ozon.fbs.pending")+": "+result.pending().size();return result.failed().isEmpty() ? text : text + " • " + result.failed().getFirst();
    }
    static boolean reconciliationRequired(Throwable error){return error instanceof com.vncode.app.integration.ozon.OzonApiException e&&(e.ambiguousMutation()||e.kind().equals("reconcile_required"));}

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
        private final VBox box = new VBox(ITEM_GAP);
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
            title.setWrapText(false);title.setTooltip(new Tooltip(title.getText()));
            Label quantityBadge = new Label("× " + item.quantity());
            quantityBadge.getStyleClass().addAll("badge", "badge-warning");
            HBox titleRow = new HBox(8, title, quantityBadge);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.ALWAYS);
            Label metadata = new Label();
            metadata.getStyleClass().add("text-muted");
            List<String> details = new ArrayList<>();
            details.add(tr("ozon.dashboard.item.article") + ": " + first(variant.article(), item.sku()));
            if (!variant.color().isBlank()) details.add(tr("ozon.dashboard.item.color") + ": " + variant.color());
            if (!variant.size().isBlank()) details.add(tr("ozon.dashboard.item.size") + ": " + variant.size());
            metadata.setText(String.join(" • ", details));
            metadata.setTooltip(new Tooltip(metadata.getText()));
            var gtin=new Label("GTIN: "+verifiedGtinsBySku.getOrDefault(item.sku(),tr("products.unverified")));gtin.getStyleClass().add("text-muted");var card=findCatalogProduct(item);var barcode=new Label("SKU: "+item.sku()+" · "+(card==null?"":String.join(", ",card.barcodes())));barcode.getStyleClass().add("text-muted");barcode.setTooltip(new Tooltip(barcode.getText()));
            var marking=new Label(tr("ozon.fbs.kiz")+": "+(posting.requirements().mandatoryMarkProductIds().contains(item.productId())?tr("ozon.fbs.required"):tr("ozon.fbs.check_before_print")));marking.getStyleClass().add("text-muted");
            VBox itemBox = new VBox(3, titleRow, metadata, gtin, barcode, marking);
            itemBox.setMinHeight(ITEM_HEIGHT); itemBox.setPrefHeight(ITEM_HEIGHT); itemBox.setMaxHeight(ITEM_HEIGHT);
            itemBox.setAlignment(Pos.TOP_LEFT);
            return itemBox;
        }
    }

    private OzonProductDto findCatalogProduct(OzonPostingItemDto item) {
        if (item == null) return null;
        var product=productsBySku.get(item.sku());
        return product==null||!item.offerId().isBlank()&&!item.offerId().equals(product.offerId())?null:product;
    }

    private record BatchTransitionResult(int requested, List<String> completed, List<String> failed,List<String> pending) { }
    private record PrintOutput(String message, List<File> files, List<String> postingNumbers) {
        private PrintOutput {
            message = message == null ? "" : message;
            files = files == null ? List.of() : List.copyOf(files);
            postingNumbers = postingNumbers == null ? List.of() : List.copyOf(postingNumbers);
        }
    }
}
