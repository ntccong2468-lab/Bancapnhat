package com.tuandev.fbsbarcode.ui.dashboard;

import com.tuandev.fbsbarcode.features.dashboard.DashboardKpis;
import com.tuandev.fbsbarcode.features.dashboard.DashboardRepository;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
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

    @FXML
    private void initialize() {
        applyTranslations();
    }

    public void setShop(Shop shop, boolean forceRefresh) {
        int previousShopId = this.shop == null ? -1 : this.shop.getId();
        this.shop = shop;
        if (shop == null) {
            clear();
            return;
        }
        boolean sameShop = previousShopId == shop.getId();
        if (!forceRefresh && sameShop && (loading || (hasLoadedData && loadedShopId != null && loadedShopId == shop.getId()))) {
            return;
        }
        load();
    }

    public void refresh() {
        if (shop != null) {
            load();
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("dashboard.title"));
        refreshButton.setText(i18n.tr("dashboard.refresh"));
        productKpiLabel.setText(i18n.tr("dashboard.kpi.products"));
        newOrdersKpiLabel.setText(i18n.tr("dashboard.kpi.new_orders"));
        openSuppliesKpiLabel.setText(i18n.tr("dashboard.kpi.open_supplies"));
        emptyStateLabel.setText(i18n.tr("dashboard.empty"));
    }

    @FXML
    private void onRefresh() {
        refresh();
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
        task.setOnFailed(event -> {
            if (token != requestToken) {
                return;
            }
            setLoading(false);
            LOGGER.error("Khong the tai dashboard cho shop {}", currentShop.getId(), task.getException());
            clear();
            statusLabel.setText(I18nService.getInstance().tr("dashboard.load_error"));
        });
        AppTaskExecutor.execute(task);
    }

    private void setData(DashboardKpis kpis) {
        productCountLabel.setText(INTEGER_FORMAT.format(kpis.productCount()));
        newOrderCountLabel.setText(INTEGER_FORMAT.format(kpis.newOrderCount()));
        openSupplyCountLabel.setText(INTEGER_FORMAT.format(kpis.openSupplyCount()));
        boolean hasLocalProducts = kpis.productCount() > 0;
        emptyStateLabel.setVisible(!hasLocalProducts);
        emptyStateLabel.setManaged(!hasLocalProducts);
        statusLabel.setText(I18nService.getInstance().tr("dashboard.status.updated"));
    }

    private void clear() {
        productCountLabel.setText("0");
        newOrderCountLabel.setText("0");
        openSupplyCountLabel.setText("0");
        statusLabel.setText("");
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
        statusLabel.setText(loading ? I18nService.getInstance().tr("dashboard.status.loading") : "");
    }
}
