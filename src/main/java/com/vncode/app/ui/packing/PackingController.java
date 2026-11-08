package com.vncode.app.ui.packing;

import com.vncode.app.features.packing.PackingWorkflow;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.wb.WbSupplySummary;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.FriendlyErrorService;
import com.vncode.app.shared.I18nService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.PauseTransition;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class PackingController {
    private final com.vncode.app.shared.TableRows.Tracked<Order,Long> renderedOrders=new com.vncode.app.shared.TableRows.Tracked<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(PackingController.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final PackingWorkflow packingWorkflow = new PackingWorkflow();
    private final Set<Long> selectedOrderIds = new LinkedHashSet<>();
    private final Set<String> selectedCategories = new LinkedHashSet<>();
    private final CheckBox selectAllCheckBox = new CheckBox();
    private List<Order> allNewOrders = List.of();
    private boolean updatingCategoryMenu;

    @FXML private ProgressIndicator refreshLoading;
    @FXML private Button refreshButton;
    @FXML private Label titleLabel;
    @FXML private Button clearFiltersButton;
    @FXML private TabPane packingTabPane;
    @FXML private Tab newOrdersTab;
    @FXML private Tab preparationTab;
    @FXML private Tab dispatchTab;
    @FXML private Tab debtTab;
    @FXML private Button debtBackButton, debtRefreshButton;
    @FXML private Label debtHeading, debtWarning;
    @FXML private ProgressIndicator debtLoading;
    @FXML private TableView<com.vncode.app.integration.wb.WbDebtShipmentService.Shipment> debtShipmentsTable;
    @FXML private TableColumn<com.vncode.app.integration.wb.WbDebtShipmentService.Shipment, String> debtShipmentNameTC, debtShipmentStatusTC, debtShipmentDateTC;
    @FXML private TableColumn<com.vncode.app.integration.wb.WbDebtShipmentService.Shipment, Number> debtShipmentCountTC;
    @FXML private TableView<Order> debtOrdersTable;
    @FXML private TableColumn<Order, String> debtOrderDateTC, debtProductTC, debtPriceTC, debtOrderStatusTC;
    @FXML private TableColumn<Order, byte[]> debtImageTC;
    @FXML private TableColumn<Order, Void> debtPrintTC;
    private final com.vncode.app.integration.wb.WbDebtShipmentService debtService = new com.vncode.app.integration.wb.WbDebtShipmentService();
    private String openDebtShipmentId;
    private long debtEpoch;
    private boolean debtBusy;
    private final Set<Long> pendingDebtPrints = new LinkedHashSet<>();
    @FXML private FlowPane selectionActionBar;
    @FXML private Label selectedCountLabel;
    @FXML private Label emptyNewOrdersLabel;
    private Label newOrdersEmptyPlaceholder;
    @FXML private Button newShipmentButton;
    @FXML private Button addToShipmentButton;
    @FXML private TextField newOrderSearchField;
    @FXML private MenuButton categoryFilterMenuButton;
    @FXML private TableView<Order> newOrdersTable;
    @FXML private TableColumn<Order, Boolean> selectedTC;
    @FXML private TableColumn<Order, String> orderDateTC;
    @FXML private TableColumn<Order, byte[]> imageTC;
    @FXML private TableColumn<Order, String> productTC;
    @FXML private TableColumn<Order, String> priceTC;
    @FXML private TableView<WbSupplySummary> preparationTable;
    @FXML private TableColumn<WbSupplySummary, String> preparationSupplyTC;
    @FXML private TableColumn<WbSupplySummary, String> preparationStatusTC;
    @FXML private TableColumn<WbSupplySummary, Number> preparationCountTC;
    @FXML private TableColumn<WbSupplySummary, String> preparationCreatedTC;
    @FXML private TableView<WbSupplySummary> dispatchTable;
    @FXML private javafx.scene.control.Button dispatchMoreButton;
    private int dispatchOffset;
    private boolean dispatchLoading;
    private long dispatchEpoch;
    @FXML private TableColumn<WbSupplySummary, String> dispatchSupplyTC;
    @FXML private TableColumn<WbSupplySummary, String> dispatchStatusTC;
    @FXML private TableColumn<WbSupplySummary, Number> dispatchCountTC;
    @FXML private TableColumn<WbSupplySummary, String> dispatchCreatedTC;
    @FXML private TableColumn<WbSupplySummary, Void> dispatchActionTC;

    private Shop shop;
    private boolean tokenValid;
    private Consumer<WbSupplySummary> onPrintSupply;
    private PauseTransition delayTransition;
    private long shopGeneration;
    private long localGeneration;
    private boolean disposed;
    private final com.vncode.app.shared.ShopReadCoordinator reads=com.vncode.app.shared.ShopReadCoordinator.shared();
    private final com.vncode.app.shared.ThumbnailLoader thumbnails=new com.vncode.app.shared.ThumbnailLoader();

    @FXML
    private void initialize() {
        delayTransition = new PauseTransition(javafx.util.Duration.millis(800));
        delayTransition.setOnFinished(event -> refreshFromWb());

        setupNewOrdersTable();
        setupNewOrderFilters();
        setupSupplyTables();
        setupDebtTables();
        setupTabs();
        applyTranslations();
        updateSelectionState();
    }

    public void setOnPrintSupply(Consumer<WbSupplySummary> onPrintSupply) {
        this.onPrintSupply = onPrintSupply;
    }

    public void setShop(Shop shop, boolean tokenValid) {
        renderedOrders.clear();shopGeneration++;
        debtEpoch++; debtBusy=false; pendingDebtPrints.clear(); openDebtShipmentId=null; debtShipmentsTable.getItems().clear(); debtOrdersTable.getItems().clear(); debtRefreshButton.setDisable(false);
        debtLoading.setVisible(false); debtLoading.setManaged(false); debtWarning.setVisible(false); debtWarning.setManaged(false); showDebtShipmentList();
        dispatchEpoch++;dispatchLoading=false;dispatchOffset=0;dispatchTable.getItems().clear();dispatchMoreButton.setDisable(true);
        this.shop = shop;
        this.tokenValid = tokenValid;
        selectedOrderIds.clear();
        setLoading(false);
        if (delayTransition != null) {
            delayTransition.stop();
        }
        if (shop == null) {
            setBoard(new PackingWorkflow.PackingBoard(List.of(), List.of(), List.of()));
            return;
        }
        if (packingTabPane.getSelectionModel().getSelectedItem() == debtTab) onRefreshDebt();
        else refresh();
    }

    @FXML private void onLoadMoreDispatch() {
        if(shop==null||dispatchLoading)return;Shop owner=shop;long generation=shopGeneration;long epoch=dispatchEpoch;int offset=dispatchOffset;
        dispatchLoading=true;dispatchMoreButton.setDisable(true);
        Task<com.vncode.app.integration.wb.WbSupplyRepository.SupplyPage> task=new Task<>(){
            protected com.vncode.app.integration.wb.WbSupplyRepository.SupplyPage call(){return packingWorkflow.loadDispatchPage(owner,offset);}
        };
        task.setOnSucceeded(e->{if(!isCurrent(owner,generation)||epoch!=dispatchEpoch||offset!=dispatchOffset)return;dispatchLoading=false;
            dispatchTable.getItems().addAll(task.getValue().items());dispatchOffset+=task.getValue().items().size();
            dispatchMoreButton.setDisable(dispatchOffset>=task.getValue().totalItems());});
        java.util.function.Consumer<Throwable> taskFailure = rejection ->{if(!isCurrent(owner,generation)||epoch!=dispatchEpoch||offset!=dispatchOffset)return;dispatchLoading=false;dispatchMoreButton.setDisable(false);AlertService.showError(FriendlyErrorService.format(rejection));};
        task.setOnFailed(e -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    @FXML
    private void onRefresh() {
        refreshFromWb();
    }

    @FXML
    private void onSelectAll() {
        selectedOrderIds.clear();
        if (selectAllCheckBox.isSelected()) {
            for (Order order : newOrdersTable.getItems()) {
                if (order.getId() != null) {
                    selectedOrderIds.add(order.getId());
                }
            }
        }
        newOrdersTable.refresh();
        updateSelectionState();
    }

    @FXML
    private void onNewShipment() {
        if (!ensureCanWrite()) {
            return;
        }
        List<Long> orderIds = selectedOrderIds.stream().toList();
        TextInputDialog dialog = new TextInputDialog(packingWorkflow.defaultShipmentName());
        AlertService.applyTheme(dialog);
        I18nService i18n = I18nService.getInstance();
        dialog.setTitle(i18n.tr("packing.dialog.new_shipment.title"));
        dialog.setHeaderText(i18n.tr("packing.dialog.new_shipment.header"));
        dialog.setContentText(i18n.tr("packing.dialog.new_shipment.content"));
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim)
                .filter(name -> !name.isBlank())
                .ifPresent(name -> {
                    Shop selectedShop = shop;
                    runWriteTask(selectedShop,
                            () -> packingWorkflow.createShipment(selectedShop, name, orderIds));
                });
    }

    @FXML
    private void onAddToShipment() {
        if (!ensureCanWrite()) {
            return;
        }
        List<WbSupplySummary> supplies = preparationTable.getItems();
        if (supplies.isEmpty()) {
            I18nService i18n = I18nService.getInstance();
            AlertService.showWarning(i18n.tr("packing.warning.supply.title"), i18n.tr("packing.warning.supply.header"), null);
            return;
        }
        ChoiceDialog<WbSupplySummary> dialog = new ChoiceDialog<>(supplies.get(0), supplies);
        AlertService.applyTheme(dialog);
        I18nService i18n = I18nService.getInstance();
        dialog.setTitle(i18n.tr("packing.dialog.add_to_shipment.title"));
        dialog.setHeaderText(i18n.tr("packing.dialog.add_to_shipment.header"));
        dialog.setContentText(i18n.tr("packing.dialog.add_to_shipment.content"));
        dialog.showAndWait().ifPresent(supply -> {
            Shop selectedShop = shop;
            List<Long> orderIds = selectedOrderIds.stream().toList();
            runWriteTask(selectedShop, () -> packingWorkflow.addOrdersToSupply(
                    selectedShop, supply.getSupplyId(), orderIds));
        });
    }

    public void refreshLocalData(){refresh();}

    private void refresh() {
        if (shop == null) {
            return;
        }
        Shop selectedShop = shop;
        long generation = shopGeneration;
        long request=++localGeneration;
        reads.submitLocal(new com.vncode.app.shared.ShopReadCoordinator.Key(selectedShop.getId(),"wb-packing-local",Long.toString(generation)),()->packingWorkflow.loadBoardData(selectedShop)).whenComplete((board,error)->javafx.application.Platform.runLater(()->{
            if(disposed||request!=localGeneration||!isCurrent(selectedShop,generation))return;
            if(error==null)setBoard(board);
        }));
    }

    private void refreshFromWb() {
        if (shop == null || !tokenValid) {
            return;
        }
        Shop selectedShop = shop;
        long generation = shopGeneration;
        Task<PackingWorkflow.PackingBoard> task = new Task<>() {
            @Override
            protected PackingWorkflow.PackingBoard call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(selectedShop.getId(), () -> {
                    packingWorkflow.refreshBoardData(selectedShop);
                    return packingWorkflow.loadBoard(selectedShop);
                });
            }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            setBoard(task.getValue());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            Throwable failure = rejection;
            if (ShopOperationCoordinator.isShopUnavailable(failure)) return;
            LOGGER.error("Не удалось обновить упаковку для shop {}", selectedShop.getId(), failure);
            if (isTimeoutFailure(failure)) {
                refresh();
                return;
            }
            AlertService.showError(FriendlyErrorService.format(failure));
        };
        task.setOnFailed(e -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void runWriteTask(Shop selectedShop, WriteAction action) {
        if (selectedShop == null) return;
        long generation = shopGeneration;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(selectedShop.getId(), () -> {
                    action.run();
                    return null;
                });
            }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            selectedOrderIds.clear();
            refreshFromWb();
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            if (ShopOperationCoordinator.isShopUnavailable(rejection)) return;
            if (rejection instanceof com.vncode.app.integration.wb.WbReadbackPendingException pending) {
                selectedOrderIds.clear();refresh();
                var i18n=I18nService.getInstance();
                AlertService.showWarning(i18n.tr("packing.readback_pending.title"),i18n.tr("packing.readback_pending.header"),i18n.tr("packing.readback_pending.detail")+" " + pending.supplyId());
                return;
            }
            LOGGER.error("WB write action failed", rejection);
            AlertService.showError(FriendlyErrorService.format(rejection));
        };
        task.setOnFailed(e -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void setBoard(PackingWorkflow.PackingBoard board) {
        var retained=new java.util.HashSet<Long>();if(board.newOrders()!=null)for(var row:board.newOrders())if(row.getId()!=null)retained.add(row.getId());selectedOrderIds.retainAll(retained);
        allNewOrders = board.newOrders() == null ? List.of() : List.copyOf(board.newOrders());
        updateCategoryFilterOptions();
        applyNewOrderFilters();
        com.vncode.app.shared.TableRows.merge(preparationTable.getItems(),board.preparationSupplies(),WbSupplySummary::getSupplyId,com.vncode.app.shared.FbsRowValues::sameSupply);
        dispatchEpoch++;
        com.vncode.app.shared.TableRows.merge(dispatchTable.getItems(),board.dispatchSupplies(),WbSupplySummary::getSupplyId,com.vncode.app.shared.FbsRowValues::sameSupply);
        dispatchOffset=board.dispatchSupplies().size();dispatchLoading=false;
        dispatchMoreButton.setDisable(shop==null||board.dispatchSupplies().size()<20);
        updateSelectionState();
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("packing.title"));
        dispatchMoreButton.setText(i18n.tr("news.more"));
        refreshButton.setText(i18n.tr("packing.refresh"));
        newOrdersTab.setText(i18n.tr("packing.tab.new"));
        preparationTab.setText(i18n.tr("packing.tab.preparation"));
        dispatchTab.setText(i18n.tr("packing.tab.dispatch"));
        debtTab.setText(i18n.tr("packing.debt.tab"));
        debtBackButton.setText(i18n.tr("packing.debt.back"));
        debtRefreshButton.setText(i18n.tr("packing.refresh"));
        debtShipmentNameTC.setText(i18n.tr("packing.col.supply"));
        debtShipmentStatusTC.setText(i18n.tr("packing.col.status"));
        debtShipmentCountTC.setText(i18n.tr("packing.debt.count"));
        debtShipmentDateTC.setText(i18n.tr("packing.col.date"));
        debtOrderDateTC.setText(i18n.tr("packing.col.order_id"));
        debtImageTC.setText(i18n.tr("packing.col.photo"));
        debtProductTC.setText(i18n.tr("packing.col.product"));
        debtPriceTC.setText(i18n.tr("packing.col.price"));
        debtOrderStatusTC.setText(i18n.tr("packing.debt.wb_status"));
        if (openDebtShipmentId == null) debtHeading.setText(i18n.tr("packing.debt.shipments"));
        newOrderSearchField.setPromptText(i18n.tr("packing.search_prompt"));
        emptyNewOrdersLabel.setText(i18n.tr("packing.empty"));
        newShipmentButton.setText(i18n.tr("packing.new_shipment"));
        addToShipmentButton.setText(i18n.tr("packing.add_to_shipment"));
        orderDateTC.setText(i18n.tr("packing.col.order_id"));
        imageTC.setText(i18n.tr("packing.col.photo"));
        productTC.setText(i18n.tr("packing.col.product"));
        priceTC.setText(i18n.tr("packing.col.price"));
        preparationSupplyTC.setText(i18n.tr("packing.col.supply"));
        preparationStatusTC.setText(i18n.tr("packing.col.status"));
        preparationCountTC.setText(i18n.tr("packing.col.items"));
        preparationCreatedTC.setText(i18n.tr("packing.col.date"));
        dispatchSupplyTC.setText(i18n.tr("packing.col.supply"));
        dispatchStatusTC.setText(i18n.tr("packing.col.status"));
        dispatchCountTC.setText(i18n.tr("packing.col.items"));
        dispatchCreatedTC.setText(i18n.tr("packing.col.date"));
        updateCategoryFilterOptions();
        updateSelectionState();
    }

    private boolean ensureCanWrite() {
        if (shop == null) {
            AlertService.showError(I18nService.getInstance().tr("packing.error.select_shop"));
            return false;
        }
        if (!tokenValid) {
            AlertService.showWarning(
                    I18nService.getInstance().tr("wb.token.title"),
                    I18nService.getInstance().tr("packing.token_expired.header"),
                    I18nService.getInstance().tr("packing.token_expired.content")
            );
            return false;
        }
        if (selectedOrderIds.isEmpty()) {
            AlertService.showWarning(
                    I18nService.getInstance().tr("packing.orders.title"),
                    I18nService.getInstance().tr("packing.orders.select"),
                    null
            );
            return false;
        }
        return true;
    }

    private void updateSelectionState() {
        int count = selectedOrderIds.size();
        selectedCountLabel.setText(MessageFormat.format(I18nService.getInstance().tr("packing.selected_count"), count));
        newShipmentButton.setDisable(count == 0 || !tokenValid);
        addToShipmentButton.setDisable(count == 0 || !tokenValid);
        selectAllCheckBox.setSelected(!newOrdersTable.getItems().isEmpty() && visibleOrderIdsSelected());
        boolean hasSelection = count > 0;
        selectionActionBar.setVisible(hasSelection);
        selectionActionBar.setManaged(hasSelection);
        if (!refreshLoading.isVisible()) {
            refreshButton.setDisable(shop == null || !tokenValid);
        }
    }

    private void setLoading(boolean loading) {
        newShipmentButton.setDisable(loading || selectedOrderIds.isEmpty() || !tokenValid);
        addToShipmentButton.setDisable(loading || selectedOrderIds.isEmpty() || !tokenValid);
        refreshButton.setDisable(loading || shop == null || !tokenValid);
        refreshLoading.setVisible(false);
        if(loading)newOrdersTable.setPlaceholder(new com.vncode.app.shared.LoadingSkeleton(I18nService.getInstance().tr("supply.loading_orders")));
        else newOrdersTable.setPlaceholder(newOrdersEmptyPlaceholder);
        newOrdersTable.setDisable(loading);
        newOrderSearchField.setDisable(loading);
        categoryFilterMenuButton.setDisable(loading);
    }

    private void setupNewOrdersTable() {
        newOrdersEmptyPlaceholder = new Label();
        newOrdersEmptyPlaceholder.textProperty().bind(emptyNewOrdersLabel.textProperty());
        newOrdersTable.setPlaceholder(newOrdersEmptyPlaceholder);
        selectAllCheckBox.setOnAction(event -> onSelectAll());
        selectedTC.setGraphic(selectAllCheckBox);
        selectedTC.setText("");
        selectedTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(selectedOrderIds.contains(data.getValue().getId())));
        selectedTC.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    Order order = getTableRow() == null ? null : getTableRow().getItem();
                    if (order == null || order.getId() == null) {
                        return;
                    }
                    if (checkBox.isSelected()) {
                        selectedOrderIds.add(order.getId());
                    } else {
                        selectedOrderIds.remove(order.getId());
                    }
                    updateSelectionState();
                });
            }

            @Override
            protected void updateItem(Boolean selected, boolean empty) {
                super.updateItem(selected, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                checkBox.setSelected(Boolean.TRUE.equals(selected));
                setGraphic(checkBox);
            }
        });
        orderDateTC.setCellValueFactory(data -> new SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        orderDateTC.setCellFactory(column -> new OrderIdDateCell());
        imageTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().getImage()));
        imageTC.setCellFactory(column -> imageCell());
        productTC.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        productTC.setCellFactory(column -> new ProductDetailsCell());
        priceTC.setCellValueFactory(data -> new SimpleStringProperty(formatPrice(data.getValue().getPrice())));
    }

    private void setupNewOrderFilters() {
        categoryFilterMenuButton.setText(I18nService.getInstance().tr("packing.all_categories"));
        newOrderSearchField.textProperty().addListener((obs, oldValue, newValue) -> applyNewOrderFilters());
    }

    private void setupTabs() {
        if (packingTabPane == null) {
            return;
        }
        packingTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            delayTransition.stop();
            if (newTab == debtTab) { onRefreshDebt(); return; }
            if(shop!=null&&!refreshLoading.isVisible())refresh();
        });
    }

    public void cancelPendingRequests() {
        if (delayTransition != null) {
            delayTransition.stop();
        }
    }

    @FXML
    private void onClearNewOrderFilters() {
        newOrderSearchField.clear();
        selectedCategories.clear();
        updateCategoryMenuChecks();
        updateCategoryFilterText();
        applyNewOrderFilters();
    }

    private void updateCategoryFilterOptions() {
        Set<String> previous = new LinkedHashSet<>(selectedCategories);
        List<String> categories = allNewOrders.stream()
                .map(Order::getSubjectName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        selectedCategories.clear();
        previous.stream()
                .filter(categories::contains)
                .forEach(selectedCategories::add);
        categoryFilterMenuButton.getItems().clear();
        String allCategoriesText = I18nService.getInstance().tr("packing.all_categories");
        CheckBox allCheckBox = new CheckBox(allCategoriesText);
        allCheckBox.setMaxWidth(Double.MAX_VALUE);
        CustomMenuItem allItem = new CustomMenuItem(allCheckBox);
        allItem.setHideOnClick(false);
        allCheckBox.setOnAction(event -> {
            if (updatingCategoryMenu) {
                return;
            }
            selectedCategories.clear();
            updateCategoryMenuChecks();
            updateCategoryFilterText();
            applyNewOrderFilters();
        });
        categoryFilterMenuButton.getItems().add(allItem);
        for (String category : categories) {
            CheckBox checkBox = new CheckBox(category);
            checkBox.setMaxWidth(Double.MAX_VALUE);
            CustomMenuItem item = new CustomMenuItem(checkBox);
            item.setHideOnClick(false);
            checkBox.setOnAction(event -> {
                if (updatingCategoryMenu) {
                    return;
                }
                if (checkBox.isSelected()) {
                    selectedCategories.add(category);
                } else {
                    selectedCategories.remove(category);
                }
                updateCategoryMenuChecks();
                updateCategoryFilterText();
                applyNewOrderFilters();
            });
            categoryFilterMenuButton.getItems().add(item);
        }
        updateCategoryMenuChecks();
        updateCategoryFilterText();
    }

    private void applyNewOrderFilters() {
        String query = normalize(newOrderSearchField.getText());
        List<Order> filtered = allNewOrders.stream()
                .filter(this::matchesCategory)
                .filter(order -> matchesQuery(order, query))
                .toList();
        Set<Long> visibleIds = filtered.stream()
                .map(Order::getId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        selectedOrderIds.retainAll(visibleIds);
        renderedOrders.merge(newOrdersTable.getItems(),filtered,Order::getId,com.vncode.app.shared.FbsRowValues::snapshot);
        emptyNewOrdersLabel.setVisible(filtered.isEmpty());
        emptyNewOrdersLabel.setManaged(filtered.isEmpty());
        updateSelectionState();
    }

    private void setupSupplyTables() {
        preparationSupplyTC.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().toString()));
        preparationStatusTC.setCellValueFactory(data -> new SimpleStringProperty(formatSupplyStatus(data.getValue())));
        preparationCountTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().getItemCount()));
        preparationCreatedTC.setCellValueFactory(data -> new SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        preparationTable.setRowFactory(table -> {
            TableRow<WbSupplySummary> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && !isInsideButton(event.getTarget())) {
                    openSupplyDetail(row.getItem());
                }
            });
            return row;
        });

        dispatchSupplyTC.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().toString()));
        dispatchStatusTC.setCellValueFactory(data -> new SimpleStringProperty(formatSupplyStatus(data.getValue())));
        dispatchCountTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().getItemCount()));
        dispatchCreatedTC.setCellValueFactory(data -> new SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        dispatchActionTC.setCellFactory(column -> dispatchActionCell());
    }

    private void setupDebtTables() {
        var headingTip = new javafx.scene.control.Tooltip();
        headingTip.textProperty().bind(debtHeading.textProperty());
        debtHeading.setTooltip(headingTip);
        debtShipmentsTable.setPlaceholder(new Label(I18nService.getInstance().tr("packing.debt.empty")));
        debtOrdersTable.setPlaceholder(new Label(I18nService.getInstance().tr("packing.debt.empty")));
        debtShipmentNameTC.setCellValueFactory(data -> new SimpleStringProperty(
                nullToEmpty(data.getValue().name()) + "\n" + nullToEmpty(data.getValue().id())));
        debtShipmentStatusTC.setCellValueFactory(data -> new SimpleStringProperty(I18nService.getInstance().tr("packing.debt.pending")));
        debtShipmentStatusTC.setCellFactory(column -> debtStatusCell());
        debtShipmentCountTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().orders().size()));
        debtShipmentDateTC.setCellValueFactory(data -> new SimpleStringProperty(formatOrderDate(data.getValue().createdAt())));
        debtShipmentsTable.setRowFactory(table -> {
            TableRow<com.vncode.app.integration.wb.WbDebtShipmentService.Shipment> row = new TableRow<>();
            row.setOnMouseClicked(event -> { if (!row.isEmpty() && event.getClickCount() >= 1) showDebtOrders(row.getItem()); });
            return row;
        });
        debtOrderDateTC.setCellValueFactory(data -> new SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        debtOrderDateTC.setCellFactory(column -> new OrderIdDateCell());
        debtImageTC.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().getImage()));
        debtImageTC.setCellFactory(column -> imageCell());
        debtProductTC.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        debtProductTC.setCellFactory(column -> new ProductDetailsCell());
        debtPriceTC.setCellValueFactory(data -> new SimpleStringProperty(formatPrice(data.getValue().getPrice())));
        debtOrderStatusTC.setCellValueFactory(data -> new SimpleStringProperty(debtOrderStatus(data.getValue())));
        debtOrderStatusTC.setCellFactory(column -> debtStatusCell());
        debtPrintTC.setCellFactory(column -> debtPrintCell());
    }

    private <T> TableCell<T, String> debtStatusCell() {
        return new TableCell<>() {
            private final Label badge = new Label();
            {
                badge.setWrapText(true);
                badge.setMinWidth(0);
                badge.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                badge.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(0, widthProperty().subtract(24)));
            }
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty); badge.setText(text);
                Object row=getTableRow()==null?null:getTableRow().getItem();
                String wb=row instanceof Order order ? nullToEmpty(order.getWbStatus()).toLowerCase(java.util.Locale.ROOT) : "";
                String palette=row instanceof com.vncode.app.integration.wb.WbDebtShipmentService.Shipment
                        ? "-fx-background-color: #4b3020; -fx-text-fill: #ffad70;" : switch(wb) {
                    case "waiting" -> "-fx-background-color: #4b3020; -fx-text-fill: #ffad70;";
                    case "sold", "ready_for_pickup" -> "-fx-background-color: #17382d; -fx-text-fill: #78e1aa;";
                    case "canceled", "canceled_by_client", "declined_by_client", "defect", "canceled_by_carrier" -> "-fx-background-color: #492329; -fx-text-fill: #ff9ca8;";
                    case "sorted", "accepted_by_carrier", "sent_to_carrier" -> "-fx-background-color: #23354c; -fx-text-fill: #a8c9ff;";
                    default -> "-fx-background-color: #292f39; -fx-text-fill: #cbd2dd;";
                };
                badge.setStyle(palette + " -fx-background-radius: 7; -fx-padding: 5 8;");
                if (row instanceof Order order) {
                    badge.setTooltip(new javafx.scene.control.Tooltip("wbStatus: " + nullToEmpty(order.getWbStatus()) + "\nsupplierStatus: " + nullToEmpty(order.getSupplierStatus())));
                } else badge.setTooltip(null);
                setGraphic(empty || text == null ? null : badge);
            }
        };
    }

    private static String debtOrderStatus(Order order) {
        String wb = order == null ? "" : nullToEmpty(order.getWbStatus());
        String supplier = order == null ? "" : nullToEmpty(order.getSupplierStatus());
        String result = "";
        if (!wb.isBlank()) result += "\nWB: " + describeDebtStatus("wb",wb);
        if (!supplier.isBlank()) result += "\nSeller: " + describeDebtStatus("supplier",supplier);
        if (wb.isBlank() && supplier.isBlank()) result = I18nService.getInstance().tr("packing.debt.status_unavailable");
        return result.strip();
    }
    private static String describeDebtStatus(String kind, String value) {
        String normalized=value.toLowerCase(java.util.Locale.ROOT);
        String translated = switch (kind + ":" + normalized) {
            case "wb:waiting", "wb:sorted", "wb:sold", "wb:canceled", "wb:canceled_by_client", "wb:declined_by_client", "wb:defect", "wb:ready_for_pickup", "wb:postponed_delivery", "wb:accepted_by_carrier", "wb:sent_to_carrier", "wb:canceled_by_carrier",
                    "supplier:new", "supplier:confirm", "supplier:complete", "supplier:cancel", "supplier:cancel_carrier" -> I18nService.getInstance().tr("packing.debt." + kind + "." + normalized);
            default -> value;
        };
        return translated;
    }

    @FXML private void onDebtBack() { showDebtShipmentList(); }
    private void showDebtShipmentList() {
        openDebtShipmentId=null;
        debtBackButton.setVisible(false); debtBackButton.setManaged(false);
        debtOrdersTable.setVisible(false); debtOrdersTable.setManaged(false);
        debtShipmentsTable.setVisible(true); debtShipmentsTable.setManaged(true);
        debtHeading.setText(I18nService.getInstance().tr("packing.debt.shipments"));
    }
    private void showDebtOrders(com.vncode.app.integration.wb.WbDebtShipmentService.Shipment shipment) {
        if (shipment == null) return;
        openDebtShipmentId=shipment.id();
        debtHeading.setText(nullToEmpty(shipment.name()) + " · " + nullToEmpty(shipment.id()));
        debtOrdersTable.getItems().setAll(shipment.orders());
        debtShipmentsTable.setVisible(false); debtShipmentsTable.setManaged(false);
        debtBackButton.setVisible(true); debtBackButton.setManaged(true);
        debtOrdersTable.setVisible(true); debtOrdersTable.setManaged(true);
    }
    @FXML private void onRefreshDebt() {
        if (shop == null || !tokenValid || debtBusy) return;
        Shop owner=debtOwnerSnapshot(); long generation=shopGeneration; long epoch=++debtEpoch;
        debtBusy=true; debtRefreshButton.setDisable(true); debtLoading.setVisible(true); debtLoading.setManaged(true);
        Task<com.vncode.app.integration.wb.WbDebtShipmentService.Snapshot> task=new Task<>() {
            @Override protected com.vncode.app.integration.wb.WbDebtShipmentService.Snapshot call() throws Exception { return debtService.load(owner); }
        };
        task.setOnSucceeded(event -> {
            if (disposed || !isCurrent(owner,generation) || epoch != debtEpoch) return;
            if (!isDebtCurrent(owner,generation)) { debtConnectionChanged(); return; }
            debtBusy=false; debtRefreshButton.setDisable(false); debtLoading.setVisible(false); debtLoading.setManaged(false);
            var snapshot=task.getValue();
            debtShipmentsTable.getItems().setAll(snapshot.shipments());
            String warning=nullToEmpty(snapshot.warning());
            if (!snapshot.complete() && warning.isBlank()) warning=I18nService.getInstance().tr("packing.debt.incomplete");
            debtWarning.setText(warning); debtWarning.setVisible(!warning.isBlank()); debtWarning.setManaged(!warning.isBlank());
            if (openDebtShipmentId != null) {
                String id=openDebtShipmentId;
                var current=snapshot.shipments().stream().filter(s -> id.equals(s.id())).findFirst();
                if (current.isPresent()) showDebtOrders(current.get());
                else if (snapshot.complete()) showDebtShipmentList();
            }
        });
        java.util.function.Consumer<Throwable> failed=error -> {
            if (disposed || !isCurrent(owner,generation) || epoch != debtEpoch) return;
            if (!isDebtCurrent(owner,generation)) { debtConnectionChanged(); return; }
            debtBusy=false; debtRefreshButton.setDisable(false); debtLoading.setVisible(false); debtLoading.setManaged(false);
            debtWarning.setText(I18nService.getInstance().tr("packing.debt.refresh_failed") + " " + FriendlyErrorService.format(error));
            debtWarning.setVisible(true); debtWarning.setManaged(true);
        };
        task.setOnFailed(event -> failed.accept(task.getException()));
        AppTaskExecutor.execute(task, failed);
    }
    private void debtConnectionChanged() {
        debtBusy=false; debtRefreshButton.setDisable(false); debtLoading.setVisible(false); debtLoading.setManaged(false);
        debtShipmentsTable.getItems().clear(); debtOrdersTable.getItems().clear(); showDebtShipmentList();
        debtWarning.setText(I18nService.getInstance().tr("packing.debt.connection_changed")); debtWarning.setVisible(true); debtWarning.setManaged(true);
    }

    private TableCell<Order, Void> debtPrintCell() {
        return new TableCell<>() {
            private final Button button=new Button();
            {
                FontIcon icon=new FontIcon("fth-printer"); icon.setIconSize(15); icon.setIconColor(Color.WHITE);
                button.setGraphic(icon); button.getStyleClass().add("btn-icon");
                button.setTooltip(new javafx.scene.control.Tooltip(I18nService.getInstance().tr("packing.debt.print")));
                button.setOnAction(event -> { Order order=getTableRow()==null?null:getTableRow().getItem(); if(order!=null) saveDebtLabel(order); });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item,empty); Order order=getTableRow()==null?null:getTableRow().getItem();
                button.setDisable(order==null || order.getId()==null || pendingDebtPrints.contains(order.getId()) || !tokenValid);
                setGraphic(empty?null:button);
            }
        };
    }
    private void saveDebtLabel(Order order) {
        if (shop==null || !tokenValid || order.getId()==null || pendingDebtPrints.contains(order.getId())) return;
        Shop owner=debtOwnerSnapshot(); long generation=shopGeneration; long orderId=order.getId();
        FileChooser chooser=new FileChooser();
        chooser.setTitle(I18nService.getInstance().tr("packing.debt.print"));
        chooser.setInitialFileName("WB-" + orderId + ".pdf");
        File directory=AppPaths.preferredDownloadsDirectory(); if(directory!=null) chooser.setInitialDirectory(directory);
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(I18nService.getInstance().tr("filechooser.pdf"),"*.pdf"));
        File file=chooser.showSaveDialog(debtOrdersTable.getScene()==null?null:debtOrdersTable.getScene().getWindow());
        if(file==null || !isDebtCurrent(owner,generation)) return;
        pendingDebtPrints.add(orderId); debtOrdersTable.refresh();
        Task<Void> task=new Task<>() {
            @Override protected Void call() throws Exception { new com.vncode.app.features.print.WbReshipmentLabelService().export(owner,orderId,file.toPath()); return null; }
        };
        task.setOnSucceeded(event -> {
            if(!isCurrent(owner,generation) || disposed)return; pendingDebtPrints.remove(orderId); debtOrdersTable.refresh();
            if(!isDebtCurrent(owner,generation)) { debtConnectionChanged(); return; }
            try { if(Desktop.isDesktopSupported())Desktop.getDesktop().open(file); } catch(IOException ex) { AlertService.showError(FriendlyErrorService.format(ex)); }
        });
        java.util.function.Consumer<Throwable> failed=error -> { if(!isCurrent(owner,generation)||disposed)return; pendingDebtPrints.remove(orderId); debtOrdersTable.refresh(); if(!isDebtCurrent(owner,generation)){debtConnectionChanged();return;} AlertService.showError(FriendlyErrorService.format(error)); };
        task.setOnFailed(event -> failed.accept(task.getException())); AppTaskExecutor.execute(task,failed);
    }
    private Shop debtOwnerSnapshot() {
        return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
    }
    private boolean isDebtCurrent(Shop expected,long generation) {
        return isCurrent(expected,generation) && shop.getMarketplace()==expected.getMarketplace()
                && java.util.Objects.equals(shop.getClientId(),expected.getClientId())
                && java.util.Objects.equals(shop.getApiKey(),expected.getApiKey());
    }

    private void openSupplyDetail(WbSupplySummary supply) {
        if (supply != null && onPrintSupply != null) {
            onPrintSupply.accept(supply);
        }
    }

    private TableCell<Order, byte[]> imageCell() {
        return new TableCell<>() {
            private long imageGeneration;
            @Override
            protected void updateItem(byte[] imageBytes, boolean empty) {
                super.updateItem(imageBytes, empty);
                long request=++imageGeneration,ownerGeneration=shopGeneration;
                if (empty) {
                    setGraphic(null);
                    return;
                }
                var order=getTableRow()==null?null:getTableRow().getItem();
                if(order!=null&&order.getImageUrl()!=null&&!order.getImageUrl().isBlank()){
                    var view=new ImageView();view.setFitWidth(36);view.setFitHeight(48);view.setPreserveRatio(true);setGraphic(view);
                    try{thumbnails.load(java.net.URI.create(order.getImageUrl()),72,96).whenComplete((image,error)->javafx.application.Platform.runLater(()->{if(!disposed&&request==imageGeneration&&ownerGeneration==shopGeneration&&getTableRow()!=null&&getTableRow().getItem()==order&&error==null)view.setImage(image);}));}catch(IllegalArgumentException invalid){}return;
                }
                if (imageBytes == null || imageBytes.length == 0) {
                    StackPane placeholder = new StackPane();
                    placeholder.setPrefSize(36, 48);
                    placeholder.setMinSize(36, 48);
                    placeholder.setMaxSize(36, 48);
                    placeholder.getStyleClass().add("image-placeholder");
                    setGraphic(placeholder);
                    return;
                }
                ImageView imageView = new ImageView();
                imageView.setFitWidth(36);
                imageView.setFitHeight(48);
                imageView.setPreserveRatio(true);
                setGraphic(imageView);
                thumbnails.loadBytes(imageBytes,72,96).whenComplete((loaded,error)->javafx.application.Platform.runLater(()->{if(!disposed&&ownerGeneration==shopGeneration&&request==imageGeneration&&getTableRow()!=null&&getTableRow().getItem()==order&&error==null)imageView.setImage(loaded);}));
            }
        };
    }
    public void dispose(){disposed=true;shopGeneration++;localGeneration++;if(delayTransition!=null)delayTransition.stop();thumbnails.close();}

    private TableCell<WbSupplySummary, Void> dispatchActionCell() {
        return new TableCell<>() {
            private final Button barcodeButton = new Button();
            {
                FontIcon icon = new FontIcon("fth-printer");
                icon.setIconSize(15);
                icon.setIconColor(Color.WHITE);
                barcodeButton.setGraphic(icon);
                barcodeButton.setPrefSize(32, 32);
                barcodeButton.getStyleClass().add("btn-icon");
                barcodeButton.setOnAction(e -> {
                    WbSupplySummary supply = getTableRow().getItem();
                    if (supply != null) {
                        saveSupplyBarcode(supply);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : barcodeButton);
            }
        };
    }

    private void saveSupplyBarcode(WbSupplySummary supply) {
        if (shop == null || !tokenValid) {
            return;
        }
        Shop selectedShop = shop;
        long generation = shopGeneration;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18nService.getInstance().tr("packing.save_qr.title"));
        chooser.setInitialFileName("SUPPLY-" + supply.getSupplyId() + ".pdf");
        File initialDirectory = AppPaths.preferredDownloadsDirectory();
        if (initialDirectory != null) {
            chooser.setInitialDirectory(initialDirectory);
        }
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(I18nService.getInstance().tr("filechooser.pdf"), "*.pdf"));
        File file = chooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<byte[]> task = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                return ShopOperationCoordinator.withActiveShop(selectedShop.getId(),
                        () -> packingWorkflow.getSupplyBarcodePdf(selectedShop, supply));
            }
        };
        task.setOnSucceeded(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(task.getValue());
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file);
                }
            } catch (IOException ex) {
                AlertService.showError(ex.getMessage());
            }
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (!isCurrent(selectedShop, generation)
                    || ShopOperationCoordinator.isShopUnavailable(rejection)) return;
            AlertService.showError(FriendlyErrorService.format(rejection));
        };
        task.setOnFailed(e -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private boolean isCurrent(Shop expectedShop, long generation) {
        return generation == shopGeneration && shop != null && expectedShop != null
                && shop.getId() == expectedShop.getId();
    }


    private static String formatPrice(Integer price) {
        if (price == null) {
            return "";
        }
        if (price % 100 == 0) {
            return (price / 100) + " ₽";
        } else {
            return String.format(java.util.Locale.US, "%.2f ₽", price / 100.0);
        }
    }

    private static String formatSupplyStatus(WbSupplySummary supply) {
        if (supply == null) {
            return "";
        }
        return supply.isDone()
                ? I18nService.getInstance().tr("packing.status.dispatch")
                : I18nService.getInstance().tr("packing.status.preparation");
    }

    private static String formatOrderDate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            Instant instant = Instant.parse(value);
            String date = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DATE_FORMAT);
            Duration age = Duration.between(instant, Instant.now());
            if (age.toHours() >= 1) {
                return date + "\n" + MessageFormat.format(I18nService.getInstance().tr("packing.order_age_hours"), age.toHours());
            }
            return date + "\n" + MessageFormat.format(I18nService.getInstance().tr("packing.order_age_minutes"), Math.max(1, age.toMinutes()));
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean visibleOrderIdsSelected() {
        for (Order order : newOrdersTable.getItems()) {
            if (order.getId() == null || !selectedOrderIds.contains(order.getId())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCategory(Order order) {
        if (selectedCategories.isEmpty()) {
            return true;
        }
        return selectedCategories.contains(order.getSubjectName());
    }

    private void updateCategoryMenuChecks() {
        updatingCategoryMenu = true;
        try {
            for (javafx.scene.control.MenuItem menuItem : categoryFilterMenuButton.getItems()) {
                if (menuItem instanceof CustomMenuItem customItem && customItem.getContent() instanceof CheckBox checkBox) {
                    if (I18nService.getInstance().tr("packing.all_categories").equals(checkBox.getText())) {
                        checkBox.setSelected(selectedCategories.isEmpty());
                    } else {
                        checkBox.setSelected(selectedCategories.contains(checkBox.getText()));
                    }
                }
            }
        } finally {
            updatingCategoryMenu = false;
        }
    }

    private void updateCategoryFilterText() {
        if (selectedCategories.isEmpty()) {
            categoryFilterMenuButton.setText(I18nService.getInstance().tr("packing.all_categories"));
        } else if (selectedCategories.size() == 1) {
            categoryFilterMenuButton.setText(selectedCategories.iterator().next());
        } else {
            categoryFilterMenuButton.setText(MessageFormat.format(I18nService.getInstance().tr("packing.categories_selected"), selectedCategories.size()));
        }
    }

    private static boolean matchesQuery(Order order, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return contains(order.getId() == null ? "" : String.valueOf(order.getId()), query)
                || contains(order.getName(), query)
                || contains(order.getBrand(), query)
                || contains(order.getSubjectName(), query)
                || contains(order.getArticle(), query)
                || contains(order.getColor(), query)
                || contains(order.getSize(), query)
                || contains(order.getBarcode(), query);
    }

    private static boolean contains(String value, String query) {
        return normalize(value).contains(query);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().trim();
    }

    private static boolean isInsideButton(Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current instanceof Button) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    @FunctionalInterface
    private interface WriteAction {
        void run() throws Exception;
    }

    private final class OrderIdDateCell extends TableCell<Order, String> {
        private final javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(4);
        private final Label idLabel = new Label();
        private final Label dateLabel = new Label();

        OrderIdDateCell() {
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -text-primary;");
            dateLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: -text-muted;");
            vbox.getChildren().addAll(idLabel, dateLabel);
            vbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            vbox.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            Order order = getTableRow() == null ? null : getTableRow().getItem();
            if (order == null) {
                setGraphic(null);
                return;
            }
            idLabel.setText(String.valueOf(order.getId()));
            dateLabel.setText(formatOrderDate(order.getCreatedAt()));
            setGraphic(vbox);
        }
    }

    private final class ProductDetailsCell extends TableCell<Order, String> {
        private final javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(4);
        private final Label titleLabel = new Label();
        private final Label metaLabel = new Label();

        ProductDetailsCell() {
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -text-primary;");
            metaLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: -text-muted;");
            vbox.getChildren().addAll(titleLabel, metaLabel);
            vbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            vbox.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            Order order = getTableRow() == null ? null : getTableRow().getItem();
            if (order == null) {
                setGraphic(null);
                return;
            }
            String name=nullToEmpty(order.getName());
            titleLabel.setText(name.isBlank() && getTableView()==debtOrdersTable ? I18nService.getInstance().tr("packing.debt.details_unavailable") : name);

            String brand = nullToEmpty(order.getBrand());
            String article = nullToEmpty(order.getArticle());
            String size = nullToEmpty(order.getSize());
            if (size.isEmpty()) {
                size = nullToEmpty(order.getRuSize());
            }

            StringBuilder metaBuilder = new StringBuilder();
            if (!brand.isEmpty()) {
                metaBuilder.append(brand).append(" • ");
            }
            metaBuilder.append(article);
            if (!size.isEmpty()) {
                metaBuilder.append(" • Size: ").append(size);
            }
            metaLabel.setText(metaBuilder.toString());

            setGraphic(vbox);
        }
    }
}
