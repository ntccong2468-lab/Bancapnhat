package com.vncode.app.ui.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WbShipmentKizPaneTest {
    @TempDir Path root;javafx.animation.Timeline approve;final java.util.List<String> reports=new java.util.ArrayList<>();WbShipmentKizPane pane;Shop one=new Shop(1,"WB 1","fixture-1"),two=new Shop(2,"WB 2","fixture-2");
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @BeforeEach void dialogDriver()throws Exception{
        fx(()->{approve=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(20),e->{
            for(var window:java.util.List.copyOf(javafx.stage.Window.getWindows()))if(window.isShowing()&&window.getScene()!=null){
                var node=window.getScene().lookup("#kiz-import-file-report");
                if(node instanceof Label label){reports.add(label.getText());var root=window.getScene().getRoot();if(root instanceof DialogPane dialog)dialog.getButtonTypes().stream().filter(b->b.getButtonData()==ButtonBar.ButtonData.OK_DONE||b==ButtonType.CLOSE).findFirst().ifPresent(b->((Button)dialog.lookupButton(b)).fire());}
            }
        }));approve.setCycleCount(javafx.animation.Animation.INDEFINITE);approve.play();return null;});
    }
    @AfterEach void stopDialogs()throws Exception{fx(()->{if(approve!=null)approve.stop();return null;});}
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB 1','fixture-1'),(2,'WB 2','fixture-2')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_id,subject_name,title,synced_at) VALUES(1,101,10,'Брюки','Trousers','fixture'),(1,102,20,'Рубашки','Shirts','fixture'),(2,201,30,'Платья','Dress','fixture')");
        }
        fx(()->{pane=new WbShipmentKizPane();new Scene(pane,480,600);return null;});
    }
    @AfterEach void clear()throws Exception{awaitTasks();fx(()->{pane.dispose();return null;});System.clearProperty("vncode.appdata.dir");}
    static void awaitTasks()throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do{Thread.sleep(10);fx(()->null);if(AppTaskExecutor.getRunningTaskCount()==0){fx(()->null);if(AppTaskExecutor.getRunningTaskCount()==0)return;}}while(System.nanoTime()<until);
        fail("Shipment tasks did not finish");
    }
    static void pulse()throws Exception{
        var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);
    }
    CheckBox imported(){return (CheckBox)pane.lookup("#shipment-kiz-imported");}
    CheckBox purchased(){return (CheckBox)pane.lookup("#shipment-kiz-purchased");}
    @Test void sourcesPersistByShipmentAndCountsIncludeEmptyCategories()throws Exception {
        fx(()->{pane.setContext(one,"S-1");assertThrows(IllegalStateException.class,pane::snapshot);return null;});awaitTasks();
        fx(()->{assertEquals(KizSourceSelection.SELLER_ONLY,pane.snapshot());assertEquals(2,((TableView<?>)pane.lookup("#shipment-kiz-categories")).getItems().size());purchased().fire();return null;});awaitTasks();
        assertEquals(new KizSourceSelection(true,true),new WbShipmentKizRepository().selection(one,"S-1"));
        fx(()->{assertEquals(new KizSourceSelection(true,true),pane.snapshot());pane.setContext(one,"S-2");return null;});awaitTasks();
        fx(()->{assertEquals(KizSourceSelection.SELLER_ONLY,pane.snapshot());imported().fire();return null;});awaitTasks();
        fx(()->{assertFalse(pane.snapshot().anySelected());assertFalse(pane.lookup("#shipment-kiz-categories").isManaged());return null;});
        assertEquals(new KizSourceSelection(false,false),new WbShipmentKizRepository().selection(one,"S-2"));
    }
    @Test void staleShopResultsCannotEnablePurchasesOrOverwriteTheNewShop()throws Exception {
        var repository=new WbShipmentKizRepository();repository.saveSelection(one,"S",new KizSourceSelection(false,true));
        fx(()->{pane.setContext(one,"S");pane.setContext(two,"S");return null;});awaitTasks();
        fx(()->{assertEquals(KizSourceSelection.SELLER_ONLY,pane.snapshot());assertFalse(purchased().isSelected());assertEquals(1,((TableView<?>)pane.lookup("#shipment-kiz-categories")).getItems().size());pane.setLocked(true);assertTrue(imported().isDisabled());assertTrue(purchased().isDisabled());return null;});
        assertEquals(new KizSourceSelection(false,true),repository.selection(one,"S"));assertEquals(KizSourceSelection.SELLER_ONLY,repository.selection(two,"S"));
    }
    @Test void selectionLabelsTranslateAndMissingCodeReportListsPublicOrderAndCategoryOnly()throws Exception {
        var i18n=I18nService.getInstance();var before=i18n.getCurrentLanguage();
        try{for(var language:AppLanguage.values())fx(()->{i18n.setLanguage(language);pane.applyTranslations();assertEquals(i18n.tr("kiz.shipment.seller_imported"),imported().getText());assertFalse(imported().getText().startsWith("kiz."));
            var error=new KizStockShortageException(java.util.List.of(new KizStockShortageException.Missing("123","456","Брюки")));String message=FriendlyErrorService.format(error);assertTrue(message.contains("123"));assertTrue(message.contains("Брюки"));assertFalse(message.contains("kiz.shipment."));return null;});}
        finally{fx(()->{i18n.setLanguage(before);return null;});}
    }
    @Test void shipmentViewEmbedsCategoryUploadsAndShowsPaidInventoryOnlyWhenSelected()throws Exception {
        var loaded=fx(()->{
            var loader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyDetailController.class,"supply-detail-view.fxml");
            javafx.scene.Parent view=FxmlViewLoader.load(loader);com.vncode.app.ui.supply.SupplyDetailController controller=loader.getController();
            var scene=new Scene(view,1500,820);ThemeService.applyTheme(scene);
            controller.setShop(one);controller.setKizShipmentContext(one,"S-FXML");controller.setSupplyInfo("WB · Shipment S-FXML","");
            var order=new com.vncode.app.models.Order();order.setId(123L);order.setNmId(101L);order.setName("Synthetic trousers");order.setSubjectName("Брюки");order.setArticle("SYNTHETIC-101");order.setSize("L");order.setBarcode("2040000000001");controller.setOrders(java.util.List.of(order));
            ((Button)view.lookup("#inventoryToggleButton")).fire();return new ShipmentView(controller,view);
        });
        try {
            awaitTasks();
            var category=new WbShipmentKizRepository().refreshCategories(one).getFirst();
            var file=root.resolve("synthetic-stock.txt");java.nio.file.Files.write(file,java.util.List.of("0100000000000017210000000000001","0100000000000017210000000000002"));
            new KizWarehouseService().importFile(one,KizImportReader.read(file),true,category.id());
            fx(()->{((WbShipmentKizPane)((javafx.scene.layout.VBox)loaded.view().lookup("#shipmentKizHost")).getChildren().getFirst()).reload();return null;});awaitTasks();
            fx(()->{
                var view=loaded.view();view.applyCss();view.layout();
                assertFalse(view.lookup("#paidInventoryPane").isManaged());assertTrue(view.lookup("#shipment-kiz-categories").isManaged());
                assertEquals(2,((TableView<?>)view.lookup("#shipment-kiz-categories")).getItems().size());assertNotNull(view.lookup("#shipment-kiz-upload"));
                assertEquals(2,((WbShipmentKizRepository.CategoryStock)((TableView<?>)view.lookup("#shipment-kiz-categories")).getItems().getFirst()).ready());
                var image=view.snapshot(null,null);var bitmap=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)bitmap.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                var output=Path.of("target/ui-checks/item2-wb-shipment.png");java.nio.file.Files.createDirectories(output.getParent());javax.imageio.ImageIO.write(bitmap,"png",output.toFile());
                ((CheckBox)view.lookup("#shipment-kiz-purchased")).fire();return null;
            });awaitTasks();
            fx(()->{assertTrue(loaded.view().lookup("#paidInventoryPane").isManaged());assertEquals(new KizSourceSelection(true,true),loaded.controller().kizSources());return null;});
        }finally{fx(()->{loaded.controller().dispose();return null;});awaitTasks();}
    }
    @Test void importSurvivesContinuousFbsRefreshAndImmediatelyShowsRemainingCategoryStock()throws Exception{
        fx(()->{pane.setContext(one,"CONTINUOUS");return null;});awaitTasks();
        var category=new ShipmentKizRepository().stocks(one).getFirst();var file=root.resolve("seller-notepad.txt");
        java.nio.file.Files.write(file,java.util.List.of("0100000000000017210000000000001","0100000000000017210000000000002"));
        fx(()->{pane.importFile(category,file);pane.setContext(new Shop(1,"WB renamed","fixture-1"),"CONTINUOUS");return null;});awaitTasks();
        fx(()->{var table=(TableView<ShipmentKizRepository.CategoryStock>)pane.lookup("#shipment-kiz-categories");assertEquals(2,table.getItems().stream().filter(c->c.id().equals(category.id())).findFirst().orElseThrow().ready());assertTrue(table.getColumns().stream().noneMatch(c->"consumed".equals(c.getUserData())));return null;});
        var selected=new KizCategoryAllocator().reservePartial(one,java.util.List.of(new KizCategoryAllocator.Demand("order-1","101",1))).get("order-1");
        assertEquals(1,selected.size());new com.vncode.app.integration.znack.ZnackGtinInventoryService().consume(one.getId(),selected);
        fx(()->{pane.reload();return null;});awaitTasks();
        fx(()->{var table=(TableView<ShipmentKizRepository.CategoryStock>)pane.lookup("#shipment-kiz-categories");assertEquals(1,table.getItems().stream().filter(c->c.id().equals(category.id())).findFirst().orElseThrow().ready());return null;});
        // Every upload is new seller stock, including an identical file and previously consumed raw codes.
        fx(()->{pane.importFile(category,file);return null;});awaitTasks();assertEquals(3,new ShipmentKizRepository().stocks(one).stream().filter(c->c.id().equals(category.id())).findFirst().orElseThrow().ready());
    }
    @Test void sixHundredCodesFromSellerTextFileAreVisibleInTheirCategory()throws Exception{
        fx(()->{pane.setContext(one,"BULK-FILE");return null;});awaitTasks();var category=new ShipmentKizRepository().stocks(one).getFirst();
        String sample=System.getProperty("vncode.kiz.sample.path");var file=sample==null?root.resolve("six-hundred.txt"):Path.of(sample);
        if(sample==null){var rows=new java.util.ArrayList<String>();for(int i=0;i<600;i++)rows.add(String.format(java.util.Locale.ROOT,"010000000000001721%013d",i));java.nio.file.Files.write(file,rows);}
        fx(()->{pane.importFile(category,file);return null;});awaitTasks();
        fx(()->{var table=(TableView<ShipmentKizRepository.CategoryStock>)pane.lookup("#shipment-kiz-categories");assertEquals(600,table.getItems().stream().filter(c->c.id().equals(category.id())).findFirst().orElseThrow().ready());return null;});
        assertEquals(600,KizImportReader.read(file).marks().size());
    }
    @Test void deleteButtonRemovesTheSelectedCategoryAndRefreshDoesNotRecreateIt()throws Exception{
        fx(()->{pane.setContext(one,"DELETE");return null;});awaitTasks();
        fx(()->{
            pane.applyCss();pane.layout();var button=(Button)pane.lookup("#shipment-kiz-delete");assertNotNull(button);
            Platform.runLater(()->{
                for(var window:java.util.List.copyOf(javafx.stage.Window.getWindows()))if(window.isShowing()&&window.getScene()!=null&&window.getScene().lookup("#shipment-kiz-delete-confirm") instanceof DialogPane dialog)((Button)dialog.lookupButton(ButtonType.OK)).fire();
            });button.fire();return null;
        });awaitTasks();
        fx(()->{assertEquals(1,((TableView<?>)pane.lookup("#shipment-kiz-categories")).getItems().size());pane.reload();return null;});awaitTasks();
        assertEquals(1,new ShipmentKizRepository().refreshCategories(one).size());
    }
    @Test void fileWithOnlyDamagedCodesShowsItsReportWithoutAddingStock()throws Exception{
        fx(()->{pane.setContext(one,"DAMAGED");return null;});awaitTasks();
        var category=new ShipmentKizRepository().stocks(one).getFirst();var file=root.resolve("damaged.txt");java.nio.file.Files.write(file,java.util.List.of("broken","not-a-code"));
        fx(()->{pane.importFile(category,file);return null;});awaitTasks();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(fx(()->reports.isEmpty())&&System.nanoTime()<until)Thread.sleep(10);
        assertFalse(fx(()->reports.isEmpty()));assertTrue(new ShipmentKizRepository().stocks(one).stream().allMatch(row->row.ready()==0));
    }
    private record ShipmentView(com.vncode.app.ui.supply.SupplyDetailController controller,javafx.scene.Parent view){}
    @Test void largePaidCatalogOnlyCreatesVisibleCardsAndCanReachTheLastGtin()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_products(shop_id,gtin,product_name,card_status,synced_at) VALUES(1,?,?,'published','2026-01-01T00:00:00Z')")) {
            c.setAutoCommit(false);for(int i=1;i<=2000;i++){p.setString(1,String.format(java.util.Locale.ROOT,"%014d",i));p.setString(2,"Synthetic "+i);p.addBatch();}p.executeBatch();c.commit();
        }
        new WbShipmentKizRepository().saveSelection(one,"LARGE",new KizSourceSelection(false,true));
        var stage=new java.util.concurrent.atomic.AtomicReference<javafx.stage.Stage>();
        var loaded=fx(()->{
            var loader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyDetailController.class,"supply-detail-view.fxml");javafx.scene.Parent view=FxmlViewLoader.load(loader);
            com.vncode.app.ui.supply.SupplyDetailController controller=loader.getController();var window=new javafx.stage.Stage();window.setScene(new Scene(view,1500,820));window.show();stage.set(window);controller.setShop(one);controller.setKizShipmentContext(one,"LARGE");((Button)view.lookup("#inventoryToggleButton")).fire();return new ShipmentView(controller,view);
        });
        try {
            // Exercise scrolling after the loaded list has actually been displayed, as in a user gesture.
            awaitTasks();pulse();fx(()->{
                var view=loaded.view();view.applyCss();view.layout();var list=(ListView<?>)view.lookup("#gtinInventoryList");
                assertEquals(2000,list.getItems().size());assertTrue(view.lookupAll(".gtin-inventory-card").size()<60,"Large catalog must not create one card node for every GTIN");
                // Exercise the scrollbar the seller actually uses. VirtualFlow.scrollTo(lastIndex)
                // estimates offsets for variable-height cells and can stop one row before the end.
                var vertical=list.lookupAll(".scroll-bar").stream().filter(n->n instanceof ScrollBar b&&b.getOrientation()==javafx.geometry.Orientation.VERTICAL).map(n->(ScrollBar)n).findFirst().orElseThrow();
                assertTrue(vertical.isVisible());vertical.setValue(vertical.getMax());view.layout();return null;
            });
            boolean lastVisible=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do{lastVisible=fx(()->lastGtinVisible(loaded.view()));if(!lastVisible)Thread.sleep(20);}while(!lastVisible&&System.nanoTime()<until);
            assertTrue(lastVisible,()->{
                try{return fx(()->{
                    var list=(ListView<?>)loaded.view().lookup("#gtinInventoryList");
                    return "The final GTIN must become visible after scrolling; viewport="+list.getWidth()+"x"+list.getHeight()+" finalItem="+list.getItems().getLast()+" visibleCells="+list.lookupAll(".list-cell").stream().filter(n->n instanceof ListCell<?>).map(n->((ListCell<?>)n).getIndex()).toList()+" bars="+list.lookupAll(".scroll-bar").stream().filter(n->n instanceof ScrollBar).map(n->{var b=(ScrollBar)n;return b.getOrientation()+":"+b.getValue()+"/"+b.getMax();}).toList();
                });}catch(Exception error){return "Cannot inspect final GTIN: "+error;}
            });
            fx(()->{loaded.controller().refreshGtinInventory();return null;});awaitTasks();
            fx(()->{
                var view=loaded.view();view.applyCss();view.layout();
                assertTrue(lastGtinVisible(view));
                assertTrue(view.lookupAll(".gtin-inventory-card").size()<60);return null;
            });
        }finally{fx(()->{loaded.controller().dispose();if(stage.get()!=null)stage.get().close();return null;});awaitTasks();}
    }
    static boolean lastGtinVisible(javafx.scene.Parent view){
        var list=view.lookup("#gtinInventoryList");var viewport=list.localToScene(list.getBoundsInLocal());
        return view.lookupAll(".gtin-code").stream().anyMatch(node->node instanceof Label label&&"00000000002000".equals(label.getText())&&node.isVisible()&&viewport.intersects(node.localToScene(node.getBoundsInLocal())));
    }
}
