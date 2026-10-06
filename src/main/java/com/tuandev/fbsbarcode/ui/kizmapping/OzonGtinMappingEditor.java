package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.integration.ozon.OzonCatalogRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductGtinMappingRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.ui.controls.CategoryFilterMenu;
import java.text.MessageFormat;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.concurrent.Task;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Maps Ozon catalog articles to a Znack GTIN without applying Wildberries category rules. */
public final class OzonGtinMappingEditor {
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonProductGtinMappingRepository mappings = new OzonProductGtinMappingRepository();
    private final FboProductImageService images = new FboProductImageService();

    public void open(int shopId, String gtin, KizGtinMappingEditor.Host host) {
        Task<Data> task = new Task<>() {
            @Override
            protected Data call() {
                return new Data(catalog.findAll(shopId), mappings.findAllArticles(shopId));
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            if (host.isCurrent()) openDialog(shopId, gtin, task.getValue(), host);
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void openDialog(int shopId, String gtin, Data data, KizGtinMappingEditor.Host host) {
        Dialog<List<String>> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("ozon.mapping.dialog.title"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText(tr("ozon.mapping.search"));
        String all = tr("ozon.mapping.filter.all");
        MenuButton category = new MenuButton();
        Runnable[] refreshAction = {() -> { }};
        CategoryFilterMenu categoryFilter = new CategoryFilterMenu(category, () -> refreshAction[0].run());
        categoryFilter.setTexts(tr("ozon.mapping.filter.category"),
                tr("znack.filter.no_category"), tr("znack.filter.clear"));
        categoryFilter.rebuild(data.products().stream().map(OzonProductDto::category).toList());
        ComboBox<String> gender = filterBox(all, data.products().stream()
                .map(OzonProductDto::gender).filter(value -> !value.isBlank()).toList());
        gender.setPromptText(tr("ozon.mapping.filter.gender"));
        CheckBox selectAll = new CheckBox(tr("ozon.mapping.select_all"));
        Label selectedLabel = new Label();
        selectedLabel.getStyleClass().add("text-muted");
        VBox rows = new VBox(6);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(480);
        Set<String> selected = selectedArticlesForGtin(data.products(), data.currentMappings(), gtin).stream()
                .map(OzonGtinMappingEditor::articleKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Runnable refresh = () -> renderRows(
                gtin, data, search.getText(), categoryFilter.selectedCategories(), filterValue(gender, all),
                selected, rows, selectedLabel, selectAll);
        refreshAction[0] = refresh;
        search.textProperty().addListener((ignored, oldValue, newValue) -> refresh.run());
        gender.valueProperty().addListener((ignored, oldValue, newValue) -> refresh.run());
        selectAll.setOnAction(event -> {
            List<OzonProductDto> visible = visibleProducts(
                    data.products(), search.getText(), categoryFilter.selectedCategories(), filterValue(gender, all));
            if (selectAll.isSelected()) visible.forEach(product -> selected.add(articleKey(product.article())));
            else visible.forEach(product -> selected.remove(articleKey(product.article())));
            refresh.run();
        });
        HBox filters = new HBox(8, category, gender);
        HBox.setHgrow(category, Priority.ALWAYS);
        HBox.setHgrow(gender, Priority.ALWAYS);
        category.setMaxWidth(Double.MAX_VALUE);
        gender.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10,
                new Label(tr("ozon.mapping.catalog_heading") + " - " + gtin),
                search,
                filters,
                selectAll,
                selectedLabel,
                scroll);
        content.setPrefSize(760, 560);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save
                ? articlesForKeys(data.products(), selected) : null);
        refresh.run();
        dialog.showAndWait().ifPresent(articles -> save(shopId, gtin, articles, host));
    }

    private void renderRows(
            String gtin,
            Data data,
            String query,
            Set<String> categories,
            String gender,
            Set<String> selected,
            VBox rows,
            Label selectedLabel,
            CheckBox selectAll) {
        rows.getChildren().clear();
        List<OzonProductDto> visible = visibleProducts(data.products(), query, categories, gender);
        for (OzonProductDto product : visible) {
            CheckBox check = new CheckBox();
            String key = articleKey(product.article());
            check.setSelected(selected.contains(key));
            check.setOnAction(event -> {
                if (check.isSelected()) selected.add(key);
                else selected.remove(key);
                selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
                updateSelectAll(selectAll, visible, selected);
            });
            Label name = new Label(product.name().isBlank() ? product.article() : product.name());
            name.getStyleClass().add("text-strong");
            name.setWrapText(true);
            String metadata = tr("ozon.mapping.article") + " " + product.article()
                    + (product.category().isBlank() ? "" : "  -  " + product.category())
                    + (product.gender().isBlank() ? "" : "  -  " + product.gender());
            String owner = currentOwner(data.currentMappings(), product.article());
            if (owner != null && !owner.equals(gtin)) {
                metadata += "  -  " + MessageFormat.format(tr("ozon.mapping.current_gtin"), owner);
            }
            Label details = new Label(metadata);
            details.getStyleClass().add("text-muted");
            details.setWrapText(true);
            VBox labels = new VBox(3, name, details);
            HBox row = new HBox(10, check, productImage(product), labels, new Pane());
            HBox.setHgrow(labels, Priority.ALWAYS);
            row.getStyleClass().add("surface");
            labels.setOnMouseClicked(event -> {
                check.setSelected(!check.isSelected());
                if (check.isSelected()) selected.add(key);
                else selected.remove(key);
                selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
                updateSelectAll(selectAll, visible, selected);
            });
            rows.getChildren().add(row);
        }
        if (visible.isEmpty()) {
            Label empty = new Label(tr("ozon.mapping.empty"));
            empty.getStyleClass().add("text-muted");
            empty.setWrapText(true);
            rows.getChildren().add(empty);
        }
        selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
        updateSelectAll(selectAll, visible, selected);
    }

    private StackPane productImage(OzonProductDto product) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(48);
        imageView.setFitHeight(58);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        StackPane placeholder = new StackPane();
        placeholder.setPrefSize(48, 58);
        placeholder.setMaxSize(48, 58);
        placeholder.getStyleClass().add("image-placeholder");
        StackPane container = new StackPane(placeholder, imageView);
        container.setMinSize(52, 62);
        container.setMaxSize(52, 62);
        if (!product.primaryImageUrl().isBlank()) {
            String requestedUrl = product.primaryImageUrl();
            images.loadImage(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                if (bytes == null || bytes.length == 0) return;
                imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                placeholder.setVisible(false);
            }));
        }
        return container;
    }

    private void save(int shopId, String gtin, List<String> articles, KizGtinMappingEditor.Host host) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                mappings.replaceArticlesForGtin(shopId, gtin, articles);
                return null;
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            host.saved();
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private static boolean matches(OzonProductDto product, String query) {
        return product.name().toLowerCase(Locale.ROOT).contains(query)
                || product.sku().toLowerCase(Locale.ROOT).contains(query)
                || product.offerId().toLowerCase(Locale.ROOT).contains(query)
                || product.article().toLowerCase(Locale.ROOT).contains(query);
    }

    static List<OzonProductDto> visibleProducts(
            List<OzonProductDto> products, String query, String category, String gender) {
        Set<String> categories = category == null || category.isBlank()
                ? Set.of() : Set.of(category.strip());
        return visibleProducts(products, query, categories, gender);
    }

    static List<OzonProductDto> visibleProducts(
            List<OzonProductDto> products, String query, Set<String> categories, String gender) {
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        Set<String> normalizedCategories = categories == null ? Set.of() : categories.stream()
                .map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String normalizedGender = gender == null ? "" : gender.strip();
        Map<String, OzonProductDto> byArticle = new java.util.LinkedHashMap<>();
        if (products == null) return List.of();
        products.stream()
                .filter(product -> product != null && !product.archived() && !product.article().isBlank())
                .filter(product -> normalizedQuery.isBlank() || matches(product, normalizedQuery))
                .filter(product -> normalizedCategories.isEmpty()
                        || normalizedCategories.contains(product.category().strip().toLowerCase(Locale.ROOT)))
                .filter(product -> normalizedGender.isBlank()
                        || normalizedGender.equalsIgnoreCase(product.gender()))
                .forEach(product -> byArticle.putIfAbsent(
                        product.article().strip().toLowerCase(Locale.ROOT), product));
        return List.copyOf(byArticle.values());
    }

    private static ComboBox<String> filterBox(String all, List<String> values) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(all);
        values.stream().filter(value -> value != null && !value.isBlank()).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).forEach(box.getItems()::add);
        box.getSelectionModel().selectFirst();
        return box;
    }

    private static String filterValue(ComboBox<String> box, String all) {
        String value = box.getValue();
        return value == null || value.equals(all) ? "" : value;
    }

    private static String currentOwner(Map<String, String> mappings, String article) {
        if (article == null) return null;
        return mappings.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(article))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static void updateSelectAll(
            CheckBox selectAll, List<OzonProductDto> visible, Set<String> selected) {
        long selectedVisible = visible.stream().map(OzonProductDto::article)
                .map(OzonGtinMappingEditor::articleKey).filter(selected::contains).count();
        selectAll.setIndeterminate(selectedVisible > 0 && selectedVisible < visible.size());
        selectAll.setSelected(!visible.isEmpty() && selectedVisible == visible.size());
    }

    static List<String> selectedArticlesForGtin(
            List<OzonProductDto> products, Map<String, String> currentMappings, String gtin) {
        Set<String> selectedKeys = currentMappings.entrySet().stream()
                .filter(entry -> gtin.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(OzonGtinMappingEditor::articleKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return articlesForKeys(products, selectedKeys);
    }

    private static List<String> articlesForKeys(List<OzonProductDto> products, Set<String> selectedKeys) {
        return visibleProducts(products, "", "", "").stream()
                .map(OzonProductDto::article)
                .filter(article -> selectedKeys.contains(articleKey(article)))
                .toList();
    }

    private static String articleKey(String article) {
        return article == null ? "" : article.strip().toLowerCase(Locale.ROOT);
    }

    private static String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private record Data(List<OzonProductDto> products, Map<String, String> currentMappings) {
    }
}
