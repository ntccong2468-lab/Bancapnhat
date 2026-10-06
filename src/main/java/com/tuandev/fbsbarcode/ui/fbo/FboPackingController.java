package com.tuandev.fbsbarcode.ui.fbo;

import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.features.fbo.FboProductSearchCriteria;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class FboPackingController {
    private static final int IMAGE_WIDTH = 48;
    private static final int IMAGE_HEIGHT = 64;
    private static final int CATEGORY_VISIBLE_ROWS = 10;
    private static final int CATEGORY_ROW_HEIGHT = 30;

    @FXML private Label titleLabel;
    @FXML private TextField searchField;
    @FXML private MenuButton categoryMenuButton;
    @FXML private Button clearFiltersButton;
    @FXML private Button printButton;
    @FXML private Label emptyStateLabel;
    @FXML private Label loadingLabel;
    @FXML private TableView<FboProductRow> productTable;
    @FXML private TableColumn<FboProductRow, FboProductRow> imageColumn;
    @FXML private TableColumn<FboProductRow, String> nameColumn;
    @FXML private TableColumn<FboProductRow, String> colorColumn;
    @FXML private TableColumn<FboProductRow, String> sizeColumn;
    @FXML private TableColumn<FboProductRow, String> catalogSkuColumn;
    @FXML private TableColumn<FboProductRow, String> skuColumn;
    @FXML private TableColumn<FboProductRow, FboProductRow> quantityColumn;
    @FXML private TableColumn<FboProductRow, FboProductRow> quickPrintColumn;

    private final ObservableList<FboProductRow> rows = FXCollections.observableArrayList();
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectCheckBoxes = new ArrayList<>();
    private final Map<String, Integer> quantitiesBySku = new HashMap<>();
    private final FboProductImageService imageService = new FboProductImageService();
    private Runnable onSearchChanged;
    private Runnable onLoadMoreRequested;
    private Consumer<List<FboBarcodePrintItem>> onPrint;
    private Consumer<FboProductSku> onQuickPrint;
    private boolean loading;
    private boolean hasMore;
    private boolean suppressSearchEvents;
    private Marketplace marketplace = Marketplace.WILDBERRIES;

    @FXML
    private void initialize() {
        productTable.setItems(rows);
        productTable.setEditable(true);
        configureColumns();
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            updateClearFiltersVisibility();
            if (!suppressSearchEvents && onSearchChanged != null) {
                onSearchChanged.run();
            }
        });
        productTable.skinProperty().addListener((obs, oldValue, newValue) ->
                productTable.lookupAll(".scroll-bar").forEach(node -> {
                    if (node instanceof javafx.scene.control.ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                        bar.valueProperty().addListener((valueObs, previousScroll, currentScroll) -> {
                            if (hasMore && !loading && currentScroll.doubleValue() >= 0.70 && onLoadMoreRequested != null) {
                                onLoadMoreRequested.run();
                            }
                        });
                    }
                })
        );
        applyTranslations();
        updatePrintAvailability();
    }

    public void setOnSearchChanged(Runnable onSearchChanged) {
        this.onSearchChanged = onSearchChanged;
    }

    public void setOnLoadMoreRequested(Runnable onLoadMoreRequested) {
        this.onLoadMoreRequested = onLoadMoreRequested;
    }

    public void setOnPrint(Consumer<List<FboBarcodePrintItem>> onPrint) {
        this.onPrint = onPrint;
    }

    public void setOnQuickPrint(Consumer<FboProductSku> onQuickPrint) {
        this.onQuickPrint = onQuickPrint;
    }

    public void setMarketplace(Marketplace marketplace) {
        this.marketplace = marketplace == null ? Marketplace.WILDBERRIES : marketplace;
        boolean ozon = this.marketplace == Marketplace.OZON;
        categoryMenuButton.setVisible(true);
        categoryMenuButton.setManaged(true);
        catalogSkuColumn.setVisible(ozon);
        applyTranslations();
    }

    public FboProductSearchCriteria criteria(int shopId, int limit, int offset) {
        return new FboProductSearchCriteria(shopId, searchField.getText(), List.copyOf(selectedSubjects), limit, offset);
    }

    public void setSubjects(List<String> subjects) {
        categoryMenuButton.getItems().clear();
        subjectCheckBoxes.clear();
        selectedSubjects.clear();
        VBox menuContent = new VBox(2);
        if (subjects != null) {
            for (String subject : subjects) {
                CheckBox item = new CheckBox(subject);
                item.getStyleClass().add("fbo-category-check");
                item.selectedProperty().addListener((obs, wasSelected, selected) -> {
                    if (selected) {
                        selectedSubjects.add(subject);
                    } else {
                        selectedSubjects.remove(subject);
                    }
                    updateCategoryText();
                    updateClearFiltersVisibility();
                    if (!suppressSearchEvents && onSearchChanged != null) {
                        onSearchChanged.run();
                    }
                });
                subjectCheckBoxes.add(item);
                menuContent.getChildren().add(item);
            }
        }
        ScrollPane scrollPane = new ScrollPane(menuContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefViewportWidth(220);
        scrollPane.setPrefViewportHeight(Math.min(CATEGORY_VISIBLE_ROWS, Math.max(1, subjectCheckBoxes.size())) * CATEGORY_ROW_HEIGHT);
        scrollPane.getStyleClass().add("fbo-category-scroll");
        CustomMenuItem menuItem = new CustomMenuItem(scrollPane, false);
        categoryMenuButton.getItems().setAll(menuItem);
        updateCategoryText();
        updateClearFiltersVisibility();
    }

    public void replaceProducts(List<FboProductSku> products, boolean hasMore) {
        rows.clear();
        appendProducts(products, hasMore);
    }

    public void appendProducts(List<FboProductSku> products, boolean hasMore) {
        if (products != null) {
            products.stream().map(FboProductRow::new).forEach(row -> {
                row.setQuantity(quantitiesBySku.getOrDefault(row.product().catalogSku(), 0));
                row.quantityProperty().addListener((obs, oldValue, newValue) -> {
                    int quantity = newValue == null ? 0 : Math.max(0, newValue.intValue());
                    if (quantity > 0) {
                        quantitiesBySku.put(row.product().catalogSku(), quantity);
                    } else {
                        quantitiesBySku.remove(row.product().catalogSku());
                    }
                    updatePrintAvailability();
                });
                rows.add(row);
            });
        }
        this.hasMore = hasMore;
        updateEmptyState();
        updatePrintAvailability();
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        loadingLabel.setVisible(loading);
        loadingLabel.setManaged(loading);
    }

    public int rowCount() {
        return rows.size();
    }

    public void clearQuantities() {
        quantitiesBySku.clear();
        rows.forEach(row -> row.setQuantity(0));
        productTable.refresh();
        updatePrintAvailability();
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        boolean ozon = marketplace == Marketplace.OZON;
        titleLabel.setText(i18n.tr(ozon ? "ozon.fbo.title" : "fbo.title"));
        searchField.setPromptText(i18n.tr(ozon ? "ozon.fbo.search" : "fbo.search"));
        emptyStateLabel.setText(i18n.tr("fbo.empty"));
        loadingLabel.setText(i18n.tr("fbo.loading"));
        imageColumn.setText(i18n.tr("fbo.column.image"));
        nameColumn.setText(i18n.tr("fbo.column.name"));
        colorColumn.setText(i18n.tr("fbo.column.color"));
        sizeColumn.setText(i18n.tr("fbo.column.size"));
        catalogSkuColumn.setText(i18n.tr("ozon.fbo.column.sku"));
        skuColumn.setText(i18n.tr("fbo.column.sku"));
        quantityColumn.setText(i18n.tr("fbo.column.quantity"));
        quickPrintColumn.setText(i18n.tr("fbo.column.print"));
        updateCategoryText();
        updateClearFiltersVisibility();
    }

    @FXML
    private void onPrint() {
        if (onPrint != null) {
            onPrint.accept(rows.stream()
                    .filter(row -> row.getQuantity() > 0)
                    .map(FboProductRow::toPrintItem)
                    .toList());
        }
    }

    @FXML
    private void onClearFilters() {
        suppressSearchEvents = true;
        searchField.clear();
        selectedSubjects.clear();
        subjectCheckBoxes.forEach(item -> item.setSelected(false));
        suppressSearchEvents = false;
        updateCategoryText();
        updateClearFiltersVisibility();
        if (onSearchChanged != null) {
            onSearchChanged.run();
        }
    }

    private void configureColumns() {
        imageColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final Region placeholder = new Region();
            private final StackPane container = new StackPane(placeholder, imageView);
            private String currentUrl;
            {
                placeholder.getStyleClass().add("fbo-image-placeholder");
                placeholder.setMinSize(IMAGE_WIDTH, IMAGE_HEIGHT);
                placeholder.setPrefSize(IMAGE_WIDTH, IMAGE_HEIGHT);
                placeholder.setMaxSize(IMAGE_WIDTH, IMAGE_HEIGHT);
                imageView.setFitWidth(IMAGE_WIDTH);
                imageView.setFitHeight(IMAGE_HEIGHT);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                container.setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(FboProductRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    currentUrl = null;
                    imageView.setImage(null);
                    setGraphic(null);
                    return;
                }
                currentUrl = row.product().imageUrl();
                imageView.setImage(null);
                imageView.setVisible(false);
                placeholder.setVisible(true);
                setGraphic(container);
                if (currentUrl == null || currentUrl.isBlank()) {
                    return;
                }
                String requestedUrl = currentUrl;
                imageService.loadImage(requestedUrl).whenComplete((imageBytes, error) ->
                        Platform.runLater(() -> updateImage(requestedUrl, imageBytes))
                );
            }

            private void updateImage(String imageUrl, byte[] imageBytes) {
                if (imageBytes == null || imageBytes.length == 0 || currentUrl == null || !currentUrl.equals(imageUrl)) {
                    return;
                }
                imageView.setImage(new Image(new ByteArrayInputStream(imageBytes)));
                imageView.setVisible(true);
                placeholder.setVisible(false);
            }
        });
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatName(cell.getValue().product())));
        colorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().product().color()));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().product().size()));
        catalogSkuColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().product().catalogSku()));
        skuColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().product().sku()));
        quantityColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        quantityColumn.setCellFactory(column -> new QuantityCell());
        quickPrintColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        quickPrintColumn.setCellFactory(column -> new QuickPrintCell());
    }

    private String formatName(FboProductSku product) {
        if (marketplace == Marketplace.OZON) {
            return safe(product.title()) + "\n" + safe(product.vendorCode());
        }
        return safe(product.title()) + "\n" + product.nmId() + " • " + safe(product.vendorCode());
    }

    private void updateCategoryText() {
        I18nService i18n = I18nService.getInstance();
        categoryMenuButton.setText(selectedSubjects.isEmpty()
                ? i18n.tr("fbo.categories")
                : i18n.tr("fbo.categories") + " (" + selectedSubjects.size() + ")");
    }

    private void updatePrintAvailability() {
        int totalQuantity = rows.stream()
                .mapToInt(row -> Math.max(0, row.getQuantity()))
                .sum();
        printButton.setDisable(totalQuantity == 0);
        String printText = I18nService.getInstance().tr("fbo.print");
        printButton.setText(totalQuantity > 0 ? printText + " (" + totalQuantity + ")" : printText);
    }

    private void updateClearFiltersVisibility() {
        boolean hasFilters = !selectedSubjects.isEmpty() || (searchField.getText() != null && !searchField.getText().isBlank());
        clearFiltersButton.setVisible(hasFilters);
        clearFiltersButton.setManaged(hasFilters);
    }

    private void updateEmptyState() {
        boolean empty = rows.isEmpty() && !loading;
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class QuantityCell extends TableCell<FboProductRow, FboProductRow> {
        private final TextField field = new TextField();
        private FboProductRow currentRow;
        private boolean updating;

        QuantityCell() {
            field.getStyleClass().add("fbo-quantity-field");
            field.setAlignment(Pos.CENTER);
            field.setPrefWidth(56);
            field.textProperty().addListener((obs, oldValue, newValue) -> {
                if (updating || currentRow == null) {
                    return;
                }
                String digits = newValue == null ? "" : newValue.replaceAll("\\D", "");
                if (!digits.equals(newValue)) {
                    field.setText(digits);
                    return;
                }
                currentRow.setQuantity(parseQuantity(digits));
            });
        }

        @Override
        protected void updateItem(FboProductRow row, boolean empty) {
            super.updateItem(row, empty);
            currentRow = row;
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            updating = true;
            field.setText(row.getQuantity() <= 0 ? "" : String.valueOf(row.getQuantity()));
            updating = false;
            setGraphic(field);
        }

        private static int parseQuantity(String value) {
            if (value == null || value.isBlank()) {
                return 0;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return Integer.MAX_VALUE;
            }
        }
    }

    private class QuickPrintCell extends TableCell<FboProductRow, FboProductRow> {
        private final Button button = new Button();
        private FboProductRow currentRow;

        QuickPrintCell() {
            button.getStyleClass().addAll("button", "btn-icon", "fbo-row-print-button");
            button.setGraphic(new FontIcon("fth-printer:15:white"));
            button.setOnAction(event -> {
                if (currentRow != null && onQuickPrint != null) {
                    onQuickPrint.accept(currentRow.product());
                }
            });
        }

        @Override
        protected void updateItem(FboProductRow row, boolean empty) {
            super.updateItem(row, empty);
            currentRow = row;
            setGraphic(empty || row == null ? null : button);
        }
    }
}
