package com.vncode.app.ui;

import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.ozon.OzonPostingDto;
import com.vncode.app.integration.ozon.OzonPostingItemDto;
import com.vncode.app.integration.ozon.OzonRequirements;
import com.vncode.app.ui.history.PrintHistoryController;
import com.vncode.app.ui.dashboard.DashboardController;
import com.vncode.app.ui.fbo.FboPackingController;
import com.vncode.app.ui.fbosupply.FboSupplyOrdersController;
import com.vncode.app.ui.finance.FinanceDashboardController;
import com.vncode.app.ui.kizmapping.KizMappingController;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.packing.PackingController;
import com.vncode.app.ui.print.PrintTemplateDesignerController;
import com.vncode.app.ui.shop.ShopDialogController;
import com.vncode.app.ui.shop.ShopSidebarController;
import com.vncode.app.ui.supply.SupplyDetailController;
import com.vncode.app.ui.supply.SupplyListController;
import com.vncode.app.ui.workspace.HomeController;
import com.vncode.app.ui.workspace.WorkspaceHeaderController;
import com.vncode.app.ui.znack.ZnackAutomationController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FxmlSmokeTest {
    @Test void wbShippingContextResetsAnOldLoadingButton() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=new com.vncode.app.ui.supply.WbShippingPane();
            var f=pane.getClass().getDeclaredField("load");f.setAccessible(true);var button=(Button)f.get(pane);
            button.setDisable(true);pane.setContext(null,null);assertFalse(button.isDisabled());return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void wbShippingFormRequiresCountryAndAlwaysClearsThePreviousDate() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=new com.vncode.app.ui.supply.WbShippingPane();
            assertThrows(IllegalArgumentException.class,pane::selection);
            var field=pane.getClass().getDeclaredField("date");field.setAccessible(true);var date=(DatePicker)field.get(pane);
            date.setValue(java.time.LocalDate.now().plusDays(1));pane.setContext(null,null);assertEquals(null,date.getValue());return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void tnvedCopyFollowsTheDisplayedTreeDetailRatherThanOldTableSelection() throws Exception {
        var c=new com.vncode.app.features.tnved.TnvedCatalog(appDataDir.resolve("tnved-copy-fixture.sqlite"));c.initialize();var roots=c.children(null);
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=new com.vncode.app.ui.tnved.TnvedPane(c);pane.setRoots(roots);
            var tableField=pane.getClass().getDeclaredField("results");tableField.setAccessible(true);
            var table=(TableView<com.vncode.app.features.tnved.TnvedModels.Node>)tableField.get(pane);
            table.getItems().setAll(roots);table.getSelectionModel().select(0);
            var treeField=pane.getClass().getDeclaredField("tree");treeField.setAccessible(true);
            var tree=(javafx.scene.control.TreeView<com.vncode.app.features.tnved.TnvedModels.Node>)treeField.get(pane);
            tree.getSelectionModel().select(tree.getRoot().getChildren().get(1));
            var right=(VBox)((javafx.scene.control.SplitPane)pane.getCenter()).getItems().get(2);
            ((Button)right.getChildren().get(2)).fire();assertEquals("II",javafx.scene.input.Clipboard.getSystemClipboard().getString());return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void tnvedLoadMoreKeepsTheSubmittedQueryWhenTheInputHasBeenEdited() throws Exception {
        var c=new com.vncode.app.features.tnved.TnvedCatalog(appDataDir.resolve("tnved-pages-fixture.sqlite"));c.initialize();
        var date=java.time.LocalDate.now();String source="https://www.consultant.ru/document/cons_doc_LAW_397176/";
        var nodes=new java.util.ArrayList<com.vncode.app.features.tnved.TnvedModels.Node>();
        nodes.add(new com.vncode.app.features.tnved.TnvedModels.Node("01","I","CHAPTER","I","Fixture","Fixture","","",false,true,date,null,source));
        for(int i=0;i<110;i++)nodes.add(new com.vncode.app.features.tnved.TnvedModels.Node(String.format("01%08d",i),"01","LEAF","I","AlphaFixture","Thử nghiệm","","",true,true,date,null,source));
        c.importVersion(new com.vncode.app.features.tnved.TnvedModels.Version("pages-fixture",source,date),nodes,true);
        var holder=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.ui.tnved.TnvedPane>();
        var create=new java.util.concurrent.FutureTask<Void>(() -> {holder.set(new com.vncode.app.ui.tnved.TnvedPane(c));return null;});Platform.runLater(create);create.get(10,TimeUnit.SECONDS);
        CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
        var search=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=holder.get();var field=pane.getClass().getDeclaredField("query");field.setAccessible(true);var query=(TextField)field.get(pane);
            var tf=pane.getClass().getDeclaredField("results");tf.setAccessible(true);var table=(TableView<?>)tf.get(pane);
            table.getItems().addListener((javafx.collections.ListChangeListener<Object>)change->{if(table.getItems().size()==100)first.countDown();});
            query.setText("AlphaFixture");query.fireEvent(new javafx.event.ActionEvent());return null;
        });Platform.runLater(search);search.get(10,TimeUnit.SECONDS);assertTrue(first.await(10,TimeUnit.SECONDS));
        var more=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=holder.get();var field=pane.getClass().getDeclaredField("query");field.setAccessible(true);((TextField)field.get(pane)).setText("NotSubmittedFixture");
            var tf=pane.getClass().getDeclaredField("results");tf.setAccessible(true);var table=(TableView<?>)tf.get(pane);
            table.getItems().addListener((javafx.collections.ListChangeListener<Object>)change->{if(table.getItems().size()==110)second.countDown();});
            var button=pane.getClass().getDeclaredField("more");button.setAccessible(true);((Button)button.get(pane)).fire();return null;
        });Platform.runLater(more);more.get(10,TimeUnit.SECONDS);assertTrue(second.await(3,TimeUnit.SECONDS),"More must append the remaining ten Alpha rows, preserving the submitted query");
    }
    @Test void tnvedModuleHasNavigationAndShowsVerifiedRootSections() throws Exception {
        var catalog=new com.vncode.app.features.tnved.TnvedCatalog(appDataDir.resolve("tnved-ui-fixture.sqlite"));
        catalog.initialize();var roots=catalog.children(null);
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            FXMLLoader loader=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");
            FxmlViewLoader.load(loader);var controller=(ShopSidebarController)loader.getController();
            var clicked=new AtomicBoolean();controller.setOnTnved(()->clicked.set(true));
            Button button=(Button)loader.getNamespace().get("tnvedButton");assertNotNull(button);button.fire();assertTrue(clicked.get());
            var pane=new com.vncode.app.ui.tnved.TnvedPane(catalog);pane.setRoots(roots);
            assertEquals(21,pane.rootCount());assertEquals("Chưa tải dữ liệu",pane.emptyBranchText());
            return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @TempDir
    static Path appDataDir;

    @BeforeAll
    static void initToolkit() throws Exception {
        System.setProperty("vncode.appdata.dir", appDataDir.toString());
        AtomicBoolean started = new AtomicBoolean(false);
        try {
            Platform.startup(() -> started.set(true));
        } catch (IllegalStateException alreadyStarted) {
            started.set(true);
        }
        if (!started.get()) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT OR IGNORE INTO shops(id,name,api_key) VALUES(1,'Shop A','a')");
        }
    }

    @AfterAll
    static void clearAppDataOverride() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void shouldLoadAllPrimaryViews() throws Exception {
        assertLoads(com.vncode.app.ui.gtinsync.GtinSyncController.class, "gtin-sync-view.fxml");
        assertLoads(HomeController.class, "home-view.fxml");
        assertLoads(DashboardController.class, "dashboard-view.fxml");
        assertLoads(FinanceDashboardController.class, "finance-dashboard-view.fxml");
        assertLoads(FboSupplyOrdersController.class, "fbo-supply-orders-view.fxml");
        assertLoads(ShopSidebarController.class, "shop-sidebar-view.fxml");
        assertLoads(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        assertLoads(SupplyListController.class, "supply-list-view.fxml");
        assertLoads(SupplyDetailController.class, "supply-detail-view.fxml");
        assertLoads(PackingController.class, "packing-view.fxml");
        assertLoads(PrintHistoryController.class, "print-history-view.fxml");
        assertLoads(KizMappingController.class, "kiz-mapping-view.fxml");
        assertLoads(ZnackAutomationController.class, "znack-automation-view.fxml");
        assertLoads(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        assertLoads(ShopDialogController.class, "shop-dialog.fxml");
        assertLoads(OzonDashboardController.class, "ozon-dashboard-view.fxml");
    }

    @Test
    void personalEditionShowsFreeStatusWithoutActivationInEveryLanguage() throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            var i18n = I18nService.getInstance();
            var previous = i18n.getCurrentLanguage();
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                FxmlViewLoader.load(loader);
                ShopSidebarController controller = loader.getController();
                Label status = (Label) loader.getNamespace().get("editionStatusLabel");
                assertNotNull(status, "The personal app must show its own free edition status");
                for (var language : com.vncode.app.shared.AppLanguage.values()) {
                    i18n.setLanguage(language);
                    controller.applyTranslations();
                    assertEquals("• " + i18n.tr("edition.personal"), status.getText());
                    assertTrue(status.isVisible());
                    var settings = (javafx.scene.control.MenuButton) loader.getNamespace().get("settingsMenuButton");
                    assertTrue(settings.getItems().stream().noneMatch(item ->
                            item.getText().equals(i18n.tr("license.menu"))), "No WCode activation entry");
                }
            } finally {
                i18n.setLanguage(previous);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void restoringHeaderShopDoesNotMasqueradeAsAnExplicitUserSelection() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger afterRestore = new AtomicInteger();
        AtomicInteger afterExplicitSelection = new AtomicInteger();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(WorkspaceHeaderController.class,
                        "workspace-header-view.fxml");
                FxmlViewLoader.load(loader);
                WorkspaceHeaderController controller = loader.getController();
                @SuppressWarnings("unchecked")
                ComboBox<Shop> shops = (ComboBox<Shop>) loader.getNamespace().get("shopComboBox");
                Shop restored = new Shop(1, "Restored", "token");
                Shop explicitlySelected = new Shop(2, "Selected", "token");
                controller.setOnShopSelected(ignored -> selections.incrementAndGet());

                controller.setShops(java.util.List.of(restored, explicitlySelected), restored);
                afterRestore.set(selections.get());

                shops.getSelectionModel().select(explicitlySelected);
                afterExplicitSelection.set(selections.get());
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, afterRestore.get());
        assertEquals(1, afterExplicitSelection.get());
    }

    @Test
    void sidebarExposesFboSupplyTrackingForEveryMarketplace() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                FxmlViewLoader.load(loader);
                ShopSidebarController controller = loader.getController();
                Button button = (Button) loader.getNamespace().get("fboOrdersButton");
                controller.setMarketplace(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
                boolean visibleForWb = button != null && button.isVisible() && button.isManaged();
                controller.setMarketplace(com.vncode.app.integration.marketplace.Marketplace.OZON);
                valid.set(visibleForWb && button.isVisible() && button.isManaged());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "FBO/FBW supply tracking must be available for WB and Ozon shops");
    }

    @Test
    void financeDashboardReloadsChangedDatesAndKeepsAccountingKpiOrder() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(
                        FinanceDashboardController.class, "finance-dashboard-view.fxml");
                FxmlViewLoader.load(loader);
                DatePicker from = (DatePicker) loader.getNamespace().get("fromDatePicker");
                DatePicker to = (DatePicker) loader.getNamespace().get("toDatePicker");
                GridPane grid = (GridPane) loader.getNamespace().get("kpiGrid");
                Label payoutValue = (Label) loader.getNamespace().get("payoutValueLabel");
                valid.set(from.getOnAction() != null
                        && to.getOnAction() != null
                        && grid != null
                        && loader.getNamespace().get("payoutHelpLabel") == null
                        && payoutValue != null
                        && payoutValue.getTooltip() != null
                        && isAt(loader, "grossTitleLabel", 0, 0)
                        && isAt(loader, "payoutTitleLabel", 1, 0)
                        && isAt(loader, "commissionTitleLabel", 2, 0)
                        && isAt(loader, "returnsTitleLabel", 3, 0)
                        && isAt(loader, "logisticsTitleLabel", 4, 0)
                        && isAt(loader, "advertisingTitleLabel", 0, 1)
                        && isAt(loader, "storageTitleLabel", 1, 1)
                        && isAt(loader, "penaltyTitleLabel", 2, 1)
                        && isAt(loader, "otherCostTitleLabel", 3, 1)
                        && isAt(loader, "netTitleLabel", 4, 1)
                        && loader.getNamespace().get("commissionColumn") != null
                        && loader.getNamespace().get("netHelpLabel") == null
                        && loader.getNamespace().get("grossRatioLabel") == null
                        && loader.getNamespace().get("netRatioLabel") == null
                        && grid.getColumnConstraints().size() == 5
                        && grid.getHgap() >= 14.0
                        && grid.getVgap() >= 14.0);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Finance dates must reload and KPIs must follow the accounting order");
    }

    private static boolean isAt(FXMLLoader loader, String labelId, int column, int row) {
        Label label = (Label) loader.getNamespace().get(labelId);
        if (label == null) return false;
        Node card = label.getParent();
        return gridIndex(GridPane.getColumnIndex(card)) == column
                && gridIndex(GridPane.getRowIndex(card)) == row;
    }

    private static int gridIndex(Integer index) {
        return index == null ? 0 : index;
    }

    @Test
    void supplyListExposesAccessibleIconOnlyDeleteForEmptySupplies() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(SupplyListController.class, "supply-list-view.fxml");
                FxmlViewLoader.load(loader);
                Button delete = (Button) loader.getNamespace().get("deleteSupplyButton");
                valid.set(delete != null
                        && (delete.getText() == null || delete.getText().isBlank())
                        && delete.getAccessibleText() != null
                        && !delete.getAccessibleText().isBlank());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Empty WB supplies need an accessible icon-only delete action");
    }

    @Test
    void editingOzonShopKeepsMarketplaceImmutableAndSecretWriteOnly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
                FxmlViewLoader.load(loader);
                ShopDialogController controller = loader.getController();
                controller.setShop(new Shop(
                        7,
                        "Ozon shop",
                        com.vncode.app.integration.marketplace.Marketplace.OZON,
                        "client-7",
                        "must-not-be-prefilled"));
                ComboBox<?> marketplace = (ComboBox<?>) loader.getNamespace().get("marketplaceField");
                TextField clientId = (TextField) loader.getNamespace().get("clientIdField");
                PasswordField apiKey = (PasswordField) loader.getNamespace().get("apiKeyField");
                valid.set(marketplace.isDisabled()
                        && clientId.isVisible()
                        && "client-7".equals(clientId.getText())
                        && apiKey.getText().isEmpty()
                        && controller.validate());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon edit must lock marketplace and never prefill its API key");
    }

    @Test
    void createShopDialogKeepsFormScrollableAndPlacesMarketplaceBeforeShopName() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
                ScrollPane root = FxmlViewLoader.load(loader);
                VBox form = (VBox) loader.getNamespace().get("shopFormContent");
                ComboBox<?> marketplace = (ComboBox<?>) loader.getNamespace().get("marketplaceField");
                TextField name = (TextField) loader.getNamespace().get("nameField");
                valid.set(root.isFitToWidth()
                        && root.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER
                        && root.getVbarPolicy() == ScrollPane.ScrollBarPolicy.AS_NEEDED
                        && root.getMaxHeight() <= 420
                        && form.getChildren().indexOf(marketplace) < form.getChildren().indexOf(name));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "The shop form must scroll while dialog buttons remain outside the content");
    }

    @Test
    void sidebarShouldExposeNavigationButtons() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean found = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                Parent root = FxmlViewLoader.load(loader);
                found.set(root.lookup("#dashboardButton") != null
                        && root.lookup("#packingButton") != null
                        && root.lookup("#kizMappingButton") != null
                        && root.lookup("#znackAutomationButton") != null);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(found.get(), "Sidebar should contain the primary navigation buttons");
    }

    @Test
    void ozonSidebarKeepsPackingAndCatalogMappingAvailable() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                FxmlViewLoader.load(loader);
                ShopSidebarController controller = loader.getController();
                controller.setMarketplace(com.vncode.app.integration.marketplace.Marketplace.OZON);
                Button packing = (Button) loader.getNamespace().get("packingButton");
                Button mapping = (Button) loader.getNamespace().get("kizMappingButton");
                Button fbo = (Button) loader.getNamespace().get("fboPackingButton");
                valid.set(packing.isVisible() && packing.isManaged() && !packing.isDisabled()
                        && (" " + I18nService.getInstance().tr("sidebar.ozon_packing")).equals(packing.getText())
                        && mapping.isVisible() && mapping.isManaged() && !mapping.isDisabled()
                        && fbo.isVisible() && fbo.isManaged() && !fbo.isDisabled()
                        && (" " + I18nService.getInstance().tr("sidebar.ozon_fbo_packing")).equals(fbo.getText()));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon must expose FBS orders, FBO packing, and SKU-to-GTIN catalog mapping");
    }

    @Test
    void ozonFboPackingShowsCatalogSkuAndCategoryFilter() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(FboPackingController.class, "fbo-packing-view.fxml");
                FxmlViewLoader.load(loader);
                FboPackingController controller = loader.getController();
                controller.setMarketplace(com.vncode.app.integration.marketplace.Marketplace.OZON);
                javafx.scene.control.MenuButton categories =
                        (javafx.scene.control.MenuButton) loader.getNamespace().get("categoryMenuButton");
                javafx.scene.control.TableColumn<?, ?> catalogSku =
                        (javafx.scene.control.TableColumn<?, ?>) loader.getNamespace().get("catalogSkuColumn");
                Label title = (Label) loader.getNamespace().get("titleLabel");
                valid.set(categories.isVisible() && categories.isManaged()
                        && catalogSku.isVisible()
                        && I18nService.getInstance().tr("ozon.fbo.title").equals(title.getText()));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon FBO must show synchronized SKUs and the multi-category filter");
    }

    @Test
    void supplyDetailShouldExposeZnackGtinInventoryPane() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(SupplyDetailController.class, "supply-detail-view.fxml");
                FxmlViewLoader.load(loader);
                valid.set(loader.getNamespace().get("gtinInventoryPane") != null
                        && loader.getNamespace().get("gtinInventoryList") != null
                        && loader.getNamespace().get("gtinInventoryRefreshButton") != null
                        && loader.getNamespace().get("gtinInventoryLoading") != null
                        && loader.getNamespace().get("gtinInventoryEmptyLabel") != null);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Supply detail should expose the Znack GTIN inventory pane");
    }

    @Test
    void ozonDashboardShouldExposeFbsOrderGroupsSelectionAndPackingLabels() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
                FxmlViewLoader.load(loader);
                TabPane tabs = (TabPane) loader.getNamespace().get("orderStatusTabs");
                @SuppressWarnings("unchecked")
                TableView<OzonPostingDto> newOrders =
                        (TableView<OzonPostingDto>) loader.getNamespace().get("newOrdersTable");
                TableView<?> packingOrders = (TableView<?>) loader.getNamespace().get("packingOrdersTable");
                Button moveToPacking = (Button) loader.getNamespace().get("moveToPackingButton");
                javafx.scene.control.Tab newOrdersTab =
                        (javafx.scene.control.Tab) loader.getNamespace().get("newOrdersTab");
                javafx.scene.control.Tab packingOrdersTab =
                        (javafx.scene.control.Tab) loader.getNamespace().get("packingOrdersTab");
                javafx.scene.control.Tab deliveringOrdersTab =
                        (javafx.scene.control.Tab) loader.getNamespace().get("deliveringOrdersTab");
                OzonDashboardController controller = loader.getController();
                controller.setShop(new Shop(909, "Ozon test",
                        com.vncode.app.integration.marketplace.Marketplace.OZON,
                        "client-909", "secret-909"), false);
                newOrders.getItems().add(new OzonPostingDto(
                        "POST-909", "ORDER-909", "ORDER-909", "awaiting_packaging", "", "warehouse",
                        "2026-08-25T08:30:00Z", "", "", "",
                        new OzonRequirements(java.util.List.of(), java.util.List.of(), java.util.List.of()),
                        java.util.List.of("ship"), true,
                        java.util.List.of(new OzonPostingItemDto(
                                0, "101", "SKU-101", "offer-101", "Item", 1, "RUB", "100"))));
                javafx.scene.control.TableColumn<?, ?> selectColumn =
                        (javafx.scene.control.TableColumn<?, ?>) loader.getNamespace().get("newOrderSelectTC");
                javafx.scene.control.TableColumn<?, ?> newOrderNumber =
                        (javafx.scene.control.TableColumn<?, ?>) loader.getNamespace().get("newOrderNumberTC");
                javafx.scene.control.TableColumn<?, ?> packingOrderNumber =
                        (javafx.scene.control.TableColumn<?, ?>) loader.getNamespace().get("packingOrderNumberTC");
                javafx.scene.control.TableColumn<?, ?> deliveringOrderNumber =
                        (javafx.scene.control.TableColumn<?, ?>) loader.getNamespace().get("deliveringOrderNumberTC");
                CheckBox selectAll = (CheckBox) selectColumn.getGraphic();
                selectAll.fire();
                valid.set(tabs != null
                        && tabs.getTabs().size() == 3
                        && tabs.getSelectionModel().getSelectedItem() == loader.getNamespace().get("newOrdersTab")
                        && newOrdersTab.getText().endsWith("(0)")
                        && packingOrdersTab.getText().endsWith("(0)")
                        && deliveringOrdersTab.getText().endsWith("(0)")
                        && newOrders != null
                        && loader.getNamespace().get("newOrderImageTC") != null
                        && loader.getNamespace().get("newOrderSelectTC") != null
                        && loader.getNamespace().get("selectionActionBar") != null
                        && moveToPacking != null
                        && !moveToPacking.isDisabled()
                        && moveToPacking.isVisible()
                        && ((javafx.scene.layout.HBox) loader.getNamespace().get("selectionActionBar")).isVisible()
                        && packingOrders != null
                        && loader.getNamespace().get("packingOrderImageTC") != null
                        && loader.getNamespace().get("packingLabelTC") != null
                        && loader.getNamespace().get("deliveringOrdersTable") != null
                        && loader.getNamespace().get("deliveringOrderImageTC") != null
                        && loader.getNamespace().get("newOrderShipmentTC") == null
                        && loader.getNamespace().get("packingOrderShipmentTC") == null
                        && loader.getNamespace().get("deliveringOrderShipmentTC") == null
                        && loader.getNamespace().get("accountLabel") == null
                        && newOrderNumber.getPrefWidth() == 100.0
                        && packingOrderNumber.getPrefWidth() == 100.0
                        && deliveringOrderNumber.getPrefWidth() == 100.0
                        && loader.getNamespace().get("printAllButton") != null
                        && loader.getNamespace().get("sortByProductCheckBox") != null
                        && loader.getNamespace().get("sortByArticleCheckBox") != null
                        && loader.getNamespace().get("sortByColorCheckBox") != null
                        && loader.getNamespace().get("sortBySizeCheckBox") != null
                        && loader.getNamespace().get("gtinInventoryTitleLabel") != null
                        && loader.getNamespace().get("gtinSearchField") != null
                        && loader.getNamespace().get("gtinInventoryList") != null
                        && loader.getNamespace().get("gtinInventoryRefreshButton") != null);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon FBS orders should expose three states, bulk selection and packing labels");
    }

    @Test
    void reopeningSameOzonDashboardWhileBusyPreservesTheActiveRequestToken() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
                FxmlViewLoader.load(loader);
                OzonDashboardController controller = loader.getController();
                Shop first = new Shop(7, "Ozon", com.vncode.app.integration.marketplace.Marketplace.OZON,
                        "client-7", "secret-7");
                Shop sameContext = new Shop(7, "Ozon renamed",
                        com.vncode.app.integration.marketplace.Marketplace.OZON,
                        "client-7", "secret-7");
                controller.setShop(first, false);
                var busyMethod = OzonDashboardController.class.getDeclaredMethod("setBusy", boolean.class);
                busyMethod.setAccessible(true);
                var tokenField = OzonDashboardController.class.getDeclaredField("requestToken");
                tokenField.setAccessible(true);
                busyMethod.invoke(controller, true);
                long activeToken = tokenField.getLong(controller);
                controller.setShop(sameContext, false);
                ProgressIndicator indicator = (ProgressIndicator) loader.getNamespace().get("loadingIndicator");
                valid.set(activeToken == tokenField.getLong(controller) && indicator.isVisible());
                busyMethod.invoke(controller, false);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Reopening the same shop must not orphan a running sync and leave its spinner stuck");
    }

    @Test
    void znackSettingsShouldExposeOnlyBasicWorkflowAndEnableSaveAfterChange() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ZnackAutomationController.class, "znack-automation-view.fxml");
                FxmlViewLoader.load(loader);
                ZnackAutomationController controller = loader.getController();
                controller.setShop(new Shop(1, "Shop A", "a"));
                Button save = (Button) loader.getNamespace().get("saveButton");
                Button omsIdHelp = (Button) loader.getNamespace().get("omsIdHelpButton");
                Button omsConnectionHelp = (Button) loader.getNamespace().get("omsConnectionHelpButton");
                Button closeOmsHelp = (Button) loader.getNamespace().get("closeOmsHelpButton");
                VBox omsHelpPane = (VBox) loader.getNamespace().get("omsHelpPane");
                Label omsHelpTitle = (Label) loader.getNamespace().get("omsHelpTitleLabel");
                TextField omsConnection = (TextField) loader.getNamespace().get("omsConnectionField");
                ComboBox<?> signatureCertificate = (ComboBox<?>) loader.getNamespace().get("signatureCertificateCombo");
                boolean initiallyDisabled = save.isDisabled();
                boolean helpInitiallyHidden = !omsHelpPane.isVisible() && !omsHelpPane.isManaged();
                omsIdHelp.fire();
                boolean omsIdHelpShown = omsHelpPane.isVisible() && omsHelpPane.isManaged()
                        && omsHelpTitle.getText().contains("omsId");
                omsConnectionHelp.fire();
                boolean omsConnectionHelpShown = omsHelpPane.isVisible()
                        && omsHelpTitle.getText().contains("omsConnection");
                closeOmsHelp.fire();
                boolean helpClosed = !omsHelpPane.isVisible() && !omsHelpPane.isManaged();
                omsConnection.setText("changed-connection");
                valid.set(loader.getNamespace().get("basicSettingsCard") != null
                        && loader.getNamespace().get("advancedSettingsPane") == null
                        && loader.getNamespace().get("omsConnectionField") != null
                        && signatureCertificate != null
                        && signatureCertificate.getOnShowing() != null
                        && loader.getNamespace().get("refreshCertificatesButton") == null
                        && loader.getNamespace().get("testSignatureButton") != null
                        && loader.getNamespace().get("documentNumberField") == null
                        && loader.getNamespace().get("trueApiUrlField") == null
                        && loader.getNamespace().get("omsIdField") != null
                        && helpInitiallyHidden && omsIdHelpShown && omsConnectionHelpShown && helpClosed
                        && loader.getNamespace().get("authenticateButton") == null
                        && initiallyDisabled && !save.isDisabled());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get());
    }

    @Test
    void newsPagePaginatesAndRendersSafeRichTextInAllLanguages() throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
            try {
                var rows=java.util.stream.IntStream.range(0,25).mapToObj(n ->
                    new com.vncode.app.features.news.NewsItem("id"+n,java.time.Instant.EPOCH.plusSeconds(n),"Title "+n,"# Header\n**Bold** *italic*\n- item\n<script>plain text</script>")).toList();
                var service=new com.vncode.app.features.news.NewsService(rows,
                    new com.vncode.app.features.news.NewsRepository(appDataDir));
                var pane=new com.vncode.app.ui.news.NewsPane(service,() -> {},count -> {});
                for(var language:com.vncode.app.shared.AppLanguage.values()) {
                    i18n.setLanguage(language);pane.applyTranslations();
                    assertEquals(i18n.tr("news.title"),pane.pageTitle());
                }
                assertEquals(20,pane.visibleItems());pane.loadMore();assertEquals(25,pane.visibleItems());
                ScrollPane newsScroll=(ScrollPane)pane.getChildren().get(2);newsScroll.setVvalue(0.65);
                pane.open(rows.getFirst());assertTrue(service.isRead("id0"));
                assertTrue(pane.showingDetail());pane.back();assertFalse(pane.showingDetail());assertEquals(0.65,newsScroll.getVvalue(),0.001);
            } finally {i18n.setLanguage(previous);}return null;
        });
        Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }

    @Test
    void boardRefreshDiscardsAnOlderDispatchPageForTheSameShop() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);
        var rows=java.util.stream.IntStream.range(1,51).mapToObj(n -> new com.vncode.app.integration.wb.WbSupplySummary("S"+n,"s",true,false,"now",1)).toList();
        var fake=new com.vncode.app.features.packing.PackingWorkflow(){
            @Override public com.vncode.app.integration.wb.WbSupplyRepository.SupplyPage loadDispatchPage(Shop shop,int offset){
                started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                returned.countDown();return new com.vncode.app.integration.wb.WbSupplyRepository.SupplyPage(rows.subList(offset,Math.min(offset+20,50)),50,0,50);
            }
        };
        var reference=new java.util.concurrent.atomic.AtomicReference<PackingController>();
        var tableRef=new java.util.concurrent.atomic.AtomicReference<TableView<?>>();
        var boardMethod=PackingController.class.getDeclaredMethod("setBoard",com.vncode.app.features.packing.PackingWorkflow.PackingBoard.class);boardMethod.setAccessible(true);
        var setup=new java.util.concurrent.FutureTask<Void>(() -> {
            FXMLLoader loader=FxmlViewLoader.loader(PackingController.class,"packing-view.fxml");FxmlViewLoader.load(loader);
            PackingController controller=loader.getController();var field=PackingController.class.getDeclaredField("packingWorkflow");field.setAccessible(true);field.set(controller,fake);
            controller.setShop(new Shop(1,"fixture","fixture"),false);
            var delay=PackingController.class.getDeclaredField("delayTransition");delay.setAccessible(true);((javafx.animation.PauseTransition)delay.get(controller)).stop();
            boardMethod.invoke(controller,new com.vncode.app.features.packing.PackingWorkflow.PackingBoard(java.util.List.of(),java.util.List.of(),rows.subList(0,40)));
            reference.set(controller);tableRef.set((TableView<?>)loader.getNamespace().get("dispatchTable"));
            ((Button)loader.getNamespace().get("dispatchMoreButton")).fire();return null;
        });Platform.runLater(setup);setup.get(5,TimeUnit.SECONDS);assertTrue(started.await(5,TimeUnit.SECONDS));
        var reset=new java.util.concurrent.FutureTask<Void>(() -> {boardMethod.invoke(reference.get(),new com.vncode.app.features.packing.PackingWorkflow.PackingBoard(java.util.List.of(),java.util.List.of(),rows.subList(0,20)));return null;});
        Platform.runLater(reset);reset.get(5,TimeUnit.SECONDS);release.countDown();assertTrue(returned.await(5,TimeUnit.SECONDS));Thread.sleep(200);
        var check=new java.util.concurrent.FutureTask<Void>(() -> {assertEquals(20,tableRef.get().getItems().size(),"Old page must not append after reset");return null;});Platform.runLater(check);check.get(5,TimeUnit.SECONDS);
    }

    private void assertLoads(Class<?> resourceOwner, String resourceName) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean loaded = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(resourceOwner, resourceName);
                Object root = FxmlViewLoader.load(loader);
                assertNotNull(root);
                loaded.set(true);
            } catch (Throwable ex) {
                failed.set(true);
                throw ex;
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(loaded.get() && !failed.get(), "Failed to load " + resourceOwner.getSimpleName() + "/" + resourceName);
    }
}
