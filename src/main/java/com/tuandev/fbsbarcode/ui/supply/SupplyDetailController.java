package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinAutoSync;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackProductService;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackSafety;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.FriendlyErrorService;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.ui.kizmapping.KizGtinMappingEditor;
import com.tuandev.fbsbarcode.ui.license.LicenseDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackInsufficientFundsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackMissingDocumentsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackOperatorTermsDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackSuzAuthDialogService;
import com.tuandev.fbsbarcode.ui.znack.ZnackKizInventoryActionService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class SupplyDetailController {
    private static final Set<String> ACTIVE_PURCHASE_STAGES = Set.of(
            "QUEUED", "VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER", "POLLING_ORDER", "DOWNLOADING_CODES",
            "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION", "POLLING_INTRODUCTION"
    );

    private boolean updatingSortControls;
    private final KizMappingRepository gtinRepository = new KizMappingRepository();
    private final ZnackKizInventoryActionService inventoryActions = new ZnackKizInventoryActionService();
    private final Set<String> purchasesStarting = new HashSet<>();
    private List<ZnackGtinInventorySummary> gtinSummaries = List.of();
    private Shop shop;
    private ZnackRepository znackRepository;
    private Timeline gtinRefreshTimer;
    private boolean gtinLoading;
    private boolean gtinSyncing;
    private boolean gtinSyncPending;
    private boolean pendingSyncShowsErrors;
    private long shopGeneration;

    @FXML
    private Label supplyTitleLabel;

    @FXML
    private Label supplyMetaLabel;

    @FXML
    private Label emptyStateLabel;

    @FXML
    private ProgressIndicator orderLoadingInline;

    @FXML
    private VBox orderLoadingBox;

    @FXML
    private Label orderLoadingLabel;

    @FXML
    private ProgressIndicator stickerLoading;

    @FXML
    private HBox stickerLoadingBox;

    @FXML
    private Label stickerLoadingLabel;

    @FXML
    private HBox sortOptionsBox;

    @FXML
    private TableView<Order> orderTable;

    @FXML
    private TableColumn<Order, Integer> noTC;

    @FXML
    private TableColumn<Order, String> idTC;

    @FXML
    private TableColumn<Order, byte[]> imageTC;

    @FXML
    private TableColumn<Order, String> nameTC;

    // Consolidating category, article, color and size into nameTC

    @FXML
    private TableColumn<Order, String> priceTC;

    @FXML
    private CheckBox sortBySubjectCheckBox;

    @FXML
    private CheckBox sortByArticleCheckBox;

    @FXML
    private CheckBox sortByColorCheckBox;

    @FXML
    private CheckBox sortBySizeCheckBox;

    @FXML
    private Button printButton;

    @FXML
    private Button backButton;

    @FXML
    private Button deliverButton;

    @FXML
    private Label gtinInventoryTitleLabel;

    @FXML
    private Label gtinInventoryEmptyLabel;

    @FXML
    private ProgressIndicator gtinInventoryLoading;

    @FXML
    private Button gtinInventoryRefreshButton;
    private javafx.animation.RotateTransition gtinRefreshSpin;

    @FXML
    private TextField gtinSearchField;

    @FXML
    private VBox gtinInventoryList;

    private Consumer<OrderSortOptions> onSortOptionsChanged;
    private Runnable onPrint;
    private Runnable onBack;
    private Runnable onDeliver;

    @FXML
    private void initialize() {
        if (gtinInventoryRefreshButton.getGraphic() != null) {
            gtinRefreshSpin = new javafx.animation.RotateTransition(
                    javafx.util.Duration.millis(800), gtinInventoryRefreshButton.getGraphic());
            gtinRefreshSpin.setByAngle(360);
            gtinRefreshSpin.setCycleCount(javafx.animation.Animation.INDEFINITE);
            gtinRefreshSpin.setInterpolator(javafx.animation.Interpolator.LINEAR);
        }
        noTC.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Integer integer, boolean empty) {
                super.updateItem(integer, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        idTC.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        idTC.setCellFactory(column -> new OrderIdDateCell());
        imageTC.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getImage()));
        nameTC.setCellValueFactory(new PropertyValueFactory<>("name"));
        priceTC.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatPrice(data.getValue().getPrice())));
        orderTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        configureImageColumn();
        nameTC.setCellFactory(column -> new OrderDetailsCell());
        centerColumn(priceTC);
        disableColumnSorting();
        bindSortCheckboxes();
        applyTranslations();
        setSupplyInfo(I18nService.getInstance().tr("supply.not_selected"), I18nService.getInstance().tr("supply.select_prompt"));
        setStickerLoading(false);
        setOrders(List.of());
        setPrintEnabled(false);
        setDeliverEnabled(false);
        gtinRefreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), event -> {
            if (shop != null && !gtinLoading && !gtinSyncing) {
                refreshGtinInventory();
            }
        }));
        gtinRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        gtinSearchField.textProperty().addListener((ignored, old, value) -> renderGtinSummaries(gtinSummaries));
        renderGtinSummaries(List.of());
        setGtinLoading(false);
    }

    @FXML
    private void onPrint() {
        if (onPrint != null) {
            onPrint.run();
        }
    }

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    @FXML
    private void onDeliver() {
        if (onDeliver != null) {
            onDeliver.run();
        }
    }

    @FXML
    private void onRefreshGtinInventory() {
        requestGtinSync(true);
    }

    public void syncGtinInventoryOnSupplyOpen() {
        refreshGtinInventory();
        if (shop == null || znackRepository == null || !hasVerifiedSignature(znackRepository.getSettings())) {
            return;
        }
        // GTIN data is auto-synced from Znack at most once per shop per session (shared with the
        // other KIZ panes); afterwards the user re-syncs manually with the refresh button.
        if (ZnackGtinAutoSync.shouldAutoSync(shop.getId())) {
            requestGtinSync(false);
        }
    }

    private void requestGtinSync(boolean showErrors) {
        if (znackRepository == null) {
            return;
        }
        if (gtinLoading || gtinSyncing) {
            gtinSyncPending = true;
            pendingSyncShowsErrors |= showErrors;
            return;
        }
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    syncProducts(currentRepository);
                } catch (Exception error) {
                    try {
                        currentRepository.log("GTIN_SYNC", null, "ERROR", error.getMessage(), null);
                    } catch (RuntimeException auditError) {
                        error.addSuppressed(auditError);
                    }
                    throw error;
                }
                return null;
            }
        };
        setGtinSyncing(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) {
                return;
            }
            setGtinSyncing(false);
            refreshGtinInventory();
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) {
                return;
            }
            setGtinSyncing(false);
            if (showErrors) {
                AlertService.showError(friendlyError(task.getException()));
            }
            refreshGtinInventory();
        });
        AppTaskExecutor.execute(task);
    }

    public void setShop(Shop selected) {
        shopGeneration++;
        shop = selected;
        purchasesStarting.clear();
        gtinSummaries = List.of();
        gtinSearchField.clear();
        gtinSyncPending = false;
        pendingSyncShowsErrors = false;
        gtinSyncing = false;
        znackRepository = selected == null
                ? null
                : new ZnackRepository(new ShopContext(selected.getId(), selected.getName()));
        setGtinLoading(false);
        if (selected == null) {
            gtinRefreshTimer.stop();
        } else {
            gtinRefreshTimer.play();
        }
        refreshGtinInventory();
    }

    public void refreshGtinInventory() {
        if (gtinLoading) {
            return;
        }
        if (shop == null) {
            renderGtinSummaries(List.of());
            setGtinLoading(false);
            return;
        }
        int shopId = shop.getId();
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override
            protected List<ZnackGtinInventorySummary> call() {
                return gtinRepository.findGtinSummaries(shopId);
            }
        };
        setGtinLoading(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != shopId) {
                return;
            }
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
            if (generation != shopGeneration) {
                return;
            }
            setGtinLoading(false);
            AlertService.showError(friendlyError(task.getException()));
            startPendingGtinSync();
        });
        AppTaskExecutor.execute(task);
    }

    public void setSupplyInfo(String title, String meta) {
        supplyTitleLabel.setText(title);
        boolean showMeta = meta != null && !meta.isBlank();
        supplyMetaLabel.setText(showMeta ? meta : "");
        supplyMetaLabel.setVisible(showMeta);
        supplyMetaLabel.setManaged(showMeta);
    }

    public void setOrders(List<Order> orders) {
        orderTable.getItems().setAll(orders == null ? List.of() : orders);
        boolean hasOrders = orders != null && !orders.isEmpty();
        orderTable.setVisible(hasOrders);
        orderTable.setManaged(hasOrders);
        sortOptionsBox.setVisible(hasOrders);
        sortOptionsBox.setManaged(hasOrders);
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
    }

    public void refreshOrders() {
        orderTable.refresh();
    }

    public void showEmptyState(String title, String message) {
        setSupplyInfo(title, "");
        setOrders(List.of());
        boolean showMessage = message != null && !message.isBlank();
        emptyStateLabel.setText(showMessage ? message : "");
        emptyStateLabel.setVisible(showMessage);
        emptyStateLabel.setManaged(showMessage);
    }

    public void setLoading(boolean loading) {
        setLoading(loading, I18nService.getInstance().tr("supply.loading_orders"));
    }

    public void setLoading(boolean loading, String message) {
        if (orderLoadingInline != null) {
            orderLoadingInline.setVisible(loading);
        }
        if (orderLoadingBox != null) {
            orderLoadingBox.setVisible(loading);
            orderLoadingBox.setManaged(loading);
        }
        if (orderLoadingLabel != null) {
            orderLoadingLabel.setText(message == null || message.isBlank()
                    ? I18nService.getInstance().tr("supply.loading_orders")
                    : message);
        }
        if (loading) {
            orderTable.setVisible(false);
            orderTable.setManaged(false);
            sortOptionsBox.setVisible(false);
            sortOptionsBox.setManaged(false);
            emptyStateLabel.setVisible(false);
            emptyStateLabel.setManaged(false);
        }
        orderTable.setDisable(loading);
        sortOptionsBox.setDisable(loading);
    }

    public void setStickerLoading(boolean loading) {
        setStickerLoading(loading, I18nService.getInstance().tr("supply.loading_stickers"));
    }

    public void setStickerLoading(boolean loading, String message) {
        stickerLoading.setVisible(loading);
        stickerLoadingBox.setVisible(loading);
        stickerLoadingBox.setManaged(loading);
        stickerLoadingLabel.setText(message == null || message.isBlank() ? I18nService.getInstance().tr("supply.loading_stickers") : message);
    }

    public void setOnSortOptionsChanged(Consumer<OrderSortOptions> onSortOptionsChanged) {
        this.onSortOptionsChanged = onSortOptionsChanged;
    }

    public void setOnPrint(Runnable onPrint) {
        this.onPrint = onPrint;
    }

    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    public void setOnDeliver(Runnable onDeliver) {
        this.onDeliver = onDeliver;
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        printButton.setText(i18n.tr("supply.print"));
        deliverButton.setText(i18n.tr("supply.deliver"));
        sortBySubjectCheckBox.setText(i18n.tr("supply.sort.subject"));
        sortByArticleCheckBox.setText(i18n.tr("supply.sort.article"));
        sortByColorCheckBox.setText(i18n.tr("supply.sort.color"));
        sortBySizeCheckBox.setText(i18n.tr("supply.sort.size"));
        noTC.setText(i18n.tr("supply.col.no"));
        idTC.setText(i18n.tr("supply.col.task_number"));
        imageTC.setText(i18n.tr("supply.col.photo"));
        nameTC.setText(i18n.tr("supply.col.details"));
        priceTC.setText(i18n.tr("supply.col.price"));
        if (orderLoadingLabel != null) {
            orderLoadingLabel.setText(i18n.tr("supply.loading_orders"));
        }
        if (stickerLoadingLabel != null && !stickerLoadingBox.isVisible()) {
            stickerLoadingLabel.setText(i18n.tr("supply.loading_stickers"));
        }
        gtinInventoryTitleLabel.setText(i18n.tr("supply.gtin_inventory.title"));
        gtinInventoryEmptyLabel.setText(i18n.tr("supply.gtin_inventory.empty"));
        gtinInventoryRefreshButton.setTooltip(new Tooltip(i18n.tr("supply.gtin_inventory.refresh")));
        gtinSearchField.setPromptText(i18n.tr("kiz_mapping.search_gtin"));
        renderGtinSummaries(gtinSummaries);
    }

    public void setPrintEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
    }

    public void setDeliverEnabled(boolean enabled) {
        deliverButton.setDisable(!enabled);
    }

    public OrderSortOptions getSortOptions() {
        return new OrderSortOptions(
                sortBySubjectCheckBox.isSelected(),
                sortByArticleCheckBox.isSelected(),
                sortByColorCheckBox.isSelected(),
                sortBySizeCheckBox.isSelected()
        );
    }

    public void setSortOptions(OrderSortOptions options) {
        OrderSortOptions safe = options == null ? OrderSortOptions.defaultOptions() : options;
        updatingSortControls = true;
        try {
            sortBySubjectCheckBox.setSelected(safe.bySubject());
            sortByArticleCheckBox.setSelected(safe.byArticle());
            sortByColorCheckBox.setSelected(safe.byColor());
            sortBySizeCheckBox.setSelected(safe.bySize());
        } finally {
            updatingSortControls = false;
        }
    }

    private void disableColumnSorting() {
        orderTable.setSortPolicy(param -> false);
        for (TableColumn<Order, ?> column : orderTable.getColumns()) {
            column.setSortable(false);
            column.setReorderable(false);
        }
    }

    private void bindSortCheckboxes() {
        sortBySubjectCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortByArticleCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortByColorCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortBySizeCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
    }

    private void notifySortChanged() {
        if (updatingSortControls) {
            return;
        }
        if (onSortOptionsChanged != null) {
            onSortOptionsChanged.accept(getSortOptions());
        }
    }

    private <T> void centerColumn(TableColumn<Order, T> column) {
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.valueOf(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        });
    }

    private void configureImageColumn() {
        imageTC.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final StackPane placeholder = new StackPane();

            {
                imageView.setFitWidth(36);
                imageView.setFitHeight(48);
                imageView.setPreserveRatio(true);
                placeholder.getStyleClass().add("image-placeholder");
                placeholder.setPrefSize(36, 48);
                placeholder.setMinSize(36, 48);
                placeholder.setMaxSize(36, 48);
                setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(byte[] item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                if (item == null || item.length == 0) {
                    setGraphic(placeholder);
                    setText(null);
                    return;
                }
                try {
                    imageView.setImage(new Image(new ByteArrayInputStream(item)));
                    setGraphic(imageView);
                    setText(null);
                } catch (Exception ex) {
                    setGraphic(placeholder);
                    setText(null);
                }
            }
        });
    }

    private void renderGtinSummaries(List<ZnackGtinInventorySummary> summaries) {
        gtinSummaries = summaries == null ? List.of() : List.copyOf(summaries);
        gtinInventoryList.getChildren().clear();
        String query = gtinSearchField.getText() == null
                ? "" : gtinSearchField.getText().trim().toLowerCase(Locale.ROOT);
        for (ZnackGtinInventorySummary summary : gtinSummaries) {
            if (query.isEmpty() || summary.matchesSearch(query)) {
                gtinInventoryList.getChildren().add(createGtinCard(summary));
            }
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

        Button export = new Button();
        export.getStyleClass().add("btn-icon");
        export.setGraphic(new FontIcon("fth-download"));
        export.setTooltip(new Tooltip(tr("kiz_mapping.action.export")));
        export.setAccessibleText(tr("kiz_mapping.action.export"));
        export.setDisable(summary.available() <= 0);
        export.setOnAction(event -> exportKiz(summary));

        Button archive = new Button();
        archive.getStyleClass().add("btn-icon");
        archive.setGraphic(new FontIcon("fth-trash-2"));
        archive.setTooltip(new Tooltip(tr("kiz_mapping.action.delete_failed")));
        archive.setAccessibleText(tr("kiz_mapping.action.delete_failed"));
        archive.setDisable(summary.discardable() <= 0);
        archive.setOnAction(event -> archiveFailedKiz(summary));

        Button buy = new Button();
        buy.getStyleClass().addAll("btn-primary", "gtin-buy-button");
        buy.setGraphic(new FontIcon("fth-plus"));
        boolean technicalGtin = GtinNormalizer.isTechnicalRange(summary.gtin());
        buy.setTooltip(new Tooltip(technicalGtin
                ? tr("supply.gtin_inventory.error.technical_gtin") : tr("supply.gtin_inventory.buy")));
        buy.setAccessibleText(tr("supply.gtin_inventory.buy"));
        // An existing pipeline no longer blocks a new purchase: the coordinator persists it in a
        // FIFO queue. Only suppress a rapid double-click while this request is being persisted.
        buy.setDisable(technicalGtin || purchasesStarting.contains(summary.gtin()));
        buy.setOnAction(event -> showBuy(summary));

        HBox header = new HBox(8, identity, mapping, export, archive, buy);
        if (summary.discardable() > 0
                && "INTRODUCTION_FAILED".equalsIgnoreCase(value(summary.latestPipelineStage()))) {
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
            // The tooltip keeps the raw stored error for debugging.
            Tooltip.install(card, new Tooltip(summary.latestError()));
        }
        return card;
    }

    private void showMapping(ZnackGtinInventorySummary summary) {
        if (shop == null) {
            return;
        }
        long generation = shopGeneration;
        int shopId = shop.getId();
        new KizGtinMappingEditor().open(shopId, summary.gtin(), new KizGtinMappingEditor.Host() {
            @Override public boolean isCurrent() {
                return generation == shopGeneration && shop != null && shop.getId() == shopId;
            }

            @Override public void busy(boolean busy) {
                if (generation == shopGeneration) {
                    setGtinLoading(busy);
                }
            }

            @Override public void saved() {
                if (generation == shopGeneration) {
                    refreshGtinInventory();
                }
            }

            @Override public void error(Throwable error) {
                if (generation == shopGeneration) {
                    AlertService.showError(friendlyError(error));
                }
            }
        });
    }

    private void showBuy(ZnackGtinInventorySummary summary) {
        if (znackRepository == null || shop == null) {
            return;
        }
        if (!new LicenseDialogService().ensureLicensed()) {
            return;
        }
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

    private void exportKiz(ZnackGtinInventorySummary summary) {
        long generation = shopGeneration;
        inventoryActions.export(gtinInventoryList, shop, summary,
                busy -> { if (generation == shopGeneration) setGtinLoading(busy); },
                () -> { if (generation == shopGeneration) refreshGtinInventory(); },
                error -> { if (generation == shopGeneration) AlertService.showError(friendlyError(error)); });
    }

    private void archiveFailedKiz(ZnackGtinInventorySummary summary) {
        long generation = shopGeneration;
        inventoryActions.archiveFailed(gtinInventoryList, shop, summary,
                busy -> { if (generation == shopGeneration) setGtinLoading(busy); },
                () -> { if (generation == shopGeneration) refreshGtinInventory(); },
                error -> { if (generation == shopGeneration) AlertService.showError(friendlyError(error)); });
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
            @Override
            protected Long call() throws Exception {
                return coordinator.enqueue(settings, gtin, quantity, java.util.UUID.randomUUID().toString());
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) {
                return;
            }
            purchasesStarting.remove(gtin);
            refreshGtinInventory();
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) {
                return;
            }
            purchasesStarting.remove(gtin);
            AlertService.showError(friendlyError(task.getException()));
            refreshGtinInventory();
        });
        AppTaskExecutor.execute(task);
    }

    private void startRetryIntroduction(String gtin) {
        if (znackRepository == null || !purchasesStarting.add(gtin)) {
            return;
        }
        renderGtinSummaries(gtinSummaries);
        long generation = shopGeneration;
        ZnackRepository currentRepository = znackRepository;
        ZnackPurchaseCoordinator coordinator = ZnackPurchaseCoordinator.create(currentRepository);
        Settings settings = currentRepository.getSettings();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                coordinator.retryIntroduction(settings, gtin);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) {
                return;
            }
            purchasesStarting.remove(gtin);
            refreshGtinInventory();
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) {
                return;
            }
            purchasesStarting.remove(gtin);
            AlertService.showError(friendlyError(task.getException()));
            refreshGtinInventory();
        });
        AppTaskExecutor.execute(task);
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
        boolean busy = gtinLoading || gtinSyncing;
        gtinInventoryLoading.setVisible(busy);
        gtinInventoryLoading.setManaged(busy);
        gtinInventoryRefreshButton.setDisable(busy || shop == null);
        if (gtinRefreshSpin != null) {
            if (busy) {
                if (gtinRefreshSpin.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                    gtinRefreshSpin.playFromStart();
                }
            } else {
                gtinRefreshSpin.stop();
                if (gtinInventoryRefreshButton.getGraphic() != null) {
                    gtinInventoryRefreshButton.getGraphic().setRotate(0);
                }
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
        if (!gtinSyncPending || gtinLoading || gtinSyncing || znackRepository == null) {
            return;
        }
        boolean showErrors = pendingSyncShowsErrors;
        gtinSyncPending = false;
        pendingSyncShowsErrors = false;
        requestGtinSync(showErrors);
    }

    private boolean isActivePipeline(String stage) {
        return stage != null && ACTIVE_PURCHASE_STAGES.contains(stage.toUpperCase(Locale.ROOT));
    }

    private String statusClass(String status) {
        String normalized = status.toUpperCase(Locale.ROOT);
        if ("FAILED".equals(normalized) || "CANCELLED".equals(normalized)
                || "INTRODUCTION_FAILED".equals(normalized)) {
            return "badge-red";
        }
        if ("WAITING_INTRODUCTION_DOCUMENTS".equals(normalized)) return "badge-warning";
        return isActivePipeline(normalized) ? "badge-warning" : "badge-green";
    }

    private String localizeStatus(String status) {
        return status == null || status.isBlank()
                ? ""
                : tr("znack.status_value." + status.toLowerCase(Locale.ROOT), status);
    }

    private String friendlyError(Throwable error) {
        return FriendlyErrorService.format(error);
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

    public void dispose() {
        shopGeneration++;
        if (gtinRefreshTimer != null) {
            gtinRefreshTimer.stop();
        }
        shop = null;
        znackRepository = null;
    }

    private String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private String tr(String key, String fallback) {
        return I18nService.getInstance().tr(key, fallback);
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? value(second) : first;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private final class OrderDetailsCell extends TableCell<Order, String> {
        private final VBox vbox = new VBox(4);
        private final Label titleLabel = new Label();
        private final Label metaLabel = new Label();
        private final Label subjectLabel = new Label();
        private final Label statusLabel = new Label();

        OrderDetailsCell() {
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -text-muted;");
            subjectLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -text-muted;");
            statusLabel.getStyleClass().add("badge");
            vbox.getChildren().addAll(titleLabel, metaLabel, subjectLabel, statusLabel);
            vbox.setAlignment(Pos.CENTER_LEFT);
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

            String subject = nullToEmpty(order.getSubjectName());
            subjectLabel.setText(subject.isEmpty()
                    ? "" : I18nService.getInstance().tr("supply.sort.subject") + ": " + subject);
            subjectLabel.setVisible(!subject.isEmpty());
            subjectLabel.setManaged(!subject.isEmpty());

            statusLabel.getStyleClass().removeAll("badge-green", "badge-red");
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);

            if (order.isRequiresKiz()) {
                String kizCode = order.getKiz();
                String error = com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator.getInstance().getAttachmentError(order.getId());

                if (kizCode != null && !kizCode.isBlank()) {
                    statusLabel.setText(I18nService.getInstance().tr("supply.status.kiz_attached"));
                    statusLabel.getStyleClass().add("badge-green");
                    statusLabel.setVisible(true);
                    statusLabel.setManaged(true);
                } else if (error != null) {
                    statusLabel.setText(I18nService.getInstance().tr("supply.status.kiz_error"));
                    statusLabel.getStyleClass().add("badge-red");
                    statusLabel.setVisible(true);
                    statusLabel.setManaged(true);
                }
            }

            setGraphic(vbox);
        }
    }

    private String formatPrice(Integer price) {
        if (price == null) {
            return "";
        }
        if (price % 100 == 0) {
            return (price / 100) + " ₽";
        } else {
            return String.format(java.util.Locale.US, "%.2f ₽", price / 100.0);
        }
    }

    private String formatOrderDate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(value);
            java.time.ZonedDateTime dateTime = instant.atZone(java.time.ZoneId.systemDefault());
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception ex) {
            return value;
        }
    }

    private final class OrderIdDateCell extends TableCell<Order, String> {
        private final javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(4);
        private final Label idLabel = new Label();
        private final Label dateLabel = new Label();

        OrderIdDateCell() {
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -text-muted;");
            vbox.getChildren().addAll(idLabel, dateLabel);
            vbox.setAlignment(Pos.CENTER_LEFT);
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
}
