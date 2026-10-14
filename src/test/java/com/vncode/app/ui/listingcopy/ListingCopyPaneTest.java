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
    static ProductSnapshot source(ProductKey key){var s=ListingCopyMappingTest.source(key.marketplace());return new ProductSnapshot(key,s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),s.metadata());}
    static class FakeAdapter implements ListingTargetAdapter {
        CountDownLatch gate,entered;volatile int sentOwner;
        public List<TargetCategory> categories(Shop s){return List.of(new TargetCategory("99","Clothing"));}public List<TargetAttribute> attributes(Shop s,String c){return List.of();}
        public CopyPreview preview(Shop s,ProductSnapshot source,CopyOptions o){String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(s),r="{}";var issues=o.documents().stream().anyMatch(d->d.file()==null)?List.of(new CopyIssue("DOCUMENT_FILE_REQUIRED","documents",true)):List.<CopyIssue>of();return new CopyPreview(s.getId(),s.getMarketplace(),owner,source.key(),source.revision(),CopyPreview.hash(r,"[]","[]",owner,source.revision(),"CREATE"),r,"[]","[]",Map.of("source",source.title()),issues,List.of("NEW"),o,"CREATE");}
        public RemoteSubmission submit(Shop s,CopyPreview p)throws java.io.IOException{sentOwner=s.getId();if(entered!=null)entered.countDown();if(gate!=null)try{gate.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException();}return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){return RemoteResult.pending();}public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r;}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> c,List<ProductDocument>d){throw new UnsupportedOperationException();}public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){throw new UnsupportedOperationException();}
    }
}
