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
    @Test void gs1DoesNotStartTwoOperationsInTheSameShopGeneration() throws Exception {
        var release=new CountDownLatch(1);var holder=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.ui.gs1.Gs1Pane>();
        var work=new java.util.concurrent.Callable<String>(){public String call()throws Exception{release.await(10,TimeUnit.SECONDS);return "fixture";}};
        var setup=new java.util.concurrent.FutureTask<Void>(()->{
            var pane=new com.vncode.app.ui.gs1.Gs1Pane();holder.set(pane);
            var method=pane.getClass().getDeclaredMethod("run",java.util.concurrent.Callable.class,java.util.function.Consumer.class);method.setAccessible(true);
            method.invoke(pane,work,(java.util.function.Consumer<String>)ignored->{});
            method.invoke(pane,work,(java.util.function.Consumer<String>)ignored->{});
            assertEquals(1,pane.activeTaskCount(),"Thread actions must not start another operation while busy");return null;
        });
        try{Platform.runLater(setup);setup.get(10,TimeUnit.SECONDS);}
        finally{release.countDown();awaitBackgroundTasksBeforeFixtureCleanup();var close=new java.util.concurrent.FutureTask<Void>(()->{if(holder.get()!=null)holder.get().dispose();return null;});Platform.runLater(close);close.get(5,TimeUnit.SECONDS);}
    }
    @Test void gs1LongConversationScrollsToNewestAfterTheSkinIsInstalled() throws Exception {
        var holder=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.ui.gs1.Gs1Pane>();var window=new java.util.concurrent.atomic.AtomicReference<javafx.stage.Stage>();
        var setup=new java.util.concurrent.FutureTask<Void>(()->{
            var pane=new com.vncode.app.ui.gs1.Gs1Pane();holder.set(pane);pane.setShop(new Shop(1,"GS1 UI fixture","fixture"));
            var inn=pane.getClass().getDeclaredField("inn");inn.setAccessible(true);inn.set(pane,"7707083893");
            var repository=pane.getClass().getDeclaredField("repository");repository.setAccessible(true);var repo=(com.vncode.app.features.gs1.Gs1Repository)repository.get(pane);
            var request=repo.createRequest("7707083893","RENEWAL","mail@gs1ru.org","Scroll fixture","Draft");
            for(int i=0;i<20;i++)repo.addMessage(request.inn(),request.id(),"scroll-"+i,false,"mail@gs1ru.org","Long fixture message\n".repeat(12),false);
            var reload=pane.getClass().getDeclaredMethod("reloadRequests");reload.setAccessible(true);reload.invoke(pane);
            var field=pane.getClass().getDeclaredField("requests");field.setAccessible(true);((javafx.scene.control.ListView<com.vncode.app.features.gs1.Gs1Repository.Request>)field.get(pane)).getSelectionModel().select(request);
            var stage=new javafx.stage.Stage();window.set(stage);stage.setScene(new javafx.scene.Scene(pane,1100,650));stage.show();pane.applyCss();pane.layout();return null;
        });
        try{
            Platform.runLater(setup);setup.get(10,TimeUnit.SECONDS);
            var check=new java.util.concurrent.FutureTask<Void>(()->{var field=holder.get().getClass().getDeclaredField("conversationScroll");field.setAccessible(true);var scroll=(ScrollPane)field.get(holder.get());
                assertTrue(scroll.getContent().getBoundsInLocal().getHeight()>scroll.getViewportBounds().getHeight());assertEquals(1,scroll.getVvalue(),0.001);return null;});
            Platform.runLater(check);check.get(10,TimeUnit.SECONDS);
        }finally{var close=new java.util.concurrent.FutureTask<Void>(()->{if(holder.get()!=null)holder.get().dispose();if(window.get()!=null)window.get().close();return null;});Platform.runLater(close);close.get(5,TimeUnit.SECONDS);}
    }
    @Test void gs1StartsWithoutNetworkAndDiscardsOldShopCallbacks() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var applied=new AtomicInteger();
        var holder=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.ui.gs1.Gs1Pane>();
        var setup=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=new com.vncode.app.ui.gs1.Gs1Pane();holder.set(pane);
            pane.setShop(new Shop(1,"Fixture A","fixture"));
            assertEquals(0,pane.activeTaskCount());
            var method=pane.getClass().getDeclaredMethod("run",java.util.concurrent.Callable.class,java.util.function.Consumer.class);method.setAccessible(true);
            method.invoke(pane,(java.util.concurrent.Callable<String>)()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return "old shop";},(java.util.function.Consumer<String>)ignored->applied.incrementAndGet());
            pane.setShop(new Shop(2,"Fixture B","fixture"));return null;
        });
        try{
            Platform.runLater(setup);setup.get(10,TimeUnit.SECONDS);assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown();
            awaitBackgroundTasksBeforeFixtureCleanup();assertEquals(0,applied.get());
        }finally{
            release.countDown();var close=new java.util.concurrent.FutureTask<Void>(() -> {if(holder.get()!=null)holder.get().dispose();return null;});Platform.runLater(close);close.get(5,TimeUnit.SECONDS);
        }
    }
    @Test void printPreflightClaimsActivityImmediately() throws Exception { assertPrintPreflightContext(true); }
    @Test void printPreflightCannotOpenDialogsForAnotherShop() throws Exception { assertPrintPreflightContext(false); }
    private void assertPrintPreflightContext(boolean checkImmediateActivity) throws Exception {
        var release = new CountDownLatch(1);
        var checked = new CountDownLatch(1);
        var dialogs = new AtomicInteger();
        var verifications = new AtomicInteger();
        var holder = new java.util.concurrent.atomic.AtomicReference<HomeController>();
        var setup = new java.util.concurrent.FutureTask<Void>(() -> {
            var loader = FxmlViewLoader.loader(HomeController.class, "home-view.fxml"); FxmlViewLoader.load(loader);
            var home = (HomeController) loader.getController(); holder.set(home);
            var sf = HomeController.class.getDeclaredField("state"); sf.setAccessible(true); var state = sf.get(home);
            var select = state.getClass().getDeclaredMethod("setSelectedShop", Shop.class); select.setAccessible(true);
            select.invoke(state, new Shop(1, "Fixture A", "fixture-only"));
            var supply = state.getClass().getDeclaredMethod("setLoadedSupplyId", String.class); supply.setAccessible(true); supply.invoke(state, "WB-A");
            var order = new com.vncode.app.models.Order(); order.setId(123L);
            var orders = state.getClass().getDeclaredMethod("setDisplayedOrders", java.util.List.class); orders.setAccessible(true); orders.invoke(state, java.util.List.of(order));
            var workflow = HomeController.class.getDeclaredField("orderExportWorkflow"); workflow.setAccessible(true);
            workflow.set(home, new com.vncode.app.features.print.OrderExportWorkflow() {
                @Override public void verifyKizAvailability(java.util.List<com.vncode.app.models.Order> ignored, Shop shop) {
                    verifications.incrementAndGet();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    checked.countDown();
                }
            });
            var options = HomeController.class.getDeclaredField("printOptionsDialogService"); options.setAccessible(true);
            options.set(home, new com.vncode.app.features.print.PrintOptionsDialogService() {
                @Override public java.util.Optional<com.vncode.app.features.print.PrintJobOptions> chooseOptions() {
                    dialogs.incrementAndGet(); return java.util.Optional.empty();
                }
            });
            home.onExport(new javafx.event.ActionEvent());
            var busy = HomeController.class.getDeclaredMethod("isShopBusy", int.class); busy.setAccessible(true);
            if (checkImmediateActivity) {
                assertTrue((boolean) busy.invoke(home, 1), "The print handoff must claim activity before its asynchronous task starts");
                home.onExport(new javafx.event.ActionEvent());
            }
            select.invoke(state, new Shop(2, "Fixture B", "fixture-only"));
            var clear = state.getClass().getDeclaredMethod("clearLoadedSupply"); clear.setAccessible(true); clear.invoke(state);
            supply.invoke(state, "WB-B");
            return null;
        });
        try {
            Platform.runLater(setup); setup.get(15, TimeUnit.SECONDS); release.countDown();
            assertTrue(checked.await(10, TimeUnit.SECONDS));
            awaitBackgroundTasksBeforeFixtureCleanup();
            var verify = new java.util.concurrent.FutureTask<Void>(() -> {
                assertEquals(1, verifications.get(), "Repeated clicks must not create a second print preflight");
                assertEquals(0, dialogs.get(), "The old shop's preflight must not open a deferred print dialog");
                return null;
            }); Platform.runLater(verify); verify.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            var cleanup = new java.util.concurrent.FutureTask<Void>(() -> { if(holder.get()!=null) holder.get().dispose(); return null; });
            Platform.runLater(cleanup); cleanup.get(10, TimeUnit.SECONDS);
        }
    }
    @Test void supplyPrintCanBeRequestedDuringLoadAndIsCancelledOnContextReset() throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            var loader = FxmlViewLoader.loader(HomeController.class, "home-view.fxml");
            FxmlViewLoader.load(loader);
            var home = (HomeController) loader.getController();
            try {
                var sf = HomeController.class.getDeclaredField("state"); sf.setAccessible(true);
                var state = sf.get(home);
                var select = state.getClass().getDeclaredMethod("setSelectedShop", Shop.class); select.setAccessible(true);
                select.invoke(state, new Shop(1, "Fixture", "fixture-only"));
                var supply = state.getClass().getDeclaredMethod("setLoadedSupplyId", String.class); supply.setAccessible(true);
                supply.invoke(state, "WB-PRINT-FIXTURE");
                var requestField = HomeController.class.getDeclaredField("supplyPrintRequest"); requestField.setAccessible(true);
                var request = requestField.get(home);
                var begin = request.getClass().getDeclaredMethod("begin", int.class, String.class, long.class); begin.setAccessible(true);
                begin.invoke(request, 1, "WB-PRINT-FIXTURE", 10L);
                var update = HomeController.class.getDeclaredMethod("updateHeaderState"); update.setAccessible(true); update.invoke(home);
                var detailField = HomeController.class.getDeclaredField("supplyDetailController"); detailField.setAccessible(true);
                var detail = detailField.get(home);
                var buttonField = detail.getClass().getDeclaredField("printButton"); buttonField.setAccessible(true);
                var button = (Button) buttonField.get(detail);
                assertTrue(button.isVisible());
                assertFalse(button.isDisabled(), "Printing must be requestable before the first background order refresh finishes");
                home.onExport(new javafx.event.ActionEvent());
                var pending = request.getClass().getDeclaredMethod("isPending"); pending.setAccessible(true);
                assertTrue((boolean) pending.invoke(request));
                assertTrue(button.isDisabled(), "Only one pending print request may be accepted");
                home.onExport(new javafx.event.ActionEvent());
                assertTrue((boolean) pending.invoke(request));
                var reset = HomeController.class.getDeclaredMethod("resetLoadedSupply"); reset.setAccessible(true); reset.invoke(home);
                assertFalse((boolean) pending.invoke(request));
                assertTrue(button.isDisabled());
            } finally { home.dispose(); }
            return null;
        });
        Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }
    @Test void narrowSupplyCanStillReachBarcodesWithInventoryClosed() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(SupplyDetailController.class,"supply-detail-view.fxml");Parent root=FxmlViewLoader.load(loader);var controller=(SupplyDetailController)loader.getController();var order=new com.vncode.app.models.Order();order.setId(123L);order.setName("Layout");controller.setOrders(java.util.List.of(order));
            var table=(TableView<?>)loader.getNamespace().get("orderTable");var scene=new javafx.scene.Scene(root,740,730);scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());var stage=new javafx.stage.Stage();stage.setScene(scene);
            try {stage.show();root.applyCss();root.layout();assertTrue(((javafx.scene.layout.Region)root).minWidth(730)<=740,"Supply header must allow the workspace to shrink");assertTrue(table.lookupAll(".scroll-bar").stream().anyMatch(n->n instanceof javafx.scene.control.ScrollBar b&&b.getOrientation()==javafx.geometry.Orientation.HORIZONTAL&&b.isVisible()&&b.getMax()>b.getMin()&&b.getHeight()>=10),"Narrow supply must keep barcode and price accessible before opening inventory");}finally {stage.close();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void homeRootFitsAResizedWindowInsteadOfClippingTheWorkspace() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(HomeController.class,"home-view.fxml");Parent root=FxmlViewLoader.load(loader);var home=(HomeController)loader.getController();var scene=new javafx.scene.Scene(root,1040,760);scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());var stage=new javafx.stage.Stage();stage.setScene(scene);
            try {stage.show();root.applyCss();root.layout();assertTrue(((javafx.scene.layout.Region)root).minWidth(760)<=1040,"Home must be able to shrink below its preferred 1300px width");assertTrue(root.getLayoutBounds().getWidth()<=scene.getWidth()+1,"Home must fit the actual window");}finally {stage.close();home.dispose();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void guidesRemainReadableOnTheDarkViewport() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=new com.vncode.app.ui.guides.GuidesPane();var shell=new javafx.scene.layout.BorderPane(pane);shell.getStyleClass().add("theme-dark");var scene=new javafx.scene.Scene(shell,1000,700);scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());shell.applyCss();shell.layout();
            var viewport=(javafx.scene.layout.Region)pane.lookup(".viewport");var background=(javafx.scene.paint.Color)viewport.getBackground().getFills().getFirst().getFill();
            var title=(javafx.scene.paint.Color)((Label)pane.lookup(".welcome-title")).getTextFill();
            if(background.getOpacity()==0)background=(javafx.scene.paint.Color)shell.getBackground().getFills().getFirst().getFill();
            assertTrue(Math.abs(title.getBrightness()-background.getBrightness())>0.5,"Guide title must contrast with its actual viewport background");return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void homeShowsLandingAndGuidesWithoutAConfiguredShop() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(HomeController.class,"home-view.fxml");FxmlViewLoader.load(loader);var home=(HomeController)loader.getController();
            try {
                assertTrue(((Node)loader.getNamespace().get("contentPane")).isVisible(),"Fresh app must show the welcome page before shop setup");
                var nf=HomeController.class.getDeclaredField("workspaceNavigator");nf.setAccessible(true);var navigator=(com.vncode.app.ui.workspace.WorkspaceNavigator)nf.get(home);navigator.show("finance");
                var reset=HomeController.class.getDeclaredMethod("clearWorkspaceView");reset.setAccessible(true);reset.invoke(home);
                assertEquals("welcome",navigator.currentRoute(),"Removing the shop context must leave shop-specific pages");
                var guides=HomeController.class.getDeclaredMethod("showGuides");guides.setAccessible(true);guides.invoke(home);
                assertTrue(((Node)loader.getNamespace().get("contentPane")).isVisible(),"Shop setup guides must stay visible without credentials");
            }finally {home.dispose();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void dashboardCardsHaveUsableRenderedHeightsWithProductionThemes() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var item=new com.vncode.app.features.news.NewsItem("layout",java.time.Instant.now(),"Wildberries và Ozon: cập nhật thông tin sản phẩm", "Đồng bộ đơn hàng, kiểm tra GTIN và in nhãn theo cấu hình của từng cửa hàng. Xem chi tiết để chuẩn bị lô hàng.");
            var news=new com.vncode.app.ui.news.NewsPane(new com.vncode.app.features.news.NewsService(java.util.List.of(item),new com.vncode.app.features.news.NewsRepository(appDataDir.resolve("layout-news"))),()->{},n->{});
            var pane=new com.vncode.app.ui.dashboard.WelcomePane(news.preview(),java.util.stream.IntStream.range(0,6).mapToObj(i->(Runnable)()->{}).toList());
            var scene=new javafx.scene.Scene(pane,1100,760);scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());
            var stage=new javafx.stage.Stage();stage.setScene(scene);
            try {
                stage.show();
                for(String theme:java.util.List.of("theme-dark","theme-light")) {
                    pane.getStyleClass().removeAll("theme-dark","theme-light");pane.getStyleClass().add(theme);pane.applyCss();pane.layout();
                    for(var node:pane.lookupAll(".feature-card"))assertTrue(node.getBoundsInParent().getHeight()>=140&&node.getBoundsInParent().getHeight()<260,"Feature card must fit a normal dashboard row: "+node.getBoundsInParent().getHeight());
                    for(var node:pane.lookupAll(".news-card"))assertTrue(node.getBoundsInParent().getHeight()<350,"News preview must not force a huge scroll area");
                    assertTrue(pane.lookup(".welcome-title").localToScene(pane.lookup(".welcome-title").getBoundsInLocal()).getMinY()>=0,"Welcome title must be visible on first open");
                }
            } finally {stage.close();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void openedInventoryKeepsTrailingSupplyColumnsAccessible() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(SupplyDetailController.class,"supply-detail-view.fxml");Parent root=FxmlViewLoader.load(loader);var controller=(SupplyDetailController)loader.getController();
            var order=new com.vncode.app.models.Order();order.setId(123L);order.setName("Sản phẩm kiểm tra");order.setBarcode("0123456789012");controller.setOrders(java.util.List.of(order));
            var table=(TableView<?>)loader.getNamespace().get("orderTable");var scene=new javafx.scene.Scene(root,1040,730);scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());var stage=new javafx.stage.Stage();stage.setScene(scene);
            try {stage.show();((Button)loader.getNamespace().get("inventoryToggleButton")).fire();root.applyCss();root.layout();
                assertTrue(table.lookupAll(".scroll-bar").stream().anyMatch(n->n instanceof javafx.scene.control.ScrollBar b&&b.getOrientation()==javafx.geometry.Orientation.HORIZONTAL&&b.isVisible()&&b.getMax()>b.getMin()&&b.getHeight()>=10),"Inventory must provide a usable horizontal scrollbar for barcode and price columns");
            } finally {stage.close();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void guidesAreReachableFromHeaderAndSidebar() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(HomeController.class,"home-view.fxml");FxmlViewLoader.load(loader);var home=(HomeController)loader.getController();
            try {var f=HomeController.class.getDeclaredField("workspaceNavigator");f.setAccessible(true);var nav=(com.vncode.app.ui.workspace.WorkspaceNavigator)f.get(home);nav.show("guides");assertEquals("guides",nav.currentRoute());
                var side=HomeController.class.getDeclaredField("shopSidebarController");side.setAccessible(true);var guide=side.get(home).getClass().getDeclaredField("guidesButton");guide.setAccessible(true);((Button)guide.get(side.get(home))).fire();assertEquals("guides",nav.currentRoute());
                nav.show("welcome");var hf=HomeController.class.getDeclaredField("workspaceHeaderController");hf.setAccessible(true);var help=hf.get(home).getClass().getDeclaredField("supportButton");help.setAccessible(true);((Button)help.get(hf.get(home))).fire();assertEquals("guides",nav.currentRoute());
            }finally {home.dispose();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void gs1NavigationWorksAndTnvedIsInternalOnly() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(HomeController.class,"home-view.fxml");FxmlViewLoader.load(loader);var home=(HomeController)loader.getController();
            try {
                var field=HomeController.class.getDeclaredField("workspaceNavigator");field.setAccessible(true);var navigator=(com.vncode.app.ui.workspace.WorkspaceNavigator)field.get(home);
                navigator.show("news");assertEquals("news",navigator.currentRoute());
                navigator.show("gs1");assertEquals("gs1",navigator.currentRoute());
                assertThrows(IllegalArgumentException.class,()->navigator.show("tnved"));
                var sidebar=HomeController.class.getDeclaredField("shopSidebarController");sidebar.setAccessible(true);
                var button=sidebar.get(home).getClass().getDeclaredField("gs1Button");button.setAccessible(true);
                ((Button)button.get(sidebar.get(home))).fire();assertEquals("gs1",navigator.currentRoute());
            } finally {home.dispose();}return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    @Test void welcomeCardsNavigateAndRemainReadableAtSmallWidth() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var clicks=new AtomicInteger();var news=new VBox(new Label("Tin tức"));
            var actions=java.util.stream.IntStream.range(0,6).mapToObj(i -> (Runnable)clicks::incrementAndGet).toList();
            var pane=new com.vncode.app.ui.dashboard.WelcomePane(news,actions);
            var scene=new javafx.scene.Scene(pane,1100,700);pane.applyCss();pane.layout();
            assertEquals(6,pane.lookupAll(".feature-card").size());
            for(var node:pane.lookupAll(".feature-card"))((Button)node).fire();assertEquals(6,clicks.get());
            pane.resize(760,700);pane.layout();assertTrue(pane.isCompact());
            for(var language:com.vncode.app.shared.AppLanguage.values()) {I18nService.getInstance().setLanguage(language);pane.applyTranslations();assertFalse(pane.welcomeTitle().contains("welcome.title"));}
            return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void supplyShowsSeparateBarcodesAndCanOpenInventoryWithoutLosingOrders() throws Exception {
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            var loader=FxmlViewLoader.loader(SupplyDetailController.class,"supply-detail-view.fxml");FxmlViewLoader.load(loader);
            var table=(TableView<?>)loader.getNamespace().get("orderTable");assertEquals(6,table.getColumns().size());
            assertNotNull(loader.getNamespace().get("barcodeTC"));
            var inventory=(VBox)loader.getNamespace().get("gtinInventoryPane");assertFalse(inventory.isManaged());
            ((Button)loader.getNamespace().get("inventoryToggleButton")).fire();assertTrue(inventory.isManaged());
            ((Button)loader.getNamespace().get("inventoryToggleButton")).fire();assertFalse(inventory.isManaged());
            assertTrue(((Button)loader.getNamespace().get("deliverButton")).isDisabled());return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
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
        CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),initialRefresh=new CountDownLatch(1);
        // initialize() synchronizes on the catalog, but searches do not. Hold the initial
        // refresh until the user has searched and paginated, reproducing a slow Windows load.
        synchronized(c) {
            var create=new java.util.concurrent.FutureTask<Void>(() -> {
                var pane=new com.vncode.app.ui.tnved.TnvedPane(c);holder.set(pane);
                var field=pane.getClass().getDeclaredField("tree");field.setAccessible(true);
                ((javafx.scene.control.TreeView<?>)field.get(pane)).rootProperty().addListener((o,a,b)->initialRefresh.countDown());return null;
            });Platform.runLater(create);create.get(10,TimeUnit.SECONDS);
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
            });Platform.runLater(more);more.get(10,TimeUnit.SECONDS);assertTrue(second.await(10,TimeUnit.SECONDS),"More must append the remaining ten Alpha rows, preserving the submitted query");
        }
        assertTrue(initialRefresh.await(10,TimeUnit.SECONDS),"The delayed initial catalog refresh must finish");
        var verify=new java.util.concurrent.FutureTask<Void>(() -> {
            var pane=holder.get();var tf=pane.getClass().getDeclaredField("results");tf.setAccessible(true);
            assertEquals(110,((TableView<?>)tf.get(pane)).getItems().size(),"Initial catalog refresh must preserve the user's submitted search and pages");
            assertEquals(21,pane.rootCount());return null;
        });Platform.runLater(verify);verify.get(10,TimeUnit.SECONDS);
    }
    @Test void tnvedCatalogRemainsAvailableWithoutStandaloneNavigation() throws Exception {
        var catalog=new com.vncode.app.features.tnved.TnvedCatalog(appDataDir.resolve("tnved-ui-fixture.sqlite"));
        catalog.initialize();var roots=catalog.children(null);
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            FXMLLoader loader=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");
            FxmlViewLoader.load(loader);var controller=(ShopSidebarController)loader.getController();
            assertFalse(loader.getNamespace().containsKey("tnvedButton"));
            assertNotNull(loader.getNamespace().get("gs1Button"));
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
            Platform.startup(() -> {Platform.setImplicitExit(false);started.set(true);});
        } catch (IllegalStateException alreadyStarted) {
            started.set(true);
        }
        Platform.setImplicitExit(false);
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

    @org.junit.jupiter.api.AfterEach
    void awaitBackgroundTasksBeforeFixtureCleanup() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        do {
            var barrier=new java.util.concurrent.FutureTask<Void>(() -> null);
            Platform.runLater(barrier);barrier.get(5,TimeUnit.SECONDS);
            if(!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks()) {
                var second=new java.util.concurrent.FutureTask<Void>(() -> null);
                Platform.runLater(second);second.get(5,TimeUnit.SECONDS);
                if(!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks())return;
            }
            Thread.sleep(20);
        } while(System.nanoTime()<deadline);
        org.junit.jupiter.api.Assertions.fail("Background tasks still use the fixture; cannot clean up its SQLite files");
    }

    @AfterAll
    static void clearAppDataOverride() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void homeSmokeLoaderReleasesLanguageSubscriptions() throws Exception {
        var listeners=I18nService.class.getDeclaredField("listeners");listeners.setAccessible(true);
        int before=((java.util.List<?>)listeners.get(I18nService.getInstance())).size();
        assertLoads(HomeController.class,"home-view.fxml");
        assertEquals(before,((java.util.List<?>)listeners.get(I18nService.getInstance())).size(),
                "A smoke-loaded Home must not receive later fixture language changes or launch database tasks after cleanup");
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
                new javafx.scene.Scene(root,260,850);root.applyCss();root.layout();
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
                if (loader.getController() instanceof HomeController home) home.dispose();
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
