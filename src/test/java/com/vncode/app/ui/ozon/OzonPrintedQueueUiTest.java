package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonPrintedQueueUiTest {
    @TempDir java.nio.file.Path directory;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    static <T>T fx(Callable<T> action)throws Exception{var result=new FutureTask<T>(action);Platform.runLater(result);return result.get(10,TimeUnit.SECONDS);}
    @Test void newTabExcludesPrintedOrdersAndPrintAllConfirmsOnlyNewOnes()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture')");}
        var owner=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");var postings=new OzonPostingRepository();
        postings.upsertPage(1,List.of(posting("RECEIVE","awaiting_packaging"),posting("NEW","awaiting_deliver"),posting("OLD","awaiting_deliver"),posting("CARRIER","delivering")));
        new OzonPrintReceiptRepository().record(owner,List.of("OLD"));
        var previous=I18nService.getInstance().getCurrentLanguage();var stage=new javafx.stage.Stage[1];
        var loader=fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);var view=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");var scene=new Scene(view.load(),1100,700);stage[0]=new javafx.stage.Stage();stage[0].setScene(scene);stage[0].show();return view;});
        OzonDashboardController controller=loader.getController();
        TableView<OzonPostingDto> fresh=(TableView<OzonPostingDto>)loader.getNamespace().get("packingOrdersTable");
        TableView<OzonPostingDto> printed=(TableView<OzonPostingDto>)loader.getNamespace().get("printedOrdersTable");
        try{
            fx(()->{controller.setShop(owner,false);return null;});await(()->fx(()->fresh.getItems().size()==1&&printed.getItems().size()==1));
            fx(()->{assertEquals("NEW",fresh.getItems().getFirst().postingNumber());assertEquals("OLD",printed.getItems().getFirst().postingNumber());
                assertTrue(((Tab)loader.getNamespace().get("newOrdersTab")).getText().startsWith("Chờ nhận"));
                assertTrue(((Tab)loader.getNamespace().get("packingOrdersTab")).getText().startsWith("Đơn mới"));
                assertTrue(((Tab)loader.getNamespace().get("printedOrdersTab")).getText().startsWith("Đang giao hàng"));
                assertTrue(((Tab)loader.getNamespace().get("deliveringOrdersTab")).getText().startsWith("Đã bàn giao"));
                ((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get("packingOrdersTab"));return null;});
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            var confirmed=new CompletableFuture<Void>();
            fx(()->{var timer=new javafx.animation.Timeline();timer.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(40),e->{
                for(var window:javafx.stage.Window.getWindows()){
                    var pane=window.getScene()==null?null:window.getScene().lookup(".dialog-pane");
                    if(pane instanceof DialogPane dialog&&dialog.getButtonTypes().contains(ButtonType.NO)){
                        timer.stop();try{assertEquals(java.text.MessageFormat.format(I18nService.getInstance().tr("ozon.batch.confirm_print"),1),dialog.getContentText());confirmed.complete(null);}catch(Throwable error){confirmed.completeExceptionally(error);}((Button)dialog.lookupButton(ButtonType.NO)).fire();break;
                    }
                }
            }));timer.setCycleCount(200);timer.play();((Button)loader.getNamespace().get("printAllButton")).fire();return null;});
            confirmed.get(10,TimeUnit.SECONDS);
            var opened=new java.util.concurrent.CopyOnWriteArrayList<java.io.File>();
            var labels=directory.resolve("new-labels.pdf").toFile();var picking=directory.resolve("new-picking.pdf").toFile();
            var exporting=new CountDownLatch(1);var continueExport=new CountDownLatch(1);
            fx(()->{assertSame(loader.getNamespace().get("packingOrdersTab"),((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem(),"Cancelling the print confirmation keeps the new orders tab");
                controller.setExportedFileOpener(opened::addAll);
                controller.runPrintTask(owner,labels,picking,List.of("NEW"),new com.vncode.app.features.kiz.KizSourceSelection(true,false),progress->{
                    exporting.countDown();if(!continueExport.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");
                    for(var file:List.of(labels,picking))try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(file))){pdf.addNewPage();}
                    new OzonPrintReceiptRepository().record(owner,List.of("NEW"));
                    return new OzonDashboardController.PrintOutput("Synthetic export completed",List.of(labels,picking),List.of("NEW"));
                });return null;});
            try {
                assertTrue(exporting.await(5,TimeUnit.SECONDS));
                fx(()->{
                    assertFalse(fresh.isDisabled(),"Rows remain browsable during export");
                    assertTrue(((Button)loader.getNamespace().get("printAllButton")).isDisabled(),"Do not start a second export");
                    var tabs=(TabPane)loader.getNamespace().get("orderStatusTabs");
                    tabs.getSelectionModel().select((Tab)loader.getNamespace().get("newOrdersTab"));
                    assertFalse(((TableView<?>)loader.getNamespace().get("newOrdersTable")).isDisabled(),"Other order tabs remain readable");
                    return null;
                });
            } finally { continueExport.countDown(); }
            await(()->fx(()->fresh.getItems().isEmpty()&&printed.getItems().size()==2&&((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem()==loader.getNamespace().get("printedOrdersTab")));
            await(()->opened.size()==2);for(var file:opened)try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file))){assertEquals(1,pdf.getNumberOfPages());}
            fx(()->{assertTrue(((TextArea)loader.getNamespace().get("batchResultsArea")).getText().contains("Thời gian sau khi chọn file:"));assertTrue(((TitledPane)loader.getNamespace().get("batchResultsPanel")).isExpanded());return null;});
            assertFalse(fx(()->((Button)loader.getNamespace().get("printAllButton")).isVisible()));
            postings.upsertDetail(1,posting("ARRIVED","awaiting_deliver"));fx(()->{controller.refreshLocalData();return null;});await(()->fx(()->fresh.getItems().size()==1));
            assertEquals("ARRIVED",fx(()->fresh.getItems().getFirst().postingNumber()));assertEquals(2,fx(()->printed.getItems().size()));assertEquals("awaiting_deliver",postings.find(1,"NEW").status());
            fx(()->{assertSame(loader.getNamespace().get("printedOrdersTab"),((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem());((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get("packingOrdersTab"));return null;});
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            com.vncode.app.testsupport.FxDialogDriver.choose(()->((Button)loader.getNamespace().get("printAllButton")).fire(),dialog->dialog.getButtonTypes().contains(ButtonType.NO),dialog->{assertEquals(java.text.MessageFormat.format(I18nService.getInstance().tr("ozon.batch.confirm_print"),1),dialog.getContentText());return ButtonType.NO;});
            assertEquals(List.of("ARRIVED"),postings.freezeFilteredNumbers(new com.vncode.app.features.ozon.OzonFbsQuery(1,"awaiting_deliver","","",0,100,com.vncode.app.features.ozon.OzonFbsQuery.PrintState.UNPRINTED),10000));
        }finally{fx(()->{controller.dispose();stage[0].close();I18nService.getInstance().setLanguage(previous);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void failedPdfExportKeepsTheOrderInNewOrdersAndDoesNotSwitchToShipping()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture')");}
        var owner=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");new OzonPostingRepository().upsertDetail(1,posting("NEW","awaiting_deliver"));
        var loader=fx(()->{var view=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");new Scene(view.load(),1100,700);return view;});OzonDashboardController controller=loader.getController();
        try{
            fx(()->{controller.setShop(owner,false);((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get("packingOrdersTab"));return null;});
            await(()->fx(()->((TableView<?>)loader.getNamespace().get("packingOrdersTable")).getItems().size()==1));
            com.vncode.app.testsupport.FxDialogDriver.choose(()->controller.runPrintTask(owner,directory.resolve("failed-labels.pdf").toFile(),directory.resolve("failed-picking.pdf").toFile(),List.of("NEW"),new com.vncode.app.features.kiz.KizSourceSelection(true,false),progress->{throw new java.io.IOException("Synthetic export failure");}),dialog->dialog.getButtonTypes().contains(ButtonType.OK),dialog->ButtonType.OK);
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            fx(()->{assertSame(loader.getNamespace().get("packingOrdersTab"),((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem());assertEquals(1,((TableView<?>)loader.getNamespace().get("packingOrdersTable")).getItems().size());assertTrue(((TableView<?>)loader.getNamespace().get("printedOrdersTable")).getItems().isEmpty());
                String prefix=I18nService.getInstance().tr("ozon.fbs.print_timing").split("\\{0\\}",2)[0];assertTrue(((TextArea)loader.getNamespace().get("batchResultsArea")).getText().contains(prefix));return null;});
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ozon_fbs_print_receipts")){assertEquals(0,r.getInt(1));}
        }finally{fx(()->{controller.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void printReadySubsetKeepsBlockedOrdersInResultAndDurableReport()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture')");}
        var owner=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");
        new OzonPostingRepository().upsertPage(1,List.of(posting("READY","awaiting_deliver"),posting("BLOCKED","awaiting_deliver")));
        var loader=fx(()->{var view=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");new Scene(view.load(),1100,700);return view;});
        OzonDashboardController controller=loader.getController();
        try{
            fx(()->{controller.setShop(owner,false);controller.setExportedFileOpener(files->{});return null;});
            await(()->fx(()->((TableView<?>)loader.getNamespace().get("packingOrdersTable")).getItems().size()==2));
            fx(()->{controller.runPrintTask(owner,directory.resolve("labels.pdf").toFile(),directory.resolve("picking.pdf").toFile(),
                    List.of("READY"),new com.vncode.app.features.kiz.KizSourceSelection(true,false),
                    List.of("READY","BLOCKED"),Map.of("BLOCKED","Thiếu thông tin"),
                    progress->new OzonDashboardController.PrintOutput("fixture",List.of(),List.of("READY")));
                return null;});
            await(()->fx(()->((TextArea)loader.getNamespace().get("batchResultsArea")).getText().contains("BLOCKED")));
            String shown=fx(()->((TextArea)loader.getNamespace().get("batchResultsArea")).getText());
            assertTrue(shown.contains("READY"));assertTrue(shown.contains("Thiếu thông tin"));
            var reports=new com.vncode.app.features.sync.SyncRunRepository();
            var run=reports.list(1,0,10).stream().filter(r->r.kind().equals("ozon_fbs_print")).findFirst().orElseThrow();
            var rows=reports.items(run.id(),0,10);assertEquals(2,rows.size());
            assertEquals("SUCCEEDED",rows.stream().filter(r->r.productId().equals("READY")).findFirst().orElseThrow().state());
            var blocked=rows.stream().filter(r->r.productId().equals("BLOCKED")).findFirst().orElseThrow();
            assertEquals("NEEDS_ACTION",blocked.state());assertTrue(blocked.evidence().contains("Thiếu thông tin"));
        }finally{fx(()->{controller.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void preflightReportsBothSharedShortageAndMissingMappingPerPosting(){
        var available=new OzonPrintReadiness.GtinAvailability("04612345678903",1,1);
        var first=new OzonPrintReadiness("P1",true,true,1,List.of(available),List.of(),List.of(),"");
        var second=new OzonPrintReadiness("P2",true,true,1,List.of(available),List.of(),List.of(),"");
        var missing=new OzonPrintReadiness("P3",false,true,0,List.of(),List.of("SKU-MISSING"),List.of(),"");
        var batch=new OzonBatchPrintReadiness(false,List.of(first,second,missing),List.of(new OzonPrintReadiness.GtinAvailability("04612345678903",2,1)),List.of("P1"));
        var failures=OzonDashboardController.preflightFailures(batch);
        assertEquals(Set.of("P2","P3"),failures.keySet());
        assertTrue(failures.get("P2").contains("04612345678903"));assertTrue(failures.get("P3").contains("SKU-MISSING"));
        var detailed=OzonDashboardController.preflightFailures(batch,List.of("P1"),Map.of("SKU-MISSING","NETWORK_TIMEOUT"));
        assertTrue(detailed.get("P3").contains("NETWORK_TIMEOUT"));
        var review=OzonDashboardController.preflightFailures(batch,List.of("P1"),Map.of("SKU-MISSING","KIZ_CROSS_PURCHASE_REVIEW_REQUIRED"));
        assertTrue(review.get("P3").contains(I18nService.getInstance().tr("kiz.error.cross_purchase_review")));
    }
    @Test void printAllKeepsPurchaseEligibleOrdersWhenAnotherOrderLacksMapping(){
        var available=new OzonPrintReadiness.GtinAvailability("04612345678903",1,1);
        var shortage=new OzonPrintReadiness.GtinAvailability("04612345678904",1,0);
        var ready=new OzonPrintReadiness("READY",true,true,1,List.of(available),List.of(),List.of(),"");
        var buy=new OzonPrintReadiness("BUY",false,true,1,List.of(shortage),List.of(),List.of(),"");
        var missing=new OzonPrintReadiness("MISSING",false,true,0,List.of(),List.of("SKU-MISSING"),List.of(),"");
        var batch=new OzonBatchPrintReadiness(false,List.of(ready,buy,missing),List.of(shortage),List.of("READY"));
        var purchased=new com.vncode.app.features.kiz.KizSourceSelection(false,true);
        var eligible=OzonDashboardController.printableForBatch(batch,purchased);
        assertEquals(List.of("READY","BUY"),eligible);
        var blocked=OzonDashboardController.preflightFailures(batch,eligible);
        assertEquals(Set.of("MISSING"),blocked.keySet());assertTrue(blocked.get("MISSING").contains("SKU-MISSING"));
        assertEquals(List.of("READY"),OzonDashboardController.printableForBatch(batch,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY));
    }
    @Test void crossMappingRefreshUsesOnlyDistinctSelectedSkusAndOnlyForPurchasedStock()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture')");}
        var owner=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");var repo=new OzonPostingRepository();
        repo.upsertPage(1,List.of(postingSku("ONE","1001"),postingSku("TWO","1001"),postingSku("THREE","1002"),postingSku("OTHER","9999")));
        var controller=new OzonDashboardController();var received=new ArrayList<List<String>>();
        controller.setCrossMappingRefresher((shop,skus,cancelled,progress)->{assertFalse(Platform.isFxApplicationThread());received.add(List.copyOf(skus));progress.accept(new com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult(2,1,Map.of("1002","NETWORK_TIMEOUT")));return Map.of("1002","NETWORK_TIMEOUT","9999","SHOULD_NOT_LEAK");});
        try{
            controller.refreshMissingCrossMappings(owner,List.of("ONE","TWO","THREE"),com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->false);
            assertTrue(received.isEmpty(),"Seller-uploaded stock does not initiate a cross-market check");
            var progress=new ArrayList<String>();
            var issues=controller.refreshMissingCrossMappings(owner,List.of("ONE","TWO","THREE"),new com.vncode.app.features.kiz.KizSourceSelection(false,true),()->false,progress::add);
            assertEquals(1,received.size());assertEquals(Set.of("1001","1002"),Set.copyOf(received.getFirst()));
            assertEquals(Map.of("1002","NETWORK_TIMEOUT"),issues,"Only issues for selected SKUs are retained");
            assertEquals(1,progress.size());assertFalse(progress.getFirst().isBlank());
            assertThrows(java.io.InterruptedIOException.class,()->controller.refreshMissingCrossMappings(owner,List.of("ONE"),new com.vncode.app.features.kiz.KizSourceSelection(false,true),()->true));
            assertEquals(1,received.size());
        }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void crossMappingForMoreThanTenSkusReportsEachBackgroundRound()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture')");}
        var owner=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");var postings=new ArrayList<OzonPostingDto>();var ids=new ArrayList<String>();
        for(int i=0;i<25;i++){String id="P"+i;ids.add(id);postings.add(postingSku(id,Integer.toString(1000+i)));}
        new OzonPostingRepository().upsertPage(1,postings);var controller=new OzonDashboardController();var progress=new ArrayList<String>();
        controller.setCrossMappingRefresher((shop,skus,cancelled,notify)->{
            assertFalse(Platform.isFxApplicationThread());assertEquals(25,skus.size());
            for(int count:new int[]{10,20,25}){assertFalse(cancelled.getAsBoolean());notify.accept(new com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult(count,count,Map.of()));}
            return Map.of();
        });
        try{
            assertTrue(controller.refreshMissingCrossMappings(owner,ids,new com.vncode.app.features.kiz.KizSourceSelection(false,true),()->false,progress::add).isEmpty());
            assertEquals(3,progress.size());assertTrue(progress.getLast().contains("25"));
        }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    static void await(Callable<Boolean> ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){if(ready.call())return;Thread.sleep(20);}fail("Ozon queue did not refresh");}
    static OzonPostingDto posting(String number,String status){return new OzonPostingDto(number,number,number,status,"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","SKU","article","Fixture",1,"RUB","1")));}
    static OzonPostingDto postingSku(String number,String sku){return new OzonPostingDto(number,number,number,"awaiting_deliver","","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1",sku,"article","Fixture",1,"RUB","1")));}
}
