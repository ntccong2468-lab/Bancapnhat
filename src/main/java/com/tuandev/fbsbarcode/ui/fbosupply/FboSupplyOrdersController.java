package com.tuandev.fbsbarcode.ui.fbosupply;

import com.tuandev.fbsbarcode.features.fbosupply.FboSupplyExecutor;
import com.tuandev.fbsbarcode.features.fbosupply.FboSupplyItem;
import com.tuandev.fbsbarcode.features.fbosupply.FboSupplyOrder;
import com.tuandev.fbsbarcode.features.fbosupply.FboSupplyStatusGroup;
import com.tuandev.fbsbarcode.features.fbosupply.FboSupplySyncService;
import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import javafx.application.Platform;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FboSupplyOrdersController {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private Label titleLabel;
    @FXML private Label marketplaceLabel;
    @FXML private Label lastSyncLabel;
    @FXML private Label statusLabel;
    @FXML private Label selectedOrderLabel;
    @FXML private Label itemSummaryLabel;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Tooltip refreshTooltip;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private ToggleButton allToggle;
    @FXML private ToggleButton preparingToggle;
    @FXML private ToggleButton readyToggle;
    @FXML private ToggleButton inProgressToggle;
    @FXML private ToggleButton issueToggle;
    @FXML private ToggleButton completedToggle;
    @FXML private ToggleButton cancelledToggle;
    @FXML private TableView<FboSupplyOrder> orderTable;
    @FXML private TableColumn<FboSupplyOrder, String> orderNumberColumn;
    @FXML private TableColumn<FboSupplyOrder, String> orderStatusColumn;
    @FXML private TableColumn<FboSupplyOrder, String> warehouseColumn;
    @FXML private TableColumn<FboSupplyOrder, String> plannedColumn;
    @FXML private TableColumn<FboSupplyOrder, Number> quantityColumn;
    @FXML private TableColumn<FboSupplyOrder, Number> acceptedColumn;
    @FXML private TableColumn<FboSupplyOrder, String> updatedColumn;
    @FXML private TableView<FboSupplyItem> itemTable;
    @FXML private TableColumn<FboSupplyItem, String> imageColumn;
    @FXML private TableColumn<FboSupplyItem, String> itemNameColumn;
    @FXML private TableColumn<FboSupplyItem, String> articleColumn;
    @FXML private TableColumn<FboSupplyItem, String> skuColumn;
    @FXML private TableColumn<FboSupplyItem, String> barcodeColumn;
    @FXML private TableColumn<FboSupplyItem, String> sizeColumn;
    @FXML private TableColumn<FboSupplyItem, String> colorColumn;
    @FXML private TableColumn<FboSupplyItem, Number> itemQuantityColumn;
    @FXML private TableColumn<FboSupplyItem, Number> itemAcceptedColumn;
    @FXML private TableColumn<FboSupplyItem, String> kizColumn;

    private final I18nService i18n = I18nService.getInstance();
    private final FboSupplySyncService syncService = new FboSupplySyncService();
    private final FboProductImageService imageService = new FboProductImageService();
    private final List<FboSupplyOrder> allOrders = new ArrayList<>();
    private final ToggleGroup statusGroup = new ToggleGroup();
    private Shop shop;
    private long shopGeneration;
    private long itemGeneration;
    private boolean orderSyncRunning;
    private String loadingOrderId;

    @FXML
    private void initialize() {
        configureStatusToggles();
        configureOrderTable();
        configureItemTable();
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        orderTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> showOrderItems(selected));
        applyTranslations();
        renderOrders(List.of());
    }

    private void configureStatusToggles() {
        allToggle.setToggleGroup(statusGroup);
        preparingToggle.setToggleGroup(statusGroup);
        readyToggle.setToggleGroup(statusGroup);
        inProgressToggle.setToggleGroup(statusGroup);
        issueToggle.setToggleGroup(statusGroup);
        completedToggle.setToggleGroup(statusGroup);
        cancelledToggle.setToggleGroup(statusGroup);
        allToggle.setUserData(null);
        preparingToggle.setUserData(FboSupplyStatusGroup.PREPARING);
        readyToggle.setUserData(FboSupplyStatusGroup.READY);
        inProgressToggle.setUserData(FboSupplyStatusGroup.IN_PROGRESS);
        issueToggle.setUserData(FboSupplyStatusGroup.ISSUE);
        completedToggle.setUserData(FboSupplyStatusGroup.COMPLETED);
        cancelledToggle.setUserData(FboSupplyStatusGroup.CANCELLED);
        allToggle.setSelected(true);
        statusGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) allToggle.setSelected(true);
            applyFilter();
        });
    }

    private void configureOrderTable() {
        orderNumberColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().displayNumber()));
        orderStatusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(statusText(cell.getValue())));
        orderStatusColumn.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(item);
                badge.getStyleClass().setAll("badge", badgeClass(getTableRow().getItem()));
                setGraphic(badge);
            }
        });
        warehouseColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().warehouseName())));
        plannedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatDate(cell.getValue().plannedAt())));
        quantityColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().quantity()));
        acceptedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().acceptedQuantity()));
        updatedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatDate(cell.getValue().updatedAt())));
        orderTable.setPlaceholder(new Label(i18n.tr("fbo.orders.empty")));
    }

    private void configureItemTable() {
        imageColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().imageUrl()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final Region placeholder = new Region();
            private final StackPane container = new StackPane(placeholder, imageView);
            private String currentUrl;
            {
                imageView.setFitWidth(42);
                imageView.setFitHeight(52);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                placeholder.setMinSize(42, 52);
                placeholder.setPrefSize(42, 52);
                placeholder.setMaxSize(42, 52);
                placeholder.getStyleClass().add("fbo-image-placeholder");
                container.setPrefSize(46, 56);
            }
            @Override protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);
                if (empty) {
                    currentUrl = null;
                    imageView.setImage(null);
                    setGraphic(null);
                    return;
                }
                currentUrl = url == null ? "" : url.strip();
                imageView.setImage(null);
                imageView.setVisible(false);
                placeholder.setVisible(true);
                setGraphic(container);
                if (currentUrl.isBlank()) return;
                String requestedUrl = currentUrl;
                imageService.loadImage(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                    if (!Objects.equals(currentUrl, requestedUrl) || bytes == null || bytes.length == 0) return;
                    imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                    imageView.setVisible(true);
                    placeholder.setVisible(false);
                }));
            }
        });
        itemNameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().name())));
        articleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().article())));
        skuColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().sku())));
        barcodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().barcode())));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().size())));
        colorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(orDash(cell.getValue().color())));
        itemQuantityColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().quantity()));
        itemAcceptedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().acceptedQuantity()));
        kizColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(kizText(cell.getValue().requiresKiz())));
        itemTable.setPlaceholder(new Label(i18n.tr("fbo.orders.items.empty")));
    }

    public void setShop(Shop selectedShop, boolean syncIfStale) {
        shopGeneration++;
        itemGeneration++;
        shop = selectedShop;
        loadingOrderId = null;
        orderSyncRunning = false;
        itemTable.getItems().clear();
        selectedOrderLabel.setText("");
        marketplaceLabel.setText(selectedShop == null ? "" : selectedShop.getMarketplace().badge());
        boolean wb = selectedShop != null && selectedShop.getMarketplace() == Marketplace.WILDBERRIES;
        acceptedColumn.setVisible(wb);
        itemAcceptedColumn.setVisible(wb);
        kizColumn.setVisible(wb);
        if (selectedShop == null) {
            renderOrders(List.of());
            setStatus(false, "");
            updateLastSync();
            return;
        }
        renderOrders(syncService.cachedOrders(selectedShop));
        updateLastSync();
        if (syncIfStale && syncService.isOrderListStale(selectedShop)) refresh();
    }

    @FXML
    private void refresh() {
        Shop requestedShop = shop;
        if (requestedShop == null || orderSyncRunning) return;
        long generation = shopGeneration;
        orderSyncRunning = true;
        setStatus(true, i18n.tr("fbo.orders.syncing"));
        Task<List<FboSupplyOrder>> task = new Task<>() {
            @Override protected List<FboSupplyOrder> call() throws Exception {
                return syncService.syncOrders(requestedShop);
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != requestedShop.getId()) return;
            orderSyncRunning = false;
            renderOrders(task.getValue());
            updateLastSync();
            setStatus(false, "");
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != requestedShop.getId()) return;
            orderSyncRunning = false;
            setStatus(false, i18n.tr("fbo.orders.sync_failed"));
        });
        FboSupplyExecutor.execute(task);
    }

    private void showOrderItems(FboSupplyOrder selected) {
        itemGeneration++;
        if (selected == null || shop == null) {
            itemTable.getItems().clear();
            selectedOrderLabel.setText("");
            itemSummaryLabel.setText("");
            return;
        }
        selectedOrderLabel.setText(i18n.tr("fbo.orders.selected") + " " + selected.displayNumber());
        List<FboSupplyItem> cached = syncService.cachedItems(shop, selected.orderId());
        renderItems(cached);
        if (cached.isEmpty() && !selected.orderId().equals(loadingOrderId)) loadItems(selected);
    }

    private void loadItems(FboSupplyOrder selected) {
        Shop requestedShop = shop;
        long generation = itemGeneration;
        loadingOrderId = selected.orderId();
        setStatus(true, i18n.tr("fbo.orders.items.loading"));
        Task<List<FboSupplyItem>> task = new Task<>() {
            @Override protected List<FboSupplyItem> call() throws Exception {
                return syncService.syncItems(requestedShop, selected);
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != itemGeneration || shop == null || shop.getId() != requestedShop.getId()) return;
            loadingOrderId = null;
            renderItems(task.getValue());
            renderOrders(syncService.cachedOrders(shop));
            orderTable.getItems().stream().filter(order -> order.orderId().equals(selected.orderId()))
                    .findFirst().ifPresent(orderTable.getSelectionModel()::select);
            setStatus(orderSyncRunning, orderSyncRunning ? i18n.tr("fbo.orders.syncing") : "");
        });
        task.setOnFailed(event -> {
            if (generation != itemGeneration || shop == null || shop.getId() != requestedShop.getId()) return;
            loadingOrderId = null;
            setStatus(false, i18n.tr("fbo.orders.items.failed"));
        });
        FboSupplyExecutor.execute(task);
    }

    private void renderOrders(List<FboSupplyOrder> orders) {
        String selectedId = orderTable.getSelectionModel().getSelectedItem() == null
                ? null : orderTable.getSelectionModel().getSelectedItem().orderId();
        allOrders.clear();
        allOrders.addAll(orders == null ? List.of() : orders);
        updateCounts();
        applyFilter();
        if (selectedId != null) {
            orderTable.getItems().stream().filter(order -> selectedId.equals(order.orderId()))
                    .findFirst().ifPresent(orderTable.getSelectionModel()::select);
        }
    }

    private void renderItems(List<FboSupplyItem> items) {
        List<FboSupplyItem> safe = items == null ? List.of() : items;
        itemTable.getItems().setAll(safe);
        int quantity = safe.stream().mapToInt(FboSupplyItem::quantity).sum();
        itemSummaryLabel.setText(String.format(i18n.tr("fbo.orders.items.summary"), safe.size(), quantity));
    }

    private void applyFilter() {
        if (orderTable == null || statusGroup == null) return;
        Object selectedData = statusGroup.getSelectedToggle() == null ? null : statusGroup.getSelectedToggle().getUserData();
        FboSupplyStatusGroup selectedGroup = selectedData instanceof FboSupplyStatusGroup group ? group : null;
        String query = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().strip().toLowerCase(Locale.ROOT);
        List<FboSupplyOrder> filtered = allOrders.stream()
                .filter(order -> matchesGroup(order, selectedGroup))
                .filter(order -> query.isEmpty() || searchable(order).contains(query))
                .toList();
        orderTable.getItems().setAll(filtered);
    }

    private static boolean matchesGroup(FboSupplyOrder order, FboSupplyStatusGroup group) {
        if (group == null) return true;
        if (group == FboSupplyStatusGroup.ISSUE) {
            return order.statusGroup() == FboSupplyStatusGroup.ISSUE
                    || order.statusGroup() == FboSupplyStatusGroup.REVIEW
                    || order.statusGroup() == FboSupplyStatusGroup.UNKNOWN;
        }
        return order.statusGroup() == group;
    }

    private static String searchable(FboSupplyOrder order) {
        return String.join(" ", safe(order.orderId()), safe(order.supplyId()), safe(order.orderNumber()),
                safe(order.rawStatus()), safe(order.warehouseName())).toLowerCase(Locale.ROOT);
    }

    private void updateCounts() {
        EnumMap<FboSupplyStatusGroup, Long> counts = new EnumMap<>(FboSupplyStatusGroup.class);
        for (FboSupplyStatusGroup value : FboSupplyStatusGroup.values()) counts.put(value, 0L);
        allOrders.forEach(order -> counts.compute(order.statusGroup(), (key, value) -> value == null ? 1 : value + 1));
        allToggle.setText(i18n.tr("fbo.orders.filter.all") + " (" + allOrders.size() + ")");
        preparingToggle.setText(i18n.tr("fbo.orders.filter.preparing") + count(counts, FboSupplyStatusGroup.PREPARING));
        readyToggle.setText(i18n.tr("fbo.orders.filter.ready") + count(counts, FboSupplyStatusGroup.READY));
        inProgressToggle.setText(i18n.tr("fbo.orders.filter.in_progress") + count(counts, FboSupplyStatusGroup.IN_PROGRESS));
        long issueCount = counts.get(FboSupplyStatusGroup.ISSUE) + counts.get(FboSupplyStatusGroup.REVIEW)
                + counts.get(FboSupplyStatusGroup.UNKNOWN);
        issueToggle.setText(i18n.tr("fbo.orders.filter.issue") + " (" + issueCount + ")");
        completedToggle.setText(i18n.tr("fbo.orders.filter.completed") + count(counts, FboSupplyStatusGroup.COMPLETED));
        cancelledToggle.setText(i18n.tr("fbo.orders.filter.cancelled") + count(counts, FboSupplyStatusGroup.CANCELLED));
    }

    private static String count(EnumMap<FboSupplyStatusGroup, Long> counts, FboSupplyStatusGroup group) {
        return " (" + counts.getOrDefault(group, 0L) + ")";
    }

    public void applyTranslations() {
        if (titleLabel == null) return;
        titleLabel.setText(i18n.tr("fbo.orders.title"));
        searchField.setPromptText(i18n.tr("fbo.orders.search"));
        searchField.setAccessibleText(i18n.tr("fbo.orders.search"));
        refreshButton.setText("");
        refreshButton.setAccessibleText(i18n.tr("fbo.orders.refresh"));
        refreshTooltip.setText(i18n.tr("fbo.orders.refresh"));
        orderNumberColumn.setText(i18n.tr("fbo.orders.column.number"));
        orderStatusColumn.setText(i18n.tr("fbo.orders.column.status"));
        warehouseColumn.setText(i18n.tr("fbo.orders.column.warehouse"));
        plannedColumn.setText(i18n.tr("fbo.orders.column.planned"));
        quantityColumn.setText(i18n.tr("fbo.orders.column.quantity"));
        acceptedColumn.setText(i18n.tr("fbo.orders.column.accepted"));
        updatedColumn.setText(i18n.tr("fbo.orders.column.updated"));
        imageColumn.setText(i18n.tr("fbo.orders.item.image"));
        itemNameColumn.setText(i18n.tr("fbo.orders.item.name"));
        articleColumn.setText(i18n.tr("fbo.orders.item.article"));
        skuColumn.setText(i18n.tr("fbo.orders.item.sku"));
        barcodeColumn.setText(i18n.tr("fbo.orders.item.barcode"));
        sizeColumn.setText(i18n.tr("fbo.orders.item.size"));
        colorColumn.setText(i18n.tr("fbo.orders.item.color"));
        itemQuantityColumn.setText(i18n.tr("fbo.orders.item.quantity"));
        itemAcceptedColumn.setText(i18n.tr("fbo.orders.item.accepted"));
        kizColumn.setText(i18n.tr("fbo.orders.item.kiz"));
        orderTable.setPlaceholder(new Label(i18n.tr("fbo.orders.empty")));
        itemTable.setPlaceholder(new Label(i18n.tr("fbo.orders.items.empty")));
        FboSupplyOrder selected = orderTable.getSelectionModel().getSelectedItem();
        selectedOrderLabel.setText(selected == null ? ""
                : i18n.tr("fbo.orders.selected") + " " + selected.displayNumber());
        int itemQuantity = itemTable.getItems().stream().mapToInt(FboSupplyItem::quantity).sum();
        itemSummaryLabel.setText(itemTable.getItems().isEmpty() ? ""
                : String.format(i18n.tr("fbo.orders.items.summary"), itemTable.getItems().size(), itemQuantity));
        updateCounts();
        updateLastSync();
        orderTable.refresh();
        itemTable.refresh();
    }

    private void updateLastSync() {
        String value = syncService.lastSyncedAt(shop);
        lastSyncLabel.setText(value == null ? i18n.tr("fbo.orders.never_synced")
                : i18n.tr("fbo.orders.last_sync") + " " + formatDate(value));
    }

    private void setStatus(boolean loading, String text) {
        progressIndicator.setVisible(loading);
        progressIndicator.setManaged(loading);
        statusLabel.setText(text == null ? "" : text);
        statusLabel.setVisible(text != null && !text.isBlank());
        statusLabel.setManaged(text != null && !text.isBlank());
        refreshButton.setDisable(loading && orderSyncRunning);
    }

    private String statusText(FboSupplyOrder order) {
        if (order.marketplace() == Marketplace.WILDBERRIES) {
            return i18n.tr("fbo.orders.wb.status." + order.rawStatus(), order.rawStatus());
        }
        return i18n.tr("fbo.orders.ozon.status." + order.rawStatus(), order.rawStatus());
    }

    private static String badgeClass(FboSupplyOrder order) {
        if (order == null) return "badge-gray";
        return switch (order.statusGroup()) {
            case COMPLETED -> "badge-green";
            case ISSUE, CANCELLED -> "badge-red";
            case READY, IN_PROGRESS, REVIEW -> "badge-warning";
            default -> "badge-gray";
        };
    }

    private String kizText(Boolean requiresKiz) {
        return requiresKiz == null ? "—" : i18n.tr(requiresKiz ? "common.yes" : "common.no");
    }

    private static String formatDate(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DISPLAY_DATE);
        } catch (RuntimeException ignored) {
            try {
                return Instant.parse(raw).atZone(ZoneId.systemDefault()).format(DISPLAY_DATE);
            } catch (RuntimeException ignoredAgain) {
                return raw.length() > 19 ? raw.substring(0, 19).replace('T', ' ') : raw.replace('T', ' ');
            }
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
