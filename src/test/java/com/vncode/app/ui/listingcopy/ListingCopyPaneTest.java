package com.vncode.app.ui.listingcopy;

import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ListingCopyPaneTest {
    @TempDir Path root;
    Shop wb=new Shop(1,"VN fashion","fixture"),oz=new Shop(2,"VN Ozon",Marketplace.OZON,"22","fixture");
    ShopReadCoordinator reads;ListingCopyPane pane;ListingCopyService service;FakeAdapter adapter;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'VN fashion','WILDBERRIES',NULL,'fixture'),(2,'VN Ozon','OZON','22','fixture')");}reads=new ShopReadCoordinator(2,64);adapter=new FakeAdapter();service=new ListingCopyService(new ListingCopyRepository(),(s,k)->source(k),s->adapter);fx(()->{pane=new ListingCopyPane(service,reads);new Scene(pane,1024,600);pane.applyCss();pane.layout();return null;});}
    @AfterEach void clear()throws Exception{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    void idle()throws Exception{int stable=0;for(int i=0;i<500&&stable<2;i++){Thread.sleep(10);fx(()->null);stable=reads.activeCount()==0&&reads.pendingCount()==0?stable+1:0;}}
    @Test void copyNavigationAndFourDirections()throws Exception{
        fx(()->{pane.setShop(wb);return null;});idle();assertEquals(Set.of(Marketplace.WILDBERRIES,Marketplace.OZON),fx(()->new HashSet<>(pane.availableDestinations().stream().map(Shop::getMarketplace).toList())));
        fx(()->{pane.setShop(oz);return null;});idle();assertEquals(2,fx(()->pane.availableDestinations().size()).intValue());assertEquals(oz.getId(),fx(()->pane.currentShopId()).intValue());
    }
    @Test void previewMissingDocumentsCannotPublishAndConfirmationStartsEmpty()throws Exception{
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","v1");var o=com.vncode.app.features.listingcopy.ListingCopyMappingTest.options(Marketplace.OZON);var doc=new ProductDocument("","2","DOC",null,null,"","",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null);var options=new CopyOptions(o.fields(),o.variantIds(),o.targetCategoryId(),o.attributeMapping(),o.attributeValues(),o.newArticles(),o.targetPrice(),o.currency(),List.of(doc),false);var j=service.draft(wb,List.of(key),List.of(oz),options).getFirst();service.preview(oz,j.id());
        fx(()->{pane.setShop(oz);return null;});idle();fx(()->{((ListView<CopyJob>)pane.lookup("#copyJobs")).getSelectionModel().selectFirst();return null;});idle();fx(()->{pane.applyCss();pane.layout();assertTrue(((Button)pane.lookup("#copyPublish")).isDisabled());assertFalse(((CheckBox)pane.lookup("#copyConfirm")).isSelected());assertTrue(pane.lookupAll(".label").stream().filter(n->n instanceof Label).map(n->((Label)n).getText()).anyMatch(t->t.contains(I18nService.getInstance().tr("copy.issue.DOCUMENT_FILE_REQUIRED"))));return null;});
    }
    @Test void leavingViewPreservesSentJob()throws Exception{
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","v1");var j=service.draft(wb,List.of(key),List.of(oz),ListingCopyMappingTest.options(Marketplace.OZON)).getFirst();service.preview(oz,j.id());adapter.gate=new CountDownLatch(1);adapter.entered=new CountDownLatch(1);
        try{fx(()->{pane.setShop(oz);return null;});idle();fx(()->{((ListView<CopyJob>)pane.lookup("#copyJobs")).getSelectionModel().selectFirst();return null;});idle();fx(()->{((CheckBox)pane.lookup("#copyConfirm")).setSelected(true);((Button)pane.lookup("#copyPublish")).fire();return null;});assertTrue(adapter.entered.await(5,TimeUnit.SECONDS));fx(()->{pane.setShop(wb);assertFalse(pane.isBusy());return null;});}
        finally{adapter.gate.countDown();}
        CopyJob persisted=null;for(int i=0;i<500;i++){persisted=new ListingCopyRepository().find(2,j.id()).orElseThrow();if(persisted.state()==CopyState.REMOTE_PROCESSING)break;Thread.sleep(10);}assertEquals(CopyState.REMOTE_PROCESSING,persisted.state());assertEquals(2,persisted.destinationShopId());assertEquals(2,adapter.sentOwner);assertEquals(1,fx(()->pane.currentShopId()).intValue());
    }
    @Test void localeAndSmallWindowLayout()throws Exception{
        var i18n=I18nService.getInstance();var original=i18n.getCurrentLanguage();
        Stage stage=fx(()->{var window=new Stage();var scene=pane.getScene();scene.getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());window.setScene(scene);window.show();return window;});
        try{for(var locale:AppLanguage.values())for(int width:new int[]{1024,1366}){
            fx(()->{i18n.setLanguage(locale);pane.applyTranslations();stage.setWidth(width);stage.setHeight(width==1024?600:768);pane.applyCss();pane.layout();return null;});
            var frame=new CountDownLatch(1);fx(()->{var pause=new javafx.animation.PauseTransition(javafx.util.Duration.millis(60));pause.setOnFinished(e->frame.countDown());pause.play();return null;});assertTrue(frame.await(3,TimeUnit.SECONDS));
            fx(()->{assertFalse(((Label)pane.lookup("#copyTitle")).getText().startsWith("copy."));assertEquals(i18n.tr("copy.prepare"),((Button)pane.lookup("#copyPrepare")).getText());assertTrue(pane.lookup("#copyPublish").getBoundsInParent().getWidth()<width);var image=pane.snapshot(null,null);Path dest=Path.of("target/ui-screenshots/copy-"+locale.code()+"-"+width+".png");Files.createDirectories(dest.getParent());var buffered=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<buffered.getHeight();y++)for(int x=0;x<buffered.getWidth();x++)buffered.setRGB(x,y,image.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(buffered,"png",dest.toFile());return null;});
        }}finally{fx(()->{stage.close();i18n.setLanguage(original);return null;});}
    }
    @Test void selectedProductsKeepSeparateConfigurationsAndAllBecomeDrafts()throws Exception{
        var keys=List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1"),new ProductKey(1,Marketplace.WILDBERRIES,"124","v2"));fx(()->{pane.open(wb,keys);return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();
        fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();((TextField)pane.lookup("#copyPrice")).setText("101");return null;});idle();
        fx(()->{((ComboBox<ProductKey>)pane.lookup("#copySource")).getSelectionModel().select(1);return null;});idle();
        fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();((TextField)pane.lookup("#copyPrice")).setText("202");return null;});idle();
        fx(()->{((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();var saved=service.list(oz).stream().map(j->service.get(oz,j.id())).toList();assertEquals(2,saved.size());assertEquals(Set.of(new java.math.BigDecimal("101"),new java.math.BigDecimal("202")),new HashSet<>(saved.stream().map(j->j.options().targetPrice()).toList()));assertEquals(Set.of("123","124"),new HashSet<>(saved.stream().map(j->j.source().key().cardId()).toList()));
    }
    @Test void copyEditorPrefillsOriginalArticleMappingsAndAttributeValues()throws Exception{
        fx(()->{pane.open(wb,List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1")));return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();
        fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{assertEquals("OLD",((TextField)pane.lookup("#copyArticle")).getText());
            var mapping=(ComboBox<TargetAttribute>)pane.lookup("#copyMapping_444");assertEquals("777",mapping.getValue().id());
            assertTrue(pane.lookupAll(".text-field").stream().filter(n->n instanceof TextField).anyMatch(n->"Cotton".equals(((TextField)n).getText())));return null;});
    }
    @Test void batchUsesEachUnvisitedProductsSourceDataInsteadOfRequiringReentry()throws Exception{
        var keys=List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1"),new ProductKey(1,Marketplace.WILDBERRIES,"124","v2"));
        fx(()->{pane.open(wb,keys);return null;});idle();fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();
        fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();
        var saved=service.list(oz).stream().map(j->service.get(oz,j.id())).toList();assertEquals(2,saved.size());
        var inherited=saved.stream().filter(j->j.source().key().cardId().equals("124")).findFirst().orElseThrow();assertEquals(Set.of("v2"),inherited.options().variantIds());assertTrue(inherited.options().textOverrides().isEmpty());
    }
    private void openTarget()throws Exception{
        fx(()->{pane.open(wb,List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1")));return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();
    }
    private void expandEditor()throws Exception{fx(()->{((TitledPane)pane.lookup("#copyDetails")).setExpanded(true);pane.applyCss();pane.layout();for(String id:List.of("copyGeneralDetails","copyVariantDetails","copyAttributeDetails","copyDocumentDetails"))((TitledPane)pane.lookup("#"+id)).setExpanded(true);pane.applyCss();pane.layout();return null;});}
    @Test void previewCanStartWithoutAChosenCategoryAndOnlyOpensTheMissingDetails()throws Exception{
        openTarget();fx(()->{assertFalse(((TitledPane)pane.lookup("#copyDetails")).isExpanded());assertFalse(((Button)pane.lookup("#copyPrepare")).isDisabled());((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();
        assertEquals(0,adapter.sends.get());assertEquals(CopyState.NEEDS_DATA,service.list(oz).getFirst().state());
        fx(()->{assertTrue(((TitledPane)pane.lookup("#copyDetails")).isExpanded());assertFalse(((CheckBox)pane.lookup("#copyConfirm")).isSelected());assertTrue(((Button)pane.lookup("#copyPublish")).isDisabled());return null;});
    }
    @Test void untouchedInheritedValuesAreNotSavedAsManualOverrides()throws Exception{
        openTarget();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();var options=service.get(oz,service.list(oz).getFirst().id()).options();
        assertNull(options.targetPrice());assertTrue(options.variantPrices().isEmpty());assertTrue(options.textOverrides().isEmpty());assertTrue(options.attributeValues().isEmpty());assertEquals(0,adapter.sends.get());
    }
    @Test void editedAttributesSurviveDictionaryReloadAndSwitchingDestinationStores()throws Exception{
        openTarget();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{((TextField)pane.lookup("#copyAttributeValue_777")).setText("Wool");var method=ListingCopyPane.class.getDeclaredMethod("loadAttributes",String.class);method.setAccessible(true);method.invoke(pane,"99");return null;});idle();
        fx(()->{assertEquals("Wool",((TextField)pane.lookup("#copyAttributeValue_777")).getText());((CheckBox)pane.lookup("#copyDestination_1")).setSelected(true);((ComboBox<Shop>)pane.lookup("#copyDestinationEditor")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{var editor=(ComboBox<Shop>)pane.lookup("#copyDestinationEditor");editor.setValue(editor.getItems().stream().filter(s->s.getId()==1).findFirst().orElseThrow());return null;});idle();
        fx(()->{var editor=(ComboBox<Shop>)pane.lookup("#copyDestinationEditor");editor.setValue(editor.getItems().stream().filter(s->s.getId()==2).findFirst().orElseThrow());return null;});idle();expandEditor();
        fx(()->{assertEquals("Wool",((TextField)pane.lookup("#copyAttributeValue_777")).getText());return null;});
    }
    @Test void aVariantPriceEditIsStoredSeparatelyFromTheCommonPrice()throws Exception{
        openTarget();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{((TextField)pane.lookup("#copyVariantPrice_v1")).setText("123");((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();
        var options=service.get(oz,service.list(oz).getFirst().id()).options();assertNull(options.targetPrice());assertEquals(new java.math.BigDecimal("123"),options.variantPrices().get("v1"));
    }
    @Test void aManuallySelectedSizeAttributeAndValueSurviveReopeningTheDestination()throws Exception{
        openTarget();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{var target=(ComboBox<TargetAttribute>)pane.lookup("#copySizeAttribute_v1");target.setValue(target.getItems().stream().filter(a->a.id().equals("889")).findFirst().orElseThrow());((TextField)pane.lookup("#copyVariantSize_v1")).setText("XL");((CheckBox)pane.lookup("#copyDestination_1")).setSelected(true);var editor=(ComboBox<Shop>)pane.lookup("#copyDestinationEditor");editor.setValue(editor.getItems().stream().filter(s->s.getId()==1).findFirst().orElseThrow());return null;});idle();
        fx(()->{var editor=(ComboBox<Shop>)pane.lookup("#copyDestinationEditor");editor.setValue(editor.getItems().stream().filter(s->s.getId()==2).findFirst().orElseThrow());return null;});idle();expandEditor();
        fx(()->{assertEquals("889",((ComboBox<TargetAttribute>)pane.lookup("#copySizeAttribute_v1")).getValue().id());assertEquals("XL",((TextField)pane.lookup("#copyVariantSize_v1")).getText());return null;});
    }
    @Test void oneFailedPreviewDoesNotDiscardTheOtherProductsResult()throws Exception{
        adapter.failFirstProduct=true;fx(()->{pane.open(wb,List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1"),new ProductKey(1,Marketplace.WILDBERRIES,"124","v2")));return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();fx(()->{((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();
        assertEquals(2,service.list(oz).size());assertTrue(service.list(oz).stream().anyMatch(j->j.source().key().cardId().equals("124")&&j.state()==CopyState.AWAITING_CONFIRMATION));
        fx(()->{assertEquals(1,((javafx.scene.layout.VBox)pane.lookup("#copyBatchErrors")).getChildren().size());return null;});assertEquals(0,adapter.sends.get());
    }
    @Test void pendingOldJobDetailsCannotRestorePublishAfterTheSellerEditsData()throws Exception{
        openTarget();expandEditor();fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});idle();
        fx(()->{((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();var job=service.get(oz,service.list(oz).getFirst().id());
        fx(()->{var method=ListingCopyPane.class.getDeclaredMethod("loadJob",CopyJob.class);method.setAccessible(true);method.invoke(pane,job.summary());((TextField)pane.lookup("#copyProductTitle")).setText("A new seller edit");return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyConfirm")).setSelected(true);assertTrue(((Button)pane.lookup("#copyPublish")).isDisabled());return null;});assertEquals(0,adapter.sends.get());
    }
    @Test void aSlowOldStoresDictionaryCannotOverwriteTheNewStoreEditor()throws Exception{
        openTarget();expandEditor();adapter.attributeGate=new CountDownLatch(1);adapter.attributeEntered=new CountDownLatch(1);
        try{
            fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});assertTrue(adapter.attributeEntered.await(3,TimeUnit.SECONDS));
            fx(()->{((CheckBox)pane.lookup("#copyDestination_1")).setSelected(true);var editor=(ComboBox<Shop>)pane.lookup("#copyDestinationEditor");editor.setValue(editor.getItems().stream().filter(s->s.getId()==1).findFirst().orElseThrow());return null;});
            for(int i=0;i<100;i++){if(fx(()->!((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getItems().isEmpty()))break;Thread.sleep(10);}
            fx(()->{((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getSelectionModel().selectFirst();return null;});
            for(int i=0;i<100;i++){if(fx(()->pane.lookup("#copyAttributeValue_111")!=null))break;Thread.sleep(10);}
            fx(()->{assertNotNull(pane.lookup("#copyAttributeValue_111"));return null;});adapter.attributeGate.countDown();idle();
            fx(()->{assertNotNull(pane.lookup("#copyAttributeValue_111"));assertNull(pane.lookup("#copyAttributeValue_777"));return null;});
        }finally{adapter.attributeGate.countDown();idle();}
    }
    @Test void realDestinationPreviewShowsDistinctVariantPricesInTheCompactLayout()throws Exception{
        fx(()->{pane.dispose();return null;});
        service=new ListingCopyService(new ListingCopyRepository(),(shop,key)->{var original=source(key);return new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),original.attributes(),original.variants(),original.dimensions(),original.documents(),original.checkedAt(),Map.of("source.category.name","Trousers","source.price.v1","100","source.price.v2","200","source.currency","RUB"));},shop->shop.getMarketplace()==Marketplace.OZON?new com.vncode.app.integration.ozon.OzonListingAdapter(owner->ListingCopyMappingTest.fixture("99:88"),image->{throw new java.io.IOException();}):new com.vncode.app.integration.wb.WbListingAdapter(owner->ListingCopyMappingTest.fixture("99"),image->{throw new java.io.IOException();}));
        fx(()->{pane=new ListingCopyPane(service,reads);new Scene(pane,1366,768);pane.applyCss();pane.layout();pane.open(wb,List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","")));return null;});idle();
        fx(()->{((CheckBox)pane.lookup("#copyDestination_2")).setSelected(true);return null;});idle();expandEditor();
        fx(()->{assertEquals("99:88",((ComboBox<TargetCategory>)pane.lookup("#copyCategory")).getValue().id());((CheckBox)pane.lookup("#copyField_images")).setSelected(false);((Button)pane.lookup("#copyPrepare")).fire();return null;});idle();
        var job=service.get(oz,service.list(oz).getFirst().id());assertEquals(CopyState.AWAITING_CONFIRMATION,job.state());assertTrue(job.preview().requestJson().contains("\"price\":\"100\""));assertTrue(job.preview().requestJson().contains("\"price\":\"200\""));
        fx(()->{((TitledPane)pane.lookup("#copyDetails")).setExpanded(false);pane.applyCss();pane.layout();assertEquals(2,((TableView<?>)pane.lookup("#copyPreviewVariants")).getItems().size());assertFalse(((CheckBox)pane.lookup("#copyConfirm")).isSelected());capturePreparedLayout();return null;});
    }
    private void capturePreparedLayout()throws Exception{
        String directory=System.getProperty("vncode.item6.capture.dir");if(directory==null)return;
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();i18n.setLanguage(AppLanguage.VI);pane.applyTranslations();
        var stage=new Stage();stage.setScene(pane.getScene());pane.getScene().getStylesheets().add(getClass().getResource("/com/vncode/app/styles/theme.css").toExternalForm());
        try{stage.show();for(int width:new int[]{1366,1024}){
            pane.setPrefSize(width,width==1366?768:600);stage.setWidth(width);stage.setHeight(width==1366?768:600);pane.resize(width,width==1366?768:600);pane.applyCss();pane.layout();var image=pane.snapshot(null,null);
            var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
            Files.createDirectories(Path.of(directory));javax.imageio.ImageIO.write(png,"png",Path.of(directory,"copy-ready-"+width+".png").toFile());
        }}finally{stage.close();i18n.setLanguage(previous);pane.applyTranslations();}
    }
    static ProductSnapshot source(ProductKey key){var s=ListingCopyMappingTest.source(key.marketplace());return new ProductSnapshot(key,s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),s.metadata());}
    static class FakeAdapter implements ListingTargetAdapter {
        CountDownLatch gate,entered,attributeGate,attributeEntered;volatile int sentOwner;boolean failFirstProduct;java.util.concurrent.atomic.AtomicInteger sends=new java.util.concurrent.atomic.AtomicInteger();
        public List<TargetCategory> categories(Shop s){return List.of(new TargetCategory("99","Clothing"));}
        public List<TargetAttribute> attributes(Shop s,String c)throws java.io.IOException{
            if(attributeGate!=null&&s.getMarketplace()==Marketplace.OZON){attributeEntered.countDown();try{attributeGate.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}}
            return List.of(new TargetAttribute(s.getMarketplace()==Marketplace.OZON?"777":"111","Material","string",false,"",3,"","",false),new TargetAttribute("888","Size","string",false,"",1,"","",false),new TargetAttribute("889","Custom size","string",false,"",1,"","",false));
        }
        public CopyPreview preview(Shop s,ProductSnapshot source,CopyOptions o)throws java.io.IOException{if(failFirstProduct&&source.key().cardId().equals("123"))throw new java.io.IOException("Synthetic read failure");String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(s),r="{}";var issues=o.documents().stream().anyMatch(d->d.file()==null)?List.of(new CopyIssue("DOCUMENT_FILE_REQUIRED","documents",true)):o.targetCategoryId().isBlank()?List.of(new CopyIssue("TARGET_CATEGORY_REQUIRED","category",true)):List.<CopyIssue>of();return new CopyPreview(s.getId(),s.getMarketplace(),owner,source.key(),source.revision(),CopyPreview.hash(r,"[]","[]",owner,source.revision(),"CREATE"),r,"[]","[]",Map.of("source",source.title()),issues,List.of("NEW"),o,"CREATE");}
        public RemoteSubmission submit(Shop s,CopyPreview p)throws java.io.IOException{sends.incrementAndGet();sentOwner=s.getId();if(entered!=null)entered.countDown();if(gate!=null)try{gate.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException();}return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){return RemoteResult.pending();}public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r;}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> c,List<ProductDocument>d){throw new UnsupportedOperationException();}public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){throw new UnsupportedOperationException();}
    }
}
