package com.vncode.app.ui.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait;

/** Clicks the real editor through the real WB reader/adapter against a stateful seller fixture. */
class ProductEditInteractionTest {
    @TempDir Path root;
    ProductBulkEditPane pane;ShopReadCoordinator reads;Shop shop=new Shop(1,"Owner","fixture");
    ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"123","1");
    AtomicInteger writes=new AtomicInteger(),readbacks=new AtomicInteger();
    AtomicReference<String> brand=new AtomicReference<>("Old");
    CountDownLatch entered,release;boolean timeout,pending;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Owner','fixture','WILDBERRIES'),(2,'Other','fixture','WILDBERRIES')");}
        reads=new ShopReadCoordinator(2,64);
        var adapter=new WbListingAdapter(s->transport(),i->{throw new java.io.IOException();});
        ProductSnapshotReader reader=new WbProductSnapshotReader(s->transport());
        var service=new ProductBulkEditService(new ProductEditRepository(),reader,s->adapter);
        var hints=new ProductSuggestionService(reader,s->adapter,new ProductRepository()::localSnapshot);
        fx(()->{pane=new ProductBulkEditPane(service,reads,hints);new Scene(pane,1024,680);pane.open(shop,"brand",List.of(key));pane.applyCss();pane.layout();return null;});
    }
    ListingTransport transport(){return (path,body,mutation)->{
        if(path.equals("content/v2/cards/update")){
            assertTrue(mutation);writes.incrementAndGet();String want=body.getAsJsonArray().get(0).getAsJsonObject().get("brand").getAsString();
            if(!pending)brand.set(want);
            if(timeout)throw new java.io.IOException("lost response");return JsonParser.parseString("{\"error\":false}");
        }
        assertFalse(mutation);
        if(path.startsWith("api/content/v1/brands"))return JsonParser.parseString("{\"brands\":[{\"id\":7,\"name\":\"New\"}],\"next\":0}");
        if(path.startsWith("content/v2/object/charcs"))return JsonParser.parseString("{\"data\":[]}");
        if(path.startsWith("api/v2/list/goods"))return JsonParser.parseString("{\"data\":{\"listGoods\":[]}}");
        if(path.equals("content/v2/cards/error/list"))return JsonParser.parseString("{\"data\":{\"items\":[],\"cursor\":{\"next\":false}}}");
        if(!path.equals("content/v2/get/cards/list"))throw new java.io.IOException("Unexpected fixture route");
        if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture wait");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}}
        if(writes.get()>0)readbacks.incrementAndGet();
        var response=JsonParser.parseString("""
          {"cards":[{"nmID":123,"subjectID":9,"vendorCode":"own","title":"Keep","description":"Keep description","brand":"Old",
          "characteristics":[],"dimensions":{},"photos":[],"sizes":[{"chrtID":1,"techSize":"M","wbSize":"46","skus":[]}]}]}
          """).getAsJsonObject();response.getAsJsonArray("cards").get(0).getAsJsonObject().addProperty("brand",brand.get());return response;
    };}
    void waitFor(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){if(fx(condition))return;Thread.sleep(20);}fail("Editor did not reach expected state");}
    Button button(String id){return (Button)pane.lookup("#"+id);}
    TextField input(){return (TextField)pane.lookup("#editValue");}
    @AfterEach void cleanup()throws Exception{
        if(release!=null)release.countDown();fx(()->{pane.dispose();return null;});closeAndAwait(reads);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(SellerMutationExecutor.activeCount()>0&&System.nanoTime()<until)Thread.sleep(10);
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void choosingSuggestionThenSavingUpdatesRemoteAndVerifiedCache()throws Exception{
        waitFor(()->!((ListView<?>)pane.lookup("#editSuggestions")).getItems().isEmpty());
        fx(()->{var list=(ListView<?>)pane.lookup("#editSuggestions");list.getSelectionModel().selectFirst();button("editUseSuggestion").fire();assertEquals("New",input().getText());button("editPreview").fire();return null;});
        waitFor(()->!((CheckBox)pane.lookup("#editConfirm")).isDisabled());
        assertEquals(0,writes.get());
        fx(()->{assertTrue(button("editApply").isDisabled());((CheckBox)pane.lookup("#editConfirm")).setSelected(true);assertFalse(button("editApply").isDisabled());button("editApply").fire();return null;});
        waitFor(()->((Label)pane.lookup("#editStatus")).getText().equals(I18nService.getInstance().tr("edit.saved")));
        assertEquals(1,writes.get());assertTrue(readbacks.get()>0);assertEquals("New",brand.get());
        assertEquals("New",new ProductRepository().localSnapshot(key).orElseThrow().brand());
        fx(()->{assertTrue(button("editApply").isDisabled());return null;});
    }
    @Test void inputChangedWhilePreviewReadsCannotSaveTheOldValue()throws Exception{
        entered=new CountDownLatch(1);release=new CountDownLatch(1);
        fx(()->{input().setText("Before");button("editPreview").fire();return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
        fx(()->{input().setText("After");return null;});release.countDown();
        waitFor(()->!button("editPreview").isDisabled());
        fx(()->{assertTrue(button("editApply").isDisabled());assertTrue(((TableView<?>)pane.lookup("#editResults")).getItems().isEmpty());return null;});assertEquals(0,writes.get());
    }
    @Test void lostResponseReadsBackWithoutSendingAgain()throws Exception{
        timeout=true;fx(()->{input().setText("New");button("editPreview").fire();return null;});
        waitFor(()->!((CheckBox)pane.lookup("#editConfirm")).isDisabled());
        fx(()->{((CheckBox)pane.lookup("#editConfirm")).setSelected(true);button("editApply").fire();return null;});
        waitFor(()->((Label)pane.lookup("#editStatus")).getText().equals(I18nService.getInstance().tr("edit.saved")));
        assertEquals(1,writes.get());assertTrue(readbacks.get()>0);
    }
    @Test void remotePendingIsNotReportedAsSavedAndKeepsReconcileAvailable()throws Exception{
        pending=true;fx(()->{input().setText("New");button("editPreview").fire();return null;});
        waitFor(()->!((CheckBox)pane.lookup("#editConfirm")).isDisabled());
        fx(()->{((CheckBox)pane.lookup("#editConfirm")).setSelected(true);button("editApply").fire();return null;});
        waitFor(()->!button("editReconcile").isDisabled());
        fx(()->{assertEquals(I18nService.getInstance().tr("edit.readback_pending"),((Label)pane.lookup("#editStatus")).getText());assertTrue(button("editApply").isDisabled());button("editReconcile").fire();return null;});
        waitFor(()->!button("editReconcile").isDisabled());assertEquals(1,writes.get());assertTrue(readbacks.get()>1);
    }
    @Test void openingAnotherDocumentEditClearsAllMetadata()throws Exception{
        fx(()->{pane.open(shop,"documents",List.of(key));((TextField)pane.lookup("#editDocumentName")).setText("First document");((TextField)pane.lookup("#editDocumentRegistry")).setText("https://example.test/old");pane.open(shop,"documents",List.of(key));assertEquals("",((TextField)pane.lookup("#editDocumentName")).getText());assertEquals("",((TextField)pane.lookup("#editDocumentRegistry")).getText());return null;});
    }
}
