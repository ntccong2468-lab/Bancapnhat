package com.vncode.app.ui.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonFbsUiTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @Test void wbStyleColumnsKeepOrderDateBarcodeGtinAndKn3VisibleOnEveryOrderTab()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','11','fixture')");}
        var language=I18nService.getInstance().getCurrentLanguage();var stage=new javafx.stage.Stage[1];
        var loader=fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);var view=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");var scene=new Scene(view.load(),1200,720);stage[0]=new javafx.stage.Stage();stage[0].setScene(scene);stage[0].show();return view;});
        OzonDashboardController controller=loader.getController();
        try{
            var item=new OzonPostingItemDto(0,"101","1001","offer-a","Fixture sports suit",1,"RUB","100");
            var product=new OzonProductDto("101","offer-a","1001","Fixture sports suit","",false,"",List.of("OZN1000001")).withCardAttributes("ART-1","BLUE","XL","Sports suits","");
            var mark=new com.vncode.app.features.kiz.status.Kn3Mark("90000000-0001-1","1001","1","synthetic",com.vncode.app.features.kiz.status.Kn3Mark.State.ACCEPTED,"");
            fx(()->{controller.setFbsReader(q->new OzonFbsPage(List.of(new OzonPostingDto("90000000-0001-1","1","90000000-0001",q.status(),"","","2026-10-25T12:00:00+03:00","","","",new OzonRequirements(List.of("101"),List.of(),List.of()),List.of(),false,List.of(item))),1,Map.of("1001",product),Map.of("1001","04631993764370"),Map.of("90000000-0001-1",List.of(mark))));controller.setShop(new Shop(1,"Fixture",Marketplace.OZON,"11","fixture"),false);return null;});
            for(int n=0;n<500&&!fx(()->((TableView<?>)loader.getNamespace().get("newOrdersTable")).getItems().size()==1);n++)Thread.sleep(10);
            for(String prefix:List.of("new","packing","printed","delivering")){
                fx(()->{((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get(prefix.equals("new")?"newOrdersTab":prefix+"OrdersTab"));return null;});
                var rendered=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();rendered.complete(null);}}}.start();return null;});rendered.get(5,TimeUnit.SECONDS);
                fx(()->{
                    var table=(TableView<?>)loader.getNamespace().get(prefix+"OrdersTable");table.applyCss();table.layout();assertTrue(table.getStyleClass().contains("supply-orders"));
                    var columns=table.getVisibleLeafColumns();assertTrue(columns.stream().anyMatch(c->"ozonRowNumber".equals(c.getId())));assertTrue(columns.stream().anyMatch(c->"ozonBarcodes".equals(c.getId())));
                    var labels=table.lookupAll(".label").stream().filter(node->node instanceof Label).map(node->((Label)node).getText()).toList();
                    for(String value:List.of("90000000-0001-1","25.10.2026","OZN1000001","04631993764370","KN3"))assertTrue(labels.stream().anyMatch(text->text.contains(value)),prefix+" missing "+value);
                    assertTrue(((Button)loader.getNamespace().get("printAllButton")).isVisible()==prefix.equals("packing"));return null;
                });
            }
        }finally{fx(()->{controller.dispose();stage[0].close();I18nService.getInstance().setLanguage(language);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void allFourOrderTabsKeepActionsAndPagingVisibleAcrossLanguagesThemesAndSmallWindows() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString()); Database.initDatabase();
        var originalLanguage = I18nService.getInstance().getCurrentLanguage();
        var stage = new javafx.stage.Stage[1];
        var loader = fx(() -> {
            var view = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
            var scene = new Scene(view.load(), 1024, 600);
            scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
            stage[0] = new javafx.stage.Stage(); stage[0].setScene(scene); stage[0].show(); return view;
        });
        OzonDashboardController controller = loader.getController();
        try {
            for (var language : AppLanguage.values()) for (var theme : List.of("theme-dark", "theme-light"))
                for (var size : List.of(new int[]{824, 600}, new int[]{1140, 768}))
                    for (var tab : List.of("newOrdersTab", "packingOrdersTab", "printedOrdersTab", "deliveringOrdersTab")) fx(() -> {
                        I18nService.getInstance().setLanguage(language); controller.applyTranslations();
                        var scene = stage[0].getScene(); var content = scene.getRoot();
                        content.getStyleClass().removeAll("theme-dark", "theme-light"); content.getStyleClass().add(theme);
                        stage[0].setWidth(size[0]); stage[0].setHeight(size[1]);
                        ((TabPane) loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab) loader.getNamespace().get(tab));
                        content.applyCss(); content.layout();
                        for (var node : content.lookupAll(".button")) {
                            boolean visible = true;
                            for (var parent = node; parent != null; parent = parent.getParent()) visible &= parent.isVisible();
                            if (!visible || !node.isManaged()) continue;
                            var bounds = node.localToScene(node.getBoundsInLocal());
                            assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1,
                                    language + " " + tab + " clipped horizontally: " + node.getId());
                            assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1,
                                    language + " " + tab + " clipped vertically: " + node.getId());
                        }
                        var paging = (Button) loader.getNamespace().get("nextPageButton");
                        var table = (TableView<?>) loader.getNamespace().get(tab.equals("newOrdersTab") ? "newOrdersTable" : tab.equals("packingOrdersTab") ? "packingOrdersTable" : tab.equals("printedOrdersTab") ? "printedOrdersTable" : "deliveringOrdersTable");
                        assertTrue(paging.localToScene(paging.getBoundsInLocal()).getMinY() >= table.localToScene(table.getBoundsInLocal()).getMaxY(), "Paging remains beneath the active table");
                        return null;
                    });
        } finally {
            fx(() -> { controller.dispose(); stage[0].close(); I18nService.getInstance().setLanguage(originalLanguage); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle(); System.clearProperty("vncode.appdata.dir");
        }
    }
    @Test void allPostingItemTitlesStayInsideTheirRenderedRow()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','11','fixture')");}
        var window=new java.util.concurrent.atomic.AtomicReference<javafx.stage.Stage>();FXMLLoader loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");var scene=new Scene(l.load(),1024,600);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());var stage=new javafx.stage.Stage();window.set(stage);stage.setScene(scene);stage.show();return l;});OzonDashboardController controller=loader.getController();
        try{
            fx(()->{controller.setFbsReader(q->new OzonFbsPage(List.of(new OzonPostingDto("fixture","","",q.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","11","fixture-a","First complete product title",1,"RUB","100"),new OzonPostingItemDto(1,"2","22","fixture-b","Second complete product title",1,"RUB","200")))),1,Map.of()));controller.setShop(new Shop(1,"Fixture",Marketplace.OZON,"11","fixture"),false);return null;});
            for(int i=0;i<500;i++){if(fx(()->((TableView<?>)loader.getNamespace().get("newOrdersTable")).getItems().size()==1))break;Thread.sleep(10);}
            var pulsed=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();pulsed.complete(null);}}}.start();return null;});pulsed.get(5,TimeUnit.SECONDS);
            fx(()->{var table=(TableView<?>)loader.getNamespace().get("newOrdersTable");table.applyCss();table.layout();var row=table.lookupAll(".table-row-cell").stream().filter(n->n instanceof TableRow<?> r&&!r.isEmpty()).findFirst().orElseThrow();var bounds=row.localToScene(row.getBoundsInLocal());
                var titles=row.lookupAll(".text-bold").stream().filter(n->n instanceof Label l&&l.getText().endsWith("complete product title")).toList();assertEquals(2,titles.size());for(var label:titles){var b=label.localToScene(label.getBoundsInLocal());assertTrue(b.getMinY()>=bounds.getMinY()-1&&b.getMaxY()<=bounds.getMaxY()+1,"Each title must remain visible inside its own row");}return null;});
            String captureDir = System.getProperty("vncode.ui.capture.dir");
            if (captureDir != null) {
                var language = I18nService.getInstance().getCurrentLanguage();
                try {
                    fx(() -> { I18nService.getInstance().setLanguage(AppLanguage.VI); controller.applyTranslations(); return null; });
                    for (var tab : List.of("newOrdersTab", "packingOrdersTab", "printedOrdersTab", "deliveringOrdersTab")) {
                        fx(() -> { ((TabPane) loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab) loader.getNamespace().get(tab)); return null; });
                        fx(() -> {
                            var scene = window.get().getScene(); scene.getRoot().applyCss(); scene.getRoot().layout();
                            var image = scene.snapshot(null); int width = (int) image.getWidth(), height = (int) image.getHeight();
                            var png = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            image.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), ((java.awt.image.DataBufferInt) png.getRaster().getDataBuffer()).getData(), 0, width);
                            java.nio.file.Files.createDirectories(Path.of(captureDir));
                            javax.imageio.ImageIO.write(png, "png", Path.of(captureDir, "ozon-" + tab + ".png").toFile()); return null;
                        });
                    }
                } finally { fx(() -> { I18nService.getInstance().setLanguage(language); return null; }); }
            }
        }finally{fx(()->{controller.dispose();window.get().close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void oldShopResultCannotUpdateTable()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'First','OZON','11','fixture'),(2,'Second','OZON','22','fixture')");}
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);FXMLLoader loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");new Scene(l.load(),1024,600);return l;});OzonDashboardController controller=loader.getController();
        try{
            fx(()->{controller.setFbsReader(q->{if(q.shopId()==1){entered.countDown();try{gate.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return new OzonFbsPage(List.of(new OzonPostingDto("shop-"+q.shopId(),"","",q.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of())),1001,Map.of());});controller.setShop(new Shop(1,"First",Marketplace.OZON,"11","fixture"),false);return null;});assertTrue(entered.await(3,TimeUnit.SECONDS));
            fx(()->{controller.setShop(new Shop(2,"Second",Marketplace.OZON,"22","fixture"),false);return null;});gate.countDown();
            for(int i=0;i<500;i++){Thread.sleep(10);if(ShopReadCoordinator.shared().activeCount()==0&&ShopReadCoordinator.shared().pendingCount()==0)break;}fx(()->{var table=(TableView<OzonPostingDto>)loader.getNamespace().get("newOrdersTable");assertEquals("shop-2",table.getItems().getFirst().postingNumber());assertTrue(((Tab)loader.getNamespace().get("newOrdersTab")).getText().contains("1001"));assertEquals(100,controller.currentPageLimit());return null;});
        }finally{gate.countDown();fx(()->{controller.dispose();return null;});for(int i=0;i<500&&(AppTaskExecutor.hasRunningTasks()||ShopReadCoordinator.shared().activeCount()>0);i++)Thread.sleep(10);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
}
