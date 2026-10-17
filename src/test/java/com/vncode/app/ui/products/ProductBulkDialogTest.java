package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductBulkDialogTest {
 @TempDir Path root;Shop shop=new Shop(1,"Fixture shop","fixture");ShopReadCoordinator reads;ProductBulkEditPane pane;Adapter adapter;ProductBulkEditService service;
 List<ProductKey> keys=List.of(new ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"123",""),new ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"124",""));
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 @BeforeEach void setup()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture shop','fixture')");}
  reads=new ShopReadCoordinator(2,64);adapter=new Adapter();ProductSnapshotReader reader=(s,k)->snapshot(k);service=new ProductBulkEditService(new ProductEditRepository(),reader,s->adapter);
  var suggestions=new ProductSuggestionService(reader,s->adapter,k->Optional.of(snapshot(k)));fx(()->{pane=new ProductBulkEditPane(service,reads,suggestions);new Scene(pane,850,580);return null;});
 }
 ProductSnapshot snapshot(ProductKey key){return new ProductSnapshot(key,"live:fixture","Fixture","Keep description","Old","article-"+key.cardId(),"9",List.of(),List.of(),List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));}
 void idle()throws Exception{int stable=0;for(int i=0;i<500;i++){Thread.sleep(10);fx(()->null);if(reads.activeCount()==0&&reads.pendingCount()==0&&SellerMutationExecutor.activeCount()==0){if(++stable==3)return;}else stable=0;}fail("Batch edit did not settle");}
 @AfterEach void clean()throws Exception{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 @Test void applyBrandOnceUpdatesEverySelectedCardWithoutPreviewOrPerCardPrompts()throws Exception{
  fx(()->{pane.open(shop,"brand",keys);pane.compact();pane.applyCss();pane.layout();assertNull(pane.lookup("#editResults"));assertNull(pane.lookup("#editPreview"));assertNull(pane.lookup("#editConfirm"));((TextField)pane.lookup("#editValue")).setText("New brand");((Button)pane.lookup("#editApply")).fire();return null;});idle();
  assertEquals(2,adapter.sent.size());assertTrue(adapter.sent.stream().allMatch(r->r.contains("New brand")));var result=service.result(shop,service.list(shop).getFirst());assertEquals(2,result.items().size());assertTrue(result.items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
 }
 @Test void genderAndMaterialAreBatchPickersAndDocumentsHaveVisibleCalendars()throws Exception{
  fx(()->{pane.open(shop,"gender",keys);pane.compact();pane.applyCss();pane.layout();assertEquals(5,pane.lookupAll(".radio-button").size());assertNull(pane.lookup("#editResults"));pane.open(shop,"material",keys);pane.compact();pane.applyCss();pane.layout();assertNotNull(pane.lookup("#bulkMaterialName"));assertNotNull(pane.lookup("#bulkMaterialPercent"));pane.open(shop,"documents",keys);pane.compact();pane.applyCss();pane.layout();assertNotNull(pane.lookup("#bulkExistingDocument"));assertNotNull(pane.lookup("#editDocumentFrom"));assertNotNull(pane.lookup("#editDocumentUntil"));return null;});idle();
 }
 class Adapter implements ListingTargetAdapter {
  List<String> sent=new CopyOnWriteArrayList<>();
  public List<TargetCategory> categories(Shop s){return List.of();}public List<TargetAttribute> attributes(Shop s,String c){return List.of();}
  public List<String> suggestValues(Shop s,String c,String f,String q){return List.of("New brand");}
  public CopyPreview preview(Shop s,ProductSnapshot p,CopyOptions o){throw new UnsupportedOperationException();}
  public ProductEditPreview previewUpdate(Shop s,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> docs){String body=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var options=new CopyOptions(changes.keySet(),Set.of(),fresh.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);return new ProductEditPreview(fresh.key(),fresh.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,fresh.key(),fresh.revision(),CopyPreview.hash(body,"[]","[]",owner,fresh.revision(),"UPDATE"),body,"[]","[]",Map.of(),List.of(),List.of(fresh.vendorArticle()),options,"UPDATE"));}
  public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){sent.add(p.preview().requestJson());return submit(s,p.preview());}
  public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
  public RemoteResult reconcile(Shop s,RemoteSubmission r){return new RemoteResult(CopyState.RECONCILED,List.of("fixture"),List.of(),Map.of());}
  public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r;}
 }
}
