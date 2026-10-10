package com.vncode.app.ui.shop;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;

import java.util.function.Consumer;

public class ShopSidebarController {
    @FXML private Button guidesButton;
    private Runnable onGuides;
    public void setOnGuides(Runnable action){onGuides=action;}
    @FXML private void onGuides(){if(onGuides!=null)onGuides.run();}
    @FXML private Button financeButton;
    @FXML private javafx.scene.control.TitledPane toolsPane;
    private Runnable onFinance;
    public void setOnFinance(Runnable action){onFinance=action;}
    @FXML private void onFinance(){if(onFinance!=null)onFinance.run();}
    public void selectNavigation(String id){for(Button button:java.util.List.of(dashboardButton,packingButton,fboPackingButton,templateButton,kizMappingButton,znackRegistrationButton,znackAutomationButton,btnPrintHistory,gtinSyncButton,gs1Button,fboOrdersButton,financeButton,guidesButton)){button.getStyleClass().remove("nav-selected");if(id.equals(button.getId()))button.getStyleClass().add("nav-selected");}}
    @FXML private Button gs1Button;
    private Runnable onGs1;
    private int gs1Unread;
    public void setOnGs1(Runnable action){onGs1=action;}
    public void setGs1Unread(int count){gs1Unread=Math.max(0,count);if(gs1Button!=null)gs1Button.setText(" "+I18nService.getInstance().tr("gs1.title")+(gs1Unread>0?" ("+gs1Unread+")":""));}
    @FXML private void onGs1(){if(onGs1!=null)onGs1.run();}
    @FXML private Button gtinSyncButton;
    private Runnable onGtinSync;
    public void setOnGtinSync(Runnable action) { onGtinSync = action; }
    @FXML private void onGtinSync() { if (onGtinSync != null) onGtinSync.run(); }
    @FXML
    private Button dashboardButton;
    @FXML
    private Button btnPrintHistory;
    @FXML
    private Button addShopButton;
    @FXML
    private Button packingButton;
    @FXML
    private Button templateButton;
    @FXML
    private Button fboPackingButton;
    @FXML
    private Button fboOrdersButton;
    @FXML
    private Button kizMappingButton;
    @FXML
    private Button znackAutomationButton;
    @FXML
    private Button znackRegistrationButton;
    @FXML
    private MenuButton settingsMenuButton;
    @FXML
    private Menu languageMenu;
    @FXML
    private RadioMenuItem languageRuMenuItem;
    @FXML
    private RadioMenuItem languageEnMenuItem;
    @FXML
    private RadioMenuItem languageZhMenuItem;
    @FXML
    private RadioMenuItem languageViMenuItem;
    @FXML
    private Menu themeMenu;
    @FXML
    private RadioMenuItem themeDarkMenuItem;
    @FXML
    private RadioMenuItem themeLightMenuItem;
    @FXML
    private MenuItem checkVersionMenuItem;
    @FXML
    private MenuItem personalEditionMenuItem;
    @FXML
    private MenuItem aboutMenuItem;
    @FXML
    private Label editionStatusLabel;

    private Runnable onPacking;
    private Runnable onDashboard;
    private Runnable onFboPacking;
    private Runnable onFboOrders;
    private Runnable onKizMapping;
    private Runnable onZnackAutomation;
    private Runnable onZnackRegistration;
    private Runnable onPrintHistory;
    private Runnable onAddShop;
    private Runnable onOpenSettings;
    private Runnable onCheckVersion;
    private Runnable onPersonalEdition;
    private Runnable onAbout;
    private Consumer<AppLanguage> onLanguageChanged;
    private Consumer<String> onThemeChanged;
    private Marketplace marketplace = Marketplace.WILDBERRIES;

    @FXML
    private void initialize() {
        ToggleGroup languageGroup = new ToggleGroup();
        languageRuMenuItem.setToggleGroup(languageGroup);
        languageEnMenuItem.setToggleGroup(languageGroup);
        languageZhMenuItem.setToggleGroup(languageGroup);
        languageViMenuItem.setToggleGroup(languageGroup);

        ToggleGroup themeGroup = new ToggleGroup();
        themeDarkMenuItem.setToggleGroup(themeGroup);
        themeLightMenuItem.setToggleGroup(themeGroup);

        applyTranslations();
        selectNavigation("dashboardButton");
    }

    public void setOnPrintHistory(Runnable onPrintHistory) {
        this.onPrintHistory = onPrintHistory;
    }

    public void setOnDashboard(Runnable onDashboard) {
        this.onDashboard = onDashboard;
    }

    public void setOnPacking(Runnable onPacking) {
        this.onPacking = onPacking;
    }

    public void setOnFboPacking(Runnable onFboPacking) {
        this.onFboPacking = onFboPacking;
    }

    public void setOnFboOrders(Runnable onFboOrders) {
        this.onFboOrders = onFboOrders;
    }

    public void setOnKizMapping(Runnable onKizMapping) {
        this.onKizMapping = onKizMapping;
    }

    public void setOnZnackAutomation(Runnable onZnackAutomation) {
        this.onZnackAutomation = onZnackAutomation;
    }

    public void setOnZnackRegistration(Runnable onZnackRegistration) {
        this.onZnackRegistration = onZnackRegistration;
    }

    public void setOnAddShop(Runnable onAddShop) {
        this.onAddShop = onAddShop;
    }

    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    public void setOnCheckVersion(Runnable onCheckVersion) {
        this.onCheckVersion = onCheckVersion;
    }

    public void setOnPersonalEdition(Runnable onPersonalEdition) {
        this.onPersonalEdition = onPersonalEdition;
    }

    public void setOnAbout(Runnable onAbout) {
        this.onAbout = onAbout;
    }

    public void setOnLanguageChanged(Consumer<AppLanguage> onLanguageChanged) {
        this.onLanguageChanged = onLanguageChanged;
    }

    public void setOnThemeChanged(Consumer<String> onThemeChanged) {
        this.onThemeChanged = onThemeChanged;
    }

    public void setSelectedTheme(String themeName) {
        if ("light".equalsIgnoreCase(themeName)) {
            themeLightMenuItem.setSelected(true);
        } else {
            themeDarkMenuItem.setSelected(true);
        }
    }

    public void setSelectedLanguage(AppLanguage language) {
        if (language == null) {
            return;
        }
        switch (language) {
            case RU -> languageRuMenuItem.setSelected(true);
            case EN -> languageEnMenuItem.setSelected(true);
            case ZH -> languageZhMenuItem.setSelected(true);
            case VI -> languageViMenuItem.setSelected(true);
        }
    }

    public void setMarketplace(Marketplace marketplace) {
        this.marketplace = marketplace == null ? Marketplace.WILDBERRIES : marketplace;
        setAvailable(packingButton, true);
        setAvailable(fboPackingButton, true);
        setAvailable(fboOrdersButton, true);
        setAvailable(kizMappingButton, true);
        setAvailable(znackRegistrationButton, this.marketplace == Marketplace.WILDBERRIES);
        applyMarketplaceTexts();
    }

    private static void setAvailable(Button button, boolean available) {
        button.setVisible(available);
        button.setManaged(available);
        button.setDisable(!available);
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        templateButton.setText(" " + i18n.tr("sidebar.template"));
        addShopButton.setText(" " + i18n.tr("sidebar.add_shop"));
        dashboardButton.setText(" " + i18n.tr("dashboard.title"));
        packingButton.setText(" " + i18n.tr("sidebar.packing"));
        btnPrintHistory.setText(" " + i18n.tr("sidebar.print_history"));
        fboPackingButton.setText(" " + i18n.tr("sidebar.fbo_packing"));
        fboOrdersButton.setText(" " + i18n.tr("sidebar.fbo_orders"));
        kizMappingButton.setText(" " + i18n.tr("sidebar.kiz_mapping"));
        gtinSyncButton.setText(" " + i18n.tr("gtinsync.sidebar"));
        znackAutomationButton.setText(" " + i18n.tr("sidebar.znack_automation"));
        znackRegistrationButton.setText(" " + i18n.tr("sidebar.znack_registration"));
        settingsMenuButton.setText(" " + i18n.tr("settings.menu"));
        languageMenu.setText(i18n.tr("sidebar.language"));
        themeMenu.setText(i18n.tr("settings.theme"));
        themeDarkMenuItem.setText(i18n.tr("settings.theme.dark"));
        themeLightMenuItem.setText(i18n.tr("settings.theme.light"));
        checkVersionMenuItem.setText(i18n.tr("settings.check_version"));
        personalEditionMenuItem.setText(i18n.tr("edition.personal"));
        aboutMenuItem.setText(i18n.tr("settings.about"));
        applyMarketplaceTexts();
        financeButton.setText(i18n.tr("sidebar.finance"));toolsPane.setText(i18n.tr("sidebar.tools"));setGs1Unread(gs1Unread);
        guidesButton.setText(i18n.tr("guides.title"));
        editionStatusLabel.setText("• " + i18n.tr("edition.personal"));
        editionStatusLabel.setStyle("-fx-text-fill: #22c55e; -fx-font-weight: 700;");
    }

    private void applyMarketplaceTexts() {
        if (packingButton == null || kizMappingButton == null) return;
        I18nService i18n = I18nService.getInstance();
        boolean ozon = marketplace == Marketplace.OZON;
        packingButton.setText(" " + i18n.tr(ozon ? "sidebar.ozon_packing" : "sidebar.packing"));
        fboPackingButton.setText(" " + i18n.tr(ozon ? "sidebar.ozon_fbo_packing" : "sidebar.fbo_packing"));
        kizMappingButton.setText(" " + i18n.tr(ozon ? "ozon.mapping.sidebar" : "sidebar.kiz_mapping"));
    }

    @FXML
    private void onDashboard() {
        if (onDashboard != null) {
            onDashboard.run();
        }
    }

    @FXML
    private void onPacking() {
        if (onPacking != null) {
            onPacking.run();
        }
    }

    @FXML
    private void onPrintHistory() {
        if (onPrintHistory != null) {
            onPrintHistory.run();
        }
    }

    @FXML
    private void onFboPacking() {
        if (onFboPacking != null) {
            onFboPacking.run();
        }
    }

    @FXML
    private void onFboOrders() {
        if (onFboOrders != null) {
            onFboOrders.run();
        }
    }

    @FXML
    private void onKizMapping() {
        if (onKizMapping != null) {
            onKizMapping.run();
        }
    }

    @FXML
    private void onZnackAutomation() {
        if (onZnackAutomation != null) {
            onZnackAutomation.run();
        }
    }

    @FXML
    private void onZnackRegistration() {
        if (onZnackRegistration != null) {
            onZnackRegistration.run();
        }
    }

    @FXML
    private void onAddShop() {
        if (onAddShop != null) {
            onAddShop.run();
        }
    }

    @FXML
    private void onSettings() {
        if (onOpenSettings != null) {
            onOpenSettings.run();
        }
    }

    @FXML
    private void onThemeDark() {
        if (onThemeChanged != null) {
            onThemeChanged.accept("dark");
        }
    }

    @FXML
    private void onThemeLight() {
        if (onThemeChanged != null) {
            onThemeChanged.accept("light");
        }
    }

    @FXML
    private void onLanguageRu() {
        changeLanguage(AppLanguage.RU);
    }

    @FXML
    private void onLanguageEn() {
        changeLanguage(AppLanguage.EN);
    }

    @FXML
    private void onLanguageZh() {
        changeLanguage(AppLanguage.ZH);
    }

    @FXML
    private void onLanguageVi() {
        changeLanguage(AppLanguage.VI);
    }

    @FXML
    private void onCheckVersion() {
        if (onCheckVersion != null) {
            onCheckVersion.run();
        }
    }

    @FXML
    private void onPersonalEdition() {
        if (onPersonalEdition != null) {
            onPersonalEdition.run();
        }
    }

    @FXML
    private void onAbout() {
        if (onAbout != null) {
            onAbout.run();
        }
    }

    private void changeLanguage(AppLanguage language) {
        if (onLanguageChanged != null) {
            onLanguageChanged.accept(language);
        }
    }
}
