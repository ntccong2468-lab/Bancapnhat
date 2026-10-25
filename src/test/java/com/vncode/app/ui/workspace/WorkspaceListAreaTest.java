package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.znackregistration.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Measure the working list, not just whether toolbar buttons happen to fit. */
class WorkspaceListAreaTest {
    @TempDir Path data;
    private final Shop wb=new Shop(1,"Synthetic WB",Marketplace.WILDBERRIES,"","fixture");
    private final Shop ozon=new Shop(2,"Synthetic Ozon",Marketplace.OZON,"fixture","fixture");
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static void pulse()throws Exception{
        var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);
    }
    private record Page(Parent root,Control list,Runnable close){}
    private record Area(double width,double height){}

    @Test void listsUseTheWbFbsWorkAreaAndGrowWithTheWindow()throws Exception{
        System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic WB','WILDBERRIES',NULL,'fixture'),(2,'Synthetic Ozon','OZON','fixture','fixture')");
            for(int n=1;n<=12;n++){
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,"+n+",'Sản phẩm kiểm thử "+n+"','DEMO-"+n+"','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+n+","+n+",'XL')");
                s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,'"+n+"','DEMO-"+n+"','"+(1000+n)+"','Sản phẩm kiểm thử "+n+"','fixture')");
            }
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(wb);
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(ozon);
        var language=I18nService.getInstance().getCurrentLanguage();var window=fx(Stage::new);var measured=new LinkedHashMap<String,Area>();
        try{
            fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);ThemeService.initializeAppearance();return null;});
            for(var size:List.of(new int[]{940,650},new int[]{1200,720},new int[]{1480,900})){
                Area baseline=null;
                for(String name:List.of("wb-fbs","products","wb-registration","ozon-registration","ozon-fbs")){
                    Page page=fx(()->page(name));
                    try{
                        fx(()->{
                            page.root().getStyleClass().add("app-page");var scene=new Scene(new StackPane(new WorkspaceViewport(page.root())),size[0],size[1]);ThemeService.applyTheme(scene);
                            window.setMinWidth(0);window.setMinHeight(0);window.setScene(scene);window.show();
                            double frameX=window.getWidth()-scene.getWidth(),frameY=window.getHeight()-scene.getHeight();
                            window.setMinWidth(size[0]+frameX);window.setMinHeight(size[1]+frameY);window.setWidth(size[0]+frameX);window.setHeight(size[1]+frameY);return null;
                        });
                        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();pulse();
                        Area area=fx(()->{
                            var scene=window.getScene();scene.getRoot().applyCss();scene.getRoot().layout();var bounds=page.list().localToScene(page.list().getBoundsInLocal());
                            assertEquals(size[0],scene.getWidth(),1);assertEquals(size[1],scene.getHeight(),1);
                            assertTrue(bounds.getMinX()>=0&&bounds.getMaxX()<=scene.getWidth()+1,name+" must fit horizontally");
                            assertTrue(bounds.getMaxY()<=scene.getHeight()+1,name+" must fit above the window edge");
                            capture(scene,name,size[0]);return new Area(bounds.getWidth(),bounds.getHeight());
                        });
                        System.out.printf(Locale.ROOT,"WORKSPACE_LIST_AREA %s viewport=%dx%d list=%.0fx%.0f%n",name,size[0],size[1],area.width(),area.height());
                        if(baseline==null)baseline=area;
                        else{
                            assertEquals(baseline.width(),area.width(),2,name+" must use the same full working width as WB FBS");
                            assertTrue(area.height()>=baseline.height()-(size[0]<1200?112:48),name+" wastes working height compared with WB FBS: "+area+" versus "+baseline);
                        }
                        measured.put(name+"-"+size[0],area);
                    }finally{fx(()->{page.close().run();window.getScene().setRoot(new StackPane());return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
                }
            }
            for(String name:List.of("wb-fbs","products","wb-registration","ozon-registration","ozon-fbs"))
                assertTrue(measured.get(name+"-1480").height()-measured.get(name+"-1200").height()>=170,name+" must grow its list when the window grows, rather than keep a small fixed frame");
        }finally{fx(()->{window.close();I18nService.getInstance().setLanguage(language);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }

    @SuppressWarnings("unchecked") private Page page(String name)throws Exception{
        if(name.equals("products")){
            var rows=new ArrayList<ProductRow>();for(int n=1;n<=12;n++)rows.add(new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,Integer.toString(n),""),"Sản phẩm kiểm thử "+n,"DEMO-"+n,Integer.toString(n),"Костюмы спортивные","Demo","Синий","XL",List.of(),"","","Хлопок","6112110000",DocumentState.VALID,"Мужской",true));
            var pane=new ProductsPane(q->new ProductPage(rows,rows.size(),""),ShopReadCoordinator.shared());pane.setShop(wb);return new Page(pane,(Control)pane.lookup("#productRows"),pane::dispose);
        }
        if(name.equals("ozon-registration")){var pane=new OzonRegistrationPane(ozon,()->{});return new Page(pane,(Control)pane.lookup("#ozonRegistrationTable"),pane::dispose);}
        String path=switch(name){case "wb-fbs"->"packing/packing-view.fxml";case "wb-registration"->"znackregistration/znack-card-registration-view.fxml";default->"ozon/ozon-dashboard-view.fxml";};
        var loader=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/"+path);Parent root=loader.load();Object controller=loader.getController();
        if(controller instanceof ZnackCardRegistrationController registration)registration.setShop(wb);
        if(controller instanceof OzonDashboardController dashboard){
            dashboard.setFbsReader(q->{var rows=new ArrayList<OzonPostingDto>();for(int n=1;n<=12;n++)rows.add(new OzonPostingDto("DEMO-"+n,"","",q.status(),"","","2026-10-25T12:00:00+03:00","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","1001","DEMO","Sản phẩm kiểm thử "+n,1,"RUB","100"))));return new OzonFbsPage(rows,rows.size(),Map.of());});dashboard.setShop(ozon,false);
        }
        if(name.equals("wb-fbs")){
            var table=(TableView<Order>)loader.getNamespace().get("newOrdersTable");for(int n=1;n<=12;n++){var order=new Order();order.setId((long)n);order.setName("Sản phẩm kiểm thử "+n);order.setArticle("DEMO-"+n);order.setBrand("Demo");order.setSize("XL");table.getItems().add(order);}
        }
        return new Page(root,(Control)loader.getNamespace().get(name.equals("wb-registration")?"productTable":"newOrdersTable"),()->{try{controller.getClass().getMethod("dispose").invoke(controller);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}});
    }
    private static void capture(Scene scene,String name,int width)throws Exception{
        String folder=System.getProperty("vncode.ui.capture.dir");if(folder==null||width!=1200)return;
        var image=scene.snapshot(null);var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0,0,png.getWidth(),png.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,png.getWidth());
        Files.createDirectories(Path.of(folder));javax.imageio.ImageIO.write(png,"png",Path.of(folder,name+".png").toFile());
    }
}
