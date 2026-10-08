package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.BuildConfig;
import com.vncode.app.features.fbo.FboBarcodePdfExporter;
import com.vncode.app.features.fbo.FboBarcodePrintItem;
import com.vncode.app.features.fbo.FboKizPrintPlanner;
import com.vncode.app.features.fbo.FboProductRepository;
import com.vncode.app.features.fbo.FboProductSearchCriteria;
import com.vncode.app.features.fbo.FboProductSku;
import com.vncode.app.features.fbo.FboPrintPlan;
import com.vncode.app.features.fbosupply.FboSupplyExecutor;
import com.vncode.app.features.finance.FinanceSyncScheduler;
import com.vncode.app.features.packing.PackingWorkflow;
import com.vncode.app.integration.wb.WbSupplySummary;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.integration.wb.WbSupplyNotEmptyException;
import com.vncode.app.integration.znack.ZnackPurchaseCoordinator;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackGtinAutoSync;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackProductService;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.ZnackSigningSession;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.integration.wb.WbSyncReport;
import com.vncode.app.integration.wb.WbSyncWorkflow;
import com.vncode.app.integration.wb.WbTokenInspector;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonFboKizPrintPlanner;
import com.vncode.app.integration.ozon.OzonFboProductRepository;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.ConfigService;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.features.print.KizAttachmentCoordinator;
import com.vncode.app.features.print.PrintJobOptions;
import com.vncode.app.features.print.PrintOptionsDialogService;
import com.vncode.app.features.print.OrderExportWorkflow;
import com.vncode.app.features.print.PrintTemplateDesignerService;
import com.vncode.app.features.print.PrintTemplateService;
import com.vncode.app.features.print.history.PrintHistoryJobSummary;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.supply.OrderSortPreferenceService;
import com.vncode.app.features.supply.OrderSortingService;
import com.vncode.app.integration.update.UpdateDialogService;
import com.vncode.app.integration.update.UpdateInfo;
import com.vncode.app.integration.update.UpdateInstallerService;
import com.vncode.app.integration.update.UpdateService;
import com.vncode.app.features.shop.ShopWorkflow;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.features.supply.SupplyLoadWorkflow;
import com.vncode.app.ui.history.PrintHistoryController;
import com.vncode.app.ui.fbo.FboPackingController;
import com.vncode.app.ui.fbosupply.FboSupplyOrdersController;
import com.vncode.app.ui.finance.FinanceDashboardController;
import com.vncode.app.ui.kizmapping.KizMappingController;
import com.vncode.app.ui.packing.PackingController;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.shop.ShopSidebarController;
import com.vncode.app.features.supply.OrderSortOptions;
import com.vncode.app.ui.supply.SupplyDetailController;
import com.vncode.app.ui.supply.SupplyListController;
import com.vncode.app.ui.supply.SupplyManagementController;
import com.vncode.app.ui.znack.ZnackAutomationController;
import com.vncode.app.ui.znackregistration.ZnackCardRegistrationController;
import javafx.concurrent.Task;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class HomeController implements Initializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeController.class);

    private final OrderExportWorkflow orderExportWorkflow = new OrderExportWorkflow();
    private final ShopWorkflow shopWorkflow = new ShopWorkflow();
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final PackingWorkflow packingWorkflow = new PackingWorkflow();
    private final SupplyLoadWorkflow supplyLoadWorkflow = new SupplyLoadWorkflow();
    private final OrderSortingService orderSortingService = new OrderSortingService();
    private final OrderSortPreferenceService orderSortPreferenceService = new OrderSortPreferenceService();
    private final PrintOptionsDialogService printOptionsDialogService = new PrintOptionsDialogService();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final PrintTemplateDesignerService printTemplateDesignerService = new PrintTemplateDesignerService();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final FboProductRepository fboProductRepository = new FboProductRepository();
    private final FboBarcodePdfExporter fboBarcodePdfExporter = new FboBarcodePdfExporter();
    private final FboKizPrintPlanner fboKizPrintPlanner = new FboKizPrintPlanner();
    private final OzonFboProductRepository ozonFboProductRepository = new OzonFboProductRepository();
    private final OzonFboKizPrintPlanner ozonFboKizPrintPlanner = new OzonFboKizPrintPlanner();
    private final KizAttachmentCoordinator kizAttachmentCoordinator = KizAttachmentCoordinator.getInstance();
    private final WorkspaceState state = new WorkspaceState();
    private final WorkspaceActivityTracker activityTracker = new WorkspaceActivityTracker();
    private final UpdateService updateService = new UpdateService();
    private final UpdateInstallerService updateInstallerService = new UpdateInstallerService();
    private final I18nService i18nService = I18nService.getInstance();

    public StackPane sidebarContainer;
    public StackPane headerContainer;
    public BorderPane contentPane;
    public StackPane dynamicContentContainer;

    private BorderPane supplyManagementView;
    private VBox financeDashboardView;
    private VBox printHistoryView;
    private VBox packingView;
    private VBox fboPackingView;
    private VBox fboSupplyOrdersView;
    private VBox ozonDashboardView;
    private Node kizMappingView;
    private Node znackAutomationView;
    private Node znackRegistrationView;
    private Node gtinSyncView;
    private final WorkspaceNavigator workspaceNavigator = new WorkspaceNavigator(this::displayDynamicContent);
    private com.vncode.app.ui.gtinsync.GtinSyncController gtinSyncController;
    private SupplyManagementController supplyManagementController;
    private FinanceDashboardController financeDashboardController;
    private PrintHistoryController printHistoryController;
    private PackingController packingController;
    private FboPackingController fboPackingController;
    private FboSupplyOrdersController fboSupplyOrdersController;
    private OzonDashboardController ozonDashboardController;
    private KizMappingController kizMappingController;
    private ZnackAutomationController znackAutomationController;
    private ZnackCardRegistrationController znackRegistrationController;

    private FileChooser fileChooser;
    private ShopSidebarController shopSidebarController;
    private WorkspaceHeaderController workspaceHeaderController;
    private SupplyListController supplyListController;
    private SupplyDetailController supplyDetailController;
    private WbSupplySummary loadedSupplySummary;
    private final PauseTransition fboSearchDebounce = new PauseTransition(javafx.util.Duration.millis(250));
    private static final int FBO_PAGE_SIZE = 50;
    private boolean fboLoading;
    private boolean fboHasMore;
    private final Consumer<com.vncode.app.shared.AppLanguage> languageListener =
            language -> Platform.runLater(this::applyTranslations);
    private final Consumer<KizAttachmentCoordinator.KizAttachmentProgress> kizProgressListener =
            progress -> Platform.runLater(() -> onKizAttachmentProgress(progress));
    private final Set<Integer> productKizSyncingShopIds = ConcurrentHashMap.newKeySet();
    private boolean initialZnackShopAuthorized;
    private boolean disposed;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        Database.initDatabase();
        new ZnackGtinInventoryService().releaseRecoverableReservations();
        printTemplateService.ensureDefaultTemplateExists();

        fileChooser = new FileChooser();
        File initialDirectory = AppPaths.preferredFileChooserDirectory();
        if (initialDirectory != null) {
            fileChooser.setInitialDirectory(initialDirectory);
        }

        initializeSidebar();
        initializeHeader();
        loadDynamicViews();
        initializeSupplyViews();
        initializeBackgroundKizProgress();
        i18nService.addListener(languageListener);

        contentPane.setVisible(false);
        showDashboard();
        updateHeaderState();
        updateExportAvailability();
        loadShops();
        checkForUpdates();
    }

    private void loadDynamicViews() {
        FXMLLoader financeLoader = FxmlViewLoader.loader(FinanceDashboardController.class, "finance-dashboard-view.fxml");
        financeDashboardView = FxmlViewLoader.load(financeLoader);
        financeDashboardController = financeLoader.getController();
        financeDashboardController.applyTranslations();

        FXMLLoader supplyLoader = FxmlViewLoader.loader(SupplyManagementController.class, "supply-management-view.fxml");
        supplyManagementView = FxmlViewLoader.load(supplyLoader);
        supplyManagementController = supplyLoader.getController();

        FXMLLoader printHistoryLoader = FxmlViewLoader.loader(PrintHistoryController.class, "print-history-view.fxml");
        printHistoryView = FxmlViewLoader.load(printHistoryLoader);
        printHistoryController = printHistoryLoader.getController();
        printHistoryController.setOnReprint(this::reprintHistoryJob);
        printHistoryController.applyTranslations();

        FXMLLoader packingLoader = FxmlViewLoader.loader(PackingController.class, "packing-view.fxml");
        packingView = FxmlViewLoader.load(packingLoader);
        packingController = packingLoader.getController();
        packingController.setOnPrintSupply(this::openSupplyForPrint);
        packingController.applyTranslations();

        FXMLLoader fboLoader = FxmlViewLoader.loader(FboPackingController.class, "fbo-packing-view.fxml");
        fboPackingView = FxmlViewLoader.load(fboLoader);
        fboPackingController = fboLoader.getController();
        fboPackingController.setOnSearchChanged(this::scheduleFboReload);
        fboPackingController.setOnLoadMoreRequested(this::loadMoreFboProducts);
        fboPackingController.setOnPrint(this::printFboBarcodes);
        fboPackingController.setOnQuickPrint(this::printSingleFboBarcode);
        fboPackingController.applyTranslations();

        FXMLLoader fboSupplyOrdersLoader = FxmlViewLoader.loader(
                FboSupplyOrdersController.class, "fbo-supply-orders-view.fxml");
        fboSupplyOrdersView = FxmlViewLoader.load(fboSupplyOrdersLoader);
        fboSupplyOrdersController = fboSupplyOrdersLoader.getController();
        fboSupplyOrdersController.applyTranslations();

        FXMLLoader kizMappingLoader = FxmlViewLoader.loader(KizMappingController.class, "kiz-mapping-view.fxml");
        kizMappingView = FxmlViewLoader.load(kizMappingLoader);
        kizMappingController = kizMappingLoader.getController();
        kizMappingController.applyTranslations();

        FXMLLoader znackLoader = FxmlViewLoader.loader(ZnackAutomationController.class, "znack-automation-view.fxml");
        znackAutomationView = FxmlViewLoader.load(znackLoader);
        znackAutomationController = znackLoader.getController();

        FXMLLoader znackRegistrationLoader = FxmlViewLoader.loader(
                ZnackCardRegistrationController.class, "znack-card-registration-view.fxml");
        znackRegistrationView = FxmlViewLoader.load(znackRegistrationLoader);
        znackRegistrationController = znackRegistrationLoader.getController();

        FXMLLoader ozonLoader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
        ozonDashboardView = FxmlViewLoader.load(ozonLoader);
        ozonDashboardController = ozonLoader.getController();
        ozonDashboardController.setOnBusy((shopId, busy) -> markShopRunning(shopId, busy));
        workspaceNavigator.register("finance", () -> financeDashboardView);
        workspaceNavigator.register("supplies", () -> supplyManagementView);
        workspaceNavigator.register("packing", () -> packingView);
        workspaceNavigator.register("fbo-packing", () -> fboPackingView);
        workspaceNavigator.register("fbo-orders", () -> fboSupplyOrdersView);
        workspaceNavigator.register("ozon", () -> ozonDashboardView);
        workspaceNavigator.register("kiz-mapping", () -> kizMappingView);
        workspaceNavigator.register("znack", () -> znackAutomationView);
        workspaceNavigator.register("znack-registration", () -> znackRegistrationView);
        workspaceNavigator.register("print-history", () -> printHistoryView);
        workspaceNavigator.register("gtin-sync", () -> gtinSyncView);
    }

    private void showPrintHistory() {
        clearKizDraft();
        setDynamicContent(printHistoryView);
        refreshPrintHistory();
    }

    private void showDashboard() {
        clearKizDraft();
        setDynamicContent(financeDashboardView);
        financeDashboardController.setShop(state.getSelectedShop());
    }

    private void showOzonDashboard(boolean syncRemote) {
        clearKizDraft();
        setDynamicContent(ozonDashboardView);
        ozonDashboardController.setShop(state.getSelectedShop(), syncRemote);
    }

    private void showPacking() {
        if (isOzonSelected()) {
            showOzonDashboard(false);
            ozonDashboardController.showPackingQueue();
            return;
        }
        clearKizDraft();
        setDynamicContent(packingView);
        refreshPackingView();
    }

    private void showFboPacking() {
        clearKizDraft();
        setDynamicContent(fboPackingView);
        refreshFboView();
    }

    private void showFboSupplyOrders() {
        clearKizDraft();
        setDynamicContent(fboSupplyOrdersView);
        fboSupplyOrdersController.setShop(state.getSelectedShop(), true);
    }

    private void showKizMapping() {
        clearKizDraft();
        setDynamicContent(kizMappingView);
        refreshKizMappingView();
        kizMappingController.syncOnOpen();
    }

    private void showZnackAutomation() {
        clearKizDraft();
        setDynamicContent(znackAutomationView);
        znackAutomationController.setShop(state.getSelectedShop());
    }

    private void showZnackRegistration() {
        clearKizDraft();
        setDynamicContent(znackRegistrationView);
        znackRegistrationController.setShop(state.getSelectedShop());
    }

    private void showGtinSync() {
        clearKizDraft();
        if (gtinSyncView == null) {
            FXMLLoader loader = FxmlViewLoader.loader(com.vncode.app.ui.gtinsync.GtinSyncController.class, "gtin-sync-view.fxml");
            gtinSyncView = FxmlViewLoader.load(loader);
            gtinSyncController = loader.getController();
            new com.vncode.app.features.gtinsync.GtinSyncRepository().recoverInterrupted();
        }
        gtinSyncController.setShop(state.getSelectedShop());
        setDynamicContent(gtinSyncView);
    }

    private void refreshPrintHistory() {
        if (printHistoryController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            printHistoryController.setJobs(List.of());
            return;
        }
        printHistoryController.setJobs(printHistoryService.getJobs(shop.getId()));
    }

    private void refreshPackingView() {
        if (packingController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        packingController.setShop(shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null,
                shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES
                        && state.isSelectedShopTokenValid());
    }

    private void refreshDashboard(boolean forceRefresh) {
        if (financeDashboardController != null && isDashboardVisible()) {
            financeDashboardController.setShop(state.getSelectedShop());
        }
    }

    private void refreshFboView() {
        if (fboPackingController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        fboPackingController.setMarketplace(shop == null ? Marketplace.WILDBERRIES : shop.getMarketplace());
        if (shop == null) {
            fboPackingController.setSubjects(List.of());
            fboPackingController.replaceProducts(List.of(), false);
            return;
        }
        fboPackingController.setSubjects(shop.getMarketplace() == Marketplace.OZON
                ? ozonFboProductRepository.findCategories(shop.getId())
                : fboProductRepository.findSubjects(shop.getId()));
        reloadFboProducts();
    }

    private void refreshKizMappingView() {
        if (kizMappingController != null) {
            kizMappingController.setShop(state.getSelectedShop());
        }
    }

    private void scheduleFboReload() {
        fboSearchDebounce.setOnFinished(event -> reloadFboProducts());
        fboSearchDebounce.playFromStart();
    }

    private void reloadFboProducts() {
        loadFboProducts(false);
    }

    private void loadMoreFboProducts() {
        if (fboHasMore && !fboLoading) {
            loadFboProducts(true);
        }
    }

    private void loadFboProducts(boolean append) {
        if (fboPackingController == null || fboLoading) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            fboPackingController.replaceProducts(List.of(), false);
            return;
        }
        fboLoading = true;
        int offset = append ? fboPackingController.rowCount() : 0;
        Task<List<FboProductSku>> task = new Task<>() {
            @Override
            protected List<FboProductSku> call() {
                FboProductSearchCriteria criteria = fboPackingController.criteria(
                        shop.getId(), FBO_PAGE_SIZE + 1, offset);
                return shop.getMarketplace() == Marketplace.OZON
                        ? ozonFboProductRepository.search(criteria)
                        : fboProductRepository.search(criteria);
            }
        };
        fboPackingController.setLoading(true);
        task.setOnSucceeded(event -> {
            fboLoading = false;
            fboPackingController.setLoading(false);
            List<FboProductSku> loaded = task.getValue() == null ? List.of() : task.getValue();
            fboHasMore = loaded.size() > FBO_PAGE_SIZE;
            List<FboProductSku> page = fboHasMore ? loaded.subList(0, FBO_PAGE_SIZE) : loaded;
            if (append) {
                fboPackingController.appendProducts(page, fboHasMore);
            } else {
                fboPackingController.replaceProducts(page, fboHasMore);
            }
        });
        task.setOnFailed(event -> {
            fboLoading = false;
            fboPackingController.setLoading(false);
            LOGGER.error("Không thể tải sản phẩm FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void openSupplyForPrint(WbSupplySummary supply) {
        setSupplyListVisible(false);
        setDynamicContent(supplyManagementView);
        refreshSupplyList(supply.getSupplyId());
    }

    private void setDynamicContent(Node view) {
        workspaceNavigator.showView(view);
    }

    private void displayDynamicContent(Node view) {
        clearFboQuantitiesIfLeaving(view);
        stopPackingDelayIfLeaving(view);
        dynamicContentContainer.getChildren().setAll(view);
        view.setOpacity(0.0);
        FadeTransition fade = new FadeTransition(javafx.util.Duration.millis(120), view);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    private void clearFboQuantitiesIfLeaving(Node nextView) {
        if (fboPackingController == null || fboPackingView == null || nextView == fboPackingView) {
            return;
        }
        if (dynamicContentContainer.getChildren().contains(fboPackingView)) {
            fboPackingController.clearQuantities();
        }
    }

    private void stopPackingDelayIfLeaving(Node nextView) {
        if (packingController == null || packingView == null || nextView == packingView) {
            return;
        }
        if (dynamicContentContainer.getChildren().contains(packingView)) {
            packingController.cancelPendingRequests();
        }
    }

    private void setSupplyListVisible(boolean visible) {
        if (supplyManagementController == null || supplyManagementController.supplyListContainer == null) {
            return;
        }
        supplyManagementController.supplyListContainer.setVisible(visible);
        supplyManagementController.supplyListContainer.setManaged(visible);
    }

    private void reprintHistoryJob(PrintHistoryJobSummary job) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }

        File orderDetailsFile = new File(file.getParent(), "NHAT_HANG-" + file.getName());
        Task<OrderExportWorkflow.ExportResult> task = new Task<>() {
            @Override
            protected OrderExportWorkflow.ExportResult call() throws Exception {
                return printHistoryService.reprint(job, file, orderDetailsFile);
            }
        };
        task.setOnRunning(e -> markShopRunning(shop.getId(), true));
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            LOGGER.error("In lại thất bại cho lịch sử in {}", job.id(), task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            tryOpenFile(orderDetailsFile);
            tryOpenFile(file);
            refreshPrintHistory();
        });
        AppTaskExecutor.execute(task);
    }

    private void printFboBarcodes(List<FboBarcodePrintItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            return;
        }
        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                    FboPrintPlan plan = shop.getMarketplace() == Marketplace.OZON
                            ? ozonFboKizPrintPlanner.plan(shop.getId(), items)
                            : fboKizPrintPlanner.plan(shop.getId(), items);
                    exportFboPlan(shop.getId(), plan, file);
                    return null;
                });
            }
        };
        task.setOnFailed(event -> {
            if (ShopOperationCoordinator.isShopUnavailable(task.getException())) return;
            LOGGER.error("Không thể in barcode FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(event -> {
            fboPackingController.clearQuantities();
            tryOpenFile(file);
        });
        AppTaskExecutor.execute(task);
    }

    private void printSingleFboBarcode(FboProductSku product) {
        if (product == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            return;
        }
        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                    List<FboBarcodePrintItem> items = List.of(new FboBarcodePrintItem(product, 1));
                    FboPrintPlan plan = shop.getMarketplace() == Marketplace.OZON
                            ? ozonFboKizPrintPlanner.plan(shop.getId(), items)
                            : fboKizPrintPlanner.plan(shop.getId(), items);
                    exportFboPlan(shop.getId(), plan, file);
                    return null;
                });
            }
        };
        task.setOnFailed(event -> {
            if (ShopOperationCoordinator.isShopUnavailable(task.getException())) return;
            LOGGER.error("Không thể in nhanh barcode FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(event -> {
            tryOpenFile(file);
        });
        AppTaskExecutor.execute(task);
    }

    private void exportFboPlan(int shopId, FboPrintPlan plan, File file) throws IOException {
        boolean[] inventoryConsumed = {false};
        try {
            fboBarcodePdfExporter.exportPlan(plan, file, () -> {
                KizService.deleteKizs(shopId, plan.usedKizs());
                inventoryConsumed[0] = true;
            });
        } catch (IOException | RuntimeException error) {
            if (!inventoryConsumed[0]) {
                KizService.releaseKizs(shopId, plan.usedKizs());
            }
            throw error;
        }
    }

    private void checkForUpdates() {
        javafx.concurrent.Task<UpdateInfo> task = new javafx.concurrent.Task<>() {
            @Override
            protected UpdateInfo call() {
                return updateService.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null) {
                showUpdateDialog(info);
            }
        });
        task.setOnFailed(e ->
            LOGGER.warn("Update check failed", task.getException())
        );
        AppTaskExecutor.execute(task);
    }

    private void checkVersionManually() {
        Task<UpdateInfo> task = new Task<>() {
            @Override
            protected UpdateInfo call() {
                return updateService.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null) {
                showUpdateDialog(info);
            } else {
                AlertService.showInfo(
                        i18nService.tr("version.current.title"),
                        i18nService.tr("version.current.header"),
                        MessageFormat.format(i18nService.tr("version.current.content"), BuildConfig.getAppVersion())
                );
            }
        });
        task.setOnFailed(e -> {
            LOGGER.warn("Manual update check failed", task.getException());
            AlertService.showError(i18nService.tr("version.check_failed"));
        });
        AppTaskExecutor.execute(task);
    }

    private void showPersonalEdition() {
        AlertService.showInfo(BuildConfig.getAppName(), i18nService.tr("edition.personal"),
                i18nService.tr("edition.personal.details"));
    }

    private void showAboutDialog() {
        AlertService.showInfo(
                i18nService.tr("about.title"),
                MessageFormat.format(i18nService.tr("about.header"), BuildConfig.getAppVersion()),
                i18nService.tr("about.content")
        );
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void preparePdfSaveChooser() {
        fileChooser.setTitle(i18nService.tr("filechooser.save_pdf"));
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(i18nService.tr("filechooser.pdf"), "*.pdf"));
        File downloadsDirectory = AppPaths.preferredDownloadsDirectory();
        if (downloadsDirectory != null) {
            fileChooser.setInitialDirectory(downloadsDirectory);
        }
    }

    private void showUpdateDialog(UpdateInfo info) {
        UpdateDialogService dialogService = new UpdateDialogService();
        UpdateDialogService.UpdateChoice choice = dialogService.showDialog(info);
        switch (choice) {
            case DOWNLOAD:
                if (updateInstallerService.supportsInAppInstall(info)) {
                    startInstallerUpdate(info);
                } else {
                    UpdateDialogService.openDownloadUrl(info);
                }
                break;
            case SKIP:
                ConfigService.setSkippedVersion(info.getVersion());
                break;
            case LATER:
                break;
        }
    }

    private void startInstallerUpdate(UpdateInfo info) {
        UpdateDialogService dialogService = new UpdateDialogService();
        Task<java.nio.file.Path> task = new Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                updateMessage(i18nService.tr("update.progress.connecting"));
                updateProgress(-1, 1);
                return updateInstallerService.downloadInstaller(info, (downloadedBytes, totalBytes) -> {
                    if (totalBytes > 0) {
                        updateProgress(downloadedBytes, totalBytes);
                        updateMessage(String.format(i18nService.tr("update.progress.downloaded"),
                                formatBytes(downloadedBytes),
                                formatBytes(totalBytes)));
                    } else {
                        updateProgress(-1, 1);
                        updateMessage(i18nService.tr("update.progress.downloading"));
                    }
                });
            }
        };
        var progressDialog = dialogService.showDownloadProgressDialog(info, task);
        task.setOnFailed(e -> {
            progressDialog.close();
            LOGGER.error("Không thể tải bản cập nhật {}", info.getVersion(), task.getException());
            AlertService.showDetailedError(
                    i18nService.tr("update.error.download_generic"),
                    com.vncode.app.integration.znack.ZnackErrorDetails.format(task.getException()));
        });
        task.setOnSucceeded(e -> {
            progressDialog.close();
            try {
                updateInstallerService.launchInstallerAfterExit(task.getValue());
                Platform.exit();
            } catch (IOException ex) {
                LOGGER.error("Không thể khởi chạy installer cập nhật {}", info.getVersion(), ex);
                AlertService.showDetailedError(
                        i18nService.tr("update.error.launch_failed"),
                        com.vncode.app.integration.znack.ZnackErrorDetails.format(ex));
            }
        });
        progressDialog.show();
        AppTaskExecutor.execute(task);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    public void onAddShop(ActionEvent actionEvent) {
        shopWorkflow.requestCreateShop().ifPresent(shop -> {
            int count = shopWorkflow.createShop(shop);
            if (count > 0) {
                state.setPendingSelectShopId(null);
                Task<List<Shop>> refreshTask = new Task<>() {
                    @Override
                    protected List<Shop> call() {
                        return shopWorkflow.loadShops();
                    }
                };
                refreshTask.setOnSucceeded(e -> {
                    List<Shop> loadedShops = refreshTask.getValue();
                    loadedShops.stream()
                            .max(Comparator.comparingInt(Shop::getId))
                            .ifPresent(createdShop -> {
                                state.setPendingSelectShopId(createdShop.getId());
                                state.setShops(loadedShops);
                                renderShops();
                                selectShopById(createdShop.getId());
                                // A newly-created Ozon shop must populate products/images even if
                                // the user created it while a shared dashboard remains visible.
                                if (createdShop.getMarketplace() == Marketplace.OZON
                                        && !isOzonDashboardVisible()) {
                                    ozonDashboardController.setShop(createdShop, true);
                                }
                            });
                    updateHeaderState();
                });
                refreshTask.setOnFailed(e -> AlertService.showError(refreshTask.getException().getMessage()));
                AppTaskExecutor.execute(refreshTask);
            }
        });
    }

    public void onExport(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        if (shop.getMarketplace() != Marketplace.WILDBERRIES) {
            showOzonDashboard(false);
            return;
        }
        if (state.getDisplayedOrders().isEmpty()) {
            AlertService.showWarning(
                    i18nService.tr("common.notice"),
                    i18nService.tr("workspace.warning.refresh_orders.title"),
                    null
            );
            return;
        }
        List<Order> exportOrders = new ArrayList<>(state.getDisplayedOrders());
        String supplyId = state.getLoadedSupplyId();
        String supplyName = state.getLoadedSupplyName();

        Task<Void> checkTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                orderExportWorkflow.verifyKizAvailability(exportOrders, shop);
                return null;
            }
        };

        checkTask.setOnRunning(e -> {
            markShopRunning(shop.getId(), true);
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(true, i18nService.tr("supply.loading_orders"));
            }
        });
        checkTask.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(false);
            }
            Throwable ex = checkTask.getException();
            LOGGER.error("Verify KIZ thất bại cho shop {}", shop.getId(), ex);
            AlertService.showError(ex.getMessage());
        });
        checkTask.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(false);
            }

            javafx.application.Platform.runLater(() -> {
                Optional<PrintJobOptions> printOptions = printOptionsDialogService.chooseOptions();
                if (printOptions.isEmpty()) {
                    return;
                }

                preparePdfSaveChooser();
                File file = fileChooser.showSaveDialog(null);
                if (file == null) {
                    return;
                }

                File orderDetailsFile = new File(file.getParent(), "NHAT_HANG-" + file.getName());
                Task<OrderExportWorkflow.ExportResult> exportTask = new Task<>() {
                    @Override
                    protected OrderExportWorkflow.ExportResult call() throws Exception {
                        List<Order> ordersWithStickers = supplyLoadWorkflow.enrichStickers(shop, exportOrders);
                        return orderExportWorkflow.export(
                                new OrderExportWorkflow.ExportRequest(
                                        shop,
                                        supplyId,
                                        supplyName,
                                        ordersWithStickers,
                                        printOptions.get(),
                                        file,
                                        orderDetailsFile
                                )
                        );
                    }
                };

                exportTask.setOnRunning(ev -> {
                    markShopRunning(shop.getId(), true);
                    if (supplyDetailController != null) {
                        supplyDetailController.setStickerLoading(true, i18nService.tr("supply.preparing_pdf"));
                    }
                });
                exportTask.setOnFailed(ev -> {
                    markShopRunning(shop.getId(), false);
                    if (supplyDetailController != null) {
                        supplyDetailController.setStickerLoading(false);
                    }
                    Throwable ex = exportTask.getException();
                    LOGGER.error("Export thất bại cho shop {}", shop.getId(), ex);
                    AlertService.showError(ex.getMessage());
                });
                exportTask.setOnSucceeded(ev -> {
                    markShopRunning(shop.getId(), false);
                    OrderExportWorkflow.ExportResult result = exportTask.getValue();
                    state.setLoadedOrdersRaw(result.exportedOrders());
                    applySortAndDisplayOrders();
                    clearKizDraft();
                    tryOpenFile(orderDetailsFile);
                    tryOpenFile(file);
                    enqueueBackgroundKizAttachment(shop, supplyId, supplyName, result);
                    if (isPrintHistoryVisible()) {
                        refreshPrintHistory();
                    }
                    if (isPackingVisible()) {
                        refreshPackingView();
                    }
                });
                AppTaskExecutor.execute(exportTask);
            });
        });
        AppTaskExecutor.execute(checkTask);
    }

    public void onSettings(ActionEvent event) {
        printTemplateDesignerService.showDialog();
    }

    public void onUpdateShop(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        shopWorkflow.requestUpdateShop(shop).ifPresent(updated -> {
            shopWorkflow.updateShop(shop.getId(), updated);
            shop.setName(updated.getName());
            shop.setClientId(updated.getClientId());
            if (updated.getApiKey() != null && !updated.getApiKey().isBlank()) {
                shop.setApiKey(updated.getApiKey());
            }
            renderShops();
            loadShops();
        });
    }

    public void onDeleteShop(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        int shopId = shop.getId();
        if (isShopBusy(shopId)) {
            AlertService.showWarning(
                    i18nService.tr("workspace.delete_shop.busy.title"),
                    i18nService.tr("workspace.delete_shop.busy.header"),
                    MessageFormat.format(i18nService.tr("workspace.delete_shop.busy.content"), shop.getName())
            );
            return;
        }

        Optional<ButtonType> result = AlertService.showConfirmation(
                i18nService.tr("workspace.delete_shop.confirm.title"),
                MessageFormat.format(i18nService.tr("workspace.delete_shop.confirm.header"), shop.getName()),
                i18nService.tr("workspace.delete_shop.confirm.content")
        );
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                return shopWorkflow.deleteShop(shopId);
            }
        };
        task.setOnRunning(e -> markShopRunning(shopId, true));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            LOGGER.error("Xóa cửa hàng thất bại cho shop {}", shopId, ex);
            markShopRunning(shopId, false);
            AlertService.showError(ex.getMessage());
        });
        task.setOnSucceeded(e -> {
            WbSupplyWorkflow.clearImageCache();
            activityTracker.clear(shopId);
            Shop fallback = state.removeShopAndChooseFallback(shopId);
            FinanceSyncScheduler.getInstance().removeShop(shopId, true);
            if (fallback == null) {
                state.clearWorkspace();
                clearWorkspaceView();
                ConfigService.setLastSelectedShopId(null);
                renderShops();
            } else {
                selectShop(fallback);
            }
        });
        AppTaskExecutor.execute(task);
    }

    public void onSyncWildberries(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop != null) {
            if (shop.getMarketplace() == Marketplace.OZON) {
                showOzonDashboard(false);
                ozonDashboardController.sync();
                return;
            }
            if (!state.isSelectedShopTokenValid()) {
                AlertService.showWarning(
                        i18nService.tr("wb.token.title"),
                        i18nService.tr("workspace.sync.token_invalid"),
                        state.getSelectedShopTokenMessage()
                );
                return;
            }
            startShopSync(shop, true);
        }
    }

    private void loadShops() {
        Task<List<Shop>> task = new Task<>() {
            @Override
            protected List<Shop> call() throws Exception {
                return shopWorkflow.loadShops();
            }
        };
        task.setOnSucceeded(e -> {
            state.setShops(task.getValue());
            FinanceSyncScheduler.getInstance().updateShops(task.getValue());
            renderShops();
            if (state.getShops().isEmpty()) {
                state.clearWorkspace();
                clearWorkspaceView();
                ConfigService.setLastSelectedShopId(null);
                return;
            }
            if (state.getPendingSelectShopId() != null) {
                selectShopById(state.getPendingSelectShopId());
                state.setPendingSelectShopId(null);
            } else if (state.getSelectedShop() != null) {
                selectShopById(state.getSelectedShop().getId());
            } else if (ConfigService.getLastSelectedShopId() != null) {
                if (!selectShopByIdIfExists(ConfigService.getLastSelectedShopId())) {
                    selectShop(state.getShops().get(0));
                }
            } else {
                selectShop(state.getShops().get(0));
            }
            if (authorizeInitialZnackShop(state.getSelectedShop())) {
                resumeAllPersistedZnackPipelines();
            }
        });
        task.setOnFailed(e -> AlertService.showError(task.getException().getMessage()));
        AppTaskExecutor.execute(task);
    }

    private void selectShopById(int shopId) {
        selectShopByIdIfExists(shopId);
    }

    private boolean selectShopByIdIfExists(int shopId) {
        Shop shop = state.getShops().stream()
                .filter(item -> item.getId() == shopId)
                .findFirst()
                .orElse(null);
        if (shop != null) {
            selectShop(shop);
            return true;
        }
        return false;
    }

    private void selectShopExplicitly(Shop shop) {
        ZnackSigningSession.authorizeShop(shop.getId());
        selectShop(shop);
        Task<Void> resume = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ZnackRepository repository = new ZnackRepository(
                        new ZnackModels.ShopContext(shop.getId(), shop.getName()));
                ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(repository);
                var settings = repository.getSettings();
                coordinator.resume(settings);
                syncGtinsForWaitingIntroductions(repository, settings);
                coordinator.resumeEligibleIntroductions(settings);
                return null;
            }
        };
        resume.setOnFailed(event -> LOGGER.warn(
                "Could not resume Znack pipelines after explicit shop selection. shopId={}",
                shop.getId(), resume.getException()));
        AppTaskExecutor.execute(resume);
    }

    boolean authorizeInitialZnackShop(Shop shop) {
        if (initialZnackShopAuthorized || shop == null) {
            return false;
        }
        initialZnackShopAuthorized = true;
        ZnackSigningSession.authorizeShop(shop.getId());
        return true;
    }

    private void resumeAllPersistedZnackPipelines() {
        Shop selectedShop = state.getSelectedShop();
        Task<Void> resume = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ZnackPurchaseCoordinator.resumeAllPersisted();
                if (selectedShop != null) {
                    ZnackRepository repository = new ZnackRepository(
                            new ZnackModels.ShopContext(selectedShop.getId(), selectedShop.getName()));
                    var settings = repository.getSettings();
                    syncGtinsForWaitingIntroductions(repository, settings);
                    ZnackPurchaseCoordinator.create(repository).resumeEligibleIntroductionsAsync(settings);
                }
                return null;
            }
        };
        resume.setOnFailed(event -> LOGGER.warn(
                "Could not resume persisted Znack pipelines after selecting the initial shop.",
                resume.getException()));
        AppTaskExecutor.execute(resume);
    }

    private static void syncGtinsForWaitingIntroductions(
            ZnackRepository repository, ZnackModels.Settings settings) throws Exception {
        if (repository.findWaitingIntroductionDocumentPipelines().isEmpty()
                || settings == null
                || settings.signerCertificate() == null
                || settings.signerCertificate().isBlank()
                || settings.signerTestedAt() == null) {
            return;
        }
        ZnackSignatureProvider signer = new CryptoProSignatureProvider(
                settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        new ZnackProductService(api, new ZnackAuthService(api, signer), repository).sync(settings);
        ZnackGtinAutoSync.markAutoSynced(repository.shop().shopId());
    }

    private void selectShop(Shop shop) {
        boolean sharedView = isPrintHistoryVisible() || isZnackAutomationVisible() || isFboSupplyOrdersVisible();
        boolean dashboardVisible = isDashboardVisible();
        state.setSelectedShop(shop);
        shopSidebarController.setMarketplace(shop.getMarketplace());
        workspaceHeaderController.setMarketplace(shop.getMarketplace());
        if (znackAutomationController != null) {
            znackAutomationController.setShop(shop);
        }
        if (znackRegistrationController != null && isZnackRegistrationVisible()) {
            znackRegistrationController.setShop(shop);
        }
        if (kizMappingController != null) {
            kizMappingController.setShop(shop);
        }
        if (fboSupplyOrdersController != null && isFboSupplyOrdersVisible()) {
            fboSupplyOrdersController.setShop(shop, true);
        }
        if (supplyDetailController != null) {
            supplyDetailController.setShop(shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null);
        }
        if (financeDashboardController != null && dashboardVisible) {
            financeDashboardController.setShop(shop);
        }
        ConfigService.setLastSelectedShopId(shop.getId());
        if (gtinSyncController != null) gtinSyncController.setShop(shop);
        renderShops();
        boolean tokenValid = applySelectedShopTokenState(shop, true);
        resetLoadedSupply();
        showWorkspace();
        updateHeaderState();
        if (shop.getMarketplace() == Marketplace.OZON) {
            refreshPackingView();
            if (sharedView) {
                if (isPrintHistoryVisible()) refreshPrintHistory();
                if (isZnackAutomationVisible()) znackAutomationController.setShop(shop);
            } else if (dashboardVisible) {
                showDashboard();
            } else {
                showOzonDashboard(true);
            }
            return;
        }
        if (isOzonDashboardVisible()) {
            showDashboard();
        }
        if (isPrintHistoryVisible()) {
            refreshPrintHistory();
        }
        if (isDashboardVisible()) {
            refreshDashboard(false);
        }
        if (isFboPackingVisible()) {
            refreshFboView();
        }
        refreshSupplyList();
        refreshPackingView();
        if (!tokenValid) {
            return;
        }
        supplyListController.setLoading(true);
        startShopSync(shop, false);
    }

    private void startShopSync(Shop shop, boolean manual) {
        if (shop == null) {
            return;
        }
        if (shop.getMarketplace() != Marketplace.WILDBERRIES) {
            if (manual && ozonDashboardController != null) ozonDashboardController.sync();
            return;
        }
        if (!state.isSelectedShopTokenValid()) {
            if (manual) {
                AlertService.showWarning(
                        i18nService.tr("wb.token.title"),
                        i18nService.tr("workspace.sync.token_invalid"),
                        state.getSelectedShopTokenMessage()
                );
            }
            return;
        }
        boolean started = activityTracker.markSyncStarted(shop.getId());
        if (isCurrentShop(shop.getId())) {
            if (!manual) {
                supplyListController.setLoading(true);
            }
            updateHeaderState();
        }
        if (!started) {
            return;
        }
        markProductKizSyncing(shop.getId(), true);

        Task<WbSyncReport> task = new Task<>() {
            @Override
            protected WbSyncReport call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(shop.getId(),
                        () -> manual ? wbSyncWorkflow.syncAll(shop) : wbSyncWorkflow.syncOverview(shop));
            }
        };
        task.setOnRunning(e -> {
            if (isCurrentShop(shop.getId()) && !manual) {
                supplyListController.setLoading(true);
            }
        });
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            markProductKizSyncing(shop.getId(), false);
            Throwable ex = task.getException();
            if (ShopOperationCoordinator.isShopUnavailable(ex)) return;
            LOGGER.error("Đồng bộ WB thất bại cho shop {}", shop.getId(), ex);
            refreshSupplyListIfCurrent(shop.getId());
            if (manual && isCurrentShop(shop.getId())) {
                AlertService.showError(ex.getMessage());
            }
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            markProductKizSyncing(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
            }
            refreshSupplyListIfCurrent(shop.getId());
            if (isPackingVisible()) {
                refreshPackingView();
            }
            if (isFboPackingVisible()) {
                refreshFboView();
            }
            if (isDashboardVisible()) {
                refreshDashboard(false);
            }
            if (isKizMappingVisible()) {
                refreshKizMappingView();
            }
        });
        AppTaskExecutor.execute(task);
    }

    private void refreshSupplyList() {
        refreshSupplyList(state.getLoadedSupplyId(), true);
    }

    private void refreshSupplyList(String supplyIdToRestore) {
        refreshSupplyList(supplyIdToRestore, true);
    }

    private void refreshSupplyList(String supplyIdToRestore, boolean notifySelection) {
        Shop shop = state.getSelectedShop();
        if (shop == null || shop.getMarketplace() != Marketplace.WILDBERRIES) {
            clearSupplyViews();
            updateHeaderState();
            return;
        }
        supplyListController.setLoading(false);
        List<WbSupplySummary> supplies = wbSupplyWorkflow.getSupplies(shop.getId()).stream()
                .filter(supply -> !supply.isDone())
                .toList();
        if (notifySelection) {
            supplyListController.setSupplies(supplies);
        } else {
            supplyListController.refreshSupplies(supplies, supplyIdToRestore);
        }
        if (supplyIdToRestore != null && supplies.stream().anyMatch(supply -> supplyIdToRestore.equals(supply.getSupplyId()))) {
            if (notifySelection) {
                supplyListController.selectSupply(supplyIdToRestore);
            }
        } else {
            resetLoadedSupply();
            supplyDetailController.showEmptyState("", "");
        }
        updateHeaderState();
    }

    private void loadSupply(WbSupplySummary supply) {
        Shop shop = requireSelectedShop();
        if (shop == null || shop.getMarketplace() != Marketplace.WILDBERRIES || supply == null) {
            return;
        }
        resetLoadedSupply();
        clearKizDraft();
        loadedSupplySummary = supply;
        long requestToken = state.nextSupplyRequestToken();
        state.setLoadedSupplyId(supply.getSupplyId());
        state.setLoadedSupplyName(supply.getName());
        supplyDetailController.setLoading(true);
        refreshCurrentKizAttachmentProgress();
        supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");
        supplyDetailController.syncGtinInventoryOnSupplyOpen();

        Task<List<Order>> localTask = new Task<>() {
            @Override
            protected List<Order> call() throws Exception {
                return supplyLoadWorkflow.loadLocal(shop, supply.getSupplyId());
            }
        };
        localTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.setLoading(false);
            LOGGER.error("Không thể mở supply {}", supply.getSupplyId(), localTask.getException());
            supplyDetailController.showEmptyState("", "");
            AlertService.showError(localTask.getException().getMessage());
        });
        localTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            state.setLoadedOrdersRaw(localTask.getValue());
            supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");
            startSupplyRefresh(shop, supply, requestToken);
        });
        AppTaskExecutor.execute(localTask);
    }

    private void startSupplyRefresh(Shop shop, WbSupplySummary supply, long requestToken) {
        Task<List<Order>> refreshTask = new Task<>() {
            @Override
            protected List<Order> call() throws Exception {
                if (supplyLoadWorkflow.hasMissingProducts(shop, supply.getSupplyId())) {
                    updateMessage(i18nService.tr("supply.loading_missing_products"));
                }
                return supplyLoadWorkflow.refreshSupplyData(shop, supply.getSupplyId());
            }
        };
        refreshTask.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            if (newMessage != null && !newMessage.isBlank()) {
                supplyDetailController.setLoading(true, newMessage);
            }
        });
        refreshTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.setLoading(false);
            applySortAndDisplayOrders();
            updateExportAvailability();
            refreshCurrentKizAttachmentProgress();
            LOGGER.warn("Không thể refresh supply {} ở nền", supply.getSupplyId(), refreshTask.getException());
            String message = refreshTask.getException() == null ? "" : refreshTask.getException().getMessage();
            if (message != null && message.startsWith("Sản phẩm không tồn tại trên WB:")) {
                AlertService.showWarning(
                        i18nService.tr("workspace.product_not_found.title"),
                        i18nService.tr("workspace.product_not_found.header"),
                        message
                );
            }
        });
        refreshTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.setLoading(false);
            state.setLoadedOrdersRaw(refreshTask.getValue());
            applySortAndDisplayOrders();
            supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");
            refreshCurrentKizAttachmentProgress();
            if (supplyListController != null) {
                supplyListController.updateSupplySummary(new WbSupplySummary(
                        supply.getSupplyId(),
                        supply.getName(),
                        supply.isDone(),
                        supply.getB2b(),
                        supply.getCreatedAt(),
                        refreshTask.getValue() == null ? 0 : refreshTask.getValue().size()
                ));
            }
            startSilentImageWarmup(shop, supply, requestToken);
        });
        AppTaskExecutor.execute(refreshTask);
    }

    private void startSilentImageWarmup(Shop shop, WbSupplySummary supply, long requestToken) {
        List<Order> orders = state.getLoadedOrdersRaw();
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Task<Void> imageTask = new Task<>() {
            @Override
            protected Void call() {
                supplyLoadWorkflow.ensureImages(orders);
                return null;
            }
        };
        imageTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            LOGGER.debug("Không thể nạp ảnh nền cho supply {}", supply.getSupplyId(), imageTask.getException());
        });
        imageTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.refreshOrders();
        });
        AppTaskExecutor.execute(imageTask);
    }

    private void applySortAndDisplayOrders() {
        if (supplyDetailController == null) {
            return;
        }
        OrderSortOptions options = supplyDetailController.getSortOptions();
        List<Order> sortedOrders = orderSortingService.sort(state.getLoadedOrdersRaw(), options);
        state.setDisplayedOrders(sortedOrders);
        supplyDetailController.setOrders(sortedOrders);
        updateExportAvailability();
    }

    private void initializeSidebar() {
        FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
        VBox root = FxmlViewLoader.load(loader);
        shopSidebarController = loader.getController();
        shopSidebarController.setOnAddShop(() -> onAddShop(new ActionEvent()));
        shopSidebarController.setOnOpenSettings(() -> onSettings(new ActionEvent()));
        shopSidebarController.setOnDashboard(this::showDashboard);
        shopSidebarController.setOnPacking(this::showPacking);
        shopSidebarController.setOnFboPacking(this::showFboPacking);
        shopSidebarController.setOnFboOrders(this::showFboSupplyOrders);
        shopSidebarController.setOnKizMapping(this::showKizMapping);
        shopSidebarController.setOnGtinSync(this::showGtinSync);
        shopSidebarController.setOnZnackAutomation(this::showZnackAutomation);
        shopSidebarController.setOnZnackRegistration(this::showZnackRegistration);
        shopSidebarController.setOnPrintHistory(this::showPrintHistory);
        shopSidebarController.setOnCheckVersion(this::checkVersionManually);
        shopSidebarController.setOnPersonalEdition(this::showPersonalEdition);
        shopSidebarController.setOnAbout(this::showAboutDialog);
        shopSidebarController.setOnLanguageChanged(i18nService::setLanguage);
        shopSidebarController.setSelectedLanguage(i18nService.getCurrentLanguage());
        shopSidebarController.setOnThemeChanged(ThemeService::switchTheme);
        shopSidebarController.setSelectedTheme(ThemeService.getCurrentTheme().getKey());
        shopSidebarController.applyTranslations();
        sidebarContainer.getChildren().setAll(root);
    }

    private void initializeHeader() {
        FXMLLoader loader = FxmlViewLoader.loader(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        HBox root = FxmlViewLoader.load(loader);
        workspaceHeaderController = loader.getController();
        workspaceHeaderController.setOnSync(() -> onSyncWildberries(new ActionEvent()));
        workspaceHeaderController.setOnEditShop(() -> onUpdateShop(new ActionEvent()));
        workspaceHeaderController.setOnDeleteShop(() -> onDeleteShop(new ActionEvent()));
        workspaceHeaderController.setOnShopSelected(this::selectShopExplicitly);
        workspaceHeaderController.applyTranslations();
        headerContainer.getChildren().setAll(root);
    }

    private void initializeSupplyViews() {
        FXMLLoader supplyListLoader = FxmlViewLoader.loader(SupplyListController.class, "supply-list-view.fxml");
        VBox supplyListRoot = FxmlViewLoader.load(supplyListLoader);
        supplyListController = supplyListLoader.getController();
        supplyListController.setOnSupplySelected(this::loadSupply);
        supplyListController.setOnRefetchRequested(this::onRefetchSupplies);
        supplyListController.setOnDeleteRequested(this::deleteEmptySupply);
        supplyListController.applyTranslations();
        supplyManagementController.supplyListContainer.getChildren().setAll(supplyListRoot);

        FXMLLoader supplyDetailLoader = FxmlViewLoader.loader(SupplyDetailController.class, "supply-detail-view.fxml");
        VBox supplyDetailRoot = FxmlViewLoader.load(supplyDetailLoader);
        supplyDetailController = supplyDetailLoader.getController();
        supplyDetailController.setSortOptions(orderSortPreferenceService.load());
        supplyDetailController.setOnSortOptionsChanged(options -> {
            orderSortPreferenceService.save(options);
            applySortAndDisplayOrders();
        });
        supplyDetailController.setOnPrint(() -> onExport(new ActionEvent()));
        supplyDetailController.setOnBack(this::showPacking);
        supplyDetailController.setOnDeliver(this::deliverLoadedSupply);
        supplyDetailController.applyTranslations();
        supplyDetailController.setShop(state.getSelectedShop());
        supplyManagementController.supplyDetailContainer.getChildren().setAll(supplyDetailRoot);
    }

    private void renderShops() {
        workspaceHeaderController.setShops(state.getShops(), state.getSelectedShop());
    }

    private void applyTranslations() {
        if (disposed) {
            return;
        }
        if (gtinSyncController != null) gtinSyncController.applyTranslations();
        if (shopSidebarController != null) {
            shopSidebarController.setSelectedLanguage(i18nService.getCurrentLanguage());
            shopSidebarController.setSelectedTheme(ThemeService.getCurrentTheme().getKey());
            shopSidebarController.applyTranslations();
        }
        if (workspaceHeaderController != null) {
            workspaceHeaderController.applyTranslations();
        }
        if (packingController != null) {
            packingController.applyTranslations();
        }
        if (financeDashboardController != null) {
            financeDashboardController.applyTranslations();
        }
        if (ozonDashboardController != null) {
            ozonDashboardController.applyTranslations();
        }
        if (fboPackingController != null) {
            fboPackingController.applyTranslations();
        }
        if (fboSupplyOrdersController != null) {
            fboSupplyOrdersController.applyTranslations();
        }
        if (kizMappingController != null) {
            kizMappingController.applyTranslations();
        }
        if (znackAutomationController != null) {
            znackAutomationController.applyTranslations();
        }
        if (znackRegistrationController != null) {
            znackRegistrationController.applyTranslations();
        }
        if (printHistoryController != null) {
            printHistoryController.applyTranslations();
        }
        if (supplyListController != null) {
            supplyListController.applyTranslations();
        }
        if (supplyDetailController != null) {
            supplyDetailController.applyTranslations();
            if (state.getLoadedSupplyId() == null) {
                supplyDetailController.setSupplyInfo(
                        i18nService.tr("supply.not_selected"),
                        i18nService.tr("supply.select_prompt")
                );
            }
        }
    }

    private void clearWorkspaceView() {
        contentPane.setVisible(false);
        if (fboSupplyOrdersController != null) {
            fboSupplyOrdersController.setShop(null, false);
        }
        clearSupplyViews();
        updateHeaderState();
    }

    private void clearSupplyViews() {
        if (supplyListController != null) {
            supplyListController.setSupplies(List.of());
            supplyListController.setLoading(false);
        }
        if (supplyDetailController != null) {
            supplyDetailController.setShop(null);
            supplyDetailController.showEmptyState("", "");
            supplyDetailController.setLoading(false);
            refreshCurrentKizAttachmentProgress();
        }
        if (packingController != null) {
            packingController.setShop(null, false);
        }
        if (financeDashboardController != null) {
            financeDashboardController.setShop(null);
        }
        if (ozonDashboardController != null) {
            ozonDashboardController.setShop(null, false);
        }
        if (kizMappingController != null) {
            kizMappingController.setShop(null);
        }
        if (znackAutomationController != null) {
            znackAutomationController.setShop(null);
        }
        if (gtinSyncController != null) gtinSyncController.setShop(null);
        resetLoadedSupply();
    }

    private void showWorkspace() {
        contentPane.setVisible(true);
        clearKizDraft();
        supplyDetailController.showEmptyState("", "");
    }

    private void resetLoadedSupply() {
        loadedSupplySummary = null;
        state.clearLoadedSupply();
        if (supplyDetailController != null) {
            supplyDetailController.setLoading(false);
            refreshCurrentKizAttachmentProgress();
            supplyDetailController.setSupplyInfo("", "");
            supplyDetailController.setDeliverEnabled(false);
            supplyDetailController.setOrders(List.of());
        }
        clearKizDraft();
        updateExportAvailability();
    }

    private void refreshSupplyListIfCurrent(int shopId) {
        if (isCurrentShop(shopId)) {
            refreshSupplyList();
        }
    }

    private void onRefetchSupplies() {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        if (!state.isSelectedShopTokenValid()) {
            AlertService.showWarning(
                    i18nService.tr("wb.token.title"),
                    i18nService.tr("workspace.refetch.token_invalid"),
                    state.getSelectedShopTokenMessage()
            );
            return;
        }
        startSupplyListRefetch(shop);
    }

    private void deleteEmptySupply(WbSupplySummary supply) {
        Shop shop = requireSelectedShop();
        if (shop == null || shop.getMarketplace() != Marketplace.WILDBERRIES || supply == null
                || supply.isDone() || supply.getItemCount() != 0 || isShopBusy(shop.getId())) {
            return;
        }
        Optional<ButtonType> confirmation = AlertService.showConfirmation(
                i18nService.tr("supply_list.delete.confirm.title"),
                i18nService.tr("supply_list.delete.confirm.header"),
                MessageFormat.format(i18nService.tr("supply_list.delete.confirm.content"), supply.getSupplyId()));
        if (confirmation.isEmpty() || confirmation.get() != ButtonType.OK) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                    wbSupplyWorkflow.deleteEmptySupply(shop, supply.getSupplyId());
                    return null;
                });
            }
        };
        task.setOnRunning(event -> {
            markShopRunning(shop.getId(), true);
            if (isCurrentShop(shop.getId())) supplyListController.setLoading(true);
        });
        task.setOnSucceeded(event -> {
            markShopRunning(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList();
                if (isPackingVisible()) refreshPackingView();
            }
        });
        task.setOnFailed(event -> {
            markShopRunning(shop.getId(), false);
            Throwable failure = task.getException();
            LOGGER.error("Không thể xoá supply rỗng {}", supply.getSupplyId(), failure);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList(supply.getSupplyId());
                AlertService.showError(failure instanceof WbSupplyNotEmptyException
                        ? i18nService.tr("supply_list.delete.not_empty")
                        : i18nService.tr("supply_list.delete.failed"));
            }
        });
        AppTaskExecutor.execute(task);
    }

    private void startSupplyListRefetch(Shop shop) {
        if (shop == null) {
            return;
        }
        boolean started = activityTracker.markSyncStarted(shop.getId());
        if (isCurrentShop(shop.getId())) {
            supplyListController.setLoading(true);
            updateHeaderState();
        }
        if (!started) {
            return;
        }

        String selectedSupplyId = state.getLoadedSupplyId();
        Task<WbSyncReport> task = new Task<>() {
            @Override
            protected WbSyncReport call() throws Exception {
                return wbSyncWorkflow.refetchSupplies(shop);
            }
        };
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            Throwable ex = task.getException();
            LOGGER.error("Refetch supply thất bại cho shop {}", shop.getId(), ex);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList(selectedSupplyId);
                AlertService.showError(ex.getMessage());
            }
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList(selectedSupplyId);
                if (isPackingVisible()) {
                    refreshPackingView();
                }
            }
        });
        AppTaskExecutor.execute(task);
    }

    private boolean isCurrentShop(int shopId) {
        return state.getSelectedShop() != null && state.getSelectedShop().getId() == shopId;
    }

    private boolean isPrintHistoryVisible() {
        return dynamicContentContainer.getChildren().contains(printHistoryView);
    }

    private boolean isDashboardVisible() {
        return financeDashboardView != null && dynamicContentContainer.getChildren().contains(financeDashboardView);
    }

    private boolean isOzonDashboardVisible() {
        return ozonDashboardView != null && dynamicContentContainer.getChildren().contains(ozonDashboardView);
    }

    private boolean isZnackAutomationVisible() {
        return znackAutomationView != null && dynamicContentContainer.getChildren().contains(znackAutomationView);
    }

    private boolean isZnackRegistrationVisible() {
        return znackRegistrationView != null && dynamicContentContainer.getChildren().contains(znackRegistrationView);
    }

    private boolean isOzonSelected() {
        Shop shop = state.getSelectedShop();
        return shop != null && shop.getMarketplace() == Marketplace.OZON;
    }

    private boolean isPackingVisible() {
        return dynamicContentContainer.getChildren().contains(packingView);
    }

    private boolean isFboPackingVisible() {
        return dynamicContentContainer.getChildren().contains(fboPackingView);
    }

    private boolean isFboSupplyOrdersVisible() {
        return fboSupplyOrdersView != null && dynamicContentContainer.getChildren().contains(fboSupplyOrdersView);
    }

    private boolean isKizMappingVisible() {
        return dynamicContentContainer.getChildren().contains(kizMappingView);
    }

    private boolean isCurrentSupplyRequest(int shopId, String supplyId, long requestToken) {
        return isCurrentShop(shopId)
                && Objects.equals(state.getLoadedSupplyId(), supplyId)
                && state.getSupplyRequestToken() == requestToken;
    }

    private Shop requireSelectedShop() {
        if (state.getSelectedShop() == null) {
            AlertService.showError(i18nService.tr("workspace.error.select_shop"));
            return null;
        }
        return state.getSelectedShop();
    }

    private boolean applySelectedShopTokenState(Shop shop, boolean showWarning) {
        if (shop != null && shop.getMarketplace() == Marketplace.OZON) {
            boolean configured = shop.isCredentialConfigured();
            state.setSelectedShopTokenValid(configured);
            state.setSelectedShopTokenMessage(configured ? null : "Ozon Client ID and API key are required.");
            return configured;
        }
        WbTokenInspector.TokenStatus status = WbTokenInspector.inspect(shop);
        state.setSelectedShopTokenValid(status.valid());
        state.setSelectedShopTokenMessage(status.message());
        if (!status.valid() && showWarning && status.message() != null && !status.message().isBlank()) {
            AlertService.showWarning(
                    i18nService.tr("wb.token.title"),
                    i18nService.tr("workspace.token.update_required"),
                    status.message()
            );
        }
        return status.valid();
    }

    private void markShopRunning(int shopId, boolean running) {
        activityTracker.markRunning(shopId, running);
        if (isCurrentShop(shopId)) {
            updateHeaderState();
        }
    }

    private void markProductKizSyncing(int shopId, boolean syncing) {
        if (syncing) {
            productKizSyncingShopIds.add(shopId);
        } else {
            productKizSyncingShopIds.remove(shopId);
        }
        if (isCurrentShop(shopId)) {
            updateHeaderState();
        }
    }

    private void updateHeaderState() {
        Shop selectedShop = state.getSelectedShop();
        boolean hasShop = selectedShop != null;
        boolean running = hasShop && isShopBusy(selectedShop.getId());
        workspaceHeaderController.setBusy(running);
        workspaceHeaderController.setProductKizSyncLoading(hasShop && productKizSyncingShopIds.contains(selectedShop.getId()));
        boolean tokenValid = !hasShop || state.isSelectedShopTokenValid();
        workspaceHeaderController.setControls(hasShop, running, canExport(), tokenValid);
        if (supplyDetailController != null) {
            supplyDetailController.setPrintEnabled(canExport());
            supplyDetailController.setDeliverEnabled(canDeliverLoadedSupply());
        }
        if (supplyListController != null) {
            supplyListController.setRefetchEnabled(hasShop && selectedShop.getMarketplace() == Marketplace.WILDBERRIES
                    && tokenValid);
        }
    }

    private void updateExportAvailability() {
        updateHeaderState();
    }

    private boolean canExport() {
        Shop shop = state.getSelectedShop();
        boolean running = shop != null && isShopBusy(shop.getId());
        return shop != null
                && shop.getMarketplace() == Marketplace.WILDBERRIES
                && state.getLoadedSupplyId() != null
                && !state.getDisplayedOrders().isEmpty()
                && !running
                && state.isSelectedShopTokenValid();
    }

    private boolean canDeliverLoadedSupply() {
        Shop shop = state.getSelectedShop();
        boolean running = shop != null && isShopBusy(shop.getId());
        return shop != null
                && shop.getMarketplace() == Marketplace.WILDBERRIES
                && loadedSupplySummary != null
                && !running
                && state.isSelectedShopTokenValid()
                && !kizAttachmentCoordinator.hasActiveJobForSupply(shop.getId(), loadedSupplySummary.getSupplyId())
                && packingWorkflow.canDeliver(shop.getId(), loadedSupplySummary);
    }

    private void initializeBackgroundKizProgress() {
        kizAttachmentCoordinator.addListener(kizProgressListener);
    }

    private void onKizAttachmentProgress(KizAttachmentCoordinator.KizAttachmentProgress progress) {
        if (disposed) {
            return;
        }
        if (progress == null) {
            return;
        }
        updateHeaderState();
        if (matchesCurrentSupply(progress.shopId(), progress.supplyId()) && supplyDetailController != null) {
            if (progress.active()) {
                supplyDetailController.setStickerLoading(true, progress.message());
            } else {
                supplyDetailController.setStickerLoading(false);
            }
        }
        if (!progress.active()) {
            if (isCurrentShop(progress.shopId())) {
            }
            if (matchesCurrentSupply(progress.shopId(), progress.supplyId())) {
                removeCompletedKizAssignments(progress.successfulKizCodes());
            }
            if (!progress.failures().isEmpty() && isCurrentShop(progress.shopId())) {
                AlertService.showWarning(
                        i18nService.tr("workspace.kiz_incomplete.title"),
                        progress.message(),
                        String.join(System.lineSeparator(), progress.failures())
                );
            }
            if (isCurrentShop(progress.shopId())) {
                refreshSupplyListIfCurrent(progress.shopId());
            }
            updateExportAvailability();
        }
    }

    private void removeCompletedKizAssignments(List<String> successfulKizCodes) {
        if (successfulKizCodes == null || successfulKizCodes.isEmpty()) {
            return;
        }
        List<Order> orders = state.getLoadedOrdersRaw();
        if (orders == null || orders.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Order order : orders) {
            if (order == null || order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }
            if (successfulKizCodes.contains(order.getKiz())) {
                order.setKiz(null);
                changed = true;
            }
        }
        if (changed) {
            applySortAndDisplayOrders();
        }
    }

    private void enqueueBackgroundKizAttachment(Shop shop,
                                                String supplyId,
                                                String supplyName,
                                                OrderExportWorkflow.ExportResult result) {
        if (result == null || result.kizAttachments() == null || result.kizAttachments().isEmpty()) {
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(false);
            }
            return;
        }
        kizAttachmentCoordinator.enqueue(shop, supplyId, supplyName, result.kizAttachments());
    }

    private void refreshCurrentKizAttachmentProgress() {
        if (supplyDetailController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        String supplyId = state.getLoadedSupplyId();
        if (shop == null || supplyId == null || supplyId.isBlank()) {
            supplyDetailController.setStickerLoading(false);
            return;
        }
        kizAttachmentCoordinator.findActiveJobForSupply(shop.getId(), supplyId)
                .ifPresentOrElse(
                        progress -> supplyDetailController.setStickerLoading(true, progress.message()),
                        () -> supplyDetailController.setStickerLoading(false)
                );
    }

    private boolean matchesCurrentSupply(int shopId, String supplyId) {
        return isCurrentShop(shopId) && Objects.equals(state.getLoadedSupplyId(), supplyId);
    }

    private boolean isShopBusy(int shopId) {
        return activityTracker.isRunning(shopId) || kizAttachmentCoordinator.hasActiveJobForShop(shopId);
    }

    private void clearKizDraft() {
        // KIZ assignment is driven by nmId mapping; no manual draft is kept.
    }

    private void deliverLoadedSupply() {
        Shop shop = requireSelectedShop();
        WbSupplySummary supply = loadedSupplySummary;
        if (shop == null || supply == null || !state.isSelectedShopTokenValid()) {
            return;
        }
        ButtonType deliver = new ButtonType(i18nService.tr("supply.deliver"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                MessageFormat.format(i18nService.tr("workspace.deliver.confirm.content"), supply.getSupplyId()),
                deliver,
                ButtonType.CANCEL);
        AlertService.applyTheme(confirm);
        confirm.setHeaderText(i18nService.tr("workspace.deliver.confirm.header"));
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != deliver) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                packingWorkflow.deliverSupply(shop, supply);
                return null;
            }
        };
        task.setOnRunning(e -> markShopRunning(shop.getId(), true));
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            LOGGER.error("Không thể chuyển supply {} sang giao hàng", supply.getSupplyId(), task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            refreshSupplyList();
            showPacking();
        });
        AppTaskExecutor.execute(task);
    }

    private String formatSupplyTitle(WbSupplySummary supply) {
        return MessageFormat.format(i18nService.tr("supply.title"), supply.getSupplyId());
    }

    private void tryOpenFile(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            LOGGER.error("Không thể mở file {}", file, ex);
        }
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (gtinSyncController != null) gtinSyncController.dispose();
        if (supplyDetailController != null) {
            supplyDetailController.dispose();
        }
        FboSupplyExecutor.shutdown();
        i18nService.removeListener(languageListener);
        kizAttachmentCoordinator.removeListener(kizProgressListener);
    }
}
