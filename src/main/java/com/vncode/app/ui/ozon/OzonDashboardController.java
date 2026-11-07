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
import com.vncode.app.ui.workspace.WorkspaceDetailDrawer.Field;
import com.vncode.app.ui.workspace.WorkspaceDetailDrawer.Section;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
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
    private static final double ITEM_HEIGHT = 60;
    private static final double ITEM_GAP = 8;
    private final List<TableColumn<OzonPostingDto,?>> rowNumberColumns=new ArrayList<>();
    private final List<TableColumn<OzonPostingDto,?>> barcodeColumns=new ArrayList<>();
    private static final Set<String> ACTIVE_PURCHASE_STAGES = Set.of(
            "QUEUED", "VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER", "POLLING_ORDER", "DOWNLOADING_CODES",
            "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION", "POLLING_INTRODUCTION");

    private final OzonPostingRepository postings = new OzonPostingRepository();
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonSyncWorkflow syncWorkflow;
    public OzonDashboardController(){this(new OzonSyncWorkflow());}
    public OzonDashboardController(OzonSyncWorkflow syncWorkflow){this.syncWorkflow=java.util.Objects.requireNonNull(syncWorkflow);}
    @FunctionalInterface interface QueueReadiness { void ensure(Shop owner,java.util.function.BooleanSupplier stopped)throws Exception; }
    private QueueReadiness queueReadiness=this::ensureCompleteQueue;
    void setQueueReadiness(QueueReadiness readiness){queueReadiness=Objects.requireNonNull(readiness);}
    private final OzonShipService shipService = new OzonShipService();
    @FunctionalInterface interface PostingAcceptor {com.vncode.app.integration.ozon.OzonShipResult accept(Shop owner,String number,KizSourceSelection sources)throws Exception;}
    private PostingAcceptor postingAcceptor=(owner,number,sources)->shipService.shipPerUnit(owner,number,true,sources);
    void setPostingAcceptor(PostingAcceptor acceptor){postingAcceptor=Objects.requireNonNull(acceptor);}
    private OzonAcceptanceRetry.Pause acceptancePause=OzonAcceptanceRetry::pause;
    void setAcceptancePause(OzonAcceptanceRetry.Pause pause){acceptancePause=Objects.requireNonNull(pause);}
    @FunctionalInterface interface CrossMappingRefresher {
        Map<String,String> refresh(Shop owner,List<String> skus,java.util.function.BooleanSupplier cancelled,
                                   java.util.function.Consumer<com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult> progress)throws IOException;
    }
    private CrossMappingRefresher crossMappingRefresher=(owner,skus,cancelled,progress)->
            com.vncode.app.features.kiz.KizCrossMarketplaceProof.refreshAllDeferred(owner,skus,cancelled,progress).issues();
    void setCrossMappingRefresher(CrossMappingRefresher refresher){crossMappingRefresher=Objects.requireNonNull(refresher);}
    @FunctionalInterface interface AcceptanceStockPurchase {
        void replenish(Shop owner,List<String> numbers,KizSourceSelection sources,java.util.function.Consumer<String> progress,java.util.function.BooleanSupplier cancelled)throws Exception;
    }
    private AcceptanceStockPurchase acceptanceStockPurchase=(owner,numbers,sources,progress,cancelled)->
            new com.vncode.app.integration.ozon.OzonAcceptanceStockReplenishment().run(owner,numbers,sources,
                    proposal->com.vncode.app.ui.kiz.KizPrintPurchaseDialog.confirm(owner,proposal,cancelled),progress,cancelled);
    void setAcceptanceStockPurchase(AcceptanceStockPurchase purchase){acceptanceStockPurchase=Objects.requireNonNull(purchase);}
    @FunctionalInterface interface ExportedFileOpener{void open(List<File> files)throws IOException;}
    private ExportedFileOpener exportedFileOpener=OzonDashboardController::openExportedFiles;
    void setExportedFileOpener(ExportedFileOpener opener){exportedFileOpener=Objects.requireNonNull(opener);}
    @FXML private StackPane kn3StatusHost;
    private record Kn3Pages(Map<String,com.vncode.app.features.ozon.OzonFbsPage> pages,Map<String,List<String>> completedTransfers,long selectedFiltered){}
    private BatchTransitionResult lastTransitionResult;
    private long transitionRevision;

    private Map<String,List<com.vncode.app.features.kiz.status.Kn3Mark>> kn3Marks=Map.of();
    private final ShipmentKizPane shipmentKizPane=new ShipmentKizPane();
    @FXML private VBox shipmentKizHost;
    @FXML private Tab shipmentKizTab;
    @FXML private Button kizInventoryButton;
    @FXML private void onOpenKizInventory(){orderStatusTabs.getSelectionModel().select(shipmentKizTab);shipmentKizPane.reload();}
    private final OzonPrintReadinessService printReadinessService = new OzonPrintReadinessService();
    private FboProductImageService imageService = new FboProductImageService();
    private final OzonPostingSortingService sortingService = new OzonPostingSortingService();
    private final OrderSortPreferenceService sortPreferences = new OrderSortPreferenceService();
    private final KizMappingRepository gtinRepository = new KizMappingRepository();
    private final Set<String> selectedPostingNumbers = new LinkedHashSet<>();
    private long filteredNewCount;
    private long selectedFilteredCount;
    private final Set<String> purchasesStarting = new HashSet<>();
    private final CheckBox selectAllCheckBox = new CheckBox();

    @FXML private Label titleLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Button cancelSelectionButton;
    private Task<List<String>> activeSelectionTask;
    @FXML private Button refreshButton;
    @FXML private TabPane orderStatusTabs;
    @FXML private Tab newOrdersTab;
    @FXML private Tab packingOrdersTab;
    @FXML private Tab deliveringOrdersTab;
    @FXML private Tab printedOrdersTab;
    @FXML private TableView<OzonPostingDto> printedOrdersTable;
    @FXML private Label emptyPrintedOrdersLabel,printedHintLabel;
    @FXML private TableColumn<OzonPostingDto,String> printedOrderNumberTC,printedOrderItemsTC;
    @FXML private TableColumn<OzonPostingDto,OzonPostingDto> printedOrderImageTC;
    @FXML private TableColumn<OzonPostingDto,Void> printedLabelTC;
    @FXML private FlowPane selectionActionBar;
    @FXML private Button clearSearchButton;
    @FXML private TableColumn<OzonPostingDto, String> newOrderShipmentTC, packingOrderShipmentTC, printedOrderShipmentTC, deliveringOrderShipmentTC;
    @FXML private TableColumn<OzonPostingDto, OzonPostingDto> newOrderPriceTC, packingOrderPriceTC, printedOrderPriceTC, deliveringOrderPriceTC;
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
    // Keep rows readable while a long operation runs; only the mutating controls
    // are disabled. The snapshot used by the worker cannot be changed from cells.
    private final SimpleBooleanProperty operationBusy = new SimpleBooleanProperty(false);
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

    private com.vncode.app.ui.workspace.WorkspaceDetailDrawer orderDetailDrawer;

    @FXML
    private void initialize() {
        statusLabel.visibleProperty().bind(statusLabel.textProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        var workflowTip=new Tooltip();workflowTip.textProperty().bind(workflowLabel.textProperty());titleLabel.setTooltip(workflowTip);
        var printedTip=new Tooltip();printedTip.textProperty().bind(printedHintLabel.textProperty());printedOrdersTab.setTooltip(printedTip);
        for(var button:List.of(selectFilteredButton,clearBatchSelectionButton)){
            button.visibleProperty().bind(newOrdersTab.selectedProperty());button.managedProperty().bind(button.visibleProperty());
        }
        orderDetailDrawer=new com.vncode.app.ui.workspace.WorkspaceDetailDrawer(kn3StatusHost);
        setupTables();
        setupSorting();
        setupGtinInventory();
        shipmentKizHost.getChildren().setAll(shipmentKizPane);
        shipmentKizPane.setOnSelectionChanged(source->{gtinInventoryPanel.setVisible(source.purchased());gtinInventoryPanel.setManaged(source.purchased());});
        shipmentKizPane.setOnReadyChanged(ready->updateSelectionState());
        gtinInventoryList.setCellFactory(list->new javafx.scene.control.ListCell<>(){
            @Override protected void updateItem(ZnackGtinInventorySummary row,boolean empty){super.updateItem(row,empty);setText(null);setGraphic(empty||row==null?null:createGtinCard(row));}
        });
        orderStatusTabs.getSelectionModel().select(newOrdersTab);
        orderStatusTabs.getSelectionModel().selectedItemProperty().addListener((ignored, oldTab, selectedTab) -> {updatePaging();loadLocal();});
        postingDebounce.setOnFinished(e->{offsets.clear();loadLocal();});
        postingSearch.textProperty().addListener((o,a,b)->{postingDebounce.playFromStart();});
        gtinInventoryPanel.expandedProperty().addListener((o,a,b)->{if(b)refreshGtinInventory();});
        applyTranslations();
        clear();
    }

    public void setShop(Shop shop, boolean syncRemote) {
        Shop selected = shop != null && shop.getMarketplace() == Marketplace.OZON ? shop : null;
        boolean sameContext = sameShopContext(this.shop, selected);
        if (!sameContext) {
            orderDetailDrawer.clear();
            if(activeSelectionTask!=null){activeSelectionTask.cancel(true);activeSelectionTask=null;cancelSelectionButton.setVisible(false);cancelSelectionButton.setManaged(false);}
            clearPrintPill();
            requestToken++;
            setBusy(false);
            selectedPostingNumbers.clear();filteredNewCount=0;selectedFilteredCount=0;offsets.clear();totals.clear();pageGeneration++;
            productsBySku=Map.of();verifiedGtinsBySku=Map.of();catalogProducts=List.of();kn3Marks=Map.of();
            newOrdersTable.getItems().clear();packingOrdersTable.getItems().clear();printedOrdersTable.getItems().clear();deliveringOrdersTable.getItems().clear();
        }
        this.shop = selected;
        if(!sameContext){lastTransitionResult=null;transitionRevision++;batchResultsArea.clear();batchResultsPanel.setVisible(false);batchResultsPanel.setManaged(false);}
        if (!sameContext) {setGtinShop(selected);shipmentKizPane.setContext(selected,"OZON:FBS");}
        if (this.shop == null) {
            clear();
            return;
        }
        loadLocal();
        if(com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))setBusy(true);
        if(gtinInventoryPanel.isExpanded())refreshGtinInventory();
        if (syncRemote) sync(false);
    }

    public void sync() {
        sync(true);
    }
    private void sync(boolean refreshCatalog){
        if(shop!=null&&com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_QUEUE)){
            if(refreshCatalog)com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.CATALOG);loadLocal();return;
        }
        Shop selected = shop;
        if (selected == null || busy) return;
        long token = ++requestToken;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(selected.getId(), () -> {
                    syncWorkflow.checkConnection(selected);
                    if(refreshCatalog)syncWorkflow.syncOverview(selected);else syncWorkflow.syncOverviewIfNeeded(selected);
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
        rowNumberColumns.forEach(column->column.setText(i18n.tr("supply.col.no")));
        barcodeColumns.forEach(column->column.setText(i18n.tr("ozon.fbs.barcodes")));
        String clearSearch = i18n.tr("ozon.fbs.clear_search");
        clearSearchButton.setAccessibleText(clearSearch);
        clearSearchButton.setTooltip(new Tooltip(clearSearch));
        for (var column : List.of(newOrderShipmentTC, packingOrderShipmentTC, printedOrderShipmentTC, deliveringOrderShipmentTC))
            column.setText(i18n.tr("ozon.dashboard.col.shipment"));
        for (var column : List.of(newOrderPriceTC, packingOrderPriceTC, printedOrderPriceTC, deliveringOrderPriceTC))
            column.setText(i18n.tr("packing.col.price"));
        postingSearch.setPromptText(i18n.tr("ozon.fbs.search"));previousPageButton.setText(i18n.tr("ozon.fbs.previous"));nextPageButton.setText(i18n.tr("ozon.fbs.next"));workflowLabel.setText(i18n.tr("ozon.fbs.workflow"));gtinInventoryPanel.setText(i18n.tr("supply.gtin_inventory.title"));
        kizInventoryButton.setText(i18n.tr("kiz.shipment.title"));shipmentKizTab.setText(i18n.tr("kiz.shipment.title"));shipmentKizPane.applyTranslations();
        batchResultsPanel.setText(i18n.tr("ozon.fbs.results"));titleLabel.setText(i18n.tr("ozon.dashboard.title"));
        refreshButton.setText(i18n.tr("ozon.dashboard.refresh"));
        updateTabTitles();
        moveToPackingButton.setText(i18n.tr("ozon.dashboard.move_to_packing"));
        printAllButton.setText(i18n.tr("ozon.dashboard.print_all"));
        selectFilteredButton.setText(i18n.tr("ozon.batch.select_filtered"));clearBatchSelectionButton.setText(i18n.tr("ozon.batch.clear_selection"));
        selectAllCheckBox.setTooltip(new Tooltip(i18n.tr("ozon.batch.select_filtered")));
        selectAllCheckBox.setAccessibleText(i18n.tr("ozon.batch.select_filtered"));
        cancelSelectionButton.setText(i18n.tr("ozon.batch.cancel_selection"));
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
        newOrdersTable.setPlaceholder(emptyNewOrdersLabel);
        packingOrdersTable.setPlaceholder(emptyPackingOrdersLabel);
        deliveringOrdersTable.setPlaceholder(emptyDeliveringOrdersLabel);
        newOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        packingOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        deliveringOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));
        newOrderItemsTC.setText(i18n.tr("supply.col.details"));
        packingOrderItemsTC.setText(i18n.tr("supply.col.details"));
        deliveringOrderItemsTC.setText(i18n.tr("supply.col.details"));
        deliveringOrderStatusTC.setText(i18n.tr("ozon.dashboard.col.status"));
        newOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        packingOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        deliveringOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        packingLabelTC.setText("");printedLabelTC.setText("");
        printedHintLabel.setText(i18n.tr("ozon.dashboard.printed_hint"));emptyPrintedOrdersLabel.setText(i18n.tr("ozon.dashboard.empty.printed"));printedOrdersTable.setPlaceholder(emptyPrintedOrdersLabel);
        printedOrderNumberTC.setText(i18n.tr("ozon.dashboard.col.order"));printedOrderItemsTC.setText(i18n.tr("supply.col.details"));printedOrderImageTC.setText(i18n.tr("ozon.dashboard.col.image"));
        updateSelectionState();
        renderGtinSummaries(gtinSummaries);
        newOrdersTable.refresh();
        packingOrdersTable.refresh();printedOrdersTable.refresh();
        deliveringOrdersTable.refresh();
    }

    @FXML private void onRefresh() { sync(); }
    @FXML private void onClearSearch() { postingSearch.clear(); }

    @FXML private void onSelectAll() {
        if (selectAllCheckBox.isSelected()) onSelectFiltered();
        else onClearBatchSelection();
    }

    @FXML private void onClearBatchSelection(){selectedPostingNumbers.clear();selectedFilteredCount=0;newOrdersTable.refresh();updateSelectionState();}
    @FXML private void onSelectFiltered(){
        Shop selected=shop;if(selected==null||busy)return;String text=postingSearch.getText();String sort=sortFields();long token=++requestToken;long generation=shopGeneration;
        Task<List<String>> task=new Task<>(){@Override protected List<String> call()throws Exception{
            queueReadiness.ensure(selected,()->isCancelled()||Thread.currentThread().isInterrupted()||disposed||generation!=shopGeneration||!sameShopContext(shop,selected));
            com.vncode.app.integration.listing.ListingIdentity.require(selected);
            try{return postings.freezeFilteredNumbers(pageQuery(selected.getId(),"awaiting_packaging",text,sort,0,100),100_000);}
            catch(IllegalArgumentException tooMany){if("Too many selected Ozon postings".equals(tooMany.getMessage()))throw new IOException(tr("ozon.batch.selection_limit"),tooMany);throw tooMany;}
        }};
        activeSelectionTask=task;cancelSelectionButton.setVisible(true);cancelSelectionButton.setManaged(true);
        setBusy(true,true);statusLabel.setText(tr("ozon.batch.sync_before_select"));task.setOnSucceeded(e->{if(token!=requestToken||generation!=shopGeneration||!sameShopContext(shop,selected))return;finishSelectionTask(task);setBusy(false);if(!text.equals(postingSearch.getText())||!sort.equals(sortFields())){updateSelectionState();loadLocal();return;}
            selectedPostingNumbers.clear();selectedPostingNumbers.addAll(task.getValue());filteredNewCount=task.getValue().size();selectedFilteredCount=task.getValue().size();newOrdersTable.refresh();updateSelectionState();loadLocal();});
        java.util.function.Consumer<Throwable> failed=error->{finishSelectionTask(task);if(token!=requestToken||generation!=shopGeneration||!sameShopContext(shop,selected))return;setBusy(false);statusLabel.setText(tr("ozon.batch.queue_incomplete"));AlertService.showError(com.vncode.app.shared.FriendlyErrorService.format(error));loadLocal();};task.setOnFailed(e->failed.accept(task.getException()));
        task.setOnCancelled(e->{if(token!=requestToken||generation!=shopGeneration)return;finishSelectionTask(task);setBusy(false);statusLabel.setText(tr("ozon.batch.selection_cancelled"));loadLocal();});
        AppTaskExecutor.execute(task,failed);
    }

    @FXML private void onCancelSelection(){if(activeSelectionTask!=null)activeSelectionTask.cancel(true);}
    private void finishSelectionTask(Task<List<String>> task){if(activeSelectionTask==task){activeSelectionTask=null;cancelSelectionButton.setVisible(false);cancelSelectionButton.setManaged(false);}}

    private void ensureCompleteQueue(Shop selected,java.util.function.BooleanSupplier stopped)throws Exception {
        if(!com.vncode.app.features.sync.SyncRuntime.requestIfRunning(selected,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_QUEUE)) {
            ShopOperationCoordinator.withActiveShop(selected.getId(),()->{syncWorkflow.syncCurrentPostings(selected);return null;});
            return;
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
        long nextReport=0;Task<List<String>> current=activeSelectionTask;
        while(!com.vncode.app.features.sync.SyncRuntime.isQueueMembershipCurrent(selected,java.time.Duration.ofSeconds(30))) {
            if(stopped.getAsBoolean())throw new java.util.concurrent.CancellationException();
            com.vncode.app.integration.listing.ListingIdentity.require(selected);
            if(System.nanoTime()>=deadline)throw new IOException(tr("ozon.batch.queue_incomplete"));
            if(System.nanoTime()>=nextReport) {
                var progress=com.vncode.app.features.sync.SyncRuntime.queueProgress(selected);
                String message=java.text.MessageFormat.format(tr("ozon.batch.sync_progress"),progress.pages());
                if("FBS_STATUS_UNCLASSIFIED".equals(progress.error()))message+=" · "+tr("ozon.batch.status_unclassified");
                else if(!progress.error().isBlank())message+=" · "+progress.error();
                if(progress.next()!=null&&!progress.error().isBlank())message+=" · "+tr("ozon.batch.sync_retry_at")+" "+
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(progress.next());
                String report=message;
                Platform.runLater(()->{if(!disposed&&activeSelectionTask==current)statusLabel.setText(report);});
                if("OZON_AUTH_REQUIRED".equals(progress.error()))throw new IOException(tr("ozon.batch.sync_auth_required"));
                if("LICENSE_REQUIRED".equals(progress.error()))throw new IOException(tr("ozon.batch.sync_license_required"));
                nextReport=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            }
            try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
        }
    }

    @FXML private void onMoveToPacking() {
        Shop selected = shop;
        List<String> postingNumbers = new ArrayList<>(selectedPostingNumbers);
        if (selected == null || postingNumbers.isEmpty() || busy) return;
        // A source is required only for postings which Ozon says need a mark.
        // Preserve an explicit empty choice so no background task falls back to
        // an implicit warehouse or purchases a code without the seller's choice.
        KizSourceSelection currentSources;
        try { currentSources=shipmentKizPane.snapshot(); }
        catch (IllegalStateException loading) { statusLabel.setText(tr("kiz.shipment.select_source"));return; }
        final KizSourceSelection stockSources=currentSources;
        var dialog=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        if(newOrdersTable.getScene()!=null&&newOrdersTable.getScene().getWindow()!=null)dialog.initOwner(newOrdersTable.getScene().getWindow());
        AlertService.applyTheme(dialog);dialog.setTitle(tr("ozon.fbs.confirm_title"));dialog.setHeaderText(selected.getName()+" · "+postingNumbers.size());dialog.setContentText(tr("ozon.fbs.confirm_body")+"\n"+tr("ozon.fbs.auto_split_notice")+"\n"+String.join(", ",postingNumbers.stream().limit(20).toList())+(postingNumbers.size()>20?" …":""));
        var accepted=new javafx.scene.control.ButtonType(tr("ozon.fbs.confirm_accept"),javafx.scene.control.ButtonBar.ButtonData.OK_DONE);var cancelled=new javafx.scene.control.ButtonType(tr("common.cancel"),javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);dialog.getButtonTypes().setAll(accepted,cancelled);if(dialog.showAndWait().orElse(cancelled)!=accepted)return;
        if(disposed||!isCurrent(selected)||busy)return;
        var lease=com.vncode.app.shared.ShopBatchRegistry.claim(selected,"ozon-pack").orElse(null);if(lease==null){statusLabel.setText(tr("runtime.queue_full"));return;}
        long token = ++requestToken;
        Task<BatchTransitionResult> task = new Task<>() {
            @Override protected BatchTransitionResult call() throws Exception {
                List<String> completed = new ArrayList<>();
                List<String> failed = new ArrayList<>();List<String> pending=new ArrayList<>();List<String> notProcessed=new ArrayList<>();
                var children=new java.util.LinkedHashMap<String,List<String>>();var reasons=new java.util.LinkedHashMap<String,String>();
                var crossIssues=Map.<String,String>of();
                if(stockSources.purchased()){
                    updateMessage(tr("ozon.fbs.checking_gtin_mapping"));
                    crossIssues=refreshMissingCrossMappings(selected,postingNumbers,stockSources,()->isCancelled()||lease.stopping(),this::updateMessage);
                }
                var remaining=new java.util.ArrayDeque<>(postingNumbers);
                int processed=0;int lastReplenishedWindow=-1;Exception purchaseFailure=null;while (!remaining.isEmpty()) {
                    if(lease.stopping()||Thread.currentThread().isInterrupted())break;
                    String postingNumber=remaining.removeFirst();
                    try {
                        lease.requireOwner(selected);
                        com.vncode.app.integration.listing.ListingIdentity.require(selected);
                    } catch (RuntimeException changed) {
                        // Stop before another remote mutation. Keep completed results and
                        // leave this and every remaining posting selected for review.
                        remaining.addFirst(postingNumber);break;
                    }
                    int position=processed+1;
                    updateMessage(MessageFormat.format(tr("ozon.fbs.processing_order"),position,postingNumbers.size()));
                    try (var progress=com.vncode.app.integration.ozon.OzonKizProgress.listen(stage->updateMessage(
                            MessageFormat.format(tr("ozon.fbs.kiz_auto."+stage.name().toLowerCase(java.util.Locale.ROOT)),position,postingNumbers.size())))) {
                        com.vncode.app.integration.ozon.OzonShipResult result;
                        try { result=OzonAcceptanceRetry.run(
                                ()->postingAcceptor.accept(selected,postingNumber,stockSources),
                                ()->{lease.requireOwner(selected);com.vncode.app.integration.listing.ListingIdentity.require(selected);},
                                ()->isCancelled()||lease.stopping(),
                                delay->updateMessage(java.text.MessageFormat.format(tr("ozon.fbs.rate_wait_seconds"),delay.toSeconds())),acceptancePause); }
                        catch (IOException shortage) {
                            if(!"OZON_ACCEPT_KIZ_STOCK_REQUIRED".equals(shortage.getMessage())
                                    ||stockSources==null||!stockSources.purchased())throw shortage;
                            int window=processed/1000;
                            if(lastReplenishedWindow==window){
                                if(purchaseFailure!=null)throw new IOException("OZON_ACCEPT_KIZ_BATCH_PREFLIGHT",purchaseFailure);
                                throw shortage;
                            }
                            lastReplenishedWindow=window;
                            purchaseFailure=null;
                            // Replenishment is a distinct, seller-approved task. Never wrap
                            // a ship mutation in the retrying purchase-session callable.
                            // The preflight resolves native split children itself and checks
                            // their source identity before any paid request is proposed.
                            var purchaseWindow=new ArrayList<String>();purchaseWindow.add(postingNumber);
                            remaining.stream().limit(999).forEach(purchaseWindow::add);
                            try{
                                acceptanceStockPurchase.replenish(selected,List.copyOf(purchaseWindow),
                                        stockSources,this::updateMessage,()->isCancelled()||lease.stopping());
                            } catch(Exception failure){purchaseFailure=failure;throw failure;}
                            lease.requireOwner(selected);
                            com.vncode.app.integration.listing.ListingIdentity.require(selected);
                            result=OzonAcceptanceRetry.run(
                                    ()->postingAcceptor.accept(selected,postingNumber,stockSources),
                                    ()->{lease.requireOwner(selected);com.vncode.app.integration.listing.ListingIdentity.require(selected);},
                                    ()->isCancelled()||lease.stopping(),
                                    delay->updateMessage(java.text.MessageFormat.format(tr("ozon.fbs.rate_wait_seconds"),delay.toSeconds())),acceptancePause);
                        }
                        children.put(postingNumber,result.resultingPostings());
                        completed.add(postingNumber);
                    } catch (Exception exception) {
                        if(exception instanceof com.vncode.app.integration.ozon.OzonKizReplacementDeferredException){
                            // Only definite, identity-matched rejections yield here. Pending or
                            // ambiguous HTTP mutations keep the normal read-only recovery path.
                            remaining.addLast(postingNumber);
                            updateMessage(MessageFormat.format(tr("ozon.fbs.kiz_auto.deferred"),processed,postingNumbers.size()));
                            continue;
                        }
                        String reason=shippingFailure(exception);
                        if(exception instanceof com.vncode.app.features.kiz.KizAssignmentException mapping&&"KIZ_GTIN_MAPPING_MISSING".equals(mapping.code())){
                            String product=mapping.product(),issue=crossIssues.get(product);int separator=product.lastIndexOf('-');
                            if(issue==null&&separator>=0&&separator+1<product.length())issue=crossIssues.get(product.substring(separator+1));
                            if(issue!=null)reason+=" · "+crossIssueMessage(issue);
                        }
                        reasons.put(postingNumber,reason);
                        try{var known=shipService.knownUnitPostings(selected,postingNumber);if(!known.isEmpty())children.put(postingNumber,known);}catch(RuntimeException unavailable){/* Keep the primary error; the durable receipt remains intact. */}
                        if(exception instanceof java.util.concurrent.CancellationException){
                            reasons.put(postingNumber,tr("kiz.print.cancelled"));
                            if(children.containsKey(postingNumber))pending.add(postingNumber);else failed.add(postingNumber);
                            updateMessage(MessageFormat.format(tr("ozon.batch.progress"),++processed,postingNumbers.size()));
                            break;
                        }
                        if(reconciliationRequired(exception))pending.add(postingNumber);else failed.add(postingNumber);
                    }
                    updateMessage(MessageFormat.format(tr("ozon.batch.progress"),++processed,postingNumbers.size()));
                }
                notProcessed.addAll(remaining);
                if(!pending.isEmpty()&&!isCancelled()&&!lease.stopping()){
                    try{
                        lease.requireOwner(selected);
                        var settled=shipService.settleSubmittedReadOnly(selected,pending,
                                ()->isCancelled()||lease.stopping(),count->updateMessage(tr("ozon.fbs.pending")+": "+count));
                        for(String number:List.copyOf(pending))if(settled.containsKey(number)){
                            pending.remove(number);completed.add(number);reasons.remove(number);
                            children.put(number,settled.get(number).resultingPostings());
                        }
                    }catch(InterruptedException interrupted){
                        Thread.currentThread().interrupt();
                    }catch(RuntimeException readbackUnavailable){
                        // Preserve the partial batch report and its durable receipts,
                        // including when the owner changes during the read-only pass.
                        for(String number:pending)reasons.put(number,tr("ozon.fbs.pending")+" · "+shippingFailure(readbackUnavailable));
                    }
                }
                return new BatchTransitionResult(postingNumbers.size(), List.copyOf(completed), List.copyOf(failed),List.copyOf(pending),List.copyOf(notProcessed),Map.copyOf(children),Map.copyOf(reasons));
            }
        };
        setBusy(true,true);
        task.messageProperty().addListener((ignored,oldValue,message)->{
            if(!disposed&&busy&&token==requestToken&&isCurrent(selected)&&message!=null&&!message.isBlank())statusLabel.setText(message);
        });
        task.setOnSucceeded(event -> {
            lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            BatchTransitionResult result = task.getValue();
            selectedPostingNumbers.removeAll(result.completed());
            lastTransitionResult=result;transitionRevision++;renderTransitionResult(result);
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
        KizSourceSelection sources=printKizSources();if(sources==null)return;
        String text=postingSearch.getText(),sort=sortFields();long epoch=pageGeneration;
        long token = ++requestToken;
        Task<List<String>> task=new Task<>(){@Override protected List<String> call(){try{return postings.freezeFilteredNumbers(pageQuery(selected.getId(),"awaiting_deliver",text,sort,0,100),10000);}catch(IllegalArgumentException tooMany){if("Too many selected Ozon postings".equals(tooMany.getMessage()))throw new IllegalStateException("OZON_PRINT_LIMIT_10000",tooMany);throw tooMany;}}};
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
    private KizSourceSelection printKizSources(){
        try{return shipmentKizPane.snapshot();}
        catch(IllegalStateException loading){statusLabel.setText(com.vncode.app.features.kiz.KizErrorMessages.message("KIZ_SOURCE_LOADING"));return null;}
    }

    /** Read only reviewed cross-market links for the selected orders; never buy or write to a marketplace here. */
    Map<String,String> refreshMissingCrossMappings(Shop owner,List<String> numbers,KizSourceSelection sources,
                                                   java.util.function.BooleanSupplier cancelled)throws IOException {
        return refreshMissingCrossMappings(owner,numbers,sources,cancelled,message->{});
    }
    Map<String,String> refreshMissingCrossMappings(Shop owner,List<String> numbers,KizSourceSelection sources,
                                                   java.util.function.BooleanSupplier cancelled,java.util.function.Consumer<String> progress)throws IOException {
        if(sources==null||!sources.purchased()||numbers.isEmpty())return Map.of();
        var skus=new LinkedHashSet<String>();
        for(int start=0;start<numbers.size();start+=10_000){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("KIZ_CROSS_REFRESH_CANCELLED");
            com.vncode.app.integration.listing.ListingIdentity.require(owner);
            var subset=numbers.subList(start,Math.min(start+10_000,numbers.size()));
            postings.findMany(owner.getId(),subset).values().stream().flatMap(posting->posting.items().stream())
                    .map(OzonPostingItemDto::sku).filter(sku->sku!=null&&!sku.isBlank()).forEach(skus::add);
        }
        var ordered=List.copyOf(skus);var issues=new java.util.LinkedHashMap<String,String>();int[] previous={0,0};
        for(int start=0;start<ordered.size();start+=10_000){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("KIZ_CROSS_REFRESH_CANCELLED");
            var subset=ordered.subList(start,Math.min(start+10_000,ordered.size()));var expected=Set.copyOf(subset);int[] last={0,0};
            var returned=crossMappingRefresher.refresh(owner,subset,cancelled,value->{last[0]=value.attempted();last[1]=value.verified();
                progress.accept(MessageFormat.format(tr("ozon.fbs.checking_gtin_progress"),previous[0]+last[0],previous[1]+last[1]));});
            previous[0]+=last[0];previous[1]+=last[1];
            if(returned!=null)for(var entry:returned.entrySet())if(expected.contains(entry.getKey())){
                String code=entry.getValue();issues.put(entry.getKey(),code!=null&&code.matches("[A-Z][A-Z0-9_]{0,80}")?code:"KIZ_CROSS_REFRESH_FAILED");
            }
            if(returned!=null&&returned.values().stream().anyMatch(code->"KIZ_CROSS_REFRESH_DEFERRED".equals(code)||"API_COOLDOWN".equals(code)||"NETWORK_TIMEOUT".equals(code)||code!=null&&code.endsWith("RATE_LIMIT"))){
                ordered.subList(Math.min(start+10_000,ordered.size()),ordered.size()).forEach(sku->issues.putIfAbsent(sku,"KIZ_CROSS_REFRESH_DEFERRED"));break;
            }
        }
        return Map.copyOf(issues);
    }

    private void checkBatchReadinessAndExport(Shop selected,List<String> postingNumbers,KizSourceSelection sources){
        var preflight=new java.util.concurrent.atomic.AtomicLong(-1);var crossIssues=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>(Map.of());
        long token=++requestToken;Task<OzonBatchPrintReadiness> task=new Task<>(){@Override protected OzonBatchPrintReadiness call()throws Exception{long started=System.nanoTime();try{if(sources!=null&&sources.purchased())updateMessage(tr("ozon.fbs.checking_gtin_mapping"));crossIssues.set(refreshMissingCrossMappings(selected,postingNumbers,sources,this::isCancelled,this::updateMessage));return printReadinessService.inspectAll(selected,postingNumbers,sources);}finally{preflight.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}}};
        task.messageProperty().addListener((ignored,oldValue,message)->{if(!disposed&&token==requestToken&&isCurrent(selected)&&message!=null&&!message.isBlank())statusLabel.setText(message);});
        setBusy(true);task.setOnSucceeded(e->{if(token!=requestToken||!isCurrent(selected))return;setBusy(false);var readiness=task.getValue();
            var printable=printableForBatch(readiness,sources);var blocked=preflightFailures(readiness,printable,crossIssues.get());
            batchResultsArea.setText(readinessTimingText(preflight.get())+readiness.postings().stream().map(row->row.postingNumber()+" · "+blocked.getOrDefault(row.postingNumber(),readinessSummary(row))).collect(java.util.stream.Collectors.joining("\n")));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!blocked.isEmpty());
            // The seller already confirmed this frozen batch. A later purchase
            // still requires the separate explicit KIZ purchase confirmation.
            chooseBatchTargetAndExport(selected,printable,sources,preflight.get(),postingNumbers,blocked);});
        java.util.function.Consumer<Throwable> failed=error->{showReadinessTimingOnFailure(token,selected,preflight.get());finishFailure(token,selected,error);};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }

    private void downloadLabel(OzonPostingDto posting) {
        Shop selected = shop;
        if (posting == null || selected == null || busy || !canPrintLabel(posting)) return;
        KizSourceSelection sources=printKizSources();if(sources==null)return;
        var preflight=new java.util.concurrent.atomic.AtomicLong(-1);var crossIssues=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>(Map.of());
        long token = ++requestToken;
        Task<OzonPrintReadiness> task = new Task<>() {
            @Override protected OzonPrintReadiness call() throws Exception {
                long started=System.nanoTime();
                try { syncWorkflow.refreshPosting(selected, posting.postingNumber());
                    if(sources!=null&&sources.purchased())updateMessage(tr("ozon.fbs.checking_gtin_mapping"));
                    crossIssues.set(refreshMissingCrossMappings(selected,List.of(posting.postingNumber()),sources,this::isCancelled,this::updateMessage));
                    return printReadinessService.inspect(selected, posting.postingNumber(),sources);
                } finally { preflight.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)); }
            }
        };
        setBusy(true);
        task.messageProperty().addListener((ignored,oldValue,message)->{if(!disposed&&token==requestToken&&isCurrent(selected)&&message!=null&&!message.isBlank())statusLabel.setText(message);});
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            OzonPrintReadiness readiness = task.getValue();
            batchResultsArea.setText(readinessTimingText(preflight.get())+readiness.postingNumber()+" · "+withCrossIssues(readiness,readinessSummary(readiness),crossIssues.get()));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!readiness.ready());
            loadLocal();
            var carrierOnly=!readiness.ready()&&!com.vncode.app.integration.ozon.OzonStockReplenishment.canResolveByPurchase(readiness,sources)
                    ?Map.of(posting.postingNumber(),withCrossIssues(readiness,readinessBlockedText(readiness),crossIssues.get())):Map.<String,String>of();
            chooseSingleTargetAndExport(selected,posting,sources,preflight.get(),carrierOnly);
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {showReadinessTimingOnFailure(token,selected,preflight.get());finishFailure(token, selected, rejection);};
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void chooseSingleTargetAndExport(Shop selected, OzonPostingDto posting,KizSourceSelection sources,long preflightMillis,Map<String,String> carrierOnly) {
        File target = choosePdf("OZON-" + safeFilename(posting.postingNumber()) + ".pdf");
        if (target == null) return;
        File picking = pickingTarget(target);
        var requested=List.of(posting.postingNumber());
        runPrintTask(selected,target,picking,carrierOnly.isEmpty()?requested:List.of(),sources,requested,carrierOnly,preflightMillis,progress->{
            var result=new OzonPrintBundleService(sources).exportRequested(selected,requested,target,picking,progress,carrierOnly);
            String message=printSummary(result);
            return new PrintOutput(message,result.successful().isEmpty()?List.of():List.of(target,picking),result.successful(),result.failures(),result.waiting(),result.carrierOnly(),result.remoteKizWarnings());
        });
    }

    private void chooseBatchTargetAndExport(Shop selected,List<String> postingNumbers,KizSourceSelection sources,long preflightMillis,
                                           List<String> requestedNumbers,Map<String,String> blocked){
        File target=choosePdf("OZON-"+selected.getId()+"-FBS-"+LocalDate.now()+".pdf");if(target==null)return;
        File picking=pickingTarget(target);
        runPrintTask(selected,target,picking,postingNumbers,sources,requestedNumbers,blocked,preflightMillis,progress->{
            var result=new OzonPrintBundleService(sources).exportRequested(selected,requestedNumbers,target,picking,progress,blocked);
            String message=printSummary(result);
            return new PrintOutput(message,result.successful().isEmpty()?List.of():List.of(target,picking),result.successful(),result.failures(),result.waiting(),result.carrierOnly(),result.remoteKizWarnings());
        });
    }

    private String printSummary(OzonPrintBundleService.AvailableBatchExportResult result){
        return MessageFormat.format(tr("ozon.dashboard.print_bundle_done"),result.successful().size(),result.kizPages(),result.carrierOnly().size(),result.failures().size());
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

    void runPrintTask(Shop selected,File labelTarget,File pickingTarget,List<String> numbers,KizSourceSelection sources,PrintWork work) {
        runPrintTask(selected,labelTarget,pickingTarget,numbers,sources,numbers,Map.of(),-1,work);
    }
    private void runPrintTask(Shop selected,File labelTarget,File pickingTarget,List<String> numbers,KizSourceSelection sources,long preflightMillis,PrintWork work) {
        runPrintTask(selected,labelTarget,pickingTarget,numbers,sources,numbers,Map.of(),preflightMillis,work);
    }
    void runPrintTask(Shop selected,File labelTarget,File pickingTarget,List<String> numbers,KizSourceSelection sources,
                      List<String> requestedNumbers,Map<String,String> blocked,PrintWork work) {
        runPrintTask(selected,labelTarget,pickingTarget,numbers,sources,requestedNumbers,blocked,-1,work);
    }
    private void runPrintTask(Shop selected,File labelTarget,File pickingTarget,List<String> numbers,KizSourceSelection sources,
                              List<String> requestedNumbers,Map<String,String> blocked,long preflightMillis,PrintWork work) {
        numbers=List.copyOf(numbers);requestedNumbers=List.copyOf(requestedNumbers);blocked=Map.copyOf(blocked);
        var requestedSet=new java.util.HashSet<>(requestedNumbers);var printedSet=new java.util.HashSet<>(numbers);
        if(requestedSet.size()!=requestedNumbers.size()||printedSet.size()!=numbers.size()||!requestedSet.containsAll(printedSet)
                ||!requestedSet.containsAll(blocked.keySet())||!java.util.Collections.disjoint(printedSet,blocked.keySet())
                ||printedSet.size()+blocked.size()!=requestedSet.size())throw new IllegalArgumentException("OZON_PRINT_REPORT_SELECTION_CHANGED");
        final List<String> printNumbers=numbers,reportNumbers=requestedNumbers;final Map<String,String> preflightBlocked=blocked;
        var timingResult=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.integration.ozon.OzonPrintTiming.Summary>();
        Task<PrintOutput> task=new Task<>(){
            protected PrintOutput call()throws Exception{
                var timer=com.vncode.app.integration.ozon.OzonPrintTiming.begin();
                try {
                var report=new com.vncode.app.integration.ozon.OzonFbsPrintReport(selected,reportNumbers);
                try{
                    final long[] published={0};
                    var session=new com.vncode.app.features.kiz.KizPrintPurchaseSession(selected,
                        proposal->com.vncode.app.ui.kiz.KizPrintPurchaseDialog.confirm(selected,proposal,this::isCancelled),this::updateMessage,this::isCancelled);
                    var result=session.run(()->{
                        long stockStarted=com.vncode.app.integration.ozon.OzonPrintTiming.tick();
                        try { if(!printNumbers.isEmpty())new com.vncode.app.integration.ozon.OzonStockReplenishment().forPrint(selected,printNumbers,sources); }
                        finally { com.vncode.app.integration.ozon.OzonPrintTiming.add(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.STOCK,stockStarted); }
                        updateMessage(tr("ozon.fbs.preparing_label_batch"));
                        return work.run(count->{updateProgress(count,reportNumbers.size());long now=System.nanoTime();if(count==reportNumbers.size()||now-published[0]>250_000_000L){published[0]=now;updateMessage(MessageFormat.format(tr("ozon.fbs.pdf_progress"),count,reportNumbers.size()));}});});
                    var failures=new java.util.LinkedHashMap<>(preflightBlocked);result.postingNumbers().forEach(failures::remove);failures.putAll(result.failures());
                    report.finishAvailable(selected,Set.copyOf(result.postingNumbers()),failures,result.waiting(),result.carrierOnly());
                    String message=failures.isEmpty()?result.message():MessageFormat.format(tr("ozon.batch.print_partial"),result.postingNumbers().size(),failures.size());
                    return new PrintOutput(message,result.files(),result.postingNumbers(),failures,result.waiting(),result.carrierOnly(),result.remoteKizWarnings());
                }catch(Exception failed){try{if(com.vncode.app.features.kiz.KizPurchasePendingException.isPending(failed))report.waitingForStock(selected,preflightBlocked);else report.finishAvailable(selected,Set.of(),preflightBlocked);}catch(RuntimeException storage){failed.addSuppressed(storage);}throw failed;}
                } finally { timingResult.set(timer.snapshot()); timer.close(); }
            }
        };
        if(disposed||!isCurrent(selected)||busy)return;
        var lease=com.vncode.app.shared.ShopBatchRegistry.claim(selected,"ozon-print",List.of(labelTarget.toPath(),pickingTarget.toPath())).orElse(null);if(lease==null){statusLabel.setText(tr("ozon.batch.target_busy"));return;}
        long token = ++requestToken;
        setBusy(true,true);
        task.messageProperty().addListener((ignored,oldValue,newValue)->{
            if(!disposed&&busy&&token==requestToken&&isCurrent(selected)&&newValue!=null&&!newValue.isBlank()){statusLabel.setText(newValue);showPrintPill(newValue);}
        });
        task.setOnSucceeded(event -> {
            lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);clearPrintPill();
            PrintOutput output = task.getValue();
            statusLabel.setText(output.message());
            String done=output.postingNumbers().stream().map(n->n+" · "+(output.carrierOnly().containsKey(n)
                    ?MessageFormat.format(tr("ozon.fbs.pdf_created_carrier_only"),output.carrierOnly().get(n)):tr(output.remoteKizWarnings().containsKey(n)?"ozon.fbs.pdf_created_kiz_rejected":"ozon.fbs.pdf_created"))).collect(java.util.stream.Collectors.joining("\n"));
            String failures=output.failures().entrySet().stream().map(e->e.getKey()+" · "+tr(output.waiting().contains(e.getKey())?"ozon.batch.wait_rate_limit":"ozon.fbs.batch_incomplete")+" · "+e.getValue()).collect(java.util.stream.Collectors.joining("\n"));
            batchResultsArea.setText(readinessTimingText(preflightMillis)+printTimingText(timingResult.get())+(output.postingNumbers().isEmpty()?"":tr("ozon.fbs.pdf_batch_layout")+"\n\n")+done+(done.isBlank()||failures.isBlank()?"":"\n")+failures);
            batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(true);
            offsets.put("printed_awaiting_deliver",0);
            if(!output.postingNumbers().isEmpty()&&orderStatusTabs.getSelectionModel().getSelectedItem()!=printedOrdersTab)orderStatusTabs.getSelectionModel().select(printedOrdersTab);else loadLocal();
            refreshGtinInventory();
            Task<Void> open=new Task<>(){@Override protected Void call()throws IOException{exportedFileOpener.open(output.files());return null;}};
            open.setOnFailed(e->{if(!disposed&&isCurrent(selected))AlertService.showWarning(tr("ozon.dashboard.open_files.title"),tr("ozon.dashboard.open_files.failed"),tr("ozon.dashboard.open_files.unsupported"));});
            if(!output.files().isEmpty())AppTaskExecutor.execute(open,error->{});
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {lease.close();if(!com.vncode.app.shared.ShopBatchRegistry.hasShop(selected.getId()))onBusy.accept(selected.getId(),false);if(!disposed&&isCurrent(selected)){clearPrintPill();batchResultsArea.setText(readinessTimingText(preflightMillis)+printTimingText(timingResult.get())+reportNumbers.stream().map(n->n+" · "+preflightBlocked.getOrDefault(n,tr(com.vncode.app.features.kiz.KizPurchasePendingException.isPending(rejection)?"kiz.pending.title":"ozon.fbs.batch_incomplete"))).collect(java.util.stream.Collectors.joining("\n")));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(true);}if(rejection instanceof java.util.concurrent.CancellationException){if(!disposed&&isCurrent(selected)){setBusy(false);statusLabel.setText(tr("kiz.print.cancelled"));}return;}finishFailure(token, selected, rejection);};
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        task.setOnCancelled(event->taskFailure.accept(new java.util.concurrent.CancellationException()));
        AppTaskExecutor.executePrint(task, taskFailure);
    }

    private String printTimingText(com.vncode.app.integration.ozon.OzonPrintTiming.Summary result){
        if(result==null)return "";
        return MessageFormat.format(tr("ozon.fbs.print_timing"),seconds(result.totalMillis()),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.STOCK)),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.PREPARATION)),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.LABEL_NETWORK)),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.PAGE_IDENTITY)),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.COMPOSITION)),
                seconds(result.millis(com.vncode.app.integration.ozon.OzonPrintTiming.Stage.PICKING)),
                result.labelGroups(),result.isolatedLabels())+"\n\n";
    }
    private String readinessTimingText(long millis){return millis<0?"":MessageFormat.format(tr("ozon.fbs.readiness_timing"),seconds(millis))+"\n";}
    private void showReadinessTimingOnFailure(long token,Shop selected,long millis){
        if(millis<0||disposed||token!=requestToken||!isCurrent(selected))return;
        batchResultsArea.setText(readinessTimingText(millis));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(true);
    }
    private static String seconds(long millis){return String.format(Locale.ROOT,"%.2f",millis/1000d);}

    private void showPrintPill(String message){
        com.vncode.app.shared.TaskStatusDock.install(kn3StatusHost);
        var existing=kn3StatusHost.lookup("#kizPrintProgressText");
        if(existing instanceof Label label){label.setText(message);return;}
        var text=new Label(message);text.setId("kizPrintProgressText");text.setWrapText(true);text.setMaxWidth(860);
        var spinner=new ProgressIndicator();spinner.setMinSize(18,18);spinner.setPrefSize(18,18);spinner.setMaxSize(18,18);
        var pill=new HBox(10,spinner,text);pill.setId("kizPrintProgressPill");pill.setAlignment(Pos.CENTER);pill.getStyleClass().add("task-status-pill");pill.setMaxSize(940,Region.USE_PREF_SIZE);
        StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new javafx.geometry.Insets(12));kn3StatusHost.getChildren().add(pill);
    }
    private void clearPrintPill(){if(kn3StatusHost!=null)kn3StatusHost.getChildren().removeIf(node->"kizPrintProgressPill".equals(node.getId()));}

    @FunctionalInterface interface PrintWork{PrintOutput run(java.util.function.IntConsumer progress)throws Exception;}

    private void finishFailure(long token, Shop selected, Throwable failure) {
        if (token != requestToken || !isCurrent(selected)) return;
        setBusy(false);
        statusLabel.setText(I18nService.getInstance().tr(com.vncode.app.features.kiz.KizPurchasePendingException.isPending(failure)?"kiz.pending.title":"ozon.dashboard.error"));
        AlertService.showPrintFailure(failure);
        loadLocal();
        refreshGtinInventory();
    }

    public void refreshLocalData(){if(!disposed&&!busy)loadLocal();}
    private void loadLocal() {
        Shop selected=shop;if(disposed||selected==null){newOrdersTable.getItems().clear();packingOrdersTable.getItems().clear();printedOrdersTable.getItems().clear();deliveringOrdersTable.getItems().clear();updateTabTitles();updatePaging();return;}
        long epoch=++pageGeneration,started=System.nanoTime();String text=postingSearch.getText();String currentStatus=activeStatus();String sort=sortFields();var frozenOffsets=Map.copyOf(offsets);
        var owner=selected;var selectedAtRead=Set.copyOf(selectedPostingNumbers);var transferReport=lastTransitionResult;String scope=com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner)+"|"+currentStatus+"|"+text+"|"+sort+"|"+frozenOffsets+"|batch:"+transitionRevision;
        reads.submitLocal(new com.vncode.app.shared.ShopReadCoordinator.Key(selected.getId(),"ozon-fbs-page",scope),()->{
            var pages=new java.util.LinkedHashMap<String,com.vncode.app.features.ozon.OzonFbsPage>();
            for(String status:List.of("awaiting_packaging","awaiting_deliver","printed_awaiting_deliver","delivering"))pages.put(status,fbsReader.apply(pageQuery(owner.getId(),status,text,sort,frozenOffsets.getOrDefault(status,0),100)));
            var completed=transferReport==null||transferReport.pending().isEmpty()?Map.<String,List<String>>of():shipService.completedUnitPostings(owner,transferReport.pending());
            long selectedFiltered=postings.countSelectedFiltered(pageQuery(owner.getId(),"awaiting_packaging",text,sort,0,100),selectedAtRead);
            return new Kn3Pages(pages,completed,selectedFiltered);
        }).whenComplete((result,error)->Platform.runLater(()->{
            if(disposed||epoch!=pageGeneration||!isCurrent(owner))return;
            if(error!=null){statusLabel.setText(tr("products.read_failed"));return;}
            if(transferReport!=null&&transferReport==lastTransitionResult&&!result.completedTransfers().isEmpty()){
                var completed=new ArrayList<>(transferReport.completed());completed.addAll(result.completedTransfers().keySet());
                var pending=new ArrayList<>(transferReport.pending());pending.removeAll(result.completedTransfers().keySet());
                var children=new java.util.LinkedHashMap<>(transferReport.children());children.putAll(result.completedTransfers());
                lastTransitionResult=new BatchTransitionResult(transferReport.requested(),List.copyOf(completed),transferReport.failed(),List.copyOf(pending),transferReport.notProcessed(),Map.copyOf(children),transferReport.reasons());
                selectedPostingNumbers.removeAll(result.completedTransfers().keySet());transitionRevision++;renderTransitionResult(lastTransitionResult);
            }
            if(!selectedAtRead.equals(selectedPostingNumbers)){loadLocal();return;}
            filteredNewCount=result.pages().get("awaiting_packaging").total();selectedFilteredCount=result.selectedFiltered();
            var pages=result.pages();boolean repage=false;
            for(var entry:pages.entrySet()){int offset=offsets.getOrDefault(entry.getKey(),0);long total=entry.getValue().total();if(offset>0&&offset>=total){offsets.put(entry.getKey(),total==0?0:(int)((total-1)/100)*100);repage=true;}}
            if(repage){loadLocal();return;}
            var previousMarks=kn3Marks;var nextMarks=new java.util.HashMap<String,List<com.vncode.app.features.kiz.status.Kn3Mark>>();pages.values().forEach(page->nextMarks.putAll(page.kn3Marks()));kn3Marks=Map.copyOf(nextMarks);
            var changedMarks=new java.util.HashSet<String>();changedMarks.addAll(previousMarks.keySet());changedMarks.addAll(kn3Marks.keySet());changedMarks.removeIf(id->java.util.Objects.equals(previousMarks.get(id),kn3Marks.get(id)));
            var previousProducts=productsBySku;var previousGtins=verifiedGtinsBySku;
            var products=new java.util.HashMap<String,OzonProductDto>();pages.values().forEach(page->products.putAll(page.productsBySku()));var gtins=new java.util.HashMap<String,String>();pages.values().forEach(page->gtins.putAll(page.verifiedGtinsBySku()));verifiedGtinsBySku=Map.copyOf(gtins);productsBySku=Map.copyOf(products);catalogProducts=List.copyOf(products.values());
            var enrichedSkus=new java.util.HashSet<String>();enrichedSkus.addAll(previousProducts.keySet());enrichedSkus.addAll(productsBySku.keySet());enrichedSkus.addAll(previousGtins.keySet());enrichedSkus.addAll(verifiedGtinsBySku.keySet());
            enrichedSkus.removeIf(sku->java.util.Objects.equals(previousProducts.get(sku),productsBySku.get(sku))&&java.util.Objects.equals(previousGtins.get(sku),verifiedGtinsBySku.get(sku)));
            java.util.function.BiPredicate<OzonPostingDto,OzonPostingDto> unchanged=(old,value)->old.equals(value)&&!changedMarks.contains(value.postingNumber())&&value.items().stream().noneMatch(item->enrichedSkus.contains(item.sku()));
            var newSelection=selectedPostingNumber(newOrdersTable);var packingSelection=selectedPostingNumber(packingOrdersTable);var deliveringSelection=selectedPostingNumber(deliveringOrdersTable);var printedSelection=selectedPostingNumber(printedOrdersTable);
            com.vncode.app.shared.TableRows.merge(newOrdersTable.getItems(),pages.get("awaiting_packaging").postings(),OzonPostingDto::postingNumber,unchanged);packingOrdersRaw=pages.get("awaiting_deliver").postings();com.vncode.app.shared.TableRows.merge(packingOrdersTable.getItems(),packingOrdersRaw,OzonPostingDto::postingNumber,unchanged);com.vncode.app.shared.TableRows.merge(printedOrdersTable.getItems(),pages.get("printed_awaiting_deliver").postings(),OzonPostingDto::postingNumber,unchanged);com.vncode.app.shared.TableRows.merge(deliveringOrdersTable.getItems(),pages.get("delivering").postings(),OzonPostingDto::postingNumber,unchanged);
            pages.forEach((status,page)->totals.put(status,page.total()));selectPosting(newOrdersTable,newSelection);selectPosting(packingOrdersTable,packingSelection);selectPosting(deliveringOrdersTable,deliveringSelection);selectPosting(printedOrdersTable,printedSelection);
            updateEmptyStates();updateSelectionState();updatePrintAllState();updateTabTitles();updatePaging();
            com.vncode.app.shared.RuntimeDiagnostics.afterNextPulse("ozon-fbs-render",started);
        }));
    }
    private String sortFields(){var fields=new ArrayList<String>();var options=getSortOptions();if(options.bySubject())fields.add("product");if(options.byArticle())fields.add("article");if(options.byColor())fields.add("color");if(options.bySize())fields.add("size");return String.join(",",fields);}
    private static com.vncode.app.features.ozon.OzonFbsQuery pageQuery(int shop,String key,String text,String sort,int offset,int limit){
        var state=key.equals("awaiting_deliver")?com.vncode.app.features.ozon.OzonFbsQuery.PrintState.UNPRINTED:
            key.equals("printed_awaiting_deliver")?com.vncode.app.features.ozon.OzonFbsQuery.PrintState.PRINTED:com.vncode.app.features.ozon.OzonFbsQuery.PrintState.ALL;
        return new com.vncode.app.features.ozon.OzonFbsQuery(shop,key.equals("printed_awaiting_deliver")?"awaiting_deliver":key,text,sort,offset,limit,state);
    }
    private String activeStatus(){return orderStatusTabs.getSelectionModel().getSelectedItem()==packingOrdersTab?"awaiting_deliver":orderStatusTabs.getSelectionModel().getSelectedItem()==deliveringOrdersTab?"delivering":orderStatusTabs.getSelectionModel().getSelectedItem()==printedOrdersTab?"printed_awaiting_deliver":"awaiting_packaging";}
    @FXML private void onPreviousPage(){String status=activeStatus();offsets.put(status,Math.max(0,offsets.getOrDefault(status,0)-100));loadLocal();}
    @FXML private void onNextPage(){String status=activeStatus();if(offsets.getOrDefault(status,0)+100<totals.getOrDefault(status,0L)){offsets.put(status,offsets.getOrDefault(status,0)+100);loadLocal();}}
    private void updatePaging(){if(pageStatusLabel==null)return;String status=activeStatus();long total=totals.getOrDefault(status,0L);int offset=offsets.getOrDefault(status,0);pageStatusLabel.setText((total==0?0:offset+1)+"–"+Math.min(offset+100,total)+" / "+total);previousPageButton.setDisable(shop==null||offset==0);nextPageButton.setDisable(shop==null||offset+100>=total);}
    public void dispose(){if(orderDetailDrawer!=null)orderDetailDrawer.clear();if(activeSelectionTask!=null)activeSelectionTask.cancel(true);clearPrintPill();shipmentKizPane.dispose();disposed=true;pageGeneration++;requestToken++;shopGeneration++;postingDebounce.stop();if(gtinRefreshTimer!=null)gtinRefreshTimer.stop();if(gtinRefreshSpin!=null)gtinRefreshSpin.stop();productsBySku=Map.of();verifiedGtinsBySku=Map.of();catalogProducts=List.of();kn3Marks=Map.of();}

    private void updateTabTitles() {
        I18nService i18n = I18nService.getInstance();
        newOrdersTab.setText(i18n.tr("ozon.dashboard.tab.new") + " (" + totals.getOrDefault("awaiting_packaging",0L) + ")");
        packingOrdersTab.setText(i18n.tr("ozon.dashboard.tab.packing") + " (" + totals.getOrDefault("awaiting_deliver",0L) + ")");
        printedOrdersTab.setText(i18n.tr("ozon.dashboard.tab.printed")+" ("+totals.getOrDefault("printed_awaiting_deliver",0L)+")");
        deliveringOrdersTab.setText(i18n.tr("ozon.dashboard.tab.delivering") + " ("
                + totals.getOrDefault("delivering",0L) + ")");
    }

    private void updateEmptyStates() {
        setEmptyState(emptyNewOrdersLabel,newOrdersTable.getItems().isEmpty());setEmptyState(emptyPackingOrdersLabel,packingOrdersTable.getItems().isEmpty());setEmptyState(emptyPrintedOrdersLabel,printedOrdersTable.getItems().isEmpty());setEmptyState(emptyDeliveringOrdersLabel,deliveringOrdersTable.getItems().isEmpty());
    }

    private static void setEmptyState(Label label, boolean empty) {
        label.setVisible(empty);
        label.setManaged(empty);
    }

    private void setupTables() {
        printAllButton.visibleProperty().bind(orderStatusTabs.getSelectionModel().selectedItemProperty().isEqualTo(packingOrdersTab));
        printAllButton.managedProperty().bind(printAllButton.visibleProperty());
        for (var column : List.of(newOrderShipmentTC, packingOrderShipmentTC, printedOrderShipmentTC, deliveringOrderShipmentTC)){
            column.setCellValueFactory(data -> new SimpleStringProperty(shipmentText(data.getValue())));
            column.setVisible(false);
        }
        for (var column : List.of(newOrderPriceTC, packingOrderPriceTC, printedOrderPriceTC, deliveringOrderPriceTC))
            configurePriceColumn(column);
        selectAllCheckBox.setOnAction(event -> onSelectAll());
        newOrderSelectTC.setGraphic(selectAllCheckBox);
        newOrderSelectTC.setCellValueFactory(data ->
                new ReadOnlyObjectWrapper<>(selectedPostingNumbers.contains(data.getValue().postingNumber())));
        newOrderSelectTC.setCellFactory(column -> selectionCell());
        configureImageColumn(newOrderImageTC);
        configureImageColumn(packingOrderImageTC);configureImageColumn(printedOrderImageTC);
        configureImageColumn(deliveringOrderImageTC);
        configureOrderColumns(newOrderNumberTC, newOrderItemsTC);
        configureOrderColumns(packingOrderNumberTC, packingOrderItemsTC);configureOrderColumns(printedOrderNumberTC,printedOrderItemsTC);
        configureOrderColumns(deliveringOrderNumberTC, deliveringOrderItemsTC);
        deliveringOrderStatusTC.setCellValueFactory(data ->
                new SimpleStringProperty(statusText(data.getValue().status())));
        deliveringOrderStatusTC.setCellFactory(column->new TableCell<>(){
            protected void updateItem(String text,boolean empty){super.updateItem(text,empty);setText(empty?null:text);var posting=getTableRow()==null?null:getTableRow().getItem();setTooltip(empty||posting==null?null:new Tooltip(text+"\n"+posting.status()));}
        });
        packingLabelTC.setCellFactory(column -> labelCell());printedLabelTC.setCellFactory(column->labelCell());
        configureReferenceTable(newOrdersTable,newOrderNumberTC,newOrderItemsTC,"awaiting_packaging");
        configureReferenceTable(packingOrdersTable,packingOrderNumberTC,packingOrderItemsTC,"awaiting_deliver");
        configureReferenceTable(printedOrdersTable,printedOrderNumberTC,printedOrderItemsTC,"printed_awaiting_deliver");
        configureReferenceTable(deliveringOrdersTable,deliveringOrderNumberTC,deliveringOrderItemsTC,"delivering");
        for (TableView<OzonPostingDto> table : List.of(newOrdersTable, packingOrdersTable, printedOrdersTable, deliveringOrdersTable)) {
            table.setSortPolicy(ignored -> false);
            table.setAccessibleHelp(tr("ui.detail.open"));
            table.setOnKeyPressed(event->{if(event.getCode()==javafx.scene.input.KeyCode.ENTER&&event.getTarget()==table){var posting=table.getSelectionModel().getSelectedItem();if(posting!=null){showOrderDetails(posting);event.consume();}}});
            table.setRowFactory(ignored -> new TableRow<>() {
                { setOnMouseClicked(event->{if(event.getClickCount()==2&&getItem()!=null)showOrderDetails(getItem());}); }
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

    private void showOrderDetails(OzonPostingDto posting) {
        var sections=new ArrayList<Section>();
        sections.add(new Section(tr("ozon.dashboard.col.order"),List.of(
            new Field(tr("ozon.dashboard.col.status"),statusText(posting.status())),
            new Field(tr("ozon.dashboard.col.shipment"),shipmentText(posting)))));
        for(var item:posting.items()){
            var variant=OzonProductVariant.from(item,findCatalogProduct(item));
            sections.add(new Section(item.name(),List.of(
                new Field(tr("products.seller_article"),variant.article()),
                new Field(tr("fbo.column.color"),variant.color()),new Field(tr("fbo.column.size"),variant.size()),
                new Field("SKU",item.sku()),new Field(tr("fbo.column.quantity"),Integer.toString(item.quantity())),
                new Field("GTIN",verifiedGtinsBySku.getOrDefault(item.sku(),tr("products.unverified"))))));
        }
        orderDetailDrawer.show(orderText(posting),sections);
    }

    private void configureOrderColumns(TableColumn<OzonPostingDto, String> orderColumn,
            TableColumn<OzonPostingDto, String> itemsColumn) {
        orderColumn.setCellValueFactory(data -> new SimpleStringProperty(orderText(data.getValue())+"\n"+shipmentText(data.getValue())));
        orderColumn.setResizable(true);orderColumn.setMaxWidth(280);orderColumn.setMinWidth(150);orderColumn.setPrefWidth(190);
        orderColumn.setCellFactory(column->new TableCell<>(){
            @Override protected void updateItem(String text,boolean empty){
                super.updateItem(text,empty);setText(null);var posting=getTableRow()==null?null:getTableRow().getItem();
                if(empty||posting==null){setGraphic(null);return;}
                var number=new Label(orderText(posting));number.getStyleClass().add("text-bold");number.setWrapText(true);
                var due=new Label(shipmentText(posting));due.getStyleClass().add("text-muted");
                due.setTooltip(new Tooltip(tr("ozon.dashboard.col.shipment")+": "+shipmentText(posting)));
                var content=new VBox(6,number,due);content.setAlignment(Pos.CENTER_LEFT);setAlignment(Pos.CENTER_LEFT);setGraphic(content);
            }
        });
        itemsColumn.setCellValueFactory(data -> new SimpleStringProperty(itemsText(data.getValue())));
        itemsColumn.setCellFactory(column -> new OrderItemsCell());
        itemsColumn.setMinWidth(230);itemsColumn.setPrefWidth(330);
    }

    private void configureReferenceTable(TableView<OzonPostingDto> table,TableColumn<OzonPostingDto,String> order,TableColumn<OzonPostingDto,String> details,String status){
        table.getStyleClass().addAll("supply-orders","ozon-posting-table");
        var index=new TableColumn<OzonPostingDto,Number>();index.setId("ozonRowNumber");index.setMinWidth(50);index.setPrefWidth(54);index.setMaxWidth(64);
        index.setCellValueFactory(data->new ReadOnlyObjectWrapper<>(0));index.setCellFactory(column->new TableCell<>(){
            @Override protected void updateItem(Number value,boolean empty){super.updateItem(value,empty);setText(empty?null:String.valueOf(offsets.getOrDefault(status,0)+getIndex()+1));setAlignment(Pos.CENTER_LEFT);}
        });
        rowNumberColumns.add(index);table.getColumns().add(table==newOrdersTable?1:0,index);
        var identifiers=new TableColumn<OzonPostingDto,OzonPostingDto>();identifiers.setId("ozonBarcodes");identifiers.setMinWidth(190);identifiers.setPrefWidth(230);
        identifiers.setCellValueFactory(data->new ReadOnlyObjectWrapper<>(data.getValue()));identifiers.setCellFactory(column->new PostingBarcodesCell());
        barcodeColumns.add(identifiers);table.getColumns().add(table.getColumns().indexOf(details)+1,identifiers);
        // Keep fixed widths locked using equal bounds: JavaFX's constrained policy must
        // include their preferred widths when deciding whether to shrink other columns.
        for (var fixed : table.getColumns()) if (!fixed.isResizable()) {
            double width = fixed.getPrefWidth();
            fixed.setMinWidth(width); fixed.setMaxWidth(width); fixed.setResizable(true);
        }
        table.widthProperty().addListener((o,a,b)->resizePostingTable(table));resizePostingTable(table);
    }
    private void resizePostingTable(TableView<OzonPostingDto> table){
        double minimum=table.getVisibleLeafColumns().stream().mapToDouble(javafx.scene.control.TableColumnBase::getMinWidth).sum()+20;
        var policy=table.getWidth()<minimum?TableView.UNCONSTRAINED_RESIZE_POLICY:TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN;
        if(table.getColumnResizePolicy()!=policy)table.setColumnResizePolicy(policy);
    }

    private void configureImageColumn(TableColumn<OzonPostingDto, OzonPostingDto> imageColumn) {
        imageColumn.setMaxWidth(64);imageColumn.setMinWidth(60);imageColumn.setPrefWidth(64);
        imageColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final VBox images = new VBox(ITEM_GAP);
            private String currentPostingNumber;
            {
                images.setAlignment(Pos.TOP_CENTER);getStyleClass().add("ozon-thumbnail-cell");
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
                imageView.setFitHeight(53);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.setVisible(false);
                StackPane placeholder = new StackPane();
                placeholder.setPrefSize(42, 53);
                placeholder.setMaxSize(42, 53);
                placeholder.getStyleClass().add("image-placeholder");
                StackPane thumbnail = new StackPane(placeholder, imageView);
                thumbnail.setMinSize(42, 53); thumbnail.setPrefSize(42, 53); thumbnail.setMaxSize(42, 53);
                StackPane container = new StackPane(thumbnail);
                container.setMinSize(42, ITEM_HEIGHT);
                container.setPrefSize(42, ITEM_HEIGHT);
                container.setMaxSize(42, ITEM_HEIGHT);
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
                checkBox.disableProperty().bind(operationBusy);
                checkBox.setOnAction(event -> {
                    OzonPostingDto posting = getTableRow() == null ? null : getTableRow().getItem();
                    if (posting == null) return;
                    boolean changed=checkBox.isSelected()?selectedPostingNumbers.add(posting.postingNumber()):selectedPostingNumbers.remove(posting.postingNumber());
                    if(changed)selectedFilteredCount+=checkBox.isSelected()?1:-1;
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
                setGraphic(checkBox);
            }
        };
    }

    private TableCell<OzonPostingDto, Void> labelCell() {
        return new TableCell<>() {
            private final Button printButton = new Button();
            private final SimpleBooleanProperty unavailable = new SimpleBooleanProperty(true);
            {
                printButton.disableProperty().bind(operationBusy.or(unavailable));
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
                unavailable.set(!canPrintLabel(posting));
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
        com.vncode.app.shared.WorkspaceMotion.selection(selectedCountLabel);
        long selectedFiltered = Math.max(0,selectedFilteredCount);
        long filtered = Math.max(0,filteredNewCount);
        selectAllCheckBox.setIndeterminate(selectedFiltered > 0 && selectedFiltered < filtered);
        selectAllCheckBox.setSelected(filtered > 0 && selectedFiltered == filtered);
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
        setBusy(busy,false);
    }

    private void setBusy(boolean busy,boolean browseWhileWorking) {
        this.busy = busy;operationBusy.set(busy);shipmentKizPane.setLocked(busy);
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
        boolean disableTables=busy&&!browseWhileWorking;
        newOrdersTable.setDisable(disableTables);
        packingOrdersTable.setDisable(disableTables);printedOrdersTable.setDisable(disableTables);
        deliveringOrdersTable.setDisable(disableTables);
        selectAllCheckBox.setDisable(busy);
        kizPolicyButton.setDisable(busy || gtinLoading || gtinSyncing || shop == null);
        updateSelectionState();
        updatePrintAllState();
    }

    private void clear() {
        selectedPostingNumbers.clear();
        filteredNewCount=0;selectedFilteredCount=0;
        catalogProducts = List.of();productsBySku=Map.of();verifiedGtinsBySku=Map.of();totals.clear();offsets.clear();
        packingOrdersRaw = List.of();
        newOrdersTable.getItems().clear();
        packingOrdersTable.getItems().clear();printedOrdersTable.getItems().clear();
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

    /** Preserve every original posting excluded by the batch allocation. */
    static List<String> printableForBatch(OzonBatchPrintReadiness readiness,KizSourceSelection sources) {
        if(sources!=null&&sources.purchased())return readiness.postings().stream()
                .filter(row->com.vncode.app.integration.ozon.OzonStockReplenishment.canResolveByPurchase(row,sources))
                .map(OzonPrintReadiness::postingNumber).toList();
        return readiness.readyPostingNumbers();
    }
    static Map<String,String> preflightFailures(OzonBatchPrintReadiness readiness) {
        return preflightFailures(readiness,readiness.readyPostingNumbers());
    }
    static Map<String,String> preflightFailures(OzonBatchPrintReadiness readiness,List<String> printable) {
        return preflightFailures(readiness,printable,Map.of());
    }
    static Map<String,String> preflightFailures(OzonBatchPrintReadiness readiness,List<String> printable,Map<String,String> crossIssues) {
        var eligible=Set.copyOf(printable);var out=new java.util.LinkedHashMap<String,String>();
        String sharedShortage=readiness.gtinAvailability().stream().filter(value->!value.sufficient())
                .map(value->MessageFormat.format(I18nService.getInstance().tr("ozon.dashboard.readiness.availability"),
                        value.gtin(),value.required(),value.available())).collect(java.util.stream.Collectors.joining("; "));
        for(var row:readiness.postings())if(!eligible.contains(row.postingNumber())) {
            String reason=!row.ready()?readinessBlockedText(row):sharedShortage.isBlank()
                    ?I18nService.getInstance().tr("ozon.dashboard.readiness.blocked")
                    :MessageFormat.format(I18nService.getInstance().tr("ozon.dashboard.readiness.insufficient"),sharedShortage);
            out.put(row.postingNumber(),withCrossIssues(row,reason,crossIssues));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
    private static String withCrossIssues(OzonPrintReadiness row,String reason,Map<String,String> crossIssues){
        var codes=row.missingSkus().stream().filter(crossIssues::containsKey).map(crossIssues::get).distinct().toList();
        return codes.isEmpty()?reason:reason+" · "+codes.stream().map(OzonDashboardController::crossIssueMessage).collect(java.util.stream.Collectors.joining("; "));
    }
    private static String crossIssueMessage(String code){
        return "KIZ_CROSS_PURCHASE_REVIEW_REQUIRED".equals(code)?com.vncode.app.features.kiz.KizErrorMessages.message(code):"["+code+"]";
    }

    private static String readinessSummary(OzonPrintReadiness readiness) {
        I18nService i18n = I18nService.getInstance();
        if (!readiness.postingAvailable()) return i18n.tr("ozon.dashboard.readiness.posting_missing");
        if (!readiness.unsupportedRequirements().isEmpty()) {
            return MessageFormat.format(i18n.tr("ozon.dashboard.readiness.unsupported"),
                    String.join(", ", readiness.unsupportedRequirements()));
        }
        if(readiness.missingSkus().stream().anyMatch(s->s.startsWith("KIZ_SOURCE_REQUIRED: ")))
            return com.vncode.app.features.kiz.KizErrorMessages.message("KIZ_SOURCE_REQUIRED")+" · SKU: "+readiness.missingSkus().stream().map(s->s.replace("KIZ_SOURCE_REQUIRED: ","")).collect(java.util.stream.Collectors.joining(", "));
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
        return ITEM_HEIGHT + 16 + Math.max(0, itemRows - 1) * (ITEM_HEIGHT + ITEM_GAP);
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
        column.setMinWidth(100);column.setPrefWidth(100);
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
        int childCount=result.completed().stream().mapToInt(id->result.children().getOrDefault(id,List.of(id)).size()).sum();
        if(childCount>result.completed().size())text+=" • "+MessageFormat.format(I18nService.getInstance().tr("ozon.fbs.split_result"),childCount);
        if(!result.pending().isEmpty())text+=" • "+I18nService.getInstance().tr("ozon.fbs.pending")+": "+result.pending().size();
        if(!result.notProcessed().isEmpty())text+=" • "+I18nService.getInstance().tr("ozon.fbs.not_processed")+": "+result.notProcessed().size();
        return result.failed().isEmpty()?text:text+" • "+result.failed().getFirst()+": "+result.reasons().getOrDefault(result.failed().getFirst(),"");
    }
    private void renderTransitionResult(BatchTransitionResult result){
        statusLabel.setText(batchResultText(result));
        var lines=new ArrayList<String>();
        result.completed().forEach(id->lines.add(id+" · "+tr("ozon.fbs.packaged")+" → "+String.join(", ",result.children().getOrDefault(id,List.of(id)))));
        result.pending().forEach(id->lines.add(id+" · "+tr("ozon.fbs.pending")+" · "+result.reasons().getOrDefault(id,"")+(result.children().containsKey(id)?" → "+String.join(", ",result.children().get(id)):"")));
        result.failed().forEach(id->lines.add(id+" · "+tr("ozon.fbs.failed")+" · "+result.reasons().getOrDefault(id,"")));
        result.notProcessed().forEach(id->lines.add(id+" · "+tr("ozon.fbs.not_processed")));
        batchResultsArea.setText(String.join("\n",lines));batchResultsPanel.setVisible(true);batchResultsPanel.setManaged(true);batchResultsPanel.setExpanded(!result.pending().isEmpty()||!result.failed().isEmpty()||!result.notProcessed().isEmpty());
    }
    static boolean reconciliationRequired(Throwable error){
        if(com.vncode.app.features.kiz.KizPurchasePendingException.isPending(error))return true;
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause())
            if(error instanceof com.vncode.app.integration.ozon.OzonApiException e&&(e.ambiguousMutation()||e.kind().equals("reconcile_required")||e.statusCode()==429)
                    ||error instanceof IOException&&"OZON_ACCEPT_KIZ_PENDING".equals(error.getMessage()))return true;
        return false;
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

    private static String shippingFailure(Throwable failure){
        if(failure instanceof com.vncode.app.integration.ozon.OzonApiException api&&api.statusCode()==429)return I18nService.getInstance().tr("ozon.fbs.rate_deferred");
        if(failure instanceof com.vncode.app.features.kiz.KizAssignmentException)return FriendlyErrorService.format(failure);
        String code=failure instanceof com.vncode.app.integration.ozon.OzonApiException api?api.safeErrorCode():failure.getMessage();
        if("FBS_INCOMPLETE_POSTING".equals(code)||"FBS_INVALID_RESPONSE".equals(code))return I18nService.getInstance().tr("ozon.fbs.incomplete_order");
        if("OZON_ACCEPT_KIZ_BATCH_PREFLIGHT".equals(code))return I18nService.getInstance().tr("ozon.fbs.purchase_batch_failed")+": "+safeFailure(failure.getCause());
        if(code!=null&&code.startsWith("OZON_SPLIT_"))return I18nService.getInstance().tr("ozon.fbs.reason."+code,safeFailure(failure));
        if(code!=null&&code.startsWith("OZON_ACCEPT_KIZ_"))return I18nService.getInstance().tr("ozon.flow."+code,safeFailure(failure));
        return safeFailure(failure);
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

    private final class PostingBarcodesCell extends TableCell<OzonPostingDto,OzonPostingDto>{
        @Override protected void updateItem(OzonPostingDto posting,boolean empty){
            super.updateItem(posting,empty);setText(null);
            if(empty||posting==null){setGraphic(null);return;}
            var content=new VBox(ITEM_GAP);
            for(var item:posting.items()){
                var card=findCatalogProduct(item);
                var barcode=new Label("Ozon: "+(card==null||card.barcodes().isEmpty()?"—":String.join(", ",card.barcodes())));
                barcode.getStyleClass().add("text-bold");barcode.setTooltip(new Tooltip(barcode.getText()));
                var gtin=new Label("GTIN: "+verifiedGtinsBySku.getOrDefault(item.sku(),tr("products.unverified")));
                gtin.getStyleClass().add("text-secondary");gtin.setTooltip(new Tooltip(gtin.getText()));
                var sku=new Label("SKU: "+item.sku());sku.getStyleClass().add("text-muted");
                var values=new VBox(1,barcode,gtin,sku);values.setAlignment(Pos.CENTER_LEFT);
                values.setMinHeight(ITEM_HEIGHT);values.setPrefHeight(ITEM_HEIGHT);values.setMaxHeight(ITEM_HEIGHT);content.getChildren().add(values);
            }
            setAlignment(Pos.TOP_LEFT);setGraphic(content);
        }
    }

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
            var card=findCatalogProduct(item);var category=new Label(card==null?"":card.category());category.getStyleClass().add("text-muted");
            category.setVisible(!category.getText().isBlank());category.setManaged(category.isVisible());category.setTooltip(new Tooltip(category.getText()));
            var marking=new Label(tr("ozon.fbs.kiz")+": "+(posting.requirements().mandatoryMarkProductIds().contains(item.productId())?tr("ozon.fbs.required"):tr("ozon.fbs.check_before_print")));marking.getStyleClass().add("text-muted");
            var observations=kn3Marks.getOrDefault(posting.postingNumber(),List.of()).stream().filter(m->m.productId().equals(item.sku())).toList();
            var badge=new com.vncode.app.ui.kiz.Kn3Badge();badge.show(observations,posting.requirements().mandatoryMarkProductIds().contains(item.productId()),null);
            metadata.setTooltip(new Tooltip(metadata.getText()+"\n"+category.getText()));
            VBox itemBox = new VBox(2, titleRow, metadata, badge.isVisible()?badge:marking);
            itemBox.setMinHeight(ITEM_HEIGHT); itemBox.setPrefHeight(ITEM_HEIGHT); itemBox.setMaxHeight(ITEM_HEIGHT);
            itemBox.setAlignment(Pos.CENTER_LEFT);
            return itemBox;
        }
    }

    private OzonProductDto findCatalogProduct(OzonPostingItemDto item) {
        if (item == null) return null;
        var product=productsBySku.get(item.sku());
        return product==null||!item.offerId().isBlank()&&!item.offerId().equals(product.offerId())?null:product;
    }

    private record BatchTransitionResult(int requested,List<String> completed,List<String> failed,List<String> pending,List<String> notProcessed,Map<String,List<String>> children,Map<String,String> reasons) { }
    record PrintOutput(String message, List<File> files, List<String> postingNumbers,Map<String,String> failures,Set<String> waiting,Map<String,String> carrierOnly,Map<String,String> remoteKizWarnings) {
        PrintOutput(String message,List<File> files,List<String> postingNumbers,Map<String,String> failures,Set<String> waiting,Map<String,String> carrierOnly){this(message,files,postingNumbers,failures,waiting,carrierOnly,Map.of());}
        PrintOutput(String message,List<File> files,List<String> postingNumbers){this(message,files,postingNumbers,Map.of(),Set.of(),Map.of());}
        PrintOutput(String message,List<File> files,List<String> postingNumbers,Map<String,String> failures,Set<String> waiting){this(message,files,postingNumbers,failures,waiting,Map.of());}
        PrintOutput {
            message = message == null ? "" : message;
            files = files == null ? List.of() : List.copyOf(files);
            postingNumbers = postingNumbers == null ? List.of() : List.copyOf(postingNumbers);
            failures=failures==null?Map.of():Map.copyOf(failures);
            waiting=waiting==null?Set.of():Set.copyOf(waiting);
            carrierOnly=carrierOnly==null?Map.of():Map.copyOf(carrierOnly);
            remoteKizWarnings=remoteKizWarnings==null?Map.of():Map.copyOf(remoteKizWarnings);
        }
    }
}
