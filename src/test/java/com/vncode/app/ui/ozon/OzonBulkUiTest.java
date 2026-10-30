package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class OzonBulkUiTest {
    @TempDir Path root;
    private FXMLLoader loader;
    private OzonDashboardController controller;
    private Stage window;
    private final AtomicInteger remoteCalls = new AtomicInteger();
    private final Shop shop = new Shop(1, "Bulk fixture", Marketplace.OZON, "11", "fixture");

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) {}
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString()); Database.initDatabase();
        try (var c = Database.getConnection(); var statement = c.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Bulk fixture','OZON','11','fixture'),(2,'Other fixture','OZON','22','other')");
        }
        fx(() -> {
            loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
            loader.setControllerFactory(type -> new OzonDashboardController(new OzonSyncWorkflow(owner -> {
                remoteCalls.incrementAndGet(); throw new AssertionError("This UI test must not call Ozon");
            })));
            window = new Stage(); window.setScene(new Scene(loader.load(), 1024, 650)); window.show();
            controller = loader.getController(); return null;
        });
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> { controller.dispose(); window.close(); return null; });
        awaitWorkers();
        assertEquals(0, remoteCalls.get());
        assertEquals(0, ShopBatchRegistry.activeCount());
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @Test void fullFilteredSelectionIncludes1000OrdersAndSurvivesPagingWithoutRenderingThemAll() throws Exception {
        seed1000("awaiting_packaging", "order");
        new OzonPostingRepository().upsertDetail(2, posting("other-order", "awaiting_packaging"));
        fx(() -> { controller.setShop(shop, false); return null; });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 100);
        fx(() -> {
            assertTrue(node("newOrdersTab", Tab.class).getText().contains("1000"));
            assertEquals(100, controller.currentPageLimit());
            ((CheckBox) node("newOrderSelectTC", TableColumn.class).getGraphic()).fire(); return null;
        });
        awaitFx(() -> selected().size() == 1000 && !node("selectFilteredButton", Button.class).isDisabled());
        fx(() -> {
            assertFalse(selected().contains("other-order"));
            node("nextPageButton", Button.class).fire(); return null;
        });
        awaitFx(() -> node("pageStatusLabel", Label.class).getText().startsWith("101"));
        fx(() -> {
            assertEquals(100, table("newOrdersTable").getItems().size()); assertEquals(1000, selected().size());
            var checkbox = (CheckBox) node("newOrderSelectTC", TableColumn.class).getGraphic();
            assertTrue(checkbox.isSelected()); checkbox.fire();
            assertEquals(0, selected().size(), "The header deselects the full filtered batch across every page");
            node("clearBatchSelectionButton", Button.class).fire(); assertTrue(selected().isEmpty());
            node("postingSearch", TextField.class).setText("order-10"); return null;
        });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 12 && node("newOrdersTab", Tab.class).getText().contains("12"));
        fx(() -> { node("selectFilteredButton", Button.class).fire(); return null; });
        awaitFx(() -> selected().size() == 12);
        fx(() -> {
            assertTrue(selected().stream().allMatch(number -> number.contains("order-10")));
            assertEquals(12, table("newOrdersTable").getItems().size()); return null;
        });
    }

    @Test void cancellingFullShipmentConfirmationStartsNoPackingOrPrintJob() throws Exception {
        seed1000("awaiting_packaging", "order"); seed1000("awaiting_deliver", "ready");
        fx(() -> { controller.setShop(shop, false); return null; });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 100 && table("packingOrdersTable").getItems().size() == 100);
        fx(() -> { node("selectFilteredButton", Button.class).fire(); return null; });
        awaitFx(() -> selected().size() == 1000 && stockSourceReady() && !node("moveToPackingButton", Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
            dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,dialog->{
                assertTrue(dialog.getHeaderText().contains("1000"));
                return dialog.getButtonTypes().stream().filter(type -> type.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
            });
        fx(() -> {
            assertEquals(1000, selected().size());
            assertFalse(node("loadingIndicator", ProgressIndicator.class).isVisible());
            node("orderStatusTabs", TabPane.class).getSelectionModel().select(node("packingOrdersTab", Tab.class));
            return null;
        });
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("printAllButton",Button.class).fire(),
            dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,dialog->{
                assertEquals(MessageFormat.format(I18nService.getInstance().tr("ozon.batch.confirm_print"),1000),dialog.getContentText());
                return ButtonType.NO;
            });
        awaitWorkers();
        fx(() -> { assertFalse(node("loadingIndicator", ProgressIndicator.class).isVisible()); return null; });
        try (var c = Database.getConnection(); var s = c.createStatement(); var row = s.executeQuery("SELECT COUNT(*) FROM ozon_exemplar_jobs")) {
            assertEquals(0, row.getInt(1));
        }
        try (var files = Files.walk(root)) { assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".pdf"))); }
        assertEquals(0, ShopBatchRegistry.activeCount());
    }

    @Test void longAcceptanceKeepsTablesBrowsableButPreventsChangingTheBatch() throws Exception {
        var repository=new OzonPostingRepository();
        repository.upsertPage(1,java.util.stream.IntStream.range(0,101)
                .mapToObj(i->posting("browse-"+i,"awaiting_packaging")).toList());
        repository.upsertDetail(1,posting("browse-ready","awaiting_deliver"));
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var called=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,sources)->{
                called.incrementAndGet();started.countDown();
                if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");
                return new OzonShipResult(number,"awaiting_deliver",false);
            });
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==100);
        fx(()->{node("selectFilteredButton",Button.class).fire();return null;});
        awaitFx(()->selected().size()==101&&stockSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        try {
            com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                    dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,
                    dialog->dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
            assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(()->{
                assertFalse(table("newOrdersTable").isDisabled(),"Users can browse rows during a long acceptance");
                assertTrue(node("moveToPackingButton",Button.class).isDisabled(),"A second acceptance must stay disabled");
                var header=(CheckBox)node("newOrderSelectTC",TableColumn.class).getGraphic();
                assertTrue(header.isDisabled(),"Batch selection is frozen until the operation completes");
                header.fire();assertEquals(101,selected().size());
                node("nextPageButton",Button.class).fire();return null;
            });
            awaitFx(()->node("pageStatusLabel",Label.class).getText().startsWith("101"));
            fx(()->{
                assertEquals(1,table("newOrdersTable").getItems().size());
                assertEquals(101,selected().size(),"Browsing a later page must not change the frozen batch");
                node("orderStatusTabs",TabPane.class).getSelectionModel().select(node("packingOrdersTab",Tab.class));
                assertFalse(table("packingOrdersTable").isDisabled(),"Users can read another tab");
                assertTrue(node("printAllButton",Button.class).isDisabled(),"A conflicting print cannot start");
                return null;
            });
        } finally { release.countDown(); }
        awaitFx(30,()->called.get()==101&&ShopBatchRegistry.activeCount()==0&&!node("loadingIndicator",ProgressIndicator.class).isVisible(),
                ()->"Acceptance did not complete after browsing: "+called.get());
    }

    @Test void acceptAllProcessesBeyondTheVisiblePageAndReportsChildrenPendingAndProtectedKizSeparately() throws Exception {
        var repository=new OzonPostingRepository();var sources=new ArrayList<OzonPostingDto>();
        for(int i=1;i<=101;i++){
            var p=posting("split-"+i,"awaiting_packaging");
            sources.add(new OzonPostingDto(p.postingNumber(),p.orderId(),p.orderNumber(),p.status(),p.substatus(),p.warehouseId(),p.shipmentAt(),p.inProcessAt(),p.lowerBarcode(),p.upperBarcode(),p.requirements(),List.of("ship"),true,
                    List.of(new OzonPostingItemDto(0,"1","11","fixture","Fixture item",2,"RUB","100"))));
        }
        repository.upsertPage(1,sources);var accepted=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,selection)->{
                assertFalse(Platform.isFxApplicationThread());assertEquals(shop.getId(),owner.getId());accepted.incrementAndGet();
                if(number.equals("split-100"))throw new OzonApiException("reconcile_required",0,false,true,null);
                if(number.equals("split-101"))throw new IllegalStateException("OZON_SPLIT_HAS_BOUND_KIZ");
                var children=List.of(number+"-A",number+"-B");
                repository.upsertDetail(1,posting(number,"cancelled"));
                repository.upsertPage(1,children.stream().map(child->posting(child,"awaiting_deliver")).toList());
                return new OzonShipResult(number,"awaiting_deliver",false,children);
            });
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==100);
        fx(()->{((CheckBox)node("newOrderSelectTC",TableColumn.class).getGraphic()).fire();return null;});
        awaitFx(()->selected().size()==101&&stockSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,dialog->{
                    assertTrue(dialog.getHeaderText().contains("101"));
                    assertTrue(dialog.getContentText().contains(I18nService.getInstance().tr("ozon.fbs.auto_split_notice")));
                    return dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
                });
        // Successful acceptance selects the packing tab. The Receive button lives
        // in the now-inactive tab and can legitimately be effectively disabled.
        // Await the committed batch/report instead of that transient control state.
        // This fixture writes 198 children individually on the Windows runner.
        awaitFx(30,()->accepted.get()==101&&selected().size()==2
                        &&ShopBatchRegistry.activeCount()==0
                        &&!node("loadingIndicator",ProgressIndicator.class).isVisible()
                        &&node("batchResultsPanel",TitledPane.class).isVisible()
                        &&node("batchResultsArea",TextArea.class).getText().lines().count()==101,
                ()->"Bulk acceptance did not finish: accepted="+accepted.get()+", selected="+selected()
                        +", activeLeases="+ShopBatchRegistry.activeCount()
                        +", loading="+node("loadingIndicator",ProgressIndicator.class).isVisible()
                        +", reportLines="+node("batchResultsArea",TextArea.class).getText().lines().count()
                        +", tab="+node("orderStatusTabs",TabPane.class).getSelectionModel().getSelectedItem().getId()
                        +", receiveOwnDisable="+node("moveToPackingButton",Button.class).isDisable()
                        +", receiveEffectiveDisabled="+node("moveToPackingButton",Button.class).isDisabled()
                        +", status="+node("statusLabel",Label.class).getText());
        awaitFx(()->node("packingOrdersTab",Tab.class).getText().contains("198"));
        fx(()->{assertSame(node("packingOrdersTab",Tab.class),node("orderStatusTabs",TabPane.class).getSelectionModel().getSelectedItem(),"Accepted orders open the new unprinted queue");return null;});
        fx(()->{
            assertEquals(Set.of("split-100","split-101"),selected());
            String report=node("batchResultsArea",TextArea.class).getText();
            assertEquals(101,report.lines().count());
            assertTrue(report.contains("split-1-A, split-1-B"));
            assertTrue(report.contains("split-100 · "+I18nService.getInstance().tr("ozon.fbs.pending")));
            assertTrue(report.contains(I18nService.getInstance().tr("ozon.fbs.reason.OZON_SPLIT_HAS_BOUND_KIZ")));
            assertEquals(99,report.lines().filter(line->line.contains(" · "+I18nService.getInstance().tr("ozon.fbs.packaged")+" → ")).count());
            assertEquals(1,report.lines().filter(line->line.startsWith("split-100 · "+I18nService.getInstance().tr("ozon.fbs.pending"))).count());
            assertEquals(1,report.lines().filter(line->line.startsWith("split-101 · "+I18nService.getInstance().tr("ozon.fbs.failed"))).count());
            assertTrue(node("batchResultsPanel",TitledPane.class).isExpanded());
            assertEquals(0,ShopBatchRegistry.activeCount());return null;
        });
        // Simulate the ordinary FBS read-only turn committing the native children.
        // The open report must advance without a second acceptance click or remote write.
        new OzonShipService().knownUnitPostings(shop,"split-100");
        repository.upsertPage(1,List.of(posting("split-100-A","awaiting_deliver"),posting("split-100-B","awaiting_deliver")));
        try(var c=Database.getConnection();var statement=c.prepareStatement("INSERT INTO ozon_unit_ship_receipts(shop_id,posting_number,owner,source_json,request_json,request_fingerprint,children_json,state,updated_at,parent_in_children) VALUES(1,'split-100',?,?,? ,?,'[\"split-100-A\",\"split-100-B\"]','COMPLETE','2026-10-20T01:00:00Z',0)")){
            statement.setString(1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));
            statement.setString(2,new com.google.gson.Gson().toJson(sources.stream().filter(p->p.postingNumber().equals("split-100")).findFirst().orElseThrow()));
            statement.setString(3,"{}");statement.setString(4,"a".repeat(64));statement.executeUpdate();
        }
        fx(()->{controller.refreshLocalData();return null;});
        awaitFx(()->selected().equals(Set.of("split-101"))&&node("packingOrdersTab",Tab.class).getText().contains("200"));
        fx(()->{
            String report=node("batchResultsArea",TextArea.class).getText();
            assertEquals(101,report.lines().count());
            assertTrue(report.contains("split-100 · "+I18nService.getInstance().tr("ozon.fbs.packaged")+" → split-100-A, split-100-B"));
            assertFalse(report.contains("split-100 · "+I18nService.getInstance().tr("ozon.fbs.pending")));
            assertEquals(101,accepted.get(),"Ordinary readback must never repeat acceptance");return null;
        });
    }

    @Test void missingKizWithPurchasedSourceRunsApprovedReplenishmentThenRetriesOnlyThatOrder() throws Exception {
        var repository=new OzonPostingRepository();repository.upsertPage(1,List.of(posting("shortage-1","awaiting_packaging"),posting("shortage-2","awaiting_packaging"),posting("shortage-3","awaiting_packaging")));
        new com.vncode.app.features.kiz.ShipmentKizRepository().saveSelection(shop,"OZON:FBS",new com.vncode.app.features.kiz.KizSourceSelection(false,true));
        var attempted=new AtomicInteger();var purchases=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,selection)->{
                if(attempted.getAndIncrement()==0)throw new java.io.IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");
                repository.upsertDetail(1,posting(number,"awaiting_deliver"));return new OzonShipResult(number,"awaiting_deliver",false);
            });
            controller.setAcceptanceStockPurchase((owner,numbers,sources,progress,cancelled)->{
                assertFalse(Platform.isFxApplicationThread());assertEquals(Set.of("shortage-1","shortage-2","shortage-3"),Set.copyOf(numbers));assertTrue(sources.purchased());purchases.incrementAndGet();
            });
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==3&&window.getScene().lookup("#shipment-kiz-purchased") instanceof CheckBox box&&box.isSelected()&&!box.isDisable());
        fx(()->{node("selectFilteredButton",Button.class).fire();return null;});
        awaitFx(()->selected().size()==3&&stockSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,
                dialog->dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
        awaitFx(()->attempted.get()==4&&purchases.get()==1&&ShopBatchRegistry.activeCount()==0&&node("batchResultsPanel",TitledPane.class).isVisible());
        fx(()->{assertTrue(selected().isEmpty());assertEquals(3,node("batchResultsArea",TextArea.class).getText().lines().filter(line->line.contains(I18nService.getInstance().tr("ozon.fbs.packaged"))).count());return null;});
    }

    @Test void failedBatchPurchaseIsReportedForEveryAffectedOrderWithoutRepeatingPurchase() throws Exception {
        var repository=new OzonPostingRepository();repository.upsertPage(1,List.of(posting("preflight-1","awaiting_packaging"),posting("preflight-2","awaiting_packaging"),posting("preflight-3","awaiting_packaging")));
        new com.vncode.app.features.kiz.ShipmentKizRepository().saveSelection(shop,"OZON:FBS",new com.vncode.app.features.kiz.KizSourceSelection(false,true));
        var attempted=new AtomicInteger();var purchases=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,selection)->{attempted.incrementAndGet();throw new java.io.IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");});
            controller.setAcceptanceStockPurchase((owner,numbers,selection,progress,cancelled)->{purchases.incrementAndGet();throw new java.io.IOException("SYNTHETIC_PREFLIGHT_FAILURE");});
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==3&&purchasedSourceReady());
        fx(()->{node("selectFilteredButton",Button.class).fire();return null;});
        awaitFx(()->selected().size()==3&&purchasedSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,
                dialog->dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
        awaitFx(()->attempted.get()==3&&ShopBatchRegistry.activeCount()==0&&node("batchResultsPanel",TitledPane.class).isVisible());
        fx(()->{
            var report=node("batchResultsArea",TextArea.class).getText();assertEquals(1,purchases.get());assertEquals(3,selected().size());
            assertEquals(3,report.lines().filter(line->line.contains("SYNTHETIC_PREFLIGHT_FAILURE")).count());
            assertEquals(2,report.lines().filter(line->line.contains(I18nService.getInstance().tr("ozon.fbs.purchase_batch_failed"))).count());return null;
        });
    }

    @Test void cancellingPurchaseStopsBatchWithoutRepeatingDialogOrAcceptingOtherOrders() throws Exception {
        var repository=new OzonPostingRepository();repository.upsertPage(1,List.of(posting("cancel-1","awaiting_packaging"),posting("cancel-2","awaiting_packaging"),posting("cancel-3","awaiting_packaging")));
        new com.vncode.app.features.kiz.ShipmentKizRepository().saveSelection(shop,"OZON:FBS",new com.vncode.app.features.kiz.KizSourceSelection(false,true));
        var attempted=new AtomicInteger();var purchaseDialogs=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,selection)->{attempted.incrementAndGet();throw new java.io.IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");});
            controller.setAcceptanceStockPurchase((owner,numbers,selection,progress,cancelled)->{purchaseDialogs.incrementAndGet();throw new java.util.concurrent.CancellationException();});
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==3&&purchasedSourceReady());
        fx(()->{node("selectFilteredButton",Button.class).fire();return null;});
        awaitFx(()->selected().size()==3&&purchasedSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,
                dialog->dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
        awaitFx(()->ShopBatchRegistry.activeCount()==0&&node("batchResultsPanel",TitledPane.class).isVisible());
        fx(()->{
            var report=node("batchResultsArea",TextArea.class).getText();
            assertEquals(1,attempted.get());assertEquals(1,purchaseDialogs.get());assertEquals(3,selected().size());
            assertEquals(2,report.lines().filter(line->line.contains(I18nService.getInstance().tr("ozon.fbs.not_processed"))).count());return null;
        });
    }

    @Test void shopConnectionChangeStopsBeforeNextOrderAndReportsEveryUnprocessedOrder() throws Exception {
        var repository=new OzonPostingRepository();
        repository.upsertPage(1,List.of(posting("stop-1","awaiting_packaging"),posting("stop-2","awaiting_packaging"),posting("stop-3","awaiting_packaging")));
        var attempted=new AtomicInteger();
        fx(()->{
            controller.setPostingAcceptor((owner,number,selection)->{
                attempted.incrementAndGet();
                repository.upsertDetail(1,posting(number,"awaiting_deliver"));
                try(var c=Database.getConnection();var s=c.createStatement()) {s.executeUpdate("UPDATE shops SET client_id='changed' WHERE id=1");}
                return new OzonShipResult(number,"awaiting_deliver",false);
            });
            controller.setShop(shop,false);return null;
        });
        awaitFx(()->table("newOrdersTable").getItems().size()==3);
        fx(()->{node("selectFilteredButton",Button.class).fire();return null;});
        awaitFx(()->selected().size()==3&&stockSourceReady()&&!node("moveToPackingButton",Button.class).isDisabled());
        com.vncode.app.testsupport.FxDialogDriver.choose(()->node("moveToPackingButton",Button.class).fire(),
                dialog->dialog.getScene().getWindow() instanceof Stage stage&&stage.getOwner()==window,
                dialog->dialog.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
        awaitFx(()->attempted.get()==1&&ShopBatchRegistry.activeCount()==0&&node("batchResultsPanel",TitledPane.class).isVisible());
        fx(()->{
            String report=node("batchResultsArea",TextArea.class).getText();
            assertEquals(3,report.lines().count());
            assertEquals(2,report.lines().filter(line->line.contains(I18nService.getInstance().tr("ozon.fbs.not_processed"))).count());
            assertEquals(2,selected().size(),"Orders not attempted remain selected");
            return null;
        });
    }

    @Test void slowBackgroundReadKeepsFxPulsingAndOnlyTheNewestSearchReachesTheTable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), pulses = new CountDownLatch(4);
        AtomicBoolean first = new AtomicBoolean(true);
        var timer = new AnimationTimer() { @Override public void handle(long now) { pulses.countDown(); } };
        try {
            fx(() -> {
                controller.setFbsReader(query -> {
                    assertFalse(Platform.isFxApplicationThread());
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    }
                    String marker = query.text().isEmpty() ? "old" : "filtered";
                    return new OzonFbsPage(List.of(posting(marker, query.status())), 1000, Map.of());
                });
                timer.start(); controller.setShop(shop, false); return null;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(pulses.await(3, TimeUnit.SECONDS), "A blocked read worker must not block JavaFX frames");
            fx(() -> { node("postingSearch", TextField.class).setText("filtered"); return null; });
            awaitFx(() -> ((javafx.animation.PauseTransition) field("postingDebounce")).getStatus() == javafx.animation.Animation.Status.STOPPED);
            release.countDown();
            awaitFx(() -> !table("newOrdersTable").getItems().isEmpty()
                    && table("newOrdersTable").getItems().getFirst().postingNumber().equals("filtered"));
            fx(() -> { assertEquals(1, table("newOrdersTable").getItems().size()); return null; });
        } finally { release.countDown(); fx(() -> { timer.stop(); return null; }); }
    }

    private void seed1000(String status, String prefix) {
        var values = new ArrayList<OzonPostingDto>();
        for (int i = 1; i <= 1000; i++) values.add(posting(prefix + "-" + i, status));
        new OzonPostingRepository().upsertPage(1, values);
    }
    private OzonPostingDto posting(String number, String status) {
        return new OzonPostingDto(number, number, number, status, "", "fixture", "2026-10-13T12:00:00Z", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "1", "11", "fixture", "Fixture item", 1, "RUB", "100")));
    }
    private <T> T node(String key, Class<T> type) { return type.cast(loader.getNamespace().get(key)); }
    @SuppressWarnings("unchecked") private TableView<OzonPostingDto> table(String key) { return (TableView<OzonPostingDto>) loader.getNamespace().get(key); }
    @SuppressWarnings("unchecked") private Set<String> selected() { return (Set<String>) field("selectedPostingNumbers"); }
    private boolean stockSourceReady() {
        try { return ((com.vncode.app.ui.kiz.ShipmentKizPane)field("shipmentKizPane")).snapshot().anySelected(); }
        catch (IllegalStateException loading) { return false; }
    }
    private boolean purchasedSourceReady() {
        try { return ((com.vncode.app.ui.kiz.ShipmentKizPane)field("shipmentKizPane")).snapshot().purchased(); }
        catch (IllegalStateException loading) { return false; }
    }
    private Object field(String name) {
        try { var field = OzonDashboardController.class.getDeclaredField(name); field.setAccessible(true); return field.get(controller); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void awaitFx(BooleanSupplier condition) throws Exception {
        awaitFx(5,condition,()->"Ozon workspace did not reach the expected state");
    }
    private void awaitFx(int timeoutSeconds,BooleanSupplier condition,Supplier<String> diagnostic) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) { if (fx(condition::getAsBoolean)) return; Thread.sleep(10); }
        boolean reached=fx(condition::getAsBoolean);
        assertTrue(reached,reached?"":fx(diagnostic::get));
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        var task = new FutureTask<>(operation); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    private static void awaitWorkers() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && (AppTaskExecutor.hasRunningTasks()
                || ShopReadCoordinator.shared().activeCount() > 0 || ShopReadCoordinator.shared().pendingCount() > 0)) Thread.sleep(10);
        assertFalse(AppTaskExecutor.hasRunningTasks()); assertEquals(0, ShopReadCoordinator.shared().activeCount());
        assertEquals(0, ShopReadCoordinator.shared().pendingCount()); fx(() -> null);
    }
}
