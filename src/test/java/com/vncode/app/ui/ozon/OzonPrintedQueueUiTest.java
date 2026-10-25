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
            fx(()->{assertSame(loader.getNamespace().get("packingOrdersTab"),((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem(),"Cancelling the print confirmation keeps the new orders tab");
                controller.setExportedFileOpener(opened::addAll);
                controller.runPrintTask(owner,labels,picking,List.of("NEW"),new com.vncode.app.features.kiz.KizSourceSelection(true,false),progress->{
                    for(var file:List.of(labels,picking))try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(file))){pdf.addNewPage();}
                    new OzonPrintReceiptRepository().record(owner,List.of("NEW"));
                    return new OzonDashboardController.PrintOutput("Synthetic export completed",List.of(labels,picking),List.of("NEW"));
                });return null;});
            await(()->fx(()->fresh.getItems().isEmpty()&&printed.getItems().size()==2&&((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem()==loader.getNamespace().get("printedOrdersTab")));
            await(()->opened.size()==2);for(var file:opened)try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file))){assertEquals(1,pdf.getNumberOfPages());}
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
            fx(()->{assertSame(loader.getNamespace().get("packingOrdersTab"),((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().getSelectedItem());assertEquals(1,((TableView<?>)loader.getNamespace().get("packingOrdersTable")).getItems().size());assertTrue(((TableView<?>)loader.getNamespace().get("printedOrdersTable")).getItems().isEmpty());return null;});
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ozon_fbs_print_receipts")){assertEquals(0,r.getInt(1));}
        }finally{fx(()->{controller.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    static void await(Callable<Boolean> ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){if(ready.call())return;Thread.sleep(20);}fail("Ozon queue did not refresh");}
    static OzonPostingDto posting(String number,String status){return new OzonPostingDto(number,number,number,status,"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","SKU","article","Fixture",1,"RUB","1")));}
}
