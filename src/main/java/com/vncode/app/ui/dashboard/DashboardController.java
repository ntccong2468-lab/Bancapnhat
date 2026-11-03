package com.vncode.app.ui.dashboard;

import com.vncode.app.features.dashboard.DashboardKpis;
import com.vncode.app.features.dashboard.DashboardRepository;
import com.vncode.app.features.finance.FinancePenaltyEntry;
import com.vncode.app.features.finance.FinancePenaltySyncService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

public class DashboardController {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardController.class);
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    private final DashboardRepository dashboardRepository = new DashboardRepository();
    private final FinancePenaltySyncService penaltyService = new FinancePenaltySyncService();
    private final javafx.animation.PauseTransition penaltyPoll = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
    private static final int PENALTY_PAGE_SIZE = 50;

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
    @FXML private Label penaltyTitleLabel;
    @FXML private Label penaltyHelpLabel;
    @FXML private Label penaltyFromLabel;
    @FXML private Label penaltyToLabel;
    @FXML private Label penaltyStatusLabel;
    @FXML private Label penaltyPageLabel;
    @FXML private DatePicker penaltyFromDate;
    @FXML private DatePicker penaltyToDate;
    @FXML private Button penaltySyncButton;
    @FXML private Button penaltyPreviousButton;
    @FXML private Button penaltyNextButton;
    @FXML private TableView<FinancePenaltyEntry> penaltyTable;
    @FXML private TableColumn<FinancePenaltyEntry, String> penaltyDateColumn;
    @FXML private TableColumn<FinancePenaltyEntry, String> penaltyDescriptionColumn;
    @FXML private TableColumn<FinancePenaltyEntry, String> penaltyOrderColumn;
    @FXML private TableColumn<FinancePenaltyEntry, String> penaltyProductColumn;
    @FXML private TableColumn<FinancePenaltyEntry, String> penaltyAmountColumn;

    private Shop shop;
    private long requestToken;
    private Integer loadedShopId;
    private boolean hasLoadedData;
    private boolean loading;
    private long penaltyRequestToken;
    private long penaltyCount;
    private int penaltyOffset;
    private String dashboardStatusKey = "";
    private String penaltyStatusKey = "";
    private String penaltyPlaceholderKey = "dashboard.penalty.owner_unverified";
    private Instant penaltyLastRead;

    @FXML
    private void initialize() {
        penaltyPoll.setOnFinished(event -> { if (shop != null) loadPenalties(); });
        LocalDate today = LocalDate.now();
        penaltyFromDate.setConverter(dateConverter());
        penaltyToDate.setConverter(dateConverter());
        penaltyFromDate.setValue(today.minusDays(29));
        penaltyToDate.setValue(today);
        penaltyDateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().date() == null ? "—" : dateFormatter().format(cell.getValue().date())));
        penaltyDescriptionColumn.setCellValueFactory(cell -> textValue(cell.getValue().description()));
        penaltyOrderColumn.setCellValueFactory(cell -> textValue(cell.getValue().orderId()));
        penaltyProductColumn.setCellValueFactory(cell -> textValue(firstNonBlank(
                cell.getValue().article(), cell.getValue().sku(), cell.getValue().productId())));
        penaltyAmountColumn.setCellValueFactory(cell -> {
            var format = NumberFormat.getNumberInstance(displayLocale());
            format.setMinimumFractionDigits(2);
            format.setMaximumFractionDigits(2);
            String currency = cell.getValue().currency();
            return new ReadOnlyStringWrapper(format.format(cell.getValue().amount()) +
                    (currency == null || currency.isBlank() ? "" : " " + currency));
        });
        penaltyAmountColumn.setStyle("-fx-alignment: CENTER-RIGHT;");
        penaltyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        penaltyTable.setPlaceholder(new Label());
        applyTranslations();
        clearPenalties();
    }

    public void setShop(Shop shop, boolean forceRefresh) {
        Shop previous = this.shop;
        this.shop = snapshot(shop);
        updatePenaltyHelp();
        if (this.shop == null) {
            ++requestToken;
            ++penaltyRequestToken;
            setLoading(false);
            clear();
            clearPenalties();
            return;
        }
        boolean sameShop = sameScope(previous, this.shop);
        if (!sameShop) {
            ++penaltyRequestToken;
            setLoading(false);
            clear();
            clearPenalties();
        }
        if (!forceRefresh && sameShop && (loading || (hasLoadedData && loadedShopId != null && loadedShopId == this.shop.getId()))) {
            return;
        }
        load();
        loadPenalties();
    }

    public void refresh() {
        if (shop != null) {
            load();
            loadPenalties();
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
        penaltyTitleLabel.setText(i18n.tr("dashboard.penalty.title"));
        updatePenaltyHelp();
        penaltyFromLabel.setText(i18n.tr("dashboard.penalty.from"));
        penaltyToLabel.setText(i18n.tr("dashboard.penalty.to"));
        penaltySyncButton.setText(i18n.tr("dashboard.penalty.sync"));
        penaltyPreviousButton.setText(i18n.tr("dashboard.penalty.previous"));
        penaltyNextButton.setText(i18n.tr("dashboard.penalty.next"));
        penaltyDateColumn.setText(i18n.tr("dashboard.penalty.date"));
        penaltyDescriptionColumn.setText(i18n.tr("dashboard.penalty.description"));
        penaltyOrderColumn.setText(i18n.tr("dashboard.penalty.order"));
        penaltyProductColumn.setText(i18n.tr("dashboard.penalty.product"));
        penaltyAmountColumn.setText(i18n.tr("dashboard.penalty.amount"));
        ((Label)penaltyTable.getPlaceholder()).setText(i18n.tr(penaltyPlaceholderKey));
        refreshDateText(penaltyFromDate);
        refreshDateText(penaltyToDate);
        statusLabel.setText(dashboardStatusKey.isEmpty() ? "" : i18n.tr(dashboardStatusKey));
        renderPenaltyStatus();
        penaltyTable.refresh();
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    @FXML private void onPenaltyRangeChanged() {
        penaltyOffset = 0;
        if (shop != null) loadPenalties();
    }

    @FXML private void onPenaltyPrevious() {
        if (shop == null || penaltyOffset == 0) return;
        penaltyOffset = Math.max(0, penaltyOffset - PENALTY_PAGE_SIZE);
        loadPenalties();
    }

    @FXML private void onPenaltyNext() {
        if (shop == null || (long)penaltyOffset + PENALTY_PAGE_SIZE >= penaltyCount) return;
        penaltyOffset += PENALTY_PAGE_SIZE;
        loadPenalties();
    }

    @FXML private void onPenaltySync() {
        if (shop == null) return;
        LocalDate from = penaltyFromDate.getValue(), to = penaltyToDate.getValue();
        if (from == null || to == null || from.isAfter(to)) { setPenaltyStatus("dashboard.penalty.invalid_range"); return; }
        Shop current = shop;
        penaltyPoll.stop();
        long token = ++penaltyRequestToken;
        penaltyTable.getItems().clear(); penaltyCount = 0; penaltyPageLabel.setText("0 / 0");
        penaltySyncButton.setDisable(true);
        setPenaltyStatus("dashboard.penalty.sync_queued");
        Task<Void> task = new Task<>() { @Override protected Void call() { penaltyService.request(current, from, to); return null; } };
        task.setOnSucceeded(event -> {
            if (token != penaltyRequestToken || !sameScope(current, shop)) return;
            penaltySyncButton.setDisable(false); penaltyOffset = 0; loadPenalties();
        });
        java.util.function.Consumer<Throwable> failure = error -> {
            if (token != penaltyRequestToken || !sameScope(current, shop)) return;
            penaltySyncButton.setDisable(false);
            String code = error == null ? "" : java.util.Objects.toString(error.getMessage(), "");
            setPenaltyStatus(switch (code) {
                case "OZON_PENALTY_UNAVAILABLE" -> "dashboard.penalty.ozon_unavailable";
                case "WB_PENALTY_INVALID_RANGE" -> "dashboard.penalty.wb_range";
                default -> "dashboard.penalty.load_error";
            });
            setPenaltyPlaceholder(penaltyStatusKey);
        };
        task.setOnFailed(event -> failure.accept(task.getException()));
        AppTaskExecutor.execute(task, failure);
    }

    private record PenaltyPage(List<FinancePenaltyEntry> rows, long total, String statusKey, Instant lastRead, boolean active) { }

    private void loadPenalties() {
        Shop current = shop;
        if (current == null) return;
        penaltyPoll.stop();
        penaltySyncButton.setDisable(false);
        long token = ++penaltyRequestToken;
        LocalDate from = penaltyFromDate.getValue(), to = penaltyToDate.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            penaltyTable.getItems().clear();
            penaltyCount = 0;
            penaltyPageLabel.setText("0 / 0");
            penaltyPreviousButton.setDisable(true);
            penaltyNextButton.setDisable(true);
            setPenaltyStatus("dashboard.penalty.invalid_range");
            return;
        }
        if (current.getMarketplace() == Marketplace.OZON) {
            clearPenalties();
            setPenaltyStatus("dashboard.penalty.ozon_unavailable");
            setPenaltyPlaceholder("dashboard.penalty.ozon_unavailable");
            return;
        }
        int offset = penaltyOffset;
        // Do not retain a previous result while the database owner is being checked again.
        penaltyTable.getItems().clear();
        setPenaltyPlaceholder("dashboard.status.loading");
        penaltyPreviousButton.setDisable(true);
        penaltyNextButton.setDisable(true);
        setPenaltyStatus("dashboard.status.loading");
        Task<PenaltyPage> task = new Task<>() {
            @Override protected PenaltyPage call() {
                var scope = penaltyService.status(current, from, to);
                String status = penaltyStatusFor(scope.status(), scope.failureCode());
                boolean active = List.of("QUEUED", "WAITING", "IN_PROGRESS").contains(scope.status());
                long total = scope.verified() ? penaltyService.count(current, from, to) : 0;
                return new PenaltyPage(scope.verified() ? penaltyService.load(current, from, to, PENALTY_PAGE_SIZE, offset) : List.of(),
                        total, status, scope.nextRunAt() != null && "WAITING".equals(scope.status()) ? scope.nextRunAt() : scope.lastReadAt(), active);
            }
        };
        task.setOnSucceeded(event -> {
            if (token != penaltyRequestToken || !sameScope(current, shop)) return;
            PenaltyPage page = task.getValue();
            setPenaltyPlaceholder("dashboard.penalty.sync_complete".equals(page.statusKey())
                    ? "dashboard.penalty.empty" : page.statusKey());
            penaltyCount = page.total();
            if (offset > 0 && offset >= penaltyCount) {
                penaltyOffset = penaltyCount == 0 ? 0 : (int)((penaltyCount - 1) / PENALTY_PAGE_SIZE * PENALTY_PAGE_SIZE);
                loadPenalties();
                return;
            }
            penaltyTable.setItems(FXCollections.observableArrayList(page.rows()));
            penaltyPageLabel.setText(penaltyCount == 0 ? "0 / 0" : (offset + 1) + "–" +
                    (offset + page.rows().size()) + " / " + penaltyCount);
            penaltyPreviousButton.setDisable(offset == 0);
            penaltyNextButton.setDisable((long)offset + page.rows().size() >= penaltyCount);
            setPenaltyStatus(page.statusKey(), page.lastRead());
            if (page.active()) penaltyPoll.playFromStart();
        });
        java.util.function.Consumer<Throwable> failure = error -> {
            if (token != penaltyRequestToken || !sameScope(current, shop)) return;
            LOGGER.warn("Cannot load saved penalty transactions for shop {}", current.getId(), error);
            penaltyTable.getItems().clear();
            penaltyCount = 0;
            penaltyPageLabel.setText("0 / 0");
            setPenaltyPlaceholder("dashboard.penalty.load_error");
            penaltyPreviousButton.setDisable(true);
            penaltyNextButton.setDisable(true);
            setPenaltyStatus("dashboard.penalty.load_error");
        };
        task.setOnFailed(event -> failure.accept(task.getException()));
        AppTaskExecutor.execute(task, failure);
    }

    private void clearPenalties() {
        penaltyPoll.stop();
        penaltyOffset = 0;
        penaltyCount = 0;
        penaltyTable.getItems().clear();
        setPenaltyPlaceholder("dashboard.penalty.owner_unverified");
        penaltyPageLabel.setText("0 / 0");
        setPenaltyStatus("");
        penaltyPreviousButton.setDisable(true);
        penaltyNextButton.setDisable(true);
        penaltySyncButton.setDisable(shop == null);
    }

    private static String penaltyStatusFor(String status, String code) {
        if (code != null) {
            String key = switch (code) {
                case "WB_EMPTY_PAGE_UNCONFIRMED" -> "empty_unconfirmed";
                case "WB_429_DELAY_UNKNOWN", "WB_429_RETRY_AFTER" -> "rate_wait";
                case "WB_FINANCE_PERMISSION" -> "permission";
                case "WB_FINANCE_UNAVAILABLE", "WB_FINANCE_INVALID_PAGE" -> "source_error";
                default -> null;
            };
            if (key != null) return "dashboard.penalty." + key;
        }
        return switch (status) {
            case "COMPLETE" -> "dashboard.penalty.sync_complete";
            case "QUEUED" -> "dashboard.penalty.sync_queued";
            case "WAITING", "IN_PROGRESS" -> "dashboard.penalty.sync_in_progress";
            case "ERROR" -> "dashboard.penalty.sync_error";
            case "CANCELLED" -> "dashboard.penalty.sync_cancelled";
            default -> "dashboard.penalty.owner_unverified";
        };
    }

    private static ReadOnlyStringWrapper textValue(String value) {
        return new ReadOnlyStringWrapper(value == null || value.isBlank() ? "—" : value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

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

    private static Locale displayLocale() {
        return I18nService.getInstance().getCurrentLanguage().toLocale();
    }

    private void setPenaltyPlaceholder(String key) {
        penaltyPlaceholderKey = key;
        ((Label) penaltyTable.getPlaceholder()).setText(I18nService.getInstance().tr(key));
    }

    private static DateTimeFormatter dateFormatter() {
        String pattern = switch (I18nService.getInstance().getCurrentLanguage()) {
            case VI -> "dd/MM/uuuu";
            case RU -> "dd.MM.uuuu";
            case ZH -> "uuuu/MM/dd";
            case EN -> "MM/dd/uuuu";
        };
        return DateTimeFormatter.ofPattern(pattern, displayLocale()).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    private static StringConverter<LocalDate> dateConverter() {
        return new StringConverter<>() {
            @Override public String toString(LocalDate value) { return value == null ? "" : dateFormatter().format(value); }
            @Override public LocalDate fromString(String value) {
                if (value == null || value.isBlank()) return null;
                try { return LocalDate.parse(value.strip(), dateFormatter()); }
                catch (java.time.format.DateTimeParseException invalid) { return null; }
            }
        };
    }

    private static void refreshDateText(DatePicker picker) {
        if (!picker.isFocused() && !picker.getEditor().isFocused())
            picker.getEditor().setText(picker.getConverter().toString(picker.getValue()));
    }

    private void setPenaltyStatus(String key) {
        setPenaltyStatus(key, null);
    }

    private void setPenaltyStatus(String key, Instant lastRead) {
        penaltyStatusKey = key;
        penaltyLastRead = lastRead;
        renderPenaltyStatus();
    }

    private void renderPenaltyStatus() {
        String text = penaltyStatusKey.isEmpty() ? "" : I18nService.getInstance().tr(penaltyStatusKey);
        if (penaltyLastRead != null) text += " · " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(displayLocale()).withZone(ZoneId.systemDefault()).format(penaltyLastRead);
        penaltyStatusLabel.setText(text);
    }

    private void updatePenaltyHelp() {
        if (penaltyHelpLabel == null) return;
        String key = shop != null && shop.getMarketplace() == Marketplace.OZON
                ? "dashboard.penalty.ozon_help" : "dashboard.penalty.help";
        penaltyHelpLabel.setText(I18nService.getInstance().tr(key));
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
