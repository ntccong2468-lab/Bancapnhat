package com.tuandev.fbsbarcode.ui.znack;

import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinAutoSync;
import com.tuandev.fbsbarcode.integration.znack.ZnackProductService;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.integration.znack.signature.*;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.ui.controls.CategoryFilterMenu;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.util.StringConverter;

import java.text.MessageFormat;
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
    @FXML private Tab settingsTab, productsTab, deletedTab, ordersTab, logsTab;
    @FXML private TextField omsIdField, omsConnectionField;
    @FXML private TextField productSearchField, deletedSearchField;
    @FXML private ComboBox<CryptoProCertificateInfo> signatureCertificateCombo;
    @FXML private CheckBox autoIntroductionCheck;
    @FXML private Button saveButton, testSignatureButton, deleteSelectedButton;
    @FXML private ProgressIndicator productBusyIndicator;
    @FXML private MenuButton productCategoryFilterButton, deletedCategoryFilterButton;
    @FXML private Button omsIdHelpButton, omsConnectionHelpButton, closeOmsHelpButton;
    @FXML private javafx.scene.layout.VBox omsHelpPane;
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
    private ZnackRepository repository;
    private Settings loaded = Settings.empty();
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
    private long shopGeneration;

    @FXML
    private void initialize() {
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

    public void setShop(Shop shop) {
        shopGeneration++;
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
        titleLabel.setText(tr("znack.title"));
        settingsTab.setText(tr("znack.tab.settings"));
        productsTab.setText(tr("znack.tab.products"));
        deletedTab.setText(tr("znack.tab.deleted"));
        ordersTab.setText(tr("znack.tab.orders"));
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
        if (currentHelpTopic != null) showOmsHelp(currentHelpTopic);
        signatureCertificateCombo.setButtonCell(certificateCell(false));
        updateSignatureSummary();
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
            savedFingerprint = fingerprint();
            authStatusLabel.setText(tr("znack.status.saved"));
            updateSaveState();
            resumeEligibleIntroductions(settings);
            if (productsTab.isSelected()) requestProductSync();
        } catch (IllegalArgumentException e) {
            AlertService.showError(e.getMessage());
        }
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
        task.setOnFailed(event -> {
            if (generation != certificateDiscoveryGeneration) return;
            certificateDiscoveryRunning = false;
            updateSignatureSummary();
            updateSaveState();
            Throwable failure = task.getException();
            AlertService.showError(failure instanceof CryptoProException error
                    ? signatureError(error) : value(failure == null ? null : failure.getMessage()));
        });
        AppTaskExecutor.execute(task);
    }

    private void applyDiscoveredCertificates(List<CryptoProCertificateInfo> discovered, Instant now) {
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
        if (signatureTestRunning) return;
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
                Settings verifiedSettings = withSignerTestedAt(candidateSettings, verifiedAt);
                ZnackApiClient api = new ZnackApiClient();
                ZnackAuthService auth = new ZnackAuthService(api,
                        new CryptoProSignatureProvider(cryptcpPath, selector, signTimeout));
                // Verify the selected certificate against Znack itself before retiring any local catalog.
                auth.trueApiToken(verifiedSettings);
                ZnackRepository.CertificateVerificationResult result =
                        currentRepository.saveVerifiedCertificate(expectedSettings, verifiedSettings);
                return new SignatureVerification(verifiedSettings, result, api, auth);
            }
        };
        task.setOnSucceeded(event -> {
            setSignatureTestBusy(false);
            if (generation != shopGeneration || currentRepository != repository) return;
            SignatureVerification verification = task.getValue();
            loaded = verification.settings();
            signerTestedAt = loaded.signerTestedAt();
            testedConfigurationKey = configKey;
            savedFingerprint = fingerprint(loaded);
            logsTable.getItems().setAll(currentRepository.findLogs());
            updateSignatureSummary();
            updateSaveState();
            if (verification.result().signerChanged()) {
                selectedProductGtins.clear();
                loadProductsFromDatabase();
                loadDeletedProducts();
                authStatusLabel.setText(tr("znack.signature.catalog_refreshing"));
                signerChangeSyncPending = true;
                requestProductSync(verification.api(), verification.auth());
            }
        });
        task.setOnFailed(event -> {
            setSignatureTestBusy(false);
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = task.getException();
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
        });
        AppTaskExecutor.execute(task);
    }

    /** Loading state for the signature test: disable the button/combo and show a "loading" label. */
    private void setSignatureTestBusy(boolean busy) {
        signatureTestRunning = busy;
        testSignatureButton.setText(tr(busy ? "znack.signature.loading" : "znack.signature.test"));
        updateSaveState();
    }

    private void load() {
        loading = true;
        loaded = repository.getSettings();
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
        task.setOnFailed(event -> {
            setProductsBusy(false);
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = task.getException();
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        });
        AppTaskExecutor.execute(task);
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
        AppTaskExecutor.execute(task);
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
        task.setOnFailed(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = task.getException();
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        });
        AppTaskExecutor.execute(task);
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
        task.setOnFailed(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            Throwable failure = task.getException();
            AlertService.showError(value(failure == null ? null : failure.getMessage()));
        });
        AppTaskExecutor.execute(task);
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
        task.setOnFailed(event -> {
            if (generation != shopGeneration || currentRepository != repository) return;
            productSyncRunning = false;
            updateSaveState();
            loadProductsFromDatabase();
            logsTable.getItems().setAll(currentRepository.findLogs());
            if (signerChangeSyncPending) {
                signerChangeSyncPending = false;
                authStatusLabel.setText(tr("znack.signature.catalog_refresh_failed"));
            }
        });
        AppTaskExecutor.execute(task);
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

    private Settings withSignerTestedAt(Settings settings, Instant testedAt) {
        return new Settings(settings.trueApiBaseUrl(), settings.suzBaseUrl(), settings.omsId(),
                settings.omsConnection(), settings.participantInn(), settings.producerInn(), settings.ownerInn(),
                settings.signerExecutable(), settings.signerCertificate(), settings.signerArgumentsJson(),
                settings.documentNumber(), settings.documentDate(), settings.pdfFolder(), settings.autoIntroduction(),
                settings.certificateListExecutable(), settings.certificateListArgumentsJson(),
                settings.certificateMetadataJson(), testedAt, settings.certmgrPath(), settings.cryptcpPath(),
                settings.csptestPath(), settings.resolvedCryptoProTimeoutSeconds(), settings.documentExpiryDate(),
                settings.documentType());
    }

    private void clear() {
        loading = true;
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
        boolean verificationRequired = (!blank(selectedCertificate()) && signerTestedAt == null)
                || (!blank(loaded.signerCertificate()) && blank(selectedCertificate()));
        saveButton.setDisable(repository == null || loading || verificationRequired
                || Objects.equals(savedFingerprint, fingerprint()));
        signatureCertificateCombo.setDisable(repository == null || signatureTestRunning || productSyncRunning);
        testSignatureButton.setDisable(repository == null || certificateDiscoveryRunning
                || signatureTestRunning || productSyncRunning || blank(selectedCertificate())
                || selectedCertificateExpired());
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
                                         ZnackApiClient api, ZnackAuthService auth) {
    }

    private String selectedCertificate() {
        CryptoProCertificateInfo selected = signatureCertificateCombo.getValue();
        return selected == null ? signerCertificate : value(selected.selector());
    }

    private String configurationKey() {
        return String.join("\u001f", selectedCertificate(), value(loaded.cryptcpPath()), value(loaded.csptestPath()));
    }

    private String metadata(CryptoProCertificateInfo certificate) {
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
        AppTaskExecutor.execute(task);
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
