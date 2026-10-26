package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductReferenceLayoutTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    static void await(Callable<Boolean> check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!fx(check)&&System.nanoTime()<end)Thread.sleep(15);assertTrue(fx(check));}
    @Test void wrappedActionsStayInsideTheirBackgroundAtNarrowAndScaledWorkAreas()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture shop','fixture','WILDBERRIES')");
            for(int i=1;i<=131;i++)s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,"+i+",'Костюм с футболкой и брюками','DEMO-"+i+"','now')");
        }
        var reads=new ShopReadCoordinator(2,16);var pane=fx(()->new ProductsPane(null,reads));var stage=fx(Stage::new);
        var language=I18nService.getInstance().getCurrentLanguage();var theme=ThemeService.getCurrentTheme();
        try{
            fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);pane.applyTranslations();ThemeService.switchTheme("dark");stage.setScene(new Scene(pane,1078,650));ThemeService.applyTheme(stage.getScene());stage.show();pane.setShop(new Shop(1,"Fixture shop","fixture"));pane.setOnEdit((field,keys)->{});return null;});
            await(()->!((Button)pane.lookup("#productSync")).isDisabled());
            fx(()->{((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});await(()->pane.selectedKeys().size()==131);
            for(String paint:List.of("dark","light"))for(var locale:AppLanguage.values())for(int width:List.of(1078,760,940,1331)){
                fx(()->{ThemeService.switchTheme(paint);I18nService.getInstance().setLanguage(locale);pane.applyTranslations();double frame=stage.getWidth()-stage.getScene().getWidth();stage.setMinWidth(width+frame);stage.setWidth(width+frame);return null;});
                await(()->Math.abs(stage.getScene().getWidth()-width)<1);
                fx(()->{
                    pane.applyCss();pane.layout();var bar=(javafx.scene.layout.Region)pane.lookup("#productBulkActions");
                    capture(stage.getScene(),paint,locale,width);
                    var bounds=bar.localToScene(bar.getLayoutBounds());var list=pane.lookup("#productRows").localToScene(pane.lookup("#productRows").getLayoutBounds());
                    assertTrue(bounds.getMaxY()<=stage.getScene().getHeight()+1,"Action background fits the window");assertTrue(list.getMaxY()<=bounds.getMinY()+1,"Actions must not cover product rows");
                    for(String id:List.of("productEdit_brand","productEdit_material","productEdit_gender","productEdit_tnved","productEdit_documents","productClearSelection")){
                        var button=pane.lookup("#"+id);var b=button.localToScene(button.getLayoutBounds());
                        assertTrue(b.getMinX()>=bounds.getMinX()-1&&b.getMaxX()<=bounds.getMaxX()+1&&b.getMinY()>=bounds.getMinY()-1&&b.getMaxY()<=bounds.getMaxY()+1,paint+" "+locale+" "+width+" "+id+" escapes its background: "+b+" / "+bounds);
                    }
                    assertTrue(list.getHeight()>290,"Product list must retain its working area");return null;
                });
            }
        }finally{fx(()->{pane.dispose();stage.close();I18nService.getInstance().setLanguage(language);ThemeService.setCurrentTheme(theme);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    private static void capture(Scene scene,String paint,AppLanguage language,int width)throws Exception{
        String file=System.getProperty("vncode.products.capture");if(file==null||!paint.equals("dark")||language!=AppLanguage.VI||width!=1078)return;
        var image=scene.snapshot(null);var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0,0,png.getWidth(),png.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,png.getWidth());
        javax.imageio.ImageIO.write(png,"png",Path.of(file).toFile());
    }
    @Test void allFilteredProductsIncludingUnloadedRowsReachEveryBatchActionAndClear()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture shop','fixture','WILDBERRIES')");
            try(var insert=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,subject_name,synced_at) VALUES(1,?,?,'fixture',?,'now')")){
                for(int i=1;i<=106;i++){insert.setInt(1,i);insert.setString(2,"Product "+i);insert.setString(3,i<=105?"Suits":"Shirts");insert.addBatch();}insert.executeBatch();
            }
        }
        var reads=new ShopReadCoordinator(2,16);var pane=fx(()->new ProductsPane(null,reads));var stage=fx(()->new Stage());var language=I18nService.getInstance().getCurrentLanguage();
        try{
            fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);pane.applyTranslations();stage.setScene(new Scene(pane,1331,808));ThemeService.applyTheme(stage.getScene());stage.show();
                // Windows can initially constrain a stage to a smaller CI desktop.
                // This test is explicitly for the 1331px reference, not the narrow layout.
                double frame=stage.getWidth()-stage.getScene().getWidth();stage.setMinWidth(1331+frame);stage.setWidth(1331+frame);
                pane.setShop(new Shop(1,"Fixture shop","fixture"));return null;});
            await(()->Math.abs(stage.getScene().getWidth()-1331)<1);
            await(()->!((Button)pane.lookup("#productSync")).isDisabled());
            fx(()->{((ComboBox<String>)pane.lookup("#productCategoryFilter")).setValue("Suits");return null;});
            await(()->!((Button)pane.lookup("#productSync")).isDisabled());
            assertEquals(100,fx(()->((ListView<?>)pane.lookup("#productRows")).getItems().size()));
            var actions=new ArrayList<String>();
            fx(()->{pane.setOnEdit((field,keys)->{assertEquals(105,keys.size());assertTrue(keys.stream().allMatch(k->Integer.parseInt(k.cardId())<=105));actions.add(field);});((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});
            await(()->pane.selectedKeys().size()==105);
            fx(()->{
                pane.applyCss();pane.layout();assertEquals(1331,stage.getScene().getWidth(),1,"Measure the actual reference client width");var select=(CheckBox)pane.lookup("#productSelectAll");assertTrue(select.isSelected());assertFalse(select.isIndeterminate());
                var bar=pane.lookup("#productBulkActions");assertTrue(bar.isVisible());assertTrue(bar.getBoundsInLocal().getHeight()<90,"The action bar stays one compact row at the reference width: "+bar.getBoundsInLocal());assertTrue(bar.localToScene(bar.getBoundsInLocal()).getMinY()>pane.lookup("#productRows").localToScene(pane.lookup("#productRows").getBoundsInLocal()).getMaxY());
                var sync=pane.lookup("#productSync");assertTrue(sync.localToScene(sync.getBoundsInLocal()).getMinX()>pane.getWidth()-180,"Sync belongs in the top-right corner");
                double previous=-1,firstY=-1;
                for(String field:List.of("brand","material","gender","tnved","documents")){var button=(Button)pane.lookup("#productEdit_"+field);assertNotNull(button.getGraphic());assertFalse(button.isDisabled());var bounds=button.localToScene(button.getBoundsInLocal());double x=bounds.getMinX();assertTrue(x>previous);previous=x;if(firstY<0)firstY=bounds.getMinY();else assertEquals(firstY,bounds.getMinY(),1,"Every batch action remains in the same row");button.fire();}
                assertEquals(List.of("brand","material","gender","tnved","documents"),actions);
                ((Button)pane.lookup("#productClearSelection")).fire();assertTrue(pane.selectedKeys().isEmpty());assertFalse(bar.isVisible());return null;
            });
        }finally{fx(()->{pane.dispose();stage.close();I18nService.getInstance().setLanguage(language);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
}
