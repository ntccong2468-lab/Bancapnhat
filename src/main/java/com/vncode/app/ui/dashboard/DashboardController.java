package com.vncode.app.ui.dashboard;

import com.vncode.app.features.dashboard.DashboardKpis;
import com.vncode.app.features.dashboard.DashboardRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.NumberFormat;
import java.util.Locale;

public class DashboardController {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardController.class);
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private final DashboardRepository dashboardRepository = new DashboardRepository();
    @FXML private Label titleLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Button refreshButton;
    @FXML private Label productKpiLabel;
    @FXML private Label newOrdersKpiLabel;
    @FXML private Label openSuppliesKpiLabel;
    @FXML private Label productCountLabel;
    @FXML private Label newOrderCountLabel;
    @FXML private Label openSupplyCountLabel;
    @FXML private Label emptyStateLabel;

    private Shop shop;
    private long requestToken;
    private Integer loadedShopId;
    private boolean hasLoadedData;
    private boolean loading;
    private String dashboardStatusKey = "";

    @FXML private void initialize() { applyTranslations(); clear(); }

    public void setShop(Shop next, boolean forceRefresh) {
        Shop previous = shop;
        shop = snapshot(next);
        if (shop == null) { ++requestToken; setLoading(false); clear(); return; }
        boolean sameShop = sameScope(previous, shop);
        if (!sameShop) { ++requestToken; setLoading(false); clear(); }
        if (!forceRefresh && sameShop && (loading || (hasLoadedData && loadedShopId != null && loadedShopId == shop.getId()))) return;
        load();
    }

    public void refresh() { if (shop != null) load(); }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("dashboard.title"));
        refreshButton.setText(i18n.tr("dashboard.refresh"));
        productKpiLabel.setText(i18n.tr("dashboard.kpi.products"));
        newOrdersKpiLabel.setText(i18n.tr("dashboard.kpi.new_orders"));
        openSuppliesKpiLabel.setText(i18n.tr("dashboard.kpi.open_supplies"));
        emptyStateLabel.setText(i18n.tr("dashboard.empty"));
        statusLabel.setText(dashboardStatusKey.isEmpty() ? "" : i18n.tr(dashboardStatusKey));
    }

    @FXML private void onRefresh() { refresh(); }

    private static Shop snapshot(Shop shop) {
        return shop == null ? null : new Shop(shop.getId(), shop.getName(), shop.getMarketplace(),
                shop.getClientId(), shop.getApiKey());
    }

    private static boolean sameScope(Shop left, Shop right) {
        return left != null && right != null && left.getId() == right.getId()
                && left.getMarketplace() == right.getMarketplace()
                && java.util.Objects.equals(left.getClientId(), right.getClientId())
                && java.util.Objects.equals(left.getApiKey(), right.getApiKey());
    }

    private void setDashboardStatus(String key) {
        dashboardStatusKey = key;
        statusLabel.setText(key.isEmpty() ? "" : I18nService.getInstance().tr(key));
    }

    private void load() {
        long token = ++requestToken;
        Shop currentShop = shop;
        Task<DashboardKpis> task = new Task<>() {
            @Override
            protected DashboardKpis call() {
                return dashboardRepository.loadKpis(currentShop.getId());
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken) {
                return;
            }
            setLoading(false);
            loadedShopId = currentShop.getId();
            hasLoadedData = true;
            setData(task.getValue());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (token != requestToken) {
                return;
            }
            setLoading(false);
            LOGGER.error("Khong the tai dashboard cho shop {}", currentShop.getId(), rejection);
            clear();
            setDashboardStatus("dashboard.load_error");
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void setData(DashboardKpis kpis) {
        productCountLabel.setText(INTEGER_FORMAT.format(kpis.productCount()));
        newOrderCountLabel.setText(INTEGER_FORMAT.format(kpis.newOrderCount()));
        openSupplyCountLabel.setText(INTEGER_FORMAT.format(kpis.openSupplyCount()));
        boolean hasLocalProducts = kpis.productCount() > 0;
        emptyStateLabel.setVisible(!hasLocalProducts);
        emptyStateLabel.setManaged(!hasLocalProducts);
        setDashboardStatus("dashboard.status.updated");
    }

    private void clear() {
        productCountLabel.setText("0");
        newOrderCountLabel.setText("0");
        openSupplyCountLabel.setText("0");
        setDashboardStatus("");
        loadedShopId = null;
        hasLoadedData = false;
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        refreshButton.setDisable(loading);
        setDashboardStatus(loading ? "dashboard.status.loading" : "");
    }
}
