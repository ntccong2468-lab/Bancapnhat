package com.tuandev.fbsbarcode.ui.finance;

import com.tuandev.fbsbarcode.features.finance.FinanceDaily;
import com.tuandev.fbsbarcode.features.finance.FinanceDashboardRepository;
import com.tuandev.fbsbarcode.features.finance.FinanceDashboardSnapshot;
import com.tuandev.fbsbarcode.features.finance.FinanceExecutor;
import com.tuandev.fbsbarcode.features.finance.FinanceSyncScheduler;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

public class FinanceDashboardController {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceDashboardController.class);
    private final FinanceDashboardRepository repository = new FinanceDashboardRepository();

    @FXML private Label titleLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private DatePicker fromDatePicker;
    @FXML private DatePicker toDatePicker;
    @FXML private Button sevenDaysButton;
    @FXML private Button thirtyDaysButton;
    @FXML private Button ninetyDaysButton;
    @FXML private Button refreshButton;
    @FXML private Button syncButton;
    @FXML private Label fromDateLabel;
    @FXML private Label toDateLabel;
    @FXML private Label grossTitleLabel;
    @FXML private Label returnsTitleLabel;
    @FXML private Label payoutTitleLabel;
    @FXML private Label commissionTitleLabel;
    @FXML private Label advertisingTitleLabel;
    @FXML private Label penaltyTitleLabel;
    @FXML private Label logisticsTitleLabel;
    @FXML private Label storageTitleLabel;
    @FXML private Label otherCostTitleLabel;
    @FXML private Label netTitleLabel;
    @FXML private Label grossValueLabel;
    @FXML private Label returnsValueLabel;
    @FXML private Label payoutValueLabel;
    @FXML private Label commissionValueLabel;
    @FXML private Label advertisingValueLabel;
    @FXML private Label penaltyValueLabel;
    @FXML private Label logisticsValueLabel;
    @FXML private Label storageValueLabel;
    @FXML private Label otherCostValueLabel;
    @FXML private Label netValueLabel;
    @FXML private Label emptyLabel;
    @FXML private TableView<FinanceDaily> dailyTable;
    @FXML private TableColumn<FinanceDaily, String> dateColumn;
    @FXML private TableColumn<FinanceDaily, String> salesColumn;
    @FXML private TableColumn<FinanceDaily, String> returnsColumn;
    @FXML private TableColumn<FinanceDaily, String> payoutColumn;
    @FXML private TableColumn<FinanceDaily, String> commissionColumn;
    @FXML private TableColumn<FinanceDaily, String> advertisingColumn;
    @FXML private TableColumn<FinanceDaily, String> penaltyColumn;
    @FXML private TableColumn<FinanceDaily, String> logisticsColumn;
    @FXML private TableColumn<FinanceDaily, String> storageColumn;
    @FXML private TableColumn<FinanceDaily, String> otherCostColumn;
    @FXML private TableColumn<FinanceDaily, String> netColumn;

    private Shop shop;
    private long requestToken;
    private FinanceDashboardSnapshot currentSnapshot;

    @FXML
    private void initialize() {
        LocalDate today = LocalDate.now();
        fromDatePicker.setValue(today.minusDays(29));
        toDatePicker.setValue(today);
        configureColumns();
        applyTranslations();
        clearData();
    }

    public void setShop(Shop shop) {
        this.shop = shop;
        updateMarketplaceTexts();
        if (this.shop == null) {
            clearData();
        } else {
            load();
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("finance.title"));
        sevenDaysButton.setText(i18n.tr("finance.range.7"));
        thirtyDaysButton.setText(i18n.tr("finance.range.30"));
        ninetyDaysButton.setText(i18n.tr("finance.range.90"));
        fromDateLabel.setText(i18n.tr("finance.range.from"));
        toDateLabel.setText(i18n.tr("finance.range.to"));
        refreshButton.setText(i18n.tr("finance.refresh"));
        syncButton.setText(i18n.tr("finance.sync"));
        grossTitleLabel.setText(i18n.tr("finance.kpi.sales"));
        returnsTitleLabel.setText(i18n.tr("finance.kpi.returns"));
        payoutTitleLabel.setText(i18n.tr("finance.kpi.payout"));
        commissionTitleLabel.setText(i18n.tr("finance.kpi.commission"));
        advertisingTitleLabel.setText(i18n.tr("finance.kpi.advertising"));
        penaltyTitleLabel.setText(i18n.tr("finance.kpi.penalty"));
        logisticsTitleLabel.setText(i18n.tr("finance.kpi.logistics"));
        storageTitleLabel.setText(i18n.tr("finance.kpi.storage"));
        otherCostTitleLabel.setText(i18n.tr("finance.kpi.other_cost"));
        netTitleLabel.setText(i18n.tr("finance.kpi.net"));
        emptyLabel.setText(i18n.tr("finance.empty"));
        dateColumn.setText(i18n.tr("finance.col.date"));
        salesColumn.setText(i18n.tr("finance.col.sales"));
        returnsColumn.setText(i18n.tr("finance.col.returns"));
        payoutColumn.setText(i18n.tr("finance.col.payout"));
        commissionColumn.setText(i18n.tr("finance.col.commission"));
        advertisingColumn.setText(i18n.tr("finance.col.advertising"));
        penaltyColumn.setText(i18n.tr("finance.col.penalty"));
        logisticsColumn.setText(i18n.tr("finance.col.logistics"));
        storageColumn.setText(i18n.tr("finance.col.storage"));
        otherCostColumn.setText(i18n.tr("finance.col.other_cost"));
        netColumn.setText(i18n.tr("finance.col.net"));
        String payoutHelp = i18n.tr("finance.kpi.payout.help");
        Tooltip payoutTooltip = new Tooltip(payoutHelp);
        payoutTooltip.setWrapText(true);
        payoutTooltip.setMaxWidth(360);
        payoutTitleLabel.setTooltip(payoutTooltip);
        payoutValueLabel.setTooltip(payoutTooltip);
        payoutTitleLabel.setAccessibleHelp(payoutHelp);
        updateMarketplaceTexts();
        if (currentSnapshot != null) {
            showData(currentSnapshot);
        }
        dailyTable.refresh();
    }

    @FXML
    private void onSevenDays() {
        selectRange(7);
    }

    @FXML
    private void onThirtyDays() {
        selectRange(30);
    }

    @FXML
    private void onNinetyDays() {
        selectRange(90);
    }

    @FXML
    private void onRefresh() {
        load();
    }

    @FXML
    private void onDateRangeChanged() {
        load();
    }

    @FXML
    private void onSync() {
        if (shop == null) return;
        FinanceSyncScheduler.getInstance().requestRecentSync(shop.getId());
        statusLabel.setText(I18nService.getInstance().tr("finance.status.queued"));
    }

    private void selectRange(int days) {
        LocalDate today = LocalDate.now();
        fromDatePicker.setValue(today.minusDays(days - 1L));
        toDatePicker.setValue(today);
        load();
    }

    private void load() {
        if (shop == null) return;
        long token = ++requestToken;
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            setLoading(false);
            statusLabel.setText(I18nService.getInstance().tr("finance.invalid_range"));
            return;
        }
        Shop currentShop = shop;
        Task<FinanceDashboardSnapshot> task = new Task<>() {
            @Override
            protected FinanceDashboardSnapshot call() {
                return repository.load(currentShop.getId(), from, to);
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || shop == null || currentShop.getId() != shop.getId()) return;
            setLoading(false);
            showData(task.getValue());
            statusLabel.setText(I18nService.getInstance().tr("finance.status.updated"));
        });
        task.setOnFailed(event -> {
            if (token != requestToken) return;
            setLoading(false);
            LOGGER.error("Không thể tải dashboard tài chính shop {}", currentShop.getId(), task.getException());
            statusLabel.setText(I18nService.getInstance().tr("finance.load_error"));
        });
        FinanceExecutor.executeQuery(task);
    }

    private void showData(FinanceDashboardSnapshot snapshot) {
        currentSnapshot = snapshot;
        dailyTable.setItems(FXCollections.observableArrayList(snapshot.days()));
        String currency = snapshot.days().isEmpty() ? "RUB" : snapshot.days().get(0).currency();
        setKpi(grossValueLabel, snapshot.grossSales(), currency);
        setKpi(payoutValueLabel, snapshot.netPayout(), currency);
        setKpi(commissionValueLabel, snapshot.commissionCost(), currency);
        setKpi(returnsValueLabel, snapshot.returnsAmount(), currency);
        setKpi(logisticsValueLabel, snapshot.logisticsCost(), currency);
        setKpi(advertisingValueLabel, snapshot.advertisingCost(), currency);
        setKpi(storageValueLabel, snapshot.storageCost(), currency);
        setKpi(penaltyValueLabel, snapshot.penaltyCost(), currency);
        setKpi(otherCostValueLabel, snapshot.otherCost(), currency);
        setKpi(netValueLabel, snapshot.netProfit(), currency);
        netValueLabel.getStyleClass().removeAll("finance-kpi-value-positive", "finance-kpi-value-negative");
        if (snapshot.netProfit() >= 0) {
            netValueLabel.getStyleClass().add("finance-kpi-value-positive");
        } else {
            netValueLabel.getStyleClass().add("finance-kpi-value-negative");
        }
        emptyLabel.setVisible(snapshot.days().isEmpty());
        emptyLabel.setManaged(snapshot.days().isEmpty());
    }

    private void setKpi(Label valueLabel, double amount, String currency) {
        valueLabel.setText(money(amount, currency));
    }

    private void configureColumns() {
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale()).format(cell.getValue().date())));
        salesColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().grossSales(), cell.getValue().currency()));
        returnsColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().returnsAmount(), cell.getValue().currency()));
        payoutColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().netPayout(), cell.getValue().currency()));
        commissionColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().commissionCost(), cell.getValue().currency()));
        advertisingColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().advertisingCost(), cell.getValue().currency()));
        penaltyColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().penaltyCost(), cell.getValue().currency()));
        logisticsColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().logisticsCost(), cell.getValue().currency()));
        storageColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().storageCost(), cell.getValue().currency()));
        otherCostColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().otherCost(), cell.getValue().currency()));
        netColumn.setCellValueFactory(cell -> moneyProperty(cell.getValue().netProfit(), cell.getValue().currency()));
    }

    private ReadOnlyStringWrapper moneyProperty(double value, String currency) {
        return new ReadOnlyStringWrapper(money(value, currency));
    }

    private String money(double value, String currency) {
        NumberFormat format = NumberFormat.getNumberInstance(locale());
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(value) + " " + (currency == null || currency.isBlank() ? "RUB" : currency);
    }

    private Locale locale() {
        return I18nService.getInstance().getCurrentLanguage().toLocale();
    }

    private void setLoading(boolean loading) {
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        refreshButton.setDisable(loading);
    }

    private void clearData() {
        ++requestToken;
        currentSnapshot = null;
        dailyTable.getItems().clear();
        for (Label label : new Label[]{grossValueLabel, returnsValueLabel, payoutValueLabel,
                commissionValueLabel, advertisingValueLabel, penaltyValueLabel, logisticsValueLabel, storageValueLabel,
                otherCostValueLabel, netValueLabel}) {
            label.setText("0");
        }
        statusLabel.setText("");
        emptyLabel.setVisible(true);
        emptyLabel.setManaged(true);
        setLoading(false);
    }

    private void updateMarketplaceTexts() {
        if (titleLabel == null || payoutTitleLabel == null || syncButton == null) return;
        I18nService i18n = I18nService.getInstance();
        String marketplace = shop == null ? "" : shop.getMarketplace().badge();
        titleLabel.setText(i18n.tr("finance.title") + (marketplace.isBlank() ? "" : " · " + marketplace));
        payoutTitleLabel.setText((marketplace.isBlank() ? "" : marketplace + " ")
                + i18n.tr("finance.kpi.payout"));
        syncButton.setText(i18n.tr("finance.sync") + (marketplace.isBlank() ? "" : " " + marketplace));
    }
}
