package com.vncode.app.ui.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncControllerTest {
    @TempDir Path temp;
    final GtinSyncRepository repository=new GtinSyncRepository();
    final RegisteredGtin first=new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now());
    final RegisteredGtin alternate=new RegisteredGtin("00000000000024","PANTS","Đen","L",true,true,"NK",Instant.now());
    final List<ProductSnapshot> products=List.of(
            ProductFingerprints.snapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","11"),"PANTS","Đen","XL",List.of("old","keep")),
            ProductFingerprints.snapshot(new ProductKey(1,Marketplace.WILDBERRIES,"102","12"),"PANTS","Đen","L",List.of("other")));
    FXMLLoader loader;GtinSyncController controller;
    CountDownLatch sendEntered=new CountDownLatch(1),releaseSend=new CountDownLatch(1);

    @BeforeAll static void toolkit()throws Exception {
        try{Platform.startup(()->{});}catch(IllegalStateException alreadyStarted){}
        fx(()->{Platform.setImplicitExit(false);return null;});
    }
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','test')");}
        repository.saveCatalog(1,Marketplace.WILDBERRIES,List.of(first,alternate));
    }
    @AfterEach void cleanup()throws Exception {
        releaseSend.countDown();
        if(controller!=null)fx(()->{controller.dispose();return null;});
        await(()->!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks());
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void previewUsesEditedOperationTargetAndOldBarcodeWithoutSavingMapping()throws Exception {
        load(null,List.of());
        fx(()->{
            table("productTable").getSelectionModel().select(0);
            this.<RegisteredGtin>combo("gtinBox").setValue(alternate);
            this.<Operation>combo("operationBox").setValue(Operation.REPLACE);
            this.<String>combo("oldBarcodeBox").setValue("old");
            button("previewButton").fire();return null;
        });
        await(()->!fxUnchecked(()->button("previewButton").isDisable()));
        String text=fx(()->((TextArea)loader.getNamespace().get("previewArea")).getText());
        assertTrue(text.contains("old → "+alternate.gtin()),text);
        assertFalse(text.contains("→ "+first.gtin()),text);
        assertEquals("",repository.mapping(products.getFirst().key()));
        assertTrue(repository.pending(1,Marketplace.WILDBERRIES).isEmpty());
        assertTrue(fx(()->button("confirmButton").isDisable()),"Unverified production adapter must remain blocked");
    }
    @Test void runningQueuePublishesStateAndAllowsCancellingUnsentRows()throws Exception {
        var cap=new Capability(true,true,"");
        UUID job=repository.createJob(products.stream().map(p->new Preview(p,Operation.ADD,"",p.key().productId().equals("101")?first.gtin():alternate.gtin(),cap)).toList());
        var adapter=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return cap;}
            public ProductSnapshot read(ProductKey key){return products.stream().filter(p->p.key().equals(key)).findFirst().orElseThrow();}
            public boolean conflicts(ProductKey key,String gtin){return false;}
            public Submission submit(Preview p)throws Exception{sendEntered.countDown();assertTrue(releaseSend.await(10,TimeUnit.SECONDS));return new Submission("fixture-task");}
            public Verification verify(Preview p,Submission submission){return Verification.APPLIED;}
        };
        load(adapter,repository.jobItems(job));
        fx(()->{table("historyTable").getSelectionModel().select(0);button("resumeButton").fire();return null;});
        assertTrue(sendEntered.await(10,TimeUnit.SECONDS));
        assertFalse(fx(()->button("pauseButton").isDisable()),"Pause must stay usable while a mutation is running");
        assertFalse(fx(()->button("cancelButton").isDisable()),"Cancel-unsent must stay usable while a mutation is running");
        await(()->fxUnchecked(()->this.<JobItem>table("historyTable").getItems().stream().anyMatch(i->i.status()==Status.SENDING)));
        fx(()->{button("cancelButton").fire();return null;});
        await(()->repository.jobItems(job).stream().anyMatch(i->i.status()==Status.CANCELLED));
        assertEquals(1,repository.jobItems(job).stream().filter(i->i.status()==Status.SENDING).count());
        releaseSend.countDown();
        await(()->repository.jobItems(job).stream().anyMatch(i->i.status()==Status.SUCCEEDED));
        assertEquals(1,repository.jobItems(job).stream().filter(i->i.status()==Status.CANCELLED).count());
    }
    private void load(GtinMarketplaceAdapter adapter,List<JobItem> history)throws Exception {
        fx(()->{
            loader=new FXMLLoader(GtinSyncController.class.getResource("gtin-sync-view.fxml"));
            if(adapter!=null){var workspace=new GtinSyncWorkspace(repository,key->adapter);loader.setControllerFactory(type->new GtinSyncController(workspace));}
            Parent root=loader.load();controller=loader.getController();controller.setShop(new Shop(1,"Fixture","test"));
            var field=GtinSyncController.class.getDeclaredField("model");field.setAccessible(true);var model=(GtinSyncViewModel)field.get(controller);
            var accept=GtinSyncController.class.getDeclaredMethod("accept",GtinSyncViewModel.LoadToken.class,GtinSyncWorkspace.Data.class);accept.setAccessible(true);
            accept.invoke(controller,model.token(),new GtinSyncWorkspace.Data(List.of(first,alternate),products,history,Map.of()));return null;
        });
        await(()->!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks());
    }
    private Button button(String id){return (Button)loader.getNamespace().get(id);}
    @SuppressWarnings("unchecked") private <T>TableView<T> table(String id){return (TableView<T>)loader.getNamespace().get(id);}
    @SuppressWarnings("unchecked") private <T>ComboBox<T> combo(String id){return (ComboBox<T>)loader.getNamespace().get(id);}
    private static <T>T fx(Callable<T> action)throws Exception {var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    private static <T>T fxUnchecked(Callable<T> action){try{return fx(action);}catch(Exception e){throw new AssertionError(e);}}
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Timed out awaiting asynchronous state");
    }
}
