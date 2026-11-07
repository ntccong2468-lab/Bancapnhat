package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.OzonExistingCardLinkService;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonCatalogVariantLinkDialogTest {
    @TempDir Path root;Shop shop;Stage stage;OzonCatalogVariantLinkDialog.Review pane;AtomicInteger approvals=new AtomicInteger();
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> c)throws Exception{var f=new FutureTask<>(c);Platform.runLater(f);return f.get(15,TimeUnit.SECONDS);}
    static void until(Callable<Boolean> c)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(fx(c))return;Thread.sleep(25);}fail("Variant review did not finish");}
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);}
    @AfterEach void cleanup()throws Exception{if(stage!=null)fx(()->{pane.closed=true;stage.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    void open()throws Exception{
        var key=new ProductKey(2,Marketplace.OZON,"501","SELLER-DIFFERENT-L");
        var row=new ProductRow(key,"Áo gilet mẫu A / Жилет A","SELLER-DIFFERENT-L","9001","Жилеты","Brand","Blue","L",List.of(),"","","Cotton","6109100000",DocumentState.UNVERIFIED,"Male",true,"52","L");
        var card=new RegisteredGtin(GtinWriteTestFixture.GTIN,"CATALOG-MODEL-A","Blue","L",true,true,RegisteredGtinProof.source(shop),Instant.now());
        var backend=new OzonCatalogVariantLinkDialog.Review.Backend(){
            public List<ProductRow> products(){return List.of(row);}public List<RegisteredGtin> catalog(boolean remote){return List.of(card);}
            public OzonExistingCardLinkService.Review read(GtinSyncModels.ProductKey k,String gtin){return new OzonExistingCardLinkService.Review(k,gtin,91,"9001",row.title(),row.vendorArticle(),row.color(),row.size(),card.article(),card.color(),card.size(),"6112110000",ListingIdentity.fingerprint(shop),card.source(),"a".repeat(64),"b".repeat(64));}
            public void approve(List<OzonExistingCardLinkService.Review> rows){assertEquals(1,rows.size());approvals.incrementAndGet();}
        };
        fx(()->{pane=new OzonCatalogVariantLinkDialog.Review(shop,List.of(key),()->{},backend);stage=new Stage();var scene=new Scene(pane,1100,700);ThemeService.applyTheme(scene);stage.setScene(scene);stage.show();return null;});
        until(()->!pane.busy&&!pane.catalog.isEmpty());
    }
    @Test void proposingAndPreviewingCannotSaveWithoutTheSellerConfirmation()throws Exception{
        open();fx(()->{pane.suggest.fire();assertNotNull(pane.table.getItems().getFirst().chosen);assertEquals(0,approvals.get());assertTrue(pane.apply.isDisabled());pane.preview.fire();return null;});
        until(()->!pane.busy&&!pane.reviewed.isEmpty());fx(()->{assertTrue(pane.apply.isDisabled());assertTrue(pane.gtin.isDisabled());pane.confirmed.fire();assertFalse(pane.apply.isDisabled());pane.apply.fire();return null;});
        until(()->!pane.busy&&!pane.approved.isEmpty());assertEquals(1,approvals.get());fx(()->{assertFalse(pane.gtin.isDisabled());assertTrue(pane.apply.isDisabled());return null;});
    }
    @Test void changingTheSuggestedPairInvalidatesTheReviewedApproval()throws Exception{
        open();fx(()->{pane.suggest.fire();pane.preview.fire();return null;});until(()->!pane.busy&&!pane.reviewed.isEmpty());
        fx(()->{pane.confirmed.fire();pane.suggest.fire();assertTrue(pane.reviewed.isEmpty());assertFalse(pane.confirmed.isSelected());assertTrue(pane.apply.isDisabled());assertTrue(pane.gtin.isDisabled());return null;});assertEquals(0,approvals.get());
    }
    @Test void darkAndLightReviewKeepTheListLargeAndAllActionsInsideTheViewport()throws Exception{
        open();fx(()->{pane.suggest.fire();return null;});
        for(var theme:ThemeService.Theme.values())fx(()->{
            ThemeService.setCurrentTheme(theme);stage.setWidth(1000);stage.setHeight(680);pane.applyCss();pane.layout();assertTrue(pane.table.getHeight()>300);assertTrue(pane.gtin.localToScene(pane.gtin.getBoundsInLocal()).getMaxY()<=stage.getScene().getHeight()+1);
            String output=System.getProperty("vncode.review.capture");if(output!=null){var target=Path.of(output);java.nio.file.Files.createDirectories(target);var image=stage.getScene().snapshot(null);var data=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<data.getHeight();y++)for(int x=0;x<data.getWidth();x++)data.setRGB(x,y,image.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(data,"png",target.resolve("ozon-catalog-link-"+theme.getKey()+".png").toFile());}return null;
        });
    }
}
