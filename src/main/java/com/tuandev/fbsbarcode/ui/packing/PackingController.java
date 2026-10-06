package com.tuandev.fbsbarcode.ui.packing;

import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.features.shop.ShopOperationCoordinator;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.FriendlyErrorService;
import com.tuandev.fbsbarcode.shared.I18nService;
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
    @FXML private HBox selectionActionBar;
    @FXML private Label selectedCountLabel;
    @FXML private Label emptyNewOrdersLabel;
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

    @FXML
    private void initialize() {
        delayTransition = new PauseTransition(javafx.util.Duration.millis(800));
        delayTransition.setOnFinished(event -> refreshFromWb());

        setupNewOrdersTable();
        setupNewOrderFilters();
        setupSupplyTables();
        setupTabs();
        applyTranslations();
        updateSelectionState();
    }

    public void setOnPrintSupply(Consumer<WbSupplySummary> onPrintSupply) {
        this.onPrintSupply = onPrintSupply;
    }

    public void setShop(Shop shop, boolean tokenValid) {
        shopGeneration++;
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
        if (newOrdersTab != null && newOrdersTab.isSelected()) {
            delayTransition.playFromStart();
        } else {
            refresh();
        }
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

    private void refresh() {
        if (shop == null) {
            return;
        }
        Shop selectedShop = shop;
        long generation = shopGeneration;
        Task<PackingWorkflow.PackingBoard> task = new Task<>() {
            @Override
            protected PackingWorkflow.PackingBoard call() {
                return packingWorkflow.loadBoard(selectedShop);
            }
        };
        task.setOnSucceeded(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            setBoard(task.getValue());
        });
        task.setOnFailed(e -> {
            if (!isCurrent(selectedShop, generation)
                    || ShopOperationCoordinator.isShopUnavailable(task.getException())) return;
            LOGGER.error("Не удалось загрузить упаковку для shop {}", selectedShop.getId(), task.getException());
            AlertService.showError(FriendlyErrorService.format(task.getException()));
        });
        AppTaskExecutor.execute(task);
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
        task.setOnFailed(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            Throwable failure = task.getException();
            if (ShopOperationCoordinator.isShopUnavailable(failure)) return;
            LOGGER.error("Не удалось обновить упаковку для shop {}", selectedShop.getId(), failure);
            if (isTimeoutFailure(failure)) {
                refresh();
                return;
            }
            AlertService.showError(FriendlyErrorService.format(failure));
        });
        AppTaskExecutor.execute(task);
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
        task.setOnFailed(e -> {
            if (!isCurrent(selectedShop, generation)) return;
            setLoading(false);
            if (ShopOperationCoordinator.isShopUnavailable(task.getException())) return;
            LOGGER.error("WB write action failed", task.getException());
            AlertService.showError(FriendlyErrorService.format(task.getException()));
        });
        AppTaskExecutor.execute(task);
    }

    private void setBoard(PackingWorkflow.PackingBoard board) {
        selectedOrderIds.clear();
        allNewOrders = board.newOrders() == null ? List.of() : List.copyOf(board.newOrders());
        updateCategoryFilterOptions();
        applyNewOrderFilters();
        preparationTable.getItems().setAll(board.preparationSupplies());
        dispatchTable.getItems().setAll(board.dispatchSupplies());
        updateSelectionState();
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("packing.title"));
        refreshButton.setText(i18n.tr("packing.refresh"));
        newOrdersTab.setText(i18n.tr("packing.tab.new"));
        preparationTab.setText(i18n.tr("packing.tab.preparation"));
        dispatchTab.setText(i18n.tr("packing.tab.dispatch"));
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
        refreshLoading.setVisible(loading);
        newOrdersTable.setDisable(loading);
        newOrderSearchField.setDisable(loading);
        categoryFilterMenuButton.setDisable(loading);
    }

    private void setupNewOrdersTable() {
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
            if (newTab == newOrdersTab && shop != null && !refreshLoading.isVisible()) {
                delayTransition.playFromStart();
            } else {
                delayTransition.stop();
            }
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
        newOrdersTable.getItems().setAll(filtered);
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

    private void openSupplyDetail(WbSupplySummary supply) {
        if (supply != null && onPrintSupply != null) {
            onPrintSupply.accept(supply);
        }
    }

    private TableCell<Order, byte[]> imageCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(byte[] imageBytes, boolean empty) {
                super.updateItem(imageBytes, empty);
                if (empty) {
                    setGraphic(null);
                    return;
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
                ImageView imageView = new ImageView(new Image(new ByteArrayInputStream(imageBytes)));
                imageView.setFitWidth(36);
                imageView.setFitHeight(48);
                imageView.setPreserveRatio(true);
                setGraphic(imageView);
            }
        };
    }

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
        task.setOnFailed(e -> {
            if (!isCurrent(selectedShop, generation)
                    || ShopOperationCoordinator.isShopUnavailable(task.getException())) return;
            AlertService.showError(FriendlyErrorService.format(task.getException()));
        });
        AppTaskExecutor.execute(task);
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
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -text-muted;");
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
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -text-muted;");
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
            titleLabel.setText(nullToEmpty(order.getName()));

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
