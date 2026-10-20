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
 @TempDir Path root;Shop shop=new Shop(1,"Fixture shop","fixture");ShopReadCoordinator reads;ProductBulkEditPane pane;Adapter adapter;ProductBulkEditService service;List<ProductAttribute> fixtureAttributes=List.of();
 List<ProductKey> keys=List.of(new ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"123",""),new ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"124",""));
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 @BeforeEach void setup()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture shop','fixture')");}
  reads=new ShopReadCoordinator(2,64);adapter=new Adapter();ProductSnapshotReader reader=(s,k)->snapshot(k);service=new ProductBulkEditService(new ProductEditRepository(),reader,s->adapter);
  var suggestions=new ProductSuggestionService(reader,s->adapter,k->Optional.of(snapshot(k)));fx(()->{pane=new ProductBulkEditPane(service,reads,suggestions);new Scene(pane,850,580);return null;});
 }
 ProductSnapshot snapshot(ProductKey key){return new ProductSnapshot(key,"live:fixture","Fixture","Keep description","Old","article-"+key.cardId(),"9",List.of(),fixtureAttributes,List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));}
 void idle()throws Exception{int stable=0;for(int i=0;i<500;i++){Thread.sleep(10);fx(()->null);if(reads.activeCount()==0&&reads.pendingCount()==0&&SellerMutationExecutor.activeCount()==0){if(++stable==3)return;}else stable=0;}fail("Batch edit did not settle");}
 @AfterEach void clean()throws Exception{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 @Test void defaultProposalNeedsOnlyApplyForWb()throws Exception{defaultProposalNeedsOnlyApply(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);}
 @Test void defaultProposalNeedsOnlyApplyForOzon()throws Exception{defaultProposalNeedsOnlyApply(com.vncode.app.integration.marketplace.Marketplace.OZON);}
 void defaultProposalNeedsOnlyApply(com.vncode.app.integration.marketplace.Marketplace marketplace)throws Exception{
  shop=new Shop(1,"Fixture shop",marketplace,marketplace==com.vncode.app.integration.marketplace.Marketplace.OZON?"fixture-client":null,"fixture");
  keys=List.of(new ProductKey(1,marketplace,"123",""),new ProductKey(1,marketplace,"124",""));
  try(var c=Database.getConnection();var statement=c.prepareStatement("UPDATE shops SET marketplace=?,client_id=? WHERE id=1")){statement.setString(1,marketplace.name());statement.setString(2,shop.getClientId());statement.executeUpdate();}
  fx(()->{pane.open(shop,"brand",keys);pane.compact();pane.applyCss();pane.layout();return null;});idle();
  fx(()->{assertEquals("Old",((TextField)pane.lookup("#editValue")).getText());var button=(Button)pane.lookup("#editApply");assertFalse(button.isDisabled());button.fire();return null;});idle();
  assertEquals(2,adapter.sent.size());assertTrue(service.result(shop,service.list(shop).getFirst()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
 }
 @Test void genderAndCompositionAreAutomaticallyProposedFromSelectedProducts()throws Exception{
  fixtureAttributes=List.of(new ProductAttribute("1","Пол",List.of("Мужской"),""),new ProductAttribute("2","Состав",List.of("хлопок 80%, полиэстер 20%"),""));
  fx(()->{pane.open(shop,"gender",keys);pane.compact();pane.applyCss();pane.layout();return null;});idle();
  fx(()->{assertTrue(pane.lookupAll(".radio-button").stream().map(n->(RadioButton)n).anyMatch(r->r.isSelected()&&r.getText().contains("Мужской")));assertFalse(((Button)pane.lookup("#editApply")).isDisabled());pane.open(shop,"material",keys);pane.compact();pane.applyCss();pane.layout();return null;});idle();
  fx(()->{assertEquals(2,pane.lookupAll("#bulkMaterialName").size());assertFalse(((Button)pane.lookup("#editApply")).isDisabled());return null;});
 }
 @Test void applyBrandOnceUpdatesEverySelectedCardWithoutPreviewOrPerCardPrompts()throws Exception{
  fx(()->{pane.open(shop,"brand",keys);pane.compact();pane.applyCss();pane.layout();assertNull(pane.lookup("#editResults"));assertNull(pane.lookup("#editPreview"));assertNull(pane.lookup("#editConfirm"));((TextField)pane.lookup("#editValue")).setText("New brand");((Button)pane.lookup("#editApply")).fire();return null;});idle();
  assertEquals(2,adapter.sent.size());assertTrue(adapter.sent.stream().allMatch(r->r.contains("New brand")));var result=service.result(shop,service.list(shop).getFirst());assertEquals(2,result.items().size());assertTrue(result.items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
 }
 @Test void genderAndMaterialAreBatchPickersAndDocumentsHaveVisibleCalendars()throws Exception{
  fx(()->{pane.open(shop,"gender",keys);pane.compact();pane.applyCss();pane.layout();assertEquals(5,pane.lookupAll(".radio-button").size());assertNull(pane.lookup("#editResults"));pane.open(shop,"material",keys);pane.compact();pane.applyCss();pane.layout();assertNotNull(pane.lookup("#bulkMaterialName"));assertNotNull(pane.lookup("#bulkMaterialPercent"));pane.open(shop,"documents",keys);pane.compact();pane.applyCss();pane.layout();assertNotNull(pane.lookup("#bulkExistingDocument"));assertNotNull(pane.lookup("#editDocumentFrom"));assertNotNull(pane.lookup("#editDocumentUntil"));return null;});idle();
 }
 @Test void actualModalUsesOneLocalizedActionRowAndHasNoPreviewInstructions()throws Exception{
  var language=I18nService.getInstance().getCurrentLanguage();
  try{fx(()->{
   I18nService.getInstance().setLanguage(AppLanguage.VI);pane.getScene().setRoot(new javafx.scene.layout.StackPane());pane.open(shop,"brand",keys);pane.compact();
   var show=ProductBulkEditPane.class.getDeclaredMethod("showCompact",javafx.stage.Window.class,Runnable.class);show.setAccessible(true);show.invoke(pane,null,(Runnable)()->{});return null;
  });idle();
  fx(()->{
   var window=javafx.stage.Window.getWindows().stream().filter(w->w.getScene()!=null&&w.getScene().getRoot() instanceof DialogPane d&&"productBulkEditDialog".equals(d.getId())).findFirst().orElseThrow();var dialog=(DialogPane)window.getScene().getRoot();dialog.applyCss();dialog.layout();
   var applyType=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();var cancelType=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();var apply=(Button)dialog.lookupButton(applyType);var cancel=(Button)dialog.lookupButton(cancelType);
   assertEquals("Áp dụng",apply.getText());assertEquals("Hủy",cancel.getText());assertEquals(apply.localToScene(apply.getLayoutBounds()).getCenterY(),cancel.localToScene(cancel.getLayoutBounds()).getCenterY(),2);
   assertTrue(dialog.lookupAll(".label").stream().map(n->((Label)n).getText().toLowerCase(java.util.Locale.ROOT)).noneMatch(text->text.contains("xem trước")));((TextField)dialog.lookup("#editValue")).setText("Modal brand");apply.fire();return null;
  });idle();assertEquals(2,adapter.sent.size());assertTrue(adapter.sent.stream().allMatch(payload->payload.contains("Modal brand")));
  fx(()->{for(var window:java.util.List.copyOf(javafx.stage.Window.getWindows()))if(window.getScene()!=null&&window.getScene().getRoot() instanceof DialogPane dialog&&"productBulkEditDialog".equals(dialog.getId())){var cancel=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)dialog.lookupButton(cancel)).fire();}return null;});}finally{fx(()->{I18nService.getInstance().setLanguage(language);return null;});}
 }
 @Test void choosingAnExistingNumericCertificateKeepsItsIdentityAndMetadata()throws Exception{
  var before=snapshot(keys.getFirst());var document=new ProductDocument("existing-doc","1","CERT-42",java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusYears(1),"Existing applicant","scope",null,shop.getMarketplace(),DocumentState.UNVERIFIED,"",null,Map.of("applicant","Existing applicant","tradeName","Original trade name","productNumber","Original product number"));
  new ProductRepository().saveVerifiedSnapshot(shop,new ProductSnapshot(before.key(),before.revision(),before.title(),before.description(),before.brand(),before.vendorArticle(),before.categoryId(),before.media(),before.attributes(),before.variants(),before.dimensions(),List.of(document),before.checkedAt(),before.metadata()));
  fx(()->{pane.open(shop,"documents",keys);pane.compact();pane.applyCss();pane.layout();return null;});idle();
  fx(()->{var existing=(ComboBox<ProductDocument>)pane.lookup("#bulkExistingDocument");assertEquals(1,existing.getItems().size());assertNotNull(existing.getValue());assertFalse(((Button)pane.lookup("#editApply")).isDisabled());existing.getSelectionModel().selectFirst();assertTrue(pane.lookupAll(".radio-button").stream().map(n->(RadioButton)n).anyMatch(r->r.getText().startsWith("Сертификат")&&r.isSelected()));pane.lookupAll(".radio-button").stream().map(n->(RadioButton)n).filter(r->r.getText().startsWith("Сертификат")).findFirst().orElseThrow().fire();var method=ProductBulkEditPane.class.getDeclaredMethod("edit");method.setAccessible(true);var edit=(ProductBulkEdit)method.invoke(pane);var retained=edit.documents().getFirst();assertEquals("existing-doc",retained.id());assertEquals("1",retained.type());assertEquals("Original trade name",retained.metadata().get("tradeName"));assertEquals("Original product number",retained.metadata().get("productNumber"));assertEquals("Existing applicant",retained.owner());pane.lookupAll(".check-box").stream().map(n->(CheckBox)n).findFirst().orElseThrow().setSelected(true);var noExpiry=((ProductBulkEdit)method.invoke(pane)).documents().getFirst();assertNull(noExpiry.validUntil());assertEquals("true",noExpiry.metadata().get("isEndless"));return null;});
 }
 @Test void documentPickerIncludesOtherCardsOfTheShopButExcludesAnotherShop()throws Exception{
  var second=snapshot(keys.get(1));var doc=new ProductDocument("other-card-doc","2","DECL-88",java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusYears(1),"","",null,shop.getMarketplace(),DocumentState.UNVERIFIED,"",null);
  new ProductRepository().saveVerifiedSnapshot(shop,new ProductSnapshot(second.key(),second.revision(),second.title(),second.description(),second.brand(),second.vendorArticle(),second.categoryId(),second.media(),second.attributes(),second.variants(),second.dimensions(),List.of(doc),second.checkedAt(),second.metadata()));
  var other=new Shop(2,"Other shop","other-fixture");try(var connection=Database.getConnection();var statement=connection.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(2,'Other shop','other-fixture')");}
  var foreign=new ProductKey(2,shop.getMarketplace(),"999","");new ProductRepository().saveVerifiedSnapshot(other,new ProductSnapshot(foreign,"live:foreign","Other","","","other","9",List.of(),List.of(),List.of(),second.dimensions(),List.of(new ProductDocument("foreign-doc","2","FOREIGN-99",doc.validFrom(),doc.validUntil(),"","",null,other.getMarketplace(),DocumentState.UNVERIFIED,"",null)),second.checkedAt(),Map.of("owner",ListingIdentity.fingerprint(other))));
  fx(()->{pane.open(shop,"documents",List.of(keys.getFirst()));pane.compact();pane.applyCss();pane.layout();return null;});idle();
  fx(()->{var picker=(ComboBox<ProductDocument>)pane.lookup("#bulkExistingDocument");assertEquals(List.of("DECL-88"),picker.getItems().stream().map(ProductDocument::number).toList());return null;});
 }
 @Test void materialPercentagesDoNotFilterAwayTheIngredientDictionary()throws Exception{
  fx(()->{pane.open(shop,"material",keys);pane.compact();pane.applyCss();pane.layout();var box=(ComboBox<String>)pane.lookup("#bulkMaterialName");box.getEditor().setText("хлопок");((Spinner<Integer>)pane.lookup("#bulkMaterialPercent")).getValueFactory().setValue(80);return null;});
  Thread.sleep(450);idle();fx(()->{var box=(ComboBox<String>)pane.lookup("#bulkMaterialName");assertTrue(box.getItems().contains("полиэстер"));assertEquals("хлопок",box.getEditor().getText());return null;});
 }
 @Test void acceptedBatchFreezesInputsUntilTheWriteCompletes()throws Exception{
  adapter.entered=new CountDownLatch(1);adapter.release=new CountDownLatch(1);
  try{
   fx(()->{pane.open(shop,"brand",keys);pane.compact();pane.applyCss();pane.layout();((TextField)pane.lookup("#editValue")).setText("Accepted brand");((Button)pane.lookup("#editApply")).fire();return null;});
   assertTrue(adapter.entered.await(5,TimeUnit.SECONDS));fx(()->{assertTrue(pane.lookup("#editValue").isDisabled());assertTrue(pane.lookup("#editApply").isDisabled());return null;});
  }finally{adapter.release.countDown();}idle();assertEquals(2,adapter.sent.size());
 }
 @Test void applyingClosesPickerAndShowsRealProgressBelowProductList()throws Exception{
  adapter.entered=new CountDownLatch(1);adapter.release=new CountDownLatch(1);var host=new ProductsPane[1];var stage=new javafx.stage.Stage[1];
  try{
   fx(()->{
    host[0]=new ProductsPane(q->new ProductPage(List.of(),0,""),reads);host[0].setShop(shop);stage[0]=new javafx.stage.Stage();stage[0].setScene(new Scene(host[0],1000,650));stage[0].show();
    pane.open(shop,"brand",keys);pane.compact();var field=ProductBulkEditPane.class.getDeclaredField("backgroundHost");field.setAccessible(true);field.set(pane,host[0]);
    var show=ProductBulkEditPane.class.getDeclaredMethod("showCompact",javafx.stage.Window.class,Runnable.class);show.setAccessible(true);show.invoke(pane,stage[0],(Runnable)()->{});
    return null;
   });
   fx(()->{
    var window=javafx.stage.Window.getWindows().stream().filter(w->w.getScene()!=null&&w.getScene().getRoot() instanceof DialogPane d&&"productBulkEditDialog".equals(d.getId())).findFirst().orElseThrow();
    var dialog=(DialogPane)window.getScene().getRoot();
    var type=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();var apply=(Button)dialog.lookupButton(type);

    ((TextField)dialog.lookup("#editValue")).setText("Background brand");
    var confirmation=((Label)dialog.lookup("#bulkEditConfirmation")).getText();
    assertTrue(confirmation.contains("Background brand"));assertTrue(confirmation.contains(shop.getName()));assertTrue(confirmation.contains("2"));assertTrue(confirmation.contains("WB"));assertFalse(apply.isDisabled());
    apply.fire();
    assertFalse(window.isShowing());assertNotNull(host[0].lookup("#productBulkProgressPill"));return null;
   });
   assertTrue(adapter.entered.await(5,TimeUnit.SECONDS));
   fx(()->{host[0].applyCss();host[0].layout();var pill=host[0].lookup("#productBulkProgressPill");var list=host[0].lookup("#productRows");var p=pill.localToScene(pill.getBoundsInLocal());var l=list.localToScene(list.getBoundsInLocal());assertEquals(l.getCenterX(),p.getCenterX(),3);assertTrue(p.getMaxY()<=l.getMaxY());assertTrue(p.getMaxY()>l.getMinY()+l.getHeight()/2);assertTrue(pane.backgroundRunning().get());return null;});
  }finally{adapter.release.countDown();}
  idle();assertEquals(2,adapter.sent.size());fx(()->{assertFalse(pane.backgroundRunning().get());assertTrue(pane.progressText().get().contains(I18nService.getInstance().tr("edit.saved")));host[0].dispose();stage[0].close();return null;});
 }
 class Adapter implements ListingTargetAdapter {
  List<String> sent=new CopyOnWriteArrayList<>();CountDownLatch entered,release;
  public List<TargetCategory> categories(Shop s){return List.of();}public List<TargetAttribute> attributes(Shop s,String c){return List.of();}
  public List<String> suggestValues(Shop s,String c,String f,String q){return List.of("New brand");}
  public CopyPreview preview(Shop s,ProductSnapshot p,CopyOptions o){throw new UnsupportedOperationException();}
  public ProductEditPreview previewUpdate(Shop s,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> docs){String body=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var options=new CopyOptions(changes.keySet(),Set.of(),fresh.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);return new ProductEditPreview(fresh.key(),fresh.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,fresh.key(),fresh.revision(),CopyPreview.hash(body,"[]","[]",owner,fresh.revision(),"UPDATE"),body,"[]","[]",Map.of(),List.of(),List.of(fresh.vendorArticle()),options,"UPDATE"));}
  public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){sent.add(p.preview().requestJson());if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for test release");}catch(InterruptedException error){throw new AssertionError(error);}}return submit(s,p.preview());}
  public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
  public RemoteResult reconcile(Shop s,RemoteSubmission r){return new RemoteResult(CopyState.RECONCILED,List.of("fixture"),List.of(),Map.of());}
  public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r;}
 }
}
