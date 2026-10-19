package com.vncode.app.ui.supply;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbShippingHistoryPaneTest {
    @TempDir Path root;Shop shop;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException ready){}}
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    static <T>T fx(Callable<T> call)throws Exception{var task=new FutureTask<T>(call);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @Test void earlyDeliveryClickWaitsOffFxAndKeepsItsOriginalSupplyWhenUiChanges()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var api=new WbApiClient(){
            @Override public WbSupplyDto getSupplyDetail(String token,String id){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return new Gson().fromJson("{\"id\":\"first\",\"shippingPointId\":123,\"shippingType\":\"selfShipping\"}",WbSupplyDto.class);}
            @Override public WbSuppliesResponse getSupplies(String token,long next,int limit){return new Gson().fromJson("{\"next\":0,\"supplies\":[]}",WbSuppliesResponse.class);}
        };
        var pane=fx(()->new WbShippingPane(new WbShippingHistoryService(api,new WbShippingPreferenceService())));
        try {
            var prepared=fx(()->{pane.setContext(shop,"first");return pane.prepareSelection();});assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(prepared.isDone());
            fx(()->{pane.setContext(null,null);assertFalse(pane.isVisible());return null;});release.countDown();
            var selection=prepared.get(10,TimeUnit.SECONDS);assertEquals("first",selection.parameters().supplyId());assertEquals(123,selection.parameters().shippingPointId());assertEquals(LocalDate.now(),selection.parameters().shippingDate());
            fx(()->{assertNull(((ComboBox<?>)pane.lookup("#shippingHistoryPoint")).getValue());pane.dispose();return null;});
        }finally{release.countDown();}
    }
    @Test void savedSuccessfulLocationIsSelectedWithoutCountryVehicleOrDateInputs()throws Exception {
        new WbShippingPreferenceService().remember(shop,new WbShippingPreferenceService.Location(123,"transportCompany",null,"Historical fixture address"));
        var pane=fx(WbShippingPane::new);
        var selected=fx(()->{pane.setContext(shop,"current");return pane.prepareSelection();}).get(10,TimeUnit.SECONDS);
        assertEquals(123,selected.parameters().shippingPointId());assertEquals("transportCompany",selected.parameters().shippingType());
        fx(()->{assertEquals(1,pane.getChildren().stream().filter(ComboBox.class::isInstance).count());assertTrue(pane.lookupAll(".text-field").isEmpty());pane.dispose();return null;});
    }
}
