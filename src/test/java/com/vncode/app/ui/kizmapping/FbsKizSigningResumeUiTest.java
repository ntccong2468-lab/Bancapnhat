package com.vncode.app.ui.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventorySummary;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.supply.SupplyDetailController;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real WB/Ozon FBS cards must resume the paid lot rather than propose another buy. */
class FbsKizSigningResumeUiTest {
    private static final String GTIN="04601234567890";
    private static final String WAITING="Waiting for the user to select this shop before signing.";
    @TempDir Path profile;
    private String previousProfile;
    private AppLanguage previousLanguage;
    private Object controller;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    @BeforeEach void initialize()throws Exception{
        previousProfile=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        previousLanguage=I18nService.getInstance().getCurrentLanguage();
        sql("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic WB','WILDBERRIES','','fixture-wb'),(2,'Synthetic Ozon','OZON','22','fixture-ozon')");
        for(int owner:List.of(1,2)){
            sql("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES("+owner+",'"+GTIN+"','Synthetic product','2026-11-16T00:00:00Z')");
            sql("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES("+owner+","+owner+",'"+GTIN+"',1,'CODES_DOWNLOADED','2026-11-16','2026-11-16')");
            sql("INSERT INTO znack_purchase_pipelines(id,shop_id,gtin,quantity,stage,order_id,error_message,created_at,updated_at) VALUES("+owner+","+owner+",'"+GTIN+"',1,'WAITING_INTRODUCTION_READINESS',"+owner+",'"+WAITING+"','2026-11-16','2026-11-16')");
        }
    }
    @AfterEach void cleanup()throws Exception{
        fx(()->{
            if(controller!=null)controller.getClass().getMethod("dispose").invoke(controller);
            for(Window window:List.copyOf(Window.getWindows()))window.hide();
            I18nService.getInstance().setLanguage(previousLanguage);return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        if(previousProfile==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousProfile);
    }
    static Stream<Arguments> cardsAndLanguages(){return Stream.of(Marketplace.WILDBERRIES,Marketplace.OZON).flatMap(m->Stream.of(AppLanguage.values()).map(l->Arguments.of(m,l)));}

    @ParameterizedTest @MethodSource("cardsAndLanguages")
    void pausedPurchaseShowsOneResumeAndNoNewBuy(Marketplace market,AppLanguage language)throws Exception{
        int owner=owner(market);var summary=new KizMappingRepository().findGtinSummaries(owner).getFirst();
        assertEquals(0,summary.available());
        fx(()->{
            I18nService.getInstance().setLanguage(language);var card=card(market,summary);
            String resume=I18nService.getInstance().tr("kiz_mapping.action.resume_purchase");
            var buttons=card.lookupAll(".button").stream().map(Button.class::cast).toList();
            assertEquals(1,buttons.stream().filter(b->b.isVisible()&&b.isManaged()&&resume.equals(b.getAccessibleText())).count(),
                    "The FBS card needs the same explicit recovery action as the central KIZ page");
            var buy=buttons.stream().filter(b->I18nService.getInstance().tr("supply.gtin_inventory.buy").equals(b.getAccessibleText())).findFirst().orElseThrow();
            assertTrue(buy.isDisabled());assertFalse(buy.isVisible());assertFalse(buy.isManaged());
            String paused=I18nService.getInstance().tr("znack.status_value.signing_paused");
            assertTrue(card.lookupAll(".label").stream().map(Label.class::cast).anyMatch(l->paused.equals(l.getText())),
                    "The card must explain why circulation is paused instead of showing ordinary processing");
            return null;
        });
        assertEquals(2,scalar("SELECT count(*) FROM kiz_orders"));assertEquals(2,scalar("SELECT count(*) FROM znack_purchase_pipelines"));
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void unpausedCardsRetainBuyAndExistingIntroductionRetry(Marketplace market)throws Exception{
        var row=new ZnackGtinInventorySummary(GTIN,"Synthetic product","",0,0,0,1,0,"",(long)owner(market),"INTRODUCTION_FAILED","SYNTHETIC_REJECTION",null);
        fx(()->{
            var card=card(market,row);var buttons=card.lookupAll(".button").stream().map(Button.class::cast).toList();
            var buy=buttons.stream().filter(b->I18nService.getInstance().tr("supply.gtin_inventory.buy").equals(b.getAccessibleText())).findFirst().orElseThrow();
            assertTrue(buy.isVisible());assertTrue(buy.isManaged());assertFalse(buy.isDisabled());
            assertEquals(1,buttons.stream().filter(b->b.isVisible()&&b.isManaged()&&I18nService.getInstance().tr("kiz_mapping.action.retry_introduction").equals(b.getAccessibleText())).count());return null;
        });
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void resumeClickTargetsTheExistingDownloadedLotAndDebouncesDoubleClick(Marketplace market)throws Exception{
        int owner=owner(market);
        sql("UPDATE znack_purchase_pipelines SET stage='DOWNLOADING_CODES',error_message='"+WAITING+" [CRYPTOPRO_CANCELLED]' WHERE shop_id="+owner);
        var summary=new KizMappingRepository().findGtinSummaries(owner).getFirst();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var requestedId=new AtomicLong(-1);var requestedRepository=new AtomicReference<ZnackRepository>();
        try{
            fx(()->{
                var card=card(market,summary);
                resumer((repository,pipeline)->{
                    requestedRepository.set(repository);requestedId.set(pipeline);calls.incrementAndGet();entered.countDown();
                    assertTrue(release.await(10,TimeUnit.SECONDS));
                });
                var resume=resume(card);resume.fire();resume.fire();return null;
            });
            assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(1,calls.get());
            assertEquals(summary.latestPipelineId().longValue(),requestedId.get());
            assertEquals(owner,requestedRepository.get().shop().shopId());
            assertEquals(2,scalar("SELECT count(*) FROM kiz_orders"));assertEquals(2,scalar("SELECT count(*) FROM znack_purchase_pipelines"));
            assertEquals(1,scalar("SELECT count(*) FROM znack_purchase_pipelines WHERE shop_id="+owner+" AND id="+owner+" AND order_id="+owner+" AND stage='DOWNLOADING_CODES'"));
        }finally{release.countDown();}
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        assertEquals(2,scalar("SELECT count(*) FROM kiz_orders"));assertEquals(2,scalar("SELECT count(*) FROM znack_purchase_pipelines"));
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void disposedCardCannotContinueSigning(Marketplace market)throws Exception{
        var summary=new KizMappingRepository().findGtinSummaries(owner(market)).getFirst();var calls=new AtomicInteger();
        fx(()->{
            var card=card(market,summary);resumer((repository,pipeline)->calls.incrementAndGet());
            controller.getClass().getMethod("dispose").invoke(controller);resume(card).fire();return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();assertEquals(0,calls.get());
        assertEquals(2,scalar("SELECT count(*) FROM znack_purchase_pipelines"));
    }

    private VBox card(Marketplace market,ZnackGtinInventorySummary summary)throws Exception{
        Class<?> type=market==Marketplace.OZON?OzonDashboardController.class:SupplyDetailController.class;
        var loader=FxmlViewLoader.loader(type,market==Marketplace.OZON?"ozon-dashboard-view.fxml":"supply-detail-view.fxml");
        Parent actualView=FxmlViewLoader.load(loader);controller=loader.getController();
        int id=owner(market);var shop=new Shop(id,"Synthetic",market,market==Marketplace.OZON?"22":"",market==Marketplace.OZON?"fixture-ozon":"fixture-wb");
        set("shop",shop);set("znackRepository",new ZnackRepository(new ZnackModels.ShopContext(id,"Synthetic")));
        // Bind local foreground data only: setShop would intentionally start real background services.
        var method=type.getDeclaredMethod("createGtinCard",ZnackGtinInventorySummary.class);method.setAccessible(true);
        var card=(VBox)method.invoke(controller,summary);var stage=new Stage();stage.setScene(new Scene(new VBox(actualView,card),1300,900));
        com.vncode.app.shared.ThemeService.applyTheme(stage.getScene());stage.show();
        card.applyCss();card.layout();return card;
    }
    private Button resume(VBox card){return card.lookupAll(".button").stream().map(Button.class::cast)
            .filter(b->b.isVisible()&&b.isManaged()&&I18nService.getInstance().tr("kiz_mapping.action.resume_purchase").equals(b.getAccessibleText())).findFirst().orElseThrow();}
    @FunctionalInterface private interface Resume{void run(ZnackRepository repository,long pipelineId)throws Exception;}
    private void resumer(Resume callback)throws Exception{
        Method setter=Stream.of(controller.getClass().getDeclaredMethods()).filter(m->m.getName().equals("setPurchaseResumer")).findFirst().orElseThrow();
        Class<?> type=setter.getParameterTypes()[0];Object proxy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,method,args)->{
            if(method.getName().equals("resume")){callback.run((ZnackRepository)args[0],(long)args[1]);return null;}
            if(method.getName().equals("toString"))return "SyntheticPurchaseResumer";
            throw new UnsupportedOperationException(method.getName());
        });
        setter.setAccessible(true);setter.invoke(controller,proxy);
    }
    private void set(String field,Object value)throws Exception{var f=controller.getClass().getDeclaredField(field);f.setAccessible(true);f.set(controller,value);}
    private static int owner(Marketplace market){return market==Marketplace.OZON?2:1;}
    private void sql(String statement)throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.execute(statement);}}
    private int scalar(String statement)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(statement)){assertTrue(r.next());return r.getInt(1);}}
    private static <T>T fx(Callable<T> work)throws Exception{
        var task=new FutureTask<T>(work);Platform.runLater(task);
        try{return task.get(20,TimeUnit.SECONDS);}
        catch(ExecutionException failure){
            if(failure.getCause() instanceof Error error)throw error;
            if(failure.getCause() instanceof Exception error)throw error;
            throw failure;
        }
    }
}
