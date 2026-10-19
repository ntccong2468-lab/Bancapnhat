package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.status.*;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class Kn3BatchUiTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @BeforeEach void database(){System.setProperty("vncode.appdata.dir",root.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void clear()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException ready){}}
    static <T>T fx(Callable<T> call)throws Exception{var f=new FutureTask<T>(call);Platform.runLater(f);return f.get(10,TimeUnit.SECONDS);}
    @Test void shipmentButtonAppearsOnlyForRedAndFitsSmallWindows()throws Exception{
        fx(()->{var loader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyDetailController.class,"supply-detail-view.fxml");var view=(javafx.scene.Parent)loader.load();var scene=new Scene(view,600,600);ThemeService.applyTheme(scene);var stage=new Stage();stage.setScene(scene);stage.show();com.vncode.app.ui.supply.SupplyDetailController controller=loader.getController();
            try{var button=(Button)loader.getNamespace().get("addAgainKizButton");assertFalse(button.isVisible());var order=new com.vncode.app.models.Order();order.setId(101L);order.setNmId(100L);order.setKn3Marks(List.of(new Kn3Mark("101","","","synthetic",Kn3Mark.State.REJECTED,"sgtinRetired")));controller.setOrders(List.of(order));assertTrue(button.isVisible());var siblings=((Pane)button.getParent()).getChildren();assertEquals(siblings.indexOf(loader.getNamespace().get("inventoryToggleButton"))+1,siblings.indexOf(button));view.applyCss();view.layout();var bounds=button.localToScene(button.getBoundsInLocal());assertTrue(bounds.getMinX()>=0&&bounds.getMaxX()<=scene.getWidth()+1);order.setKn3Marks(List.of(new Kn3Mark("101","","","synthetic",Kn3Mark.State.ACCEPTED,"sgtinIntroduced")));controller.setOrders(List.of(order));assertFalse(button.isVisible());}
            finally{controller.dispose();stage.close();}return null;});
    }
    @Test void ozonReportsRejectedOrdersBeyondTheVisiblePageUsingCatalogProductIds()throws Exception{
        var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(Marketplace.OZON);var postings=new com.vncode.app.integration.ozon.OzonPostingRepository();
        new com.vncode.app.integration.ozon.OzonCatalogRepository().upsertPage(shop.getId(),List.of(new com.vncode.app.integration.ozon.OzonProductDto("9001","Synthetic offer","501","Synthetic product","","Synthetic","brown","52","Synthetic","",false,"fixture",List.of())),"");
        try(var database=com.vncode.app.config.Database.openBatchSession()){for(int n=1;n<=121;n++)postings.upsertDetail(shop.getId(),new com.vncode.app.integration.ozon.OzonPostingDto("101-1-"+n,"","","awaiting_deliver","","","","","","",new com.vncode.app.integration.ozon.OzonRequirements(List.of("501"),List.of(),List.of()),List.of(),false,List.of(new com.vncode.app.integration.ozon.OzonPostingItemDto(0,"501","501","Synthetic offer","Synthetic product",1,"RUB","100"))));}
        var query=new com.vncode.app.features.ozon.OzonFbsQuery(shop.getId(),"awaiting_deliver","","",0,100);var ids=postings.freezeFilteredNumbers(query,10000);assertEquals(121,ids.size());String hidden=ids.getLast();
        new Kn3Repository().observe(shop,hidden,List.of(new Kn3Mark(hidden,"501","11","synthetic",Kn3Mark.State.REJECTED,"invalid_mark")));
        var loader=fx(()->{var l=FxmlViewLoader.loader(com.vncode.app.ui.ozon.OzonDashboardController.class,"ozon-dashboard-view.fxml");l.load();return l;});com.vncode.app.ui.ozon.OzonDashboardController controller=loader.getController();
        try{fx(()->{controller.setShop(shop,false);((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get("packingOrdersTab"));return null;});
            for(int n=0;n<500&&!fx(()->((Button)loader.getNamespace().get("addAgainKizButton")).isVisible());n++)Thread.sleep(10);
            assertTrue(fx(()->((Button)loader.getNamespace().get("addAgainKizButton")).isVisible()));var report=new Kn3BatchReplacement().report(shop,ids);assertEquals(1,report.rows().size());assertEquals(hidden,report.rows().getFirst().mark().orderId());assertEquals("9001",report.rows().getFirst().productId());assertEquals("501",report.rows().getFirst().barcode());
        }finally{fx(()->{controller.dispose();return null;});}
    }
    @Test void kn3UsesRemoteVerdictAndHasNoPerOrderReplacementButton()throws Exception{
        fx(()->{var badge=new Kn3Badge();badge.show(List.of(),true,()->{});assertEquals("KN3",((Label)badge.lookup("#kn3Status")).getText());assertNull(badge.lookup("#kn3Replace"));assertTrue(badge.lookup("#kn3Status").getStyleClass().contains("badge-warning"));
            badge.show(List.of(new Kn3Mark("101","","","synthetic",Kn3Mark.State.REJECTED,"sgtinRetired")),true,()->{});assertNull(badge.lookup("#kn3Replace"));assertTrue(badge.lookup("#kn3Status").getStyleClass().contains("badge-red"));
            badge.show(List.of(new Kn3Mark("101","","","synthetic",Kn3Mark.State.ACCEPTED,"sgtinIntroduced")),true,()->{});assertNull(badge.lookup("#kn3Replace"));assertTrue(badge.lookup("#kn3Status").getStyleClass().contains("badge-green"));return null;});
    }
    @Test void cancelDoesNothingConfirmClosesReportAndBottomPillWaitsForVerifiedCompletion()throws Exception{
        var previous=I18nService.getInstance().getCurrentLanguage();var host=new StackPane();var owner=fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);var stage=new Stage();stage.setScene(new Scene(host,1000,650));ThemeService.applyTheme(stage.getScene());stage.show();return stage;});
        var mark=new Kn3Mark("101","","","synthetic",Kn3Mark.State.REJECTED,"sgtinRetired");var report=new Kn3BatchReplacement.Report(List.of("101"),List.of(new Kn3BatchReplacement.Row(mark,"100","synthetic-barcode","","Synthetic article")));var shop=new Shop(1,"Synthetic",Marketplace.WILDBERRIES,null,"fixture");var work=new CompletableFuture<Void>();var actions=new AtomicInteger();var refreshes=new AtomicInteger();
        Runnable open=()->Kn3BatchDialog.open(host,shop,report,()->{actions.incrementAndGet();return work;},refreshes::incrementAndGet);
        try{
            Platform.runLater(open);var first=awaitReport();fx(()->{assertEquals(1,((TableView<?>)first.lookup("#kn3RejectedOrdersReport")).getItems().size());assertEquals("Xác nhận",((Button)first.lookup("#kn3BatchConfirm")).getText());assertEquals("Hủy",((Button)first.lookup("#kn3BatchCancel")).getText());((Button)first.lookup("#kn3BatchCancel")).fire();return null;});
            fx(()->{assertEquals(0,actions.get());assertNull(host.lookup("#kn3BatchPill"));return null;});
            Platform.runLater(open);var second=awaitReport();fx(()->{capture(second.getScene(),"kn3-rejected-report");((Button)second.lookup("#kn3BatchConfirm")).fire();return null;});
            fx(()->{assertEquals(1,actions.get());assertTrue(second.getScene()==null||!second.getScene().getWindow().isShowing());var pill=(HBox)host.lookup("#kn3BatchPill");assertNotNull(pill);assertEquals(Pos.BOTTOM_CENTER,StackPane.getAlignment(pill));assertEquals("Đang gán KIZ",pill.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).findFirst().orElseThrow().getText());assertFalse(work.isDone());capture(host.getScene(),"kn3-assigning-pill");return null;});
            work.complete(null);fx(()->{var pill=(HBox)host.lookup("#kn3BatchPill");assertEquals("Đã gán KIZ xong",((Label)pill.getChildren().getFirst()).getText());assertEquals(1,refreshes.get());capture(host.getScene(),"kn3-complete-pill");return null;});
        }finally{fx(()->{for(var window:List.copyOf(Window.getWindows()))if(window instanceof Stage s&&s!=owner)s.close();owner.close();I18nService.getInstance().setLanguage(previous);return null;});}
    }
    static void capture(Scene scene,String name)throws Exception{
        String directory=System.getProperty("vncode.ui.capture.dir");if(directory==null)return;scene.getRoot().applyCss();scene.getRoot().layout();var image=scene.snapshot(null);int width=(int)image.getWidth(),height=(int)image.getHeight();var png=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,width);java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));javax.imageio.ImageIO.write(png,"png",java.nio.file.Path.of(directory,name+".png").toFile());
    }
    static DialogPane awaitReport()throws Exception{
        for(int n=0;n<200;n++){var pane=fx(()->Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).filter(p->p.lookup("#kn3BatchConfirm")!=null).findFirst().orElse(null));if(pane!=null)return pane;Thread.sleep(10);}throw new AssertionError("Report did not open");
    }
}
