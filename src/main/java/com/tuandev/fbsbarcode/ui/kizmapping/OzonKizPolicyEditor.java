package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.integration.ozon.OzonCatalogRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductKizPolicyRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Lets users explicitly exempt catalog SKUs that genuinely do not require KIZ. */
public final class OzonKizPolicyEditor {
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonProductKizPolicyRepository policies = new OzonProductKizPolicyRepository();
    private final FboProductImageService images = new FboProductImageService();

    public void open(int shopId, KizGtinMappingEditor.Host host) {
        Task<Data> task = new Task<>() {
            @Override protected Data call() {
                return new Data(catalog.findAll(shopId), policies.findExemptSkus(shopId));
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            if (host.isCurrent()) openDialog(shopId, task.getValue(), host);
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void openDialog(int shopId, Data data, KizGtinMappingEditor.Host host) {
        Dialog<Set<String>> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("ozon.kiz_policy.title"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText(tr("ozon.kiz_policy.search"));
        Label description = new Label(tr("ozon.kiz_policy.description"));
        description.getStyleClass().add("text-muted");
        description.setWrapText(true);
        VBox rows = new VBox(6);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(480);
        Set<String> exemptions = new LinkedHashSet<>(data.exemptSkus());
        Runnable refresh = () -> renderRows(data.products(), search.getText(), exemptions, rows);
        search.textProperty().addListener((ignored, oldValue, newValue) -> refresh.run());

        VBox content = new VBox(10, description, search, scroll);
        content.setPrefSize(720, 560);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? Set.copyOf(exemptions) : null);
        refresh.run();
        dialog.showAndWait().ifPresent(selected -> save(shopId, selected, host));
    }

    private void renderRows(List<OzonProductDto> products, String query, Set<String> exemptions, VBox rows) {
        rows.getChildren().clear();
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<OzonProductDto> visible = products.stream()
                .filter(product -> !product.archived() && !product.sku().isBlank())
                .filter(product -> normalized.isBlank() || matches(product, normalized))
                .toList();
        for (OzonProductDto product : visible) {
            Label name = new Label(product.name().isBlank() ? product.sku() : product.name());
            name.getStyleClass().add("text-strong");
            name.setWrapText(true);
            String article = product.article().isBlank() ? product.offerId() : product.article();
            Label details = new Label("SKU " + product.sku()
                    + (article.isBlank() ? "" : "  -  Article " + article));
            details.getStyleClass().add("text-muted");
            details.setWrapText(true);
            VBox labels = new VBox(3, name, details);
            HBox.setHgrow(labels, Priority.ALWAYS);

            CheckBox noKiz = new CheckBox(tr("ozon.kiz_policy.no_kiz"));
            noKiz.setSelected(exemptions.contains(product.sku()));
            noKiz.setOnAction(event -> {
                if (noKiz.isSelected()) exemptions.add(product.sku());
                else exemptions.remove(product.sku());
            });
            HBox row = new HBox(10, productImage(product), labels, noKiz);
            row.getStyleClass().add("surface");
            rows.getChildren().add(row);
        }
        if (visible.isEmpty()) {
            Label empty = new Label(tr("ozon.kiz_policy.empty"));
            empty.getStyleClass().add("text-muted");
            empty.setWrapText(true);
            rows.getChildren().add(empty);
        }
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

    private void save(int shopId, Set<String> exemptSkus, KizGtinMappingEditor.Host host) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                policies.replaceExemptSkus(shopId, exemptSkus);
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

    private static String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private record Data(List<OzonProductDto> products, Set<String> exemptSkus) {
    }
}
