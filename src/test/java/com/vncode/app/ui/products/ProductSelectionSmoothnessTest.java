package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.ShopReadCoordinator;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductSelectionSmoothnessTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    static void await(Callable<Boolean> check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!fx(check)&&System.nanoTime()<end)Thread.sleep(15);assertTrue(fx(check));}

    @Test void selectionKeepsVisibleImagesAndBulkTargetsInSync()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture','fixture','WILDBERRIES')");}
        var product=new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"123",""),"Product","SKU","123","Suit","Brand","Black","L",List.of(),"","","Cotton","6103109000",DocumentState.MISSING,"Мужской",true);
        var reads=new ShopReadCoordinator(2,16);var pane=fx(()->new ProductsPane(q->new ProductPage(List.of(product),1,null),reads));var stage=fx(Stage::new);
        try{
            fx(()->{stage.setScene(new Scene(pane,1100,680));com.vncode.app.shared.ThemeService.applyTheme(stage.getScene());stage.show();pane.setShop(new Shop(1,"Fixture","fixture"));pane.setOnEdit((field,keys)->{});return null;});
            await(()->!((Button)pane.lookup("#productSync")).isDisabled()&&!((ListView<?>)pane.lookup("#productRows")).getItems().isEmpty());
            var sentinel=new WritableImage(2,2);for(int x=0;x<2;x++)for(int y=0;y<2;y++)sentinel.getPixelWriter().setColor(x,y,javafx.scene.paint.Color.rgb(91,145,176));
            fx(()->{pane.applyCss();pane.layout();var cell=visibleCell(pane);((ImageView)cell.lookup(".image-view")).setImage(sentinel);((CheckBox)cell.lookup(".check-box")).fire();return null;});
            // Force a JavaFX pulse so ListView.refresh() would recreate/rebind its cells.
            Thread.sleep(150);
            fx(()->{pane.applyCss();pane.layout();capture(stage.getScene());var cell=visibleCell(pane);assertSame(sentinel,((ImageView)cell.lookup(".image-view")).getImage(),"Changing selection must not discard an already rendered thumbnail");assertTrue(((CheckBox)cell.lookup(".check-box")).isSelected());assertEquals(List.of(product.key()),pane.selectedKeys());assertFalse(((Button)pane.lookup("#productEdit_brand")).isDisabled());return null;});
            fx(()->{((Button)pane.lookup("#productClearSelection")).fire();return null;});
            fx(()->{pane.applyCss();pane.layout();var cell=visibleCell(pane);assertFalse(((CheckBox)cell.lookup(".check-box")).isSelected());assertSame(sentinel,((ImageView)cell.lookup(".image-view")).getImage());return null;});
        }finally{fx(()->{pane.dispose();stage.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void searchingCannotSelectThePreviousResultsDuringTheDebounce()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture','fixture','WILDBERRIES')");}
        var old=new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"111",""),"Old","OLD","111","Suit","Brand","Black","L",List.of(),"","","Cotton","6103109000",DocumentState.MISSING);
        var found=new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"222",""),"New","NEW","222","Suit","Brand","Black","L",List.of(),"","","Cotton","6103109000",DocumentState.MISSING);
        var reads=new ShopReadCoordinator(2,16);var pane=fx(()->new ProductsPane(q->new ProductPage(List.of(q.text().isBlank()?old:found),1,null),reads));var stage=fx(Stage::new);
        try{
            fx(()->{stage.setScene(new Scene(pane,1100,680));stage.show();pane.setShop(new Shop(1,"Fixture","fixture"));return null;});
            await(()->!((Button)pane.lookup("#productSync")).isDisabled()&&!((ListView<?>)pane.lookup("#productRows")).getItems().isEmpty());
            fx(()->{((TextField)pane.lookup("#productSearch")).setText("NEW");var select=(CheckBox)pane.lookup("#productSelectAll");assertTrue(select.isDisabled(),"Selection must wait until the new search has loaded");select.fire();assertTrue(pane.selectedKeys().isEmpty());return null;});
            await(()->!((CheckBox)pane.lookup("#productSelectAll")).isDisabled()&&((ListView<?>)pane.lookup("#productRows")).getItems().contains(found));
            fx(()->{((CheckBox)pane.lookup("#productSelectAll")).fire();assertEquals(List.of(found.key()),pane.selectedKeys());return null;});
        }finally{fx(()->{pane.dispose();stage.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    private static ListCell<?> visibleCell(ProductsPane pane){
        return pane.lookupAll(".list-cell").stream().filter(n->n instanceof ListCell<?> c&&!c.isEmpty()&&c.getItem() instanceof ProductRow).map(n->(ListCell<?>)n).findFirst().orElseThrow();
    }
    private static void capture(Scene scene)throws Exception{
        String destination=System.getProperty("vncode.product-selection.capture");if(destination==null)return;
        var image=scene.snapshot(null);var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0,0,png.getWidth(),png.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,png.getWidth());
        assertTrue(javax.imageio.ImageIO.write(png,"png",Path.of(destination).toFile()));
    }
}
