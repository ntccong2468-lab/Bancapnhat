package com.vncode.app.ui.znack;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.ZnackPurchaseCoordinator;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackGtinAutoSync;
import com.vncode.app.integration.znack.ZnackProductService;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.integration.znack.BalanceSnapshot;
import com.vncode.app.integration.znack.ChestnyZnakBalanceClient;
import com.vncode.app.integration.znack.ZnackAccountInfo;
import com.vncode.app.features.gs1.Gs1BalanceRepository;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import com.vncode.app.ui.controls.CategoryFilterMenu;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.util.StringConverter;

import java.text.MessageFormat;
import java.text.NumberFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ZnackAutomationController {
    private static final DateTimeFormatter CERTIFICATE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    @FXML private Label titleLabel, authStatusLabel, signatureSummaryLabel;
    @FXML private Label settingsTitleLabel, omsIdLabel, omsConnectionLabel;
    @FXML private Label omsHelpTitleLabel, omsHelpDescriptionLabel, omsHelpStepsTitleLabel, omsHelpStepsLabel;
    @FXML private Label omsHelpRecognizeTitleLabel, omsHelpRecognizeLabel, omsHelpWarningLabel;
    @FXML private Label signatureTitleLabel;
    private final com.vncode.app.ui.kiz.KizWarehousePane warehousePane=new com.vncode.app.ui.kiz.KizWarehousePane();
    private final Tab warehouseTab=new Tab();
    @FXML private Tab informationTab, settingsTab, productsTab, deletedTab, ordersTab, logsTab;
    @FXML private Label accountOwnerTitleLabel, accountOwnerLabel, accountInnLabel, accountStatusLabel;
    @FXML private Label accountBalanceTitleLabel, accountBalanceLabel, accountBalanceUpdatedLabel;
    @FXML private Label accountCertificateTitleLabel, accountCertificateLabel, accountCertificateDaysLabel, accountEvidenceLabel;
    @FXML private Button refreshAccountButton, accountSettingsButton;
    @FXML private ProgressIndicator accountBusyIndicator;
    @FXML private ComboBox<BalanceSnapshot> accountBalanceCombo;
    public void openSettings(){settingsTab.getTabPane().getSelectionModel().select(settingsTab);}
    @FXML private TextField omsIdField, omsConnectionField;
    @FXML private PasswordField catalogKeyField;
    @FXML private Label catalogTitleLabel, catalogHelpLabel, catalogStatusLabel;
    @FXML private Button checkCatalogButton, removeCatalogButton;
    private Shop catalogShop;
    private boolean catalogCheckRunning;
    private String catalogStatusKey = "znack.catalog.not_saved";
    @FXML private TextField productSearchField, deletedSearchField;
    @FXML private ComboBox<CryptoProCertificateInfo> signatureCertificateCombo;
    @FXML private CheckBox autoIntroductionCheck;
    @FXML private Button saveButton, testSignatureButton, deleteSelectedButton;
    @FXML private ProgressIndicator productBusyIndicator;
    @FXML private MenuButton productCategoryFilterButton, deletedCategoryFilterButton;
    @FXML private Button omsIdHelpButton, omsConnectionHelpButton, closeOmsHelpButton;
    @FXML private javafx.scene.layout.VBox omsHelpPane;
    @FXML private javafx.scene.layout.GridPane connectionPanels;
    @FXML private javafx.scene.layout.VBox catalogPanel, signaturePanel;
    private int connectionColumns;
    @FXML private TableView<Product> productsTable;
    @FXML private TableColumn<Product,String> productGtinColumn, productNameColumn, productCategoryColumn, productTnVedColumn;
    @FXML private TableColumn<Product,Product> productSelectColumn, productActionsColumn;
    @FXML private TableView<Product> deletedTable;
    @FXML private TableColumn<Product,String> deletedGtinColumn, deletedNameColumn;
    @FXML private TableColumn<Product,Product> deletedActionsColumn;
    @FXML private TableView<KizOrder> ordersTable;
    @FXML private TableColumn<KizOrder,Number> orderIdColumn;
    @FXML private TableColumn<KizOrder,String> orderGtinColumn, orderStatusColumn;
    @FXML private TableView<OperationLog> logsTable;
    @FXML private TableColumn<OperationLog,String> logTimeColumn, logActionColumn, logEntityColumn, logSeverityColumn, logResultColumn;

    private static final java.util.Comparator<Product> BY_CATEGORY_THEN_GTIN = java.util.Comparator
            .comparing((Product p) -> p.category() == null || p.category().isBlank() ? 1 : 0)
            .thenComparing(p -> value(p.category()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Product::gtin);
    private final CheckBox selectAllProducts = new CheckBox();
    private final java.util.Set<String> selectedProductGtins = new java.util.HashSet<>();
    private CategoryFilterMenu productCategoryFilter;
    private CategoryFilterMenu deletedCategoryFilter;
    private List<Product> allProducts = List.of();
    private List<Product> allDeletedProducts = List.of();
    private volatile ZnackRepository repository;
    private volatile Settings loaded = Settings.empty();
    private String signerCertificate = "";
    private String certificateMetadata = "";
    private Instant signerTestedAt;
    private String testedConfigurationKey;
    private String savedFingerprint = "";
    private String currentHelpTopic;
    private boolean loading;
    private boolean certificateDiscoveryRunning;
    private boolean updatingCertificateSelection;
    private boolean reopeningCertificatePopup;
    private boolean productSyncRunning;
    private boolean signerChangeSyncPending;
    private boolean signatureTestRunning;
    private long certificateDiscoveryGeneration;
    private volatile long shopGeneration;
    private boolean accountRefreshRunning;
    private long accountRefreshGeneration;
    private Instant accountAuthenticatedAt;
    private ChestnyZnakBalanceClient.ServiceSession accountBalanceSession;
    private List<BalanceSnapshot> accountBalances = List.of();
    private boolean accountBalancesFromCurrentCheck;
    private boolean updatingBalanceChoice;
    private AccountReader accountReader = this::readAccount;

    @FunctionalInterface
    interface AccountReader {
        AccountRefresh read(Settings expected, ZnackRepository owner,
                            ChestnyZnakBalanceClient.ServiceSession existing,
                            java.util.function.BooleanSupplier active) throws Exception;
    }
    record AccountRefresh(ChestnyZnakBalanceClient.ServiceSession session, List<BalanceSnapshot> balances) {
        AccountRefresh { balances = List.copyOf(balances); }
        @Override public String toString() { return "AccountRefresh[scoped balance evidence]"; }
    }
    void accountReader(AccountReader reader) { accountReader = Objects.requireNonNull(reader); }

    @FXML
    private void initialize() {
        warehouseTab.setId("warehouseTab");warehouseTab.setClosable(false);warehouseTab.setContent(warehousePane);informationTab.getTabPane().getTabs().add(warehouseTab);
        warehouseTab.selectedProperty().addListener((o,a,b)->{if(b)warehousePane.reload();});
        connectionPanels.widthProperty().addListener((ignored,old,width)->arrangeConnectionPanels(width.doubleValue()));
        arrangeConnectionPanels(0);
        catalogKeyField.textProperty().addListener((ignored, old, text) -> {
            if (!loading) {
                catalogStatusKey = "znack.catalog.unchecked";
                catalogStatusLabel.setText(tr(catalogStatusKey));
            }
        });
        informationTab.selectedProperty().addListener((ignored, old, selected) -> {
            if (selected) updateAccountInfo();
        });
        accountBalanceCombo.setConverter(new StringConverter<>() {
            @Override public String toString(BalanceSnapshot balance) {
                return balance == null ? tr("znack.account.choose_balance")
                        : tr("gs1.balance_group") + " " + balance.scope().productGroup()
                          + " · " + tr("gs1.balance_account") + " " + balance.scope().accountId();
            }
            @Override public BalanceSnapshot fromString(String value) { return null; }
        });
        accountBalanceCombo.valueProperty().addListener((ignored, old, selected) -> {
            if (!updatingBalanceChoice) updateAccountInfo();
        });
        productGtinColumn.setCellValueFactory(v -> text(v.getValue().gtin()));
        productNameColumn.setCellValueFactory(v -> text(v.getValue().productName()));
        productCategoryColumn.setCellValueFactory(v -> text(v.getValue().category()));
        productTnVedColumn.setCellValueFactory(v -> text(v.getValue().tnVed()));
        productActionsColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        productActionsColumn.setCellFactory(column -> new ProductActionsCell());
        productSelectColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        productSelectColumn.setCellFactory(column -> new ProductSelectCell());
        productSelectColumn.setGraphic(selectAllProducts);
        selectAllProducts.setOnAction(event -> {
            if (selectAllProducts.isSelected()) {
                productsTable.getItems().forEach(product -> selectedProductGtins.add(product.gtin()));
            } else {
                selectedProductGtins.clear();
            }
            productsTable.refresh();
            updateProductSelectionState();
        });
        productSearchField.textProperty().addListener((ignored, old, value) -> applyProductFilter());
        deletedSearchField.textProperty().addListener((ignored, old, value) -> applyDeletedFilter());
        productCategoryFilter = new CategoryFilterMenu(productCategoryFilterButton, this::applyProductFilter);
        deletedCategoryFilter = new CategoryFilterMenu(deletedCategoryFilterButton, this::applyDeletedFilter);
        deletedGtinColumn.setCellValueFactory(v -> text(v.getValue().gtin()));
        deletedNameColumn.setCellValueFactory(v -> text(v.getValue().productName()));
        deletedActionsColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        deletedActionsColumn.setCellFactory(column -> new DeletedProductActionsCell());
        deletedTab.selectedProperty().addListener((ignored, old, selected) -> {
            if (selected) loadDeletedProducts();
        });
        orderIdColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleLongProperty(v.getValue().id()));
        orderGtinColumn.setCellValueFactory(v -> text(v.getValue().gtin()));
        orderStatusColumn.setCellValueFactory(v -> text(localizeStatus(v.getValue().localStatus().name())));
        logTimeColumn.setCellValueFactory(v -> text(v.getValue().createdAt().toString()));
        logActionColumn.setCellValueFactory(v -> text(v.getValue().action()));
        logEntityColumn.setCellValueFactory(v -> text(v.getValue().entityReference()));
        logSeverityColumn.setCellValueFactory(v -> text(v.getValue().severity()));
        logResultColumn.setCellValueFactory(v -> text(v.getValue().message()));
        configureLogCopy();
        signatureCertificateCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CryptoProCertificateInfo value) { return certificateDisplay(value); }
            @Override public CryptoProCertificateInfo fromString(String value) { return null; }
        });
        signatureCertificateCombo.setCellFactory(ignored -> certificateCell(true));
        signatureCertificateCombo.setButtonCell(certificateCell(false));
        signatureCertificateCombo.setVisibleRowCount(8);
        signatureCertificateCombo.setOnShowing(ignored -> {
            if (!reopeningCertificatePopup) loadCertificates();
        });
        productsTab.selectedProperty().addListener((ignored, old, selected) -> {
            if (selected) {
                loadProductsFromDatabase();
                requestProductSyncOnOpen();
            }
        });
        for (TextField field : List.of(omsIdField, omsConnectionField)) {
            field.textProperty().addListener((o, old, value) -> updateSaveState());
        }
        autoIntroductionCheck.selectedProperty().addListener((o, old, value) -> updateSaveState());
        signatureCertificateCombo.valueProperty().addListener((o, old, value) -> {
            if (updatingCertificateSelection) return;
            if (value == null) return;
            if (!loading && !certificateSelectable(value, Instant.now())) {
                updatingCertificateSelection = true;
                signatureCertificateCombo.setValue(old);
                updatingCertificateSelection = false;
                return;
            }
            signerCertificate = value.selector();
            if (!loading) {
                certificateMetadata = metadata(value);
                if (sameCertificate(selectedCertificate(), loaded.signerCertificate())
                        && loaded.signerTestedAt() != null) {
                    signerTestedAt = loaded.signerTestedAt();
                    testedConfigurationKey = configurationKey();
                } else if (!configurationKey().equals(testedConfigurationKey)) {
                    signerTestedAt = null;
                }
            }
            updateSignatureSummary();
            updateSaveState();
        });
        applyTranslations();
        clear();
    }

    private void arrangeConnectionPanels(double width) {
        int columns=width>=780?2:1;
        if(columns==connectionColumns)return;
        connectionColumns=columns;
        connectionPanels.getColumnConstraints().clear();
        for(int column=0;column<columns;column++) {
            var constraint=new javafx.scene.layout.ColumnConstraints();
            constraint.setPercentWidth(100.0/columns);constraint.setMinWidth(0);
            constraint.setHgrow(javafx.scene.layout.Priority.ALWAYS);connectionPanels.getColumnConstraints().add(constraint);
        }
        javafx.scene.layout.GridPane.setColumnIndex(catalogPanel,0);
        javafx.scene.layout.GridPane.setRowIndex(catalogPanel,0);
        javafx.scene.layout.GridPane.setColumnIndex(signaturePanel,columns==2?1:0);
        javafx.scene.layout.GridPane.setRowIndex(signaturePanel,columns==2?0:1);
        javafx.scene.layout.GridPane.setValignment(catalogPanel,javafx.geometry.VPos.TOP);
        javafx.scene.layout.GridPane.setValignment(signaturePanel,javafx.geometry.VPos.TOP);
    }

    public void setShop(Shop shop) {
        warehousePane.setShop(shop);
        if(warehouseTab.isSelected())warehousePane.reload();
        shopGeneration++;
        catalogCheckRunning = false;
        catalogShop = shop == null ? null : new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
        resetAccountSession();
        setSignatureTestBusy(false);
        productSyncRunning = false;
        signerChangeSyncPending = false;
        invalidateCertificateDiscovery();
        repository = shop == null ? null : new ZnackRepository(new ShopContext(shop.getId(), shop.getName()));
        if (repository == null) clear(); else {
            load();
            loadDeletedProducts();
            if (productsTab.isSelected()) requestProductSyncOnOpen();
        }
    }

    public void refresh() {
        if (repository != null) {
            invalidateCertificateDiscovery();
            load();
        }
    }

    public void applyTranslations() {
        warehouseTab.setText(I18nService.getInstance().tr("kiz.warehouse.title"));warehousePane.applyTranslations();
        omsConnectionField.setPromptText(tr("znack.oms_connection_prompt"));
        if(repository==null)authStatusLabel.setText(tr("znack.error.select_shop"));
        catalogTitleLabel.setText(tr("znack.catalog.title"));
        catalogHelpLabel.setText(tr("znack.catalog.help"));
        catalogKeyField.setPromptText(tr("znack.catalog.key_prompt"));
        checkCatalogButton.setText(tr("znack.catalog.check"));
        removeCatalogButton.setText(tr("znack.catalog.remove"));
        catalogStatusLabel.setText(tr(catalogStatusKey));
        titleLabel.setText(tr("znack.title"));
        informationTab.setText(tr("znack.tab.information"));
        settingsTab.setText(tr("znack.tab.settings"));
        productsTab.setText(tr("znack.tab.products"));
        deletedTab.setText(tr("znack.tab.deleted"));
        ordersTab.setText(tr("znack.tab.kiz_history"));
        logsTab.setText(tr("znack.tab.logs"));
        productSearchField.setPromptText(tr("kiz_mapping.search_gtin"));
        deletedSearchField.setPromptText(tr("kiz_mapping.search_gtin"));
        productCategoryFilter.setTexts(tr("znack.filter.button"), tr("znack.filter.no_category"), tr("znack.filter.clear"));
        deletedCategoryFilter.setTexts(tr("znack.filter.button"), tr("znack.filter.no_category"), tr("znack.filter.clear"));
        deleteSelectedButton.setTooltip(new Tooltip(tr("znack.products.delete_selected")));
        deleteSelectedButton.setAccessibleText(tr("znack.products.delete_selected"));
        deletedGtinColumn.setText(tr("znack.field.gtin"));
        deletedNameColumn.setText(tr("znack.field.name"));
        deletedActionsColumn.setText(tr("znack.field.actions"));
        settingsTitleLabel.setText(tr("znack.settings.basic"));
        omsIdLabel.setText(tr("znack.oms_id"));
        omsConnectionLabel.setText(tr("znack.oms_connection"));
        omsIdHelpButton.setTooltip(new Tooltip(tr("common.help")));
        omsConnectionHelpButton.setTooltip(new Tooltip(tr("common.help")));
        omsIdHelpButton.setAccessibleText(tr("znack.help.oms_id.button"));
        omsConnectionHelpButton.setAccessibleText(tr("znack.help.oms_connection.button"));
        closeOmsHelpButton.setText(tr("common.close"));
        omsHelpStepsTitleLabel.setText(tr("znack.help.steps_title"));
        omsHelpRecognizeTitleLabel.setText(tr("znack.help.recognize_title"));
        signatureTitleLabel.setText(tr("znack.digital_signature"));
        testSignatureButton.setText(tr("znack.signature.test"));
        testSignatureButton.setTooltip(new Tooltip(tr("znack.signature.help")));
        autoIntroductionCheck.setText(tr("znack.auto_introduction"));
        saveButton.setText(tr("znack.save"));
        productGtinColumn.setText(tr("znack.field.gtin"));
        productNameColumn.setText(tr("znack.field.name"));
        productCategoryColumn.setText(tr("znack.field.category"));
        productTnVedColumn.setText(tr("znack.field.tn_ved"));
        productActionsColumn.setText(tr("znack.field.actions"));
        orderIdColumn.setText(tr("znack.field.id"));
        orderGtinColumn.setText(tr("znack.field.gtin"));
        orderStatusColumn.setText(tr("znack.field.status"));
        logTimeColumn.setText(tr("znack.field.time"));
        logActionColumn.setText(tr("znack.field.action"));
        logEntityColumn.setText(tr("znack.field.entity"));
        logSeverityColumn.setText(tr("znack.field.severity"));
        logResultColumn.setText(tr("znack.field.result"));
        accountOwnerTitleLabel.setText(tr("znack.account.owner"));
        accountBalanceTitleLabel.setText(tr("znack.account.balance"));
        accountCertificateTitleLabel.setText(tr("znack.account.certificate"));
        refreshAccountButton.setText(tr("znack.account.refresh"));
        accountSettingsButton.setText(tr("znack.account.open_settings"));
        accountBalanceCombo.setPromptText(tr("znack.account.choose_balance"));
        if (currentHelpTopic != null) showOmsHelp(currentHelpTopic);
        signatureCertificateCombo.setButtonCell(certificateCell(false));
        updateSignatureSummary();
        updateAccountInfo();
    }

    @FXML
    private void showOmsIdHelp() {
        showOmsHelp("oms_id");
    }

    @FXML
    private void showOmsConnectionHelp() {
        showOmsHelp("oms_connection");
    }

    @FXML
    private void closeOmsHelp() {
        currentHelpTopic = null;
        omsHelpPane.setVisible(false);
        omsHelpPane.setManaged(false);
    }

    private void showOmsHelp(String topic) {
        currentHelpTopic = topic;
        omsHelpTitleLabel.setText(tr("znack.help." + topic + ".title"));
        omsHelpDescriptionLabel.setText(tr("znack.help." + topic + ".description"));
        omsHelpStepsLabel.setText(tr("znack.help." + topic + ".steps"));
        omsHelpRecognizeLabel.setText(tr("znack.help." + topic + ".recognize"));
        omsHelpWarningLabel.setText(tr("znack.help." + topic + ".warning"));
        omsHelpPane.setManaged(true);
        omsHelpPane.setVisible(true);
    }

    @FXML
    private void save() {
        Settings settings = settings();
        if (blank(settings.omsId()) || blank(settings.omsConnection())) {
            AlertService.showError(tr("znack.error.oms_required"));
            return;
        }
        boolean removingCertificate = !blank(loaded.signerCertificate()) && blank(settings.signerCertificate());
        if (removingCertificate || (!blank(settings.signerCertificate()) && settings.signerTestedAt() == null)) {
            AlertService.showError(tr("znack.signature.error.verify_required"));
            return;
        }
        try {
            repository.saveSettings(settings);
            repository.log("SETTINGS_SAVE", null, "INFO", "SAVED", null);
            loaded = settings;
            resetAccountSession();
            savedFingerprint = fingerprint();
            authStatusLabel.setText(tr("znack.status.saved"));
            updateSaveState();
            resumeEligibleIntroductions(settings);
            if (productsTab.isSelected()) requestProductSync();
        } catch (IllegalArgumentException e) {
            AlertService.showError(e.getMessage());
        }
    }

    @FXML
    private void showAccountSettings() { openSettings(); }

    @FXML
    private void refreshAccountInfo() {
        Settings expected = loaded;
        var identity = ZnackAccountInfo.identity(expected, Instant.now()).orElse(null);
        if (repository == null || accountRefreshRunning || signatureTestRunning || productSyncRunning
                || !Objects.equals(savedFingerprint, fingerprint()) || identity == null
                || !identity.networkUsable(Instant.now())) return;
        long generation = shopGeneration;
        long refreshGeneration = ++accountRefreshGeneration;
        ZnackRepository owner = repository;
        String expectedUi = fingerprint();
        var existing = accountBalanceSession;
        AccountReader reader = accountReader;
        accountRefreshRunning = true;
        updateAccountInfo();
        updateSaveState();
        Task<AccountRefresh> task = new Task<>() {
            @Override protected AccountRefresh call() throws Exception {
                LicenseRuntime.require(Permission.GS1);
                var result = reader.read(expected, owner, existing,
                        () -> generation == shopGeneration && owner == repository && loaded.equals(expected));
                requireAccountScope(expected, owner,
                        () -> generation == shopGeneration && owner == repository && loaded.equals(expected));
                var scoped = ZnackAccountInfo.scopedBalances(identity, result.balances());
                if (scoped.size() != result.balances().size() || result.session() == null
                        || !"ELK".equals(result.session().authKind())
                        || !identity.inn().equals(result.session().verifiedInn())
                        || !identity.environment().equals(result.session().environment())
                        || !ChestnyZnakBalanceClient.PRODUCTION.equals(result.session().base())
                        || !result.session().expiresAt().isAfter(Instant.now()))
                    throw new IllegalStateException("Account balance scope could not be verified.");
                for (var balance : scoped) {
                    requireAccountScope(expected, owner,
                            () -> generation == shopGeneration && owner == repository && loaded.equals(expected));
                    new Gs1BalanceRepository().save(balance);
                }
                return result;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || refreshGeneration != accountRefreshGeneration || owner != repository) return;
            accountRefreshRunning = false;
            if (!expected.equals(loaded) || !expectedUi.equals(fingerprint())) {
                accountBalanceSession = null;
                accountBalancesFromCurrentCheck = false;
                updateAccountInfo();
                updateSaveState();
                return;
            }
            AccountRefresh result = task.getValue();
            accountBalanceSession = result.session();
            accountBalancesFromCurrentCheck = true;
            applyAccountBalances(result.balances());
            authStatusLabel.setText(tr("znack.account.refreshed"));
            updateSaveState();
        });
        java.util.function.Consumer<Throwable> failure = ignored -> {
            if (generation != shopGeneration || refreshGeneration != accountRefreshGeneration || owner != repository) return;
            accountRefreshRunning = false;
            accountBalanceSession = null;
            accountBalancesFromCurrentCheck = false;
            authStatusLabel.setText(tr("znack.account.refresh_failed"));
            updateAccountInfo();
            updateSaveState();
        };
        task.setOnFailed(event -> failure.accept(task.getException()));
        AppTaskExecutor.execute(task, failure);
    }

    private AccountRefresh readAccount(Settings expected, ZnackRepository owner,
                                       ChestnyZnakBalanceClient.ServiceSession existing,
                                       java.util.function.BooleanSupplier active) throws Exception {
        requireAccountScope(expected, owner, active);
        var identity = ZnackAccountInfo.identity(expected, Instant.now()).orElseThrow();
        var client = new ChestnyZnakBalanceClient();
        var session = existing;
        if (session == null || !"ELK".equals(session.authKind())
                || !identity.inn().equals(session.verifiedInn()) || !identity.environment().equals(session.environment())
                || !ChestnyZnakBalanceClient.PRODUCTION.equals(session.base())
                || !session.expiresAt().isAfter(Instant.now())) {
            var provider = new CryptoProSignatureProvider(expected.cryptcpPath(), expected.signerCertificate(),
                    Duration.ofSeconds(expected.resolvedCryptoProTimeoutSeconds()));
            ZnackSignatureProvider signer = (payload, context) -> {
                requireAccountScope(expected, owner, active);
                var signed = provider.sign(payload, context);
                var actual = CryptoProSignedCertificateReader.read(signed, expected.signerCertificate());
                recoverCertificateSettings(expected, actual, Instant.now());
                requireAccountScope(expected, owner, active);
                return signed;
            };
            session = client.authenticate(identity.inn(), identity.fingerprint(), signer);
        }
        requireAccountScope(expected, owner, active);
        var balances = client.readAccounts(session);
        requireAccountScope(expected, owner, active);
        return new AccountRefresh(session, balances);
    }

    private static void requireAccountScope(Settings expected, ZnackRepository owner,
                                             java.util.function.BooleanSupplier active) {
        LicenseRuntime.require(Permission.GS1);
        var identity = ZnackAccountInfo.identity(expected, Instant.now()).orElse(null);
        if (!active.getAsBoolean() || identity == null || !identity.networkUsable(Instant.now())
                || !expected.equals(owner.getSettings()))
            throw new IllegalStateException("Account configuration changed or its certificate is unavailable.");
    }

    private void resetAccountSession() {
        accountRefreshGeneration++;
        accountRefreshRunning = false;
        accountAuthenticatedAt = null;
        accountBalanceSession = null;
        accountBalancesFromCurrentCheck = false;
        accountBalances = List.of();
        if (accountBalanceCombo != null) applyAccountBalances(List.of());
    }

    private void loadCachedAccountBalances() {
        var identity = ZnackAccountInfo.identity(loaded, Instant.now()).orElse(null);
        if (repository == null || identity == null) { applyAccountBalances(List.of()); return; }
        long generation = shopGeneration;
        long refreshGeneration = accountRefreshGeneration;
        Settings expected = loaded;
        Task<List<BalanceSnapshot>> task = new Task<>() {
            @Override protected List<BalanceSnapshot> call() {
                return new Gs1BalanceRepository().list(identity.inn(), identity.environment());
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || refreshGeneration != accountRefreshGeneration
                    || !expected.equals(loaded) || accountRefreshRunning || accountBalancesFromCurrentCheck) return;
            applyAccountBalances(task.getValue());
        });
        // A cache miss does not imply failed remote authentication or trigger a signing prompt.
        AppTaskExecutor.execute(task, ignored -> {});
    }

    private void applyAccountBalances(List<BalanceSnapshot> values) {
        var identity = ZnackAccountInfo.identity(loaded, Instant.now()).orElse(null);
        var previous = accountBalanceCombo.getValue();
        accountBalances = ZnackAccountInfo.scopedBalances(identity, values);
        updatingBalanceChoice = true;
        try {
            accountBalanceCombo.getItems().setAll(accountBalances);
            accountBalanceCombo.setValue(previous == null ? (accountBalances.size() == 1 ? accountBalances.getFirst() : null)
                    : accountBalances.stream().filter(value -> value.scope().equals(previous.scope())).findFirst()
                        .orElse(accountBalances.size() == 1 ? accountBalances.getFirst() : null));
        } finally { updatingBalanceChoice = false; }
        updateAccountInfo();
    }

    private void updateAccountInfo() {
        if (accountOwnerLabel == null) return;
        Instant now = Instant.now();
        var identity = ZnackAccountInfo.identity(loaded, now).orElse(null);
        boolean savedConfiguration = Objects.equals(savedFingerprint, fingerprint());
        boolean valid = identity != null && identity.networkUsable(now);
        accountOwnerLabel.setText(identity == null ? tr("znack.account.owner_unknown") : identity.ownerName());
        accountInnLabel.setText(identity == null ? tr("znack.account.inn_unknown") : "INN " + identity.inn());
        boolean liveTrueApi = savedConfiguration && ZnackAccountInfo.currentLogin(identity, accountAuthenticatedAt, now);
        accountStatusLabel.setText(tr(!savedConfiguration ? "znack.account.unsaved"
                : identity == null ? "znack.account.unverified"
                : !"production".equals(identity.environment()) ? "znack.account.unsupported"
                : !valid ? identity.certificate().validFrom().isAfter(now)
                    ? "gs1.readiness.signature.not_yet_valid" : "znack.account.expired"
                : liveTrueApi ? "znack.account.authenticated" : "znack.account.not_checked"));
        accountStatusLabel.getStyleClass().removeAll("activation-active", "activation-warning");
        accountStatusLabel.getStyleClass().add(liveTrueApi ? "activation-active" : "activation-warning");
        accountCertificateLabel.setText(identity == null ? tr("znack.account.unknown")
                : CERTIFICATE_DATE.format(identity.validToDate()));
        accountCertificateDaysLabel.setText(identity == null ? tr("znack.account.unverified")
                : identity.certificate().validFrom().isAfter(now) ? tr("gs1.readiness.signature.not_yet_valid")
                : !identity.certificate().validTo().isAfter(now) ? tr("znack.account.expired")
                : MessageFormat.format(tr("znack.account.days_left"), identity.remainingDays(now)));
        accountEvidenceLabel.setText(identity == null ? tr("znack.account.certificate_source")
                : MessageFormat.format(tr("znack.account.tested_at"), CERTIFICATE_DATE.withZone(java.time.ZoneId.systemDefault()).format(identity.testedAt())));
        var balance = accountBalanceCombo.getValue();
        boolean scoped = balance != null && accountBalances.contains(balance);
        boolean fresh = scoped && ZnackAccountInfo.freshBalance(balance, now);
        var amountFormat = NumberFormat.getNumberInstance(I18nService.getInstance().getCurrentLanguage().toLocale());
        amountFormat.setMinimumFractionDigits(2);
        amountFormat.setMaximumFractionDigits(2);
        accountBalanceLabel.setText(!scoped ? tr("znack.account.unknown") : amountFormat.format(balance.amount()) + " ₽");
        boolean currentBalance = savedConfiguration && accountBalancesFromCurrentCheck && fresh
                && accountBalanceSession != null && accountBalanceSession.expiresAt().isAfter(now);
        accountBalanceUpdatedLabel.setText(!scoped ? tr(accountBalances.isEmpty() ? "znack.account.balance_unknown" : "znack.account.choose_balance")
                : MessageFormat.format(tr(!fresh ? "znack.account.balance_stale" : currentBalance
                        ? "znack.account.balance_updated" : "znack.account.balance_cached"),
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(balance.checkedAt())));
        accountBalanceCombo.setVisible(accountBalances.size() > 1);
        accountBalanceCombo.setManaged(accountBalances.size() > 1);
        accountBalanceCombo.setDisable(accountRefreshRunning || accountBalances.isEmpty());
        accountBusyIndicator.setVisible(accountRefreshRunning);
        accountBusyIndicator.setManaged(accountRefreshRunning);
        refreshAccountButton.setDisable(repository == null || !savedConfiguration || !valid
                || accountRefreshRunning || signatureTestRunning || productSyncRunning);
        accountSettingsButton.setDisable(repository == null);
    }

    private void loadCertificates() {
        if (repository == null || certificateDiscoveryRunning) return;
        long generation = certificateDiscoveryGeneration;
        Settings discoverySettings = loaded;
        certificateDiscoveryRunning = true;
        updateSignatureSummary();
        updateSaveState();
        Task<List<CryptoProCertificateInfo>> task = new Task<>() {
            @Override protected List<CryptoProCertificateInfo> call() throws CryptoProException {
                return new CryptoProCertificateDiscoveryService().discover(
                        discoverySettings.certmgrPath(), discoverySettings.csptestPath(),
                        Duration.ofSeconds(discoverySettings.resolvedCryptoProTimeoutSeconds()));
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != certificateDiscoveryGeneration) return;
            certificateDiscoveryRunning = false;
            applyDiscoveredCertificates(task.getValue(), Instant.now());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != certificateDiscoveryGeneration) return;
            certificateDiscoveryRunning = false;
            updateSignatureSummary();
            updateSaveState();
            Throwable failure = rejection;
            AlertService.showError(failure instanceof CryptoProException error
                    ? signatureError(error) : value(failure == null ? null : failure.getMessage()));
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void applyDiscoveredCertificates(List<CryptoProCertificateInfo> discovered, Instant now) {
        discovered = discovered.stream().map(certificate -> preserveVerifiedCertificateMetadata(certificate, loaded)).toList();
        boolean resizeOpenPopup = signatureCertificateCombo.isShowing();
        String previousSelector = selectedCertificate();
        CryptoProCertificateInfo current = signatureCertificateCombo.getValue();
        boolean hadConfiguredCertificate = !blank(previousSelector);
        List<CryptoProCertificateInfo> certificates = new ArrayList<>(orderedCertificates(discovered, now));
        CryptoProCertificateInfo previous = findCertificate(certificates, previousSelector);
        if (previous == null && current != null && previousSelector.equals(current.selector()) && current.expired(now)) {
            certificates.add(current);
            certificates = new ArrayList<>(orderedCertificates(certificates, now));
            previous = current;
        }
        CryptoProCertificateInfo selection = previous;
        if (selection == null && !hadConfiguredCertificate) {
            selection = certificates.stream().filter(certificate -> certificateSelectable(certificate, now))
                    .findFirst().orElse(null);
        }

        loading = true;
        updatingCertificateSelection = true;
        try {
            signatureCertificateCombo.getItems().setAll(certificates);
            signatureCertificateCombo.setValue(selection);
        } finally {
            updatingCertificateSelection = false;
            loading = false;
        }
        if (selection == null) {
            signerCertificate = "";
            certificateMetadata = "";
            signerTestedAt = null;
            testedConfigurationKey = null;
        } else {
            signerCertificate = selection.selector();
            certificateMetadata = metadata(selection);
            if (selection.expired(now) || !configurationKey().equals(testedConfigurationKey)) {
                signerTestedAt = null;
                testedConfigurationKey = null;
            }
        }
        updateSignatureSummary();
        updateSaveState();
        if (resizeOpenPopup) resizeCertificatePopup();
        if (discovered.isEmpty()) AlertService.showError(tr("znack.signature.not_found"));
    }

    private void resizeCertificatePopup() {
        reopeningCertificatePopup = true;
        signatureCertificateCombo.hide();
        javafx.application.Platform.runLater(() -> {
            try {
                signatureCertificateCombo.show();
            } finally {
                reopeningCertificatePopup = false;
            }
        });
    }

    @FXML
    private void testSignature() {
        if (selectedCertificateExpired()) {
            AlertService.showError(tr("znack.signature.error.expired"));
            return;
        }
        if (signatureTestRunning || accountRefreshRunning) return;
        // Signing shells out to CryptoPro (csptest/cryptcp) and may show a token PIN dialog — never on
        // the FX thread, or the UI freezes with no feedback. Run it as a background Task.
        String cryptcpPath = loaded.cryptcpPath();
        String selector = selectedCertificate();
        if (blank(selector)) {
            AlertService.showError(tr("znack.signature.not_configured"));
            return;
        }
        Duration signTimeout = timeout();
        String configKey = configurationKey();
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        Settings expectedSettings = loaded;
        Settings candidateSettings = settings();
        setSignatureTestBusy(true);
        Task<SignatureVerification> task = new Task<>() {
            @Override protected SignatureVerification call() throws Exception {
                Instant verifiedAt = Instant.now();
                var provider = new CryptoProSignatureProvider(cryptcpPath, selector, signTimeout);
                // certmgr can omit INN, validity or the private-key link. Obtain the identity from
                // a real local signature before constructing an authentication request for its owner.
                var identityProof = provider.sign(("VN code certificate identity check " + java.util.UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8), ZnackSignatureContext.SIGNATURE_TEST);
                var actualCertificate = CryptoProSignedCertificateReader.read(identityProof, selector);
                Settings verifiedSettings = recoverCertificateSettings(candidateSettings, actualCertificate, verifiedAt);
                ZnackApiClient api = new ZnackApiClient();
                ZnackSignatureProvider verifiedProvider = (payload, context) -> {
                    var signature = provider.sign(payload, context);
                    var currentCertificate = CryptoProSignedCertificateReader.read(signature, selector);
                    // Reject an inconsistent identity before any signed challenge is posted.
                    recoverCertificateSettings(verifiedSettings, currentCertificate, Instant.now());
                    return signature;
                };
                ZnackAuthService auth = new ZnackAuthService(api, verifiedProvider);
                // Verify the selected certificate against Znack itself before retiring any local catalog.
                auth.trueApiToken(verifiedSettings);
                String authenticatedInn = auth.authenticatedParticipantInn();
                if (!blank(authenticatedInn) && !actualCertificate.inn().equals(authenticatedInn))
                    throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,
                            "The authenticated account does not match the selected signing certificate.");
                ZnackRepository.CertificateVerificationResult result =
                        currentRepository.saveVerifiedCertificate(expectedSettings, verifiedSettings);
                return new SignatureVerification(verifiedSettings, result, api, auth, actualCertificate);
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            setSignatureTestBusy(false);
            SignatureVerification verification = task.getValue();
            loaded = verification.settings();
            signerCertificate = loaded.signerCertificate();
            certificateMetadata = loaded.certificateMetadataJson();
            var actual = verification.certificate();
            var selectedActual = new CryptoProCertificateInfo(signerCertificate, actual.thumbprint(), actual.subject(),
                    actual.issuer(), actual.inn(), actual.validFrom(), actual.validTo(), true, actual.provider(), "");
            updatingCertificateSelection = true;
            try {
                signatureCertificateCombo.getItems().removeIf(certificate -> sameCertificate(certificate.selector(), signerCertificate));
                signatureCertificateCombo.getItems().add(selectedActual);
                signatureCertificateCombo.setValue(selectedActual);
            } finally { updatingCertificateSelection = false; }
            signerTestedAt = loaded.signerTestedAt();
            accountAuthenticatedAt = Instant.now();
            accountBalanceSession = null;
            accountBalancesFromCurrentCheck = false;
            testedConfigurationKey = configKey;
            savedFingerprint = fingerprint(loaded);
            logsTable.getItems().setAll(currentRepository.findLogs());
            updateSignatureSummary();
            updateSaveState();
            if (verification.result().signerChanged()) {
                applyAccountBalances(List.of());
                selectedProductGtins.clear();
                loadProductsFromDatabase();
                loadDeletedProducts();
                authStatusLabel.setText(tr("znack.signature.catalog_refreshing"));
                signerChangeSyncPending = true;
                requestProductSync(verification.api(), verification.auth());
            } else loadCachedAccountBalances();
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            setSignatureTestBusy(false);
            accountAuthenticatedAt = null;
            Throwable failure = rejection;
            if (failure instanceof ZnackRepository.SignerChangeBlockedException) {
                signerTestedAt = null;
                currentRepository.log("SIGNATURE_TEST", null, "WARN", failure.getMessage(), null);
                logsTable.getItems().setAll(currentRepository.findLogs());
                updateSignatureSummary();
                updateSaveState();
                AlertService.showError(tr("znack.signature.error.active_work"));
                return;
            }
            if (failure instanceof ZnackRepository.SettingsConflictException) {
                signerTestedAt = null;
                updateSignatureSummary();
                updateSaveState();
                AlertService.showError(tr("znack.signature.error.settings_changed"));
                return;
            }
            CryptoProException error = failure instanceof CryptoProException cpe ? cpe
                    : new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "CryptoPro signing failed.", failure);
            signerTestedAt = null;
            currentRepository.log("SIGNATURE_TEST", null, "ERROR", signatureDiagnostic(error), null);
            logsTable.getItems().setAll(currentRepository.findLogs());
            updateSignatureSummary();
            updateSaveState();
            AlertService.showError(signatureError(error));
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    /** Loading state for the signature test: disable the button/combo and show a "loading" label. */
    private void setSignatureTestBusy(boolean busy) {
        signatureTestRunning = busy;
        testSignatureButton.setText(tr(busy ? "znack.signature.loading" : "znack.signature.test"));
        updateSaveState();
    }

    private void load() {
        resetAccountSession();
        loading = true;
        loaded = repository.getSettings();
        catalogKeyField.clear();
        catalogStatusKey = new com.vncode.app.integration.znack.CatalogCredentialRepository().hasSaved(repository.shop().shopId())
                ? "znack.catalog.saved" : "znack.catalog.not_saved";
        catalogStatusLabel.setText(tr(catalogStatusKey));
        signerCertificate = value(loaded.signerCertificate());
        certificateMetadata = value(loaded.certificateMetadataJson());
        signerTestedAt = loaded.signerTestedAt();
        omsIdField.setText(value(loaded.omsId()));
        omsConnectionField.setText(value(loaded.omsConnection()));
        autoIntroductionCheck.setSelected(loaded.autoIntroduction());
        signatureCertificateCombo.getItems().clear();
        if (!signerCertificate.isBlank()) {
            CryptoProCertificateInfo stored = storedCertificate();
            signatureCertificateCombo.getItems().add(stored);
            signatureCertificateCombo.setValue(stored);
        }
        loadProductsFromDatabase();
        ordersTable.getItems().setAll(repository.findOrders());
        logsTable.getItems().setAll(repository.findLogs());
        testedConfigurationKey = signerTestedAt == null ? null : configurationKey();
        loading = false;
        savedFingerprint = fingerprint();
        authStatusLabel.setText("");
        updateSignatureSummary();
        updateSaveState();
        loadCachedAccountBalances();
    }

    private void loadProductsFromDatabase() {
        allProducts = repository == null ? List.of() : repository.findProducts();
        selectedProductGtins.retainAll(allProducts.stream().map(Product::gtin)
                .collect(java.util.stream.Collectors.toSet()));
        productCategoryFilter.rebuild(allProducts.stream().map(Product::category).toList());
        applyProductFilter();
    }

    private void applyProductFilter() {
        String query = productSearchField.getText() == null
                ? "" : productSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        productsTable.getItems().setAll(allProducts.stream()
                .filter(product -> productCategoryFilter.matches(product.category()))
                .filter(product -> query.isEmpty()
                        || containsIgnoreCase(product.gtin(), query)
                        || containsIgnoreCase(product.productName(), query)
                        || containsIgnoreCase(product.category(), query))
                .sorted(BY_CATEGORY_THEN_GTIN)
                .toList());
        updateProductSelectionState();
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private void updateProductSelectionState() {
        deleteSelectedButton.setDisable(repository == null || selectedProductGtins.isEmpty());
        selectAllProducts.setSelected(!productsTable.getItems().isEmpty()
                && productsTable.getItems().stream().allMatch(p -> selectedProductGtins.contains(p.gtin())));
    }

    @FXML
    private void deleteSelectedProducts() {
        deleteProducts(List.copyOf(selectedProductGtins));
    }

    private void deleteProduct(Product product) {
        if (product != null) deleteProducts(List.of(product.gtin()));
    }

    private void deleteProducts(List<String> gtins) {
        if (repository == null || gtins.isEmpty()) return;
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        setProductsBusy(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                currentRepository.softDeleteProducts(gtins);
                for (String gtin : gtins) currentRepository.log("GTIN_DELETE", gtin, "INFO", "HIDDEN", null);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            setProductsBusy(false);
            if (generation != shopGeneration || currentRepository != repository) return;
            gtins.forEach(selectedProductGtins::remove);
            loadProductsFromDatabase();
            loadDeletedProducts();
            logsTable.getItems().setAll(currentRepository.findLogs());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            setProductsBusy(false);
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = rejection;
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    /** Hiện/ẩn loading khi xoá GTIN: spinner + khoá nút xoá + con trỏ chờ trên bảng. */
    private void setProductsBusy(boolean busy) {
        if (productBusyIndicator != null) {
            productBusyIndicator.setVisible(busy);
            productBusyIndicator.setManaged(busy);
        }
        deleteSelectedButton.setDisable(busy || repository == null || selectedProductGtins.isEmpty());
        productsTable.setCursor(busy ? javafx.scene.Cursor.WAIT : javafx.scene.Cursor.DEFAULT);
        productsTable.setDisable(busy);
    }

    private void loadDeletedProducts() {
        if (repository == null) {
            allDeletedProducts = List.of();
            deletedTable.getItems().clear();
            return;
        }
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        Task<List<Product>> task = new Task<>() {
            @Override protected List<Product> call() {
                return currentRepository.findDeletedProducts();
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            allDeletedProducts = task.getValue();
            deletedCategoryFilter.rebuild(allDeletedProducts.stream().map(Product::category).toList());
            applyDeletedFilter();
        });
        AppTaskExecutor.execute(task,error->{if(generation==shopGeneration&&currentRepository==repository)AlertService.showError(error.getMessage());});
    }

    private void applyDeletedFilter() {
        String query = deletedSearchField.getText() == null
                ? "" : deletedSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        deletedTable.getItems().setAll(allDeletedProducts.stream()
                .filter(product -> deletedCategoryFilter.matches(product.category()))
                .filter(product -> query.isEmpty()
                        || containsIgnoreCase(product.gtin(), query)
                        || containsIgnoreCase(product.productName(), query)
                        || containsIgnoreCase(product.category(), query))
                .sorted(BY_CATEGORY_THEN_GTIN)
                .toList());
    }

    private void restoreProduct(Product product) {
        if (repository == null || product == null) return;
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        String gtin = product.gtin();
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                currentRepository.restoreProducts(List.of(gtin));
                currentRepository.log("GTIN_RESTORE", gtin, "INFO", "RESTORED", null);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            loadProductsFromDatabase();
            loadDeletedProducts();
            logsTable.getItems().setAll(currentRepository.findLogs());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = rejection;
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void purgeProduct(Product product) {
        if (repository == null || product == null) return;
        Optional<ButtonType> result = AlertService.showConfirmation(
                tr("znack.product.delete.title"),
                MessageFormat.format(tr("znack.product.delete.header"), value(product.gtin())),
                tr("znack.product.delete.content"));
        if (result.isEmpty() || result.get() != ButtonType.OK) return;
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        String gtin = product.gtin();
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                currentRepository.deleteProduct(gtin);
                currentRepository.log("GTIN_DELETE", gtin, "INFO", "DELETED", null);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            loadDeletedProducts();
            ordersTable.getItems().setAll(currentRepository.findOrders());
            logsTable.getItems().setAll(currentRepository.findLogs());
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = rejection;
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private Button iconButton(String icon, String tooltip) {
        Button button = new Button();
        button.getStyleClass().add("btn-icon");
        button.setGraphic(new org.kordamp.ikonli.javafx.FontIcon(icon));
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    private final class ProductSelectCell extends TableCell<Product, Product> {
        private final CheckBox check = new CheckBox();

        private ProductSelectCell() {
            check.setOnAction(event -> {
                Product item = getItem();
                if (item == null) return;
                if (check.isSelected()) selectedProductGtins.add(item.gtin());
                else selectedProductGtins.remove(item.gtin());
                updateProductSelectionState();
            });
        }

        @Override protected void updateItem(Product item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            check.setSelected(selectedProductGtins.contains(item.gtin()));
            setGraphic(check);
        }
    }

    private final class ProductActionsCell extends TableCell<Product, Product> {
        private final Button delete = iconButton("fth-trash-2", tr("common.delete"));

        private ProductActionsCell() {
            delete.setOnAction(event -> deleteProduct(getItem()));
        }

        @Override protected void updateItem(Product item, boolean empty) {
            super.updateItem(item, empty);
            setGraphic(empty || item == null ? null : delete);
        }
    }

    private final class DeletedProductActionsCell extends TableCell<Product, Product> {
        private final Button restore = iconButton("fth-rotate-ccw", tr("znack.products.restore"));
        private final Button purge = iconButton("fth-trash-2", tr("znack.products.delete_forever"));
        private final javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(6, restore, purge);

        private DeletedProductActionsCell() {
            restore.setOnAction(event -> restoreProduct(getItem()));
            purge.setOnAction(event -> purgeProduct(getItem()));
        }

        @Override protected void updateItem(Product item, boolean empty) {
            super.updateItem(item, empty);
            setGraphic(empty || item == null ? null : box);
        }
    }

    private void requestProductSyncOnOpen() {
        if (repository == null || productSyncRunning || !hasVerifiedSignature(loaded)) return;
        // Auto-sync from Znack only the first time a shop's GTIN data is loaded in this session
        // (shared with the other KIZ panes); saving settings still triggers a fresh sync.
        if (ZnackGtinAutoSync.shouldAutoSync(repository.shop().shopId())) requestProductSync();
    }

    private void requestProductSync() {
        requestProductSync(null, null);
    }

    private void requestProductSync(ZnackApiClient verifiedApi, ZnackAuthService verifiedAuth) {
        if (repository == null || productSyncRunning || !hasVerifiedSignature(loaded)) return;
        long generation = shopGeneration;
        ZnackRepository currentRepository = repository;
        Settings currentSettings = loaded;
        Task<List<Product>> task = new Task<>() {
            @Override protected List<Product> call() throws Exception {
                try {
                    ZnackApiClient api = verifiedApi == null ? new ZnackApiClient() : verifiedApi;
                    ZnackAuthService auth = verifiedAuth;
                    if (auth == null) {
                        ZnackSignatureProvider signer = new CryptoProSignatureProvider(currentSettings.cryptcpPath(),
                                currentSettings.signerCertificate(), Duration.ofSeconds(currentSettings.resolvedCryptoProTimeoutSeconds()));
                        auth = new ZnackAuthService(api, signer);
                    }
                    return new ZnackProductService(api, auth, currentRepository).sync(currentSettings);
                } catch (Exception error) {
                    currentRepository.log("GTIN_SYNC", null, "ERROR", error.getMessage(), null);
                    throw error;
                }
            }
        };
        productSyncRunning = true;
        updateSaveState();
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            productSyncRunning = false;
            updateSaveState();
            allProducts = task.getValue();
            productCategoryFilter.rebuild(allProducts.stream().map(Product::category).toList());
            applyProductFilter();
            ordersTable.getItems().setAll(currentRepository.findOrders());
            logsTable.getItems().setAll(currentRepository.findLogs());
            resumeEligibleIntroductions(currentSettings);
            if (signerChangeSyncPending) {
                signerChangeSyncPending = false;
                authStatusLabel.setText(tr("znack.signature.catalog_refreshed"));
            }
        });
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            productSyncRunning = false;
            updateSaveState();
            loadProductsFromDatabase();
            logsTable.getItems().setAll(currentRepository.findLogs());
            if (signerChangeSyncPending) {
                signerChangeSyncPending = false;
                authStatusLabel.setText(tr("znack.signature.catalog_refresh_failed"));
            }
        };
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private boolean hasVerifiedSignature(Settings settings) {
        return settings != null && !blank(settings.signerCertificate()) && settings.signerTestedAt() != null;
    }

    private Settings settings() {
        boolean certificateChanged = !blank(loaded.signerCertificate())
                && !sameCertificate(selectedCertificate(), loaded.signerCertificate());
        return new Settings(loaded.trueApiBaseUrl(), loaded.suzBaseUrl(), omsIdField.getText(), omsConnectionField.getText(),
                certificateChanged ? "" : loaded.participantInn(),
                certificateChanged ? "" : loaded.producerInn(),
                certificateChanged ? "" : loaded.ownerInn(), loaded.signerExecutable(),
                selectedCertificate(), loaded.signerArgumentsJson(), loaded.documentNumber(),
                loaded.documentDate(), loaded.pdfFolder(), autoIntroductionCheck.isSelected(),
                loaded.certificateListExecutable(), loaded.certificateListArgumentsJson(), certificateMetadata,
                signerTestedAt, loaded.certmgrPath(), loaded.cryptcpPath(), loaded.csptestPath(),
                loaded.resolvedCryptoProTimeoutSeconds(), loaded.documentExpiryDate(), loaded.documentType());
    }

    /** Pure preparation: persistence only follows successful remote authentication and owner check. */
    static Settings recoverCertificateSettings(Settings settings, CryptoProCertificateInfo actual, Instant testedAt)
            throws CryptoProException {
        String selected = value(settings.signerCertificate()).replaceAll("\\s+", "");
        if (actual == null || testedAt == null || !selected.matches("[A-Fa-f0-9]{40}")
                || !selected.equalsIgnoreCase(value(actual.thumbprint()).replaceAll("\\s+", ""))
                || !value(actual.inn()).matches("[0-9]{10}|[0-9]{12}") || !actual.hasPrivateKey()
                || actual.validFrom() == null || actual.validTo() == null
                || actual.validFrom().isAfter(testedAt) || !actual.validTo().isAfter(testedAt))
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,
                    "Cannot verify the identity and validity of the selected signing certificate.");
        String expectedInn = value(settings.participantInn()).trim();
        if (!expectedInn.isEmpty() && !expectedInn.equals(actual.inn()))
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,
                    "The configured account does not match the selected signing certificate.");
        return new Settings(settings.trueApiBaseUrl(), settings.suzBaseUrl(), settings.omsId(),
                settings.omsConnection(), actual.inn(), settings.producerInn(), settings.ownerInn(),
                settings.signerExecutable(), settings.signerCertificate(), settings.signerArgumentsJson(),
                settings.documentNumber(), settings.documentDate(), settings.pdfFolder(), settings.autoIntroduction(),
                settings.certificateListExecutable(), settings.certificateListArgumentsJson(),
                metadata(actual), testedAt, settings.certmgrPath(), settings.cryptcpPath(),
                settings.csptestPath(), settings.resolvedCryptoProTimeoutSeconds(), settings.documentExpiryDate(),
                settings.documentType());
    }

    private void clear() {
        resetAccountSession();
        loading = true;
        catalogKeyField.clear();
        catalogStatusKey = "znack.catalog.not_saved";
        catalogStatusLabel.setText(tr(catalogStatusKey));
        loaded = Settings.empty();
        for (TextField field : List.of(omsIdField, omsConnectionField)) field.clear();
        signatureCertificateCombo.getItems().clear();
        allProducts = List.of();
        allDeletedProducts = List.of();
        selectedProductGtins.clear();
        productSearchField.clear();
        deletedSearchField.clear();
        productCategoryFilter.rebuild(List.of());
        deletedCategoryFilter.rebuild(List.of());
        productsTable.getItems().clear();
        deletedTable.getItems().clear();
        ordersTable.getItems().clear();
        logsTable.getItems().clear();
        updateProductSelectionState();
        autoIntroductionCheck.setSelected(false);
        signerCertificate = "";
        certificateMetadata = "";
        signerTestedAt = null;
        testedConfigurationKey = null;
        signerChangeSyncPending = false;
        loading = false;
        savedFingerprint = fingerprint();
        authStatusLabel.setText(tr("znack.error.select_shop"));
        updateSaveState();
    }

    private void updateSaveState() {
        boolean catalogBusy = repository == null || loading || catalogCheckRunning || signatureTestRunning || productSyncRunning || accountRefreshRunning;
        catalogKeyField.setDisable(catalogBusy);
        checkCatalogButton.setDisable(catalogBusy || !Objects.equals(savedFingerprint, fingerprint()));
        removeCatalogButton.setDisable(catalogBusy);
        boolean verificationRequired = (!blank(selectedCertificate()) && signerTestedAt == null)
                || (!blank(loaded.signerCertificate()) && blank(selectedCertificate()));
        saveButton.setDisable(repository == null || loading || verificationRequired
                || accountRefreshRunning || catalogCheckRunning
                || Objects.equals(savedFingerprint, fingerprint()));
        signatureCertificateCombo.setDisable(repository == null || signatureTestRunning || productSyncRunning || accountRefreshRunning || catalogCheckRunning);
        testSignatureButton.setDisable(repository == null || certificateDiscoveryRunning
                || signatureTestRunning || productSyncRunning || accountRefreshRunning || catalogCheckRunning || blank(selectedCertificate())
                || selectedCertificateExpired());
        updateAccountInfo();
    }

    @FXML private void checkCatalogConnection() {
        if (repository == null || catalogCheckRunning || !Objects.equals(savedFingerprint, fingerprint())) return;
        Shop owner = catalogShop; long generation = shopGeneration;
        String input = catalogKeyField.getText();
        catalogCheckRunning = true;
        catalogStatusKey = "znack.catalog.checking";
        catalogStatusLabel.setText(tr(catalogStatusKey));
        updateSaveState();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                new com.vncode.app.integration.znack.CatalogConnectionService().checkAndSave(owner, input,
                        () -> generation == shopGeneration);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) return;
            catalogCheckRunning = false;
            loading = true; catalogKeyField.clear(); loading = false;
            catalogStatusKey = "znack.catalog.connected";
            catalogStatusLabel.setText(tr(catalogStatusKey));
            updateSaveState();
        });
        java.util.function.Consumer<Throwable> failure = error -> {
            if (generation != shopGeneration) return;
            catalogCheckRunning = false;
            catalogStatusKey = "znack.catalog.failed";
            String code = error == null ? "" : Objects.toString(error.getMessage(), "");
            String detail = code.startsWith("CATALOG_KEY_")
                    ? I18nService.getInstance().tr("znack.catalog.error." + code, tr("znack.catalog.failed"))
                    : com.vncode.app.shared.FriendlyErrorService.format(error);
            catalogStatusLabel.setText(tr(catalogStatusKey) + " " + detail);
            updateSaveState();
        };
        task.setOnFailed(event -> failure.accept(task.getException()));
        AppTaskExecutor.execute(task, failure);
    }
    @FXML private void removeCatalogKey() {
        if (catalogShop == null || catalogCheckRunning) return;
        try {
            new com.vncode.app.integration.znack.CatalogCredentialRepository().remove(catalogShop);
            loading = true; catalogKeyField.clear(); loading = false;
            catalogStatusKey = "znack.catalog.not_saved";
            catalogStatusLabel.setText(tr(catalogStatusKey));
        } catch (RuntimeException failure) { AlertService.showError(tr("znack.catalog.failed")); }
        updateSaveState();
    }

    private void updateSignatureSummary() {
        if (signatureSummaryLabel == null) return;
        signatureSummaryLabel.setText(tr(certificateDiscoveryRunning ? "znack.signature.loading"
                : selectedCertificateExpired() ? "znack.signature.error.expired"
                : blank(signerCertificate) ? "znack.signature.not_configured"
                : signerTestedAt == null ? "znack.signature.not_verified" : "znack.signature.verified"));
    }

    private String fingerprint() {
        return String.join("\u001f", value(omsIdField.getText()), value(omsConnectionField.getText()), selectedCertificate(),
                String.valueOf(autoIntroductionCheck.isSelected()),
                value(certificateMetadata), signerTestedAt == null ? "" : signerTestedAt.toString());
    }

    private String fingerprint(Settings settings) {
        return String.join("\u001f", value(settings.omsId()), value(settings.omsConnection()),
                value(settings.signerCertificate()), String.valueOf(settings.autoIntroduction()),
                value(settings.certificateMetadataJson()),
                settings.signerTestedAt() == null ? "" : settings.signerTestedAt().toString());
    }

    private boolean sameCertificate(String first, String second) {
        return value(first).trim().equalsIgnoreCase(value(second).trim());
    }

    private record SignatureVerification(Settings settings,
                                         ZnackRepository.CertificateVerificationResult result,
                                         ZnackApiClient api, ZnackAuthService auth,
                                         CryptoProCertificateInfo certificate) {
    }

    private String selectedCertificate() {
        CryptoProCertificateInfo selected = signatureCertificateCombo.getValue();
        return selected == null ? signerCertificate : value(selected.selector());
    }

    private String configurationKey() {
        return String.join("\u001f", selectedCertificate(), value(loaded.cryptcpPath()), value(loaded.csptestPath()));
    }

    private static String metadata(CryptoProCertificateInfo certificate) {
        JsonObject json = new JsonObject();
        json.addProperty("selector", certificate.selector());
        json.addProperty("thumbprint", certificate.thumbprint());
        json.addProperty("subject", certificate.subject());
        json.addProperty("issuer", certificate.issuer());
        json.addProperty("inn", certificate.inn());
        if (certificate.validFrom() != null) json.addProperty("validFrom", certificate.validFrom().toString());
        if (certificate.validTo() != null) json.addProperty("validTo", certificate.validTo().toString());
        json.addProperty("hasPrivateKey", certificate.hasPrivateKey());
        json.addProperty("provider", certificate.provider());
        return json.toString();
    }

    private CryptoProCertificateInfo storedCertificate() {
        return storedCertificate(certificateMetadata, signerCertificate);
    }

    private static CryptoProCertificateInfo storedCertificate(String certificateMetadata, String signerCertificate) {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(certificateMetadata).getAsJsonObject();
            return new CryptoProCertificateInfo(
                    jsonString(json, "selector", signerCertificate),
                    jsonString(json, "thumbprint", signerCertificate),
                    jsonString(json, "subject", signerCertificate),
                    jsonString(json, "issuer", ""),
                    jsonString(json, "inn", ""),
                    jsonInstant(json, "validFrom"),
                    jsonInstant(json, "validTo"),
                    json.has("hasPrivateKey") && json.get("hasPrivateKey").getAsBoolean(),
                    jsonString(json, "provider", "CryptoPro"),
                    "");
        } catch (Exception ignored) {
            return new CryptoProCertificateInfo(signerCertificate, signerCertificate, signerCertificate,
                    "", "", null, null, false, "CryptoPro", "");
        }
    }

    private ListCell<CryptoProCertificateInfo> certificateCell(boolean disableExpired) {
        return new ListCell<>() {
            @Override protected void updateItem(CryptoProCertificateInfo certificate, boolean empty) {
                super.updateItem(certificate, empty);
                getStyleClass().removeAll("certificate-valid", "certificate-expired");
                setDisable(false);
                setText(empty || certificate == null ? null : certificateDisplay(certificate));
                setTooltip(empty || certificate == null ? null : new Tooltip(certificate.thumbprint()));
                if (!empty && certificate != null) {
                    boolean expired = certificate.expired(Instant.now());
                    getStyleClass().add(expired ? "certificate-expired" : "certificate-valid");
                    setDisable(disableExpired && expired);
                }
            }
        };
    }

    static List<CryptoProCertificateInfo> orderedCertificates(List<CryptoProCertificateInfo> certificates, Instant now) {
        return certificates.stream()
                .sorted(Comparator.comparing((CryptoProCertificateInfo certificate) -> certificate.expired(now))
                        .thenComparing(certificate -> value(certificate.ownerName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(certificate -> value(certificate.selector()), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static boolean certificateSelectable(CryptoProCertificateInfo certificate, Instant now) {
        return certificate != null && !certificate.expired(now);
    }

    /** A later sparse certmgr listing cannot erase identity established by a successful CMS/CZ test. */
    static CryptoProCertificateInfo preserveVerifiedCertificateMetadata(CryptoProCertificateInfo discovered, Settings configured) {
        if (configured == null || configured.signerTestedAt() == null) return discovered;
        var actual = storedCertificate(configured.certificateMetadataJson(), configured.signerCertificate());
        if (!"CryptoPro CMS signer".equals(actual.provider()) || !actual.hasPrivateKey()
                || actual.validFrom() == null || actual.validTo() == null
                || !value(actual.thumbprint()).matches("[A-Fa-f0-9]{40}")
                || !actual.thumbprint().equalsIgnoreCase(value(configured.signerCertificate()).replaceAll("\\s+", ""))
                || !actual.thumbprint().equalsIgnoreCase(value(discovered.thumbprint()).replaceAll("\\s+", "")))
            return discovered;
        return new CryptoProCertificateInfo(configured.signerCertificate(), actual.thumbprint(), actual.subject(), actual.issuer(),
                actual.inn(), actual.validFrom(), actual.validTo(), true, actual.provider(), "");
    }

    private static CryptoProCertificateInfo findCertificate(List<CryptoProCertificateInfo> certificates, String selector) {
        return certificates.stream().filter(certificate -> selector.equals(certificate.selector())).findFirst().orElse(null);
    }

    private boolean selectedCertificateExpired() {
        CryptoProCertificateInfo selected = signatureCertificateCombo.getValue();
        return selected != null && selected.expired(Instant.now());
    }

    private void invalidateCertificateDiscovery() {
        certificateDiscoveryGeneration++;
        certificateDiscoveryRunning = false;
    }

    private String certificateDisplay(CryptoProCertificateInfo certificate) {
        if (certificate == null) return "";
        StringBuilder display = new StringBuilder(value(certificate.ownerName()));
        if (!blank(certificate.inn())) display.append(" / INN ").append(certificate.inn());
        String expiry = certificate.validToDate() == null
                ? tr("znack.signature.expiry_unknown")
                : certificate.validToDate().format(CERTIFICATE_DATE);
        return display.append(" / ").append(tr("znack.signature.expires_on")).append(" ").append(expiry).toString();
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static Instant jsonInstant(JsonObject json, String key) {
        String value = jsonString(json, key, "");
        return value.isBlank() ? null : Instant.parse(value);
    }

    private Duration timeout() {
        return Duration.ofSeconds(loaded.resolvedCryptoProTimeoutSeconds());
    }

    private void resumeEligibleIntroductions(Settings settings) {
        ZnackRepository currentRepository = repository;
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                ZnackPurchaseCoordinator.create(currentRepository).resumeEligibleIntroductionsAsync(settings);
                return null;
            }
        };
        AppTaskExecutor.execute(task,error->{if(currentRepository==repository)AlertService.showError(error.getMessage());});
    }

    private String signatureError(CryptoProException error) {
        String message = tr("znack.signature.error." + switch (error.code()) {
            case CRYPTOPRO_MISSING -> "cryptopro_missing";
            case CRYPTCP_MISSING -> "cryptcp_missing";
            case CRYPTCP_LICENSE_INVALID -> "cryptcp_license";
            case CERTMGR_MISSING -> "certmgr_missing";
            case CADESCOM_MISSING -> "cadescom_missing";
            case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
            case PRIVATE_KEY_UNAVAILABLE -> "private_key";
            case CERTIFICATE_EXPIRED -> "expired";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
            case INVALID_SIGNATURE_OUTPUT -> "invalid_output";
            default -> "failed";
        });
        return error.code() == CryptoProErrorCode.SIGNING_FAILED
                ? withSignatureDetails(message, ZnackSanitizer.error(error)) : message;
    }

    private String withSignatureDetails(String message, String details) {
        String sanitized = ZnackSanitizer.message(details);
        return sanitized.isBlank() ? message : message + "\n\n" + tr("znack.signature.error.details") + ": " + sanitized;
    }

    private String signatureDiagnostic(CryptoProException error) {
        return "code=" + error.code() + "; " + ZnackSanitizer.error(error);
    }

    private void configureLogCopy() {
        MenuItem copy = new MenuItem(tr("common.copy"));
        copy.setOnAction(event -> copySelectedLog());
        ContextMenu menu = new ContextMenu(copy);
        menu.setOnShowing(event -> copy.setDisable(logsTable.getSelectionModel().getSelectedItem() == null));
        logsTable.setContextMenu(menu);
        logsTable.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) copySelectedLog();
        });
        logsTable.setRowFactory(ignored -> {
            TableRow<OperationLog> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) logsTable.getSelectionModel().select(row.getItem());
            });
            return row;
        });
    }

    private void copySelectedLog() {
        OperationLog selected = logsTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(logText(selected));
        Clipboard.getSystemClipboard().setContent(content);
    }

    static String logText(OperationLog log) {
        return "time=" + log.createdAt() + "\n"
                + "action=" + value(log.action()) + "\n"
                + "entity=" + value(log.entityReference()) + "\n"
                + "severity=" + value(log.severity()) + "\n"
                + "httpStatus=" + (log.httpStatus() == null ? "" : log.httpStatus()) + "\n"
                + "message=" + value(log.message());
    }

    private javafx.beans.property.SimpleStringProperty text(String value) {
        return new javafx.beans.property.SimpleStringProperty(value(value));
    }

    private String tr(String key) { return I18nService.getInstance().tr(key); }
    private String localizeStatus(String status) {
        return I18nService.getInstance().tr("znack.status_value." + status.toLowerCase(java.util.Locale.ROOT), status);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(String value) { return value == null ? "" : value; }
}
