package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.registration.RegistrationReviewedValues;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic owned identities only: a reviewed association is not a marketplace barcode write. */
class KizCrossMarketplaceProofTest {
    @TempDir Path root;
    Shop wb,ozon; ProductSnapshot source,target; WbOzonLinkRepository.Link link;
    static final String GTIN=GtinWriteTestFixture.GTIN;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'WB-ARTICLE','WB title','fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'WB-SKU'),(1,11,'"+GTIN+"')");
        }
        new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("501","OZON-OFFER","9001","Different Ozon title","","OZON-OFFER","Black","M","Fashion","",false,"",List.of())),"");
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        var card=new GtinSyncModels.RegisteredGtin(GTIN,"WB-ARTICLE","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),details);
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        source=snapshot(wb,new ProductKey(1,Marketplace.WILDBERRIES,"101","11"),"WB title","WB-ARTICLE","11",List.of(GTIN,"WB-SKU"),"");
        target=snapshot(ozon,new ProductKey(2,Marketplace.OZON,"501","OZON-OFFER"),"Different Ozon title","OZON-OFFER","OZON-OFFER",List.of(),"9001");
        var products=new ProductRepository();products.saveVerifiedSnapshot(wb,source);products.saveVerifiedSnapshot(ozon,target);
        link=new WbOzonLinkRepository.Link(1,"101","11",ListingIdentity.fingerprint(wb),2,"501","OZON-OFFER","9001",ListingIdentity.fingerprint(ozon),GTIN,91,
                RegisteredGtinProof.source(wb),RegisteredGtinProof.source(ozon),RegistrationReviewedValues.productRevision(source),RegistrationReviewedValues.productRevision(target),"MANUAL_PAIR","c".repeat(64),Instant.now());
        new WbOzonLinkRepository().save(wb,ozon,List.of(link));
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private ProductSnapshot snapshot(Shop shop,ProductKey key,String title,String article,String variant,List<String> barcodes,String sku){
        var values=new HashMap<String,String>();values.put("owner",ListingIdentity.fingerprint(shop));if(!sku.isEmpty())values.put("sku",sku);
        return new ProductSnapshot(key,"live:fixture",title,"","",article,"fashion",List.of(),List.of(),
                List.of(new ProductVariant(variant,article,"Black","M","",false,"",barcodes,ProductVariant.GtinEvidence.UNVERIFIED)),null,List.of(),Instant.now(),values);
    }
    private KizPurchaseMappingProof.Product selected(){return new KizPurchaseMappingProof.Product("501","9001",GTIN,"fixture-order");}
    @Test void reviewedDifferentArticleCannotReserveOrPurchaseWithoutAtomicProof(){
        assertNull(new OzonProductGtinMappingRepository().findResolvedBySku(2).get("9001"));
        assertNull(KizPurchaseMappingProof.resolve(ozon,List.of(selected())).get(selected()));
        assertEquals("KIZ_CROSS_PURCHASE_REVIEW_REQUIRED",assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN))).code());
        assertTrue(new GtinSyncRepository().verifiedMappings(ozon).isEmpty());
    }
    @Test void changedSkuOrRevisionMustNotAuthorizeAnotherProduct()throws Exception {
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(new KizPurchaseMappingProof.Product("501","9999",GTIN)),Set.of(GTIN)));
        var changed=snapshot(ozon,target.key(),"Changed listing",target.vendorArticle(),"OZON-OFFER",List.of(),"9001");
        new ProductRepository().saveVerifiedSnapshot(ozon,changed);
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void staleCatalogOrMismatchedGoodIdCannotAuthorizePurchase(){
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("92"),"@catalog_status",List.of("published")),List.of(),List.of());
        var card=new GtinSyncModels.RegisteredGtin(GTIN,"WB-ARTICLE","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),details);
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void expiredSnapshotAndChangedCachedVariantMustNotAuthorizePurchase()throws Exception {
        var stale=new ProductSnapshot(target.key(),target.revision(),target.title(),target.description(),target.brand(),target.vendorArticle(),target.categoryId(),target.media(),target.attributes(),target.variants(),target.dimensions(),target.documents(),Instant.now().minusSeconds(90000),target.metadata());
        new ProductRepository().saveVerifiedSnapshot(ozon,stale);
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
        new ProductRepository().saveVerifiedSnapshot(ozon,target);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_products SET size='L' WHERE shop_id=2 AND product_id='501'")){assertEquals(1,p.executeUpdate());}
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void certificateRotationCannotReuseAStaleCrossMarketplaceApproval(){
        GtinWriteTestFixture.saveSettings(wb,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void newerSourceProductFactsCannotBeIgnored()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE wb_product_cards SET title='Changed source' WHERE shop_id=1 AND nm_id=101")){assertEquals(1,p.executeUpdate());}
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void cachingVerifiedOzonObservationPreservesNewerProofAndAllExistingData()throws Exception {
        var docs=List.of(new CatalogProductData.Document("DECLARATION","synthetic-document","2026-01-01","2027-01-01",false,"synthetic",Map.of("source","fixture")));
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),docs);
        var existing=new GtinSyncModels.RegisteredGtin(GTIN,"WB-ARTICLE","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),details);
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(existing));
        String beforeCatalog=scalar("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=2 AND marketplace='OZON'"),beforeProduct=scalar("SELECT payload FROM product_snapshots WHERE shop_id=2 AND marketplace='OZON'");
        String beforeDocs=scalar("SELECT COUNT(*) FROM product_documents WHERE shop_id=2");
        var newer=new ProductSnapshot(target.key(),target.revision(),"Newer title",target.description(),target.brand(),target.vendorArticle(),target.categoryId(),target.media(),target.attributes(),target.variants(),target.dimensions(),target.documents(),target.checkedAt().plusSeconds(2),target.metadata());
        KizCrossMarketplaceProof.cacheObservedOzonSnapshot(ozon,newer,existing,91);
        String saved=scalar("SELECT value FROM app_config WHERE key LIKE 'kiz.cross.observation.2.%'");
        KizCrossMarketplaceProof.cacheObservedOzonSnapshot(ozon,target,existing,91);
        assertEquals(saved,scalar("SELECT value FROM app_config WHERE key LIKE 'kiz.cross.observation.2.%'"));
        var metadata=new HashMap<>(target.metadata());metadata.put("owner","foreign-synthetic-owner");
        var foreign=new ProductSnapshot(target.key(),target.revision(),target.title(),target.description(),target.brand(),target.vendorArticle(),target.categoryId(),target.media(),target.attributes(),target.variants(),target.dimensions(),target.documents(),target.checkedAt(),metadata);
        assertThrows(SecurityException.class,()->KizCrossMarketplaceProof.cacheObservedOzonSnapshot(ozon,foreign,existing,91));
        assertEquals(saved,scalar("SELECT value FROM app_config WHERE key LIKE 'kiz.cross.observation.2.%'"));
        assertEquals(beforeCatalog,scalar("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=2 AND marketplace='OZON'"));
        assertEquals(beforeProduct,scalar("SELECT payload FROM product_snapshots WHERE shop_id=2 AND marketplace='OZON'"));
        assertEquals(beforeDocs,scalar("SELECT COUNT(*) FROM product_documents WHERE shop_id=2"));
    }
    @Test void changedOzonBarcodeSnapshotMustNotAuthorizeOldLink()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'501','9780201379624')")){p.executeUpdate();}
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void missingObservationsAreRefreshedWithoutReplacingDocumentsOrSnapshots() throws Exception {
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        var card=new GtinSyncModels.RegisteredGtin(GTIN,"WB-ARTICLE","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),details);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO product_documents(shop_id,marketplace,card_id,document_id,state,payload) VALUES(1,'WILDBERRIES','101','fixture-wb','VERIFIED','{\"seller\":\"wb-preserved\"}'),(2,'OZON','501','fixture-ozon','VERIFIED','{\"seller\":\"ozon-preserved\"}')");}
        String wbDoc=scalar("SELECT payload FROM product_documents WHERE shop_id=1"),ozonDoc=scalar("SELECT payload FROM product_documents WHERE shop_id=2");
        String docsBefore=scalar("SELECT COUNT(*) FROM product_documents");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM product_snapshots");s.execute("DELETE FROM gtin_sync_catalog");}
        var reader=new KizCrossMarketplaceProof.ObservationReader(){
            public ProductSnapshot wb(Shop shop,ProductKey key){return source;}
            public com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review ozon(Shop shop,GtinSyncModels.ProductKey key,String gtin){
                KizCrossMarketplaceProof.cacheObservedOzonSnapshot(shop,target,card,91);
                return new com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review(key,gtin,91,"9001",target.title(),target.vendorArticle(),"Black","M","WB-ARTICLE","Black","M","",ListingIdentity.fingerprint(shop),RegisteredGtinProof.source(shop),RegistrationReviewedValues.productRevision(target),"catalog-revision");
            }
        };
        var result=KizCrossMarketplaceProof.refreshMissing(ozon,List.of("9001"),()->false,reader);
        assertEquals(1,result.attempted());assertEquals(1,result.verified());assertEquals("KIZ_CROSS_PURCHASE_REVIEW_REQUIRED",result.issues().get("9001"));
        assertEquals("0",scalar("SELECT COUNT(*) FROM product_snapshots"));assertEquals("0",scalar("SELECT COUNT(*) FROM gtin_sync_catalog"));
        assertEquals(docsBefore,scalar("SELECT COUNT(*) FROM product_documents"));
        assertEquals(wbDoc,scalar("SELECT payload FROM product_documents WHERE shop_id=1"));assertEquals(ozonDoc,scalar("SELECT payload FROM product_documents WHERE shop_id=2"));
        assertNull(KizPurchaseMappingProof.resolve(ozon,List.of(selected())).get(selected()));
        assertEquals("KIZ_CROSS_PURCHASE_REVIEW_REQUIRED",assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN))).code());
    }
    @Test void refreshHasBoundedWorkAndHonorsCancellation() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM product_snapshots");}
        var reader=new KizCrossMarketplaceProof.ObservationReader(){
            public ProductSnapshot wb(Shop shop,ProductKey key){fail("budget exhausted before network");return null;}
            public com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review ozon(Shop shop,GtinSyncModels.ProductKey key,String gtin){fail("budget exhausted before network");return null;}
        };
        var ticks=new java.util.concurrent.atomic.AtomicLong();
        var result=KizCrossMarketplaceProof.refreshMissing(ozon,List.of("9001"),()->false,reader,1,1,ticks::getAndIncrement);
        assertEquals(0,result.attempted());assertEquals("KIZ_CROSS_REFRESH_DEFERRED",result.issues().get("9001"));
        assertThrows(java.io.InterruptedIOException.class,()->KizCrossMarketplaceProof.refreshMissing(ozon,List.of("9001"),()->true,reader));
    }
    @Test void timeoutIsReportedWithoutTurningItIntoCancellation() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM product_snapshots");}
        var reader=new KizCrossMarketplaceProof.ObservationReader(){
            public ProductSnapshot wb(Shop shop,ProductKey key)throws Exception{throw new java.net.SocketTimeoutException("synthetic timeout");}
            public com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review ozon(Shop shop,GtinSyncModels.ProductKey key,String gtin){fail("must stop on timeout");return null;}
        };
        var result=KizCrossMarketplaceProof.refreshMissing(ozon,List.of("9001"),()->false,reader);
        assertEquals(1,result.attempted());assertEquals("NETWORK_TIMEOUT",result.issues().get("9001"));assertFalse(Thread.currentThread().isInterrupted());
    }
    @Test void shopWithoutReviewedLinksDoesNotRequireCatalogConfiguration() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM wb_ozon_gtin_links");s.execute("DELETE FROM znack_settings WHERE shop_id=2");}
        var result=KizCrossMarketplaceProof.refreshMissing(ozon,List.of("9001"),()->false);
        assertEquals(0,result.attempted());assertTrue(result.issues().isEmpty());
    }
    @Test void directVerifiedBarcodeConflictingWithReviewedCrossGtinMustNotAuthorizePurchase() throws Exception {
        final String other="00000000000024";
        assertTrue(RegisteredGtinValidator.isValid(other));
        var updated=snapshot(ozon,target.key(),target.title(),target.vendorArticle(),"OZON-OFFER",List.of(other),"9001");
        new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("501","OZON-OFFER","9001","Different Ozon title","","OZON-OFFER","Black","M","Fashion","",false,"",List.of(other))),"");
        new ProductRepository().saveVerifiedSnapshot(ozon,updated);
        var detailsA=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        var detailsB=new CatalogProductData(Map.of("@catalog_id",List.of("92"),"@catalog_status",List.of("published")),List.of(),List.of());
        var a=new GtinSyncModels.RegisteredGtin(GTIN,"WB-ARTICLE","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),detailsA);
        var b=new GtinSyncModels.RegisteredGtin(other,"OZON-OFFER","Black","M",true,true,RegisteredGtinProof.source(ozon),Instant.now(),detailsB);
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(a,b));
        var updatedLink=new WbOzonLinkRepository.Link(link.wbShopId(),link.wbNmId(),link.wbChrtId(),link.wbOwner(),link.ozonShopId(),link.ozonProductId(),link.ozonOfferId(),link.ozonSku(),link.ozonOwner(),link.gtin(),link.goodId(),link.wbCatalogSource(),link.catalogSource(),link.wbRevision(),RegistrationReviewedValues.productRevision(updated),link.evidenceKind(),link.evidenceDigest(),link.approvedAt());
        new WbOzonLinkRepository().replaceReviewed(wb,ozon,link,updatedLink);
        var ref=new com.vncode.app.features.fbo.FboProductSku(1,"","","","","","","","9001","",true,"9001","","","501");
        assertEquals(other,new com.vncode.app.features.fbo.FboVerifiedGtinRepository().resolve(2,Marketplace.OZON,List.of(ref)).get(ref),"independent barcode proof should confirm GTIN B");
        assertNull(KizCrossMarketplaceProof.resolve(ozon).get(new KizCrossMarketplaceProof.Variant("501","9001")),"conflicting barcode must invalidate reviewed cross proof");
        assertNull(new OzonProductGtinMappingRepository().findResolvedBySku(2).get("9001"),"planner must reject conflicting direct and cross GTINs");
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)),"paid purchase A must reject conflicting proofs");
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(new KizPurchaseMappingProof.Product("501","9001",other)),Set.of(other)),"paid purchase B must reject conflicting reviewed link");
    }

    @Test void duplicateSkuAcrossOzonProductIdsMustNotAuthorizeCrossLink() throws Exception {
        new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("502","OTHER-OFFER","9001","Other product","","OTHER-OFFER","Black","M","Fashion","",false,"",List.of())),"");
        assertFalse(new OzonCatalogRepository().findBySkus(2,List.of("9001")).containsKey("9001"));
        assertTrue(KizCrossMarketplaceProof.resolve(ozon).isEmpty());
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(ozon,List.of(selected()),Set.of(GTIN)));
    }
    @Test void backgroundRefreshContinuesMoreThanTenWithoutPurchase() throws Exception {
        var skus=java.util.stream.IntStream.range(1,26).mapToObj(Integer::toString).toList();var calls=new java.util.concurrent.atomic.AtomicInteger();var progress=new ArrayList<KizCrossMarketplaceProof.RefreshResult>();
        var result=KizCrossMarketplaceProof.refreshAllDeferred(skus,()->false,progress::add,remaining->{
            calls.incrementAndGet();var list=new ArrayList<>(remaining);int n=Math.min(10,list.size());var issues=new LinkedHashMap<String,String>();list.subList(n,list.size()).forEach(sku->issues.put(sku,"KIZ_CROSS_REFRESH_DEFERRED"));
            return new KizCrossMarketplaceProof.RefreshResult(n,n,issues);
        });
        assertEquals(3,calls.get());assertEquals(25,result.attempted());assertEquals(25,result.verified());assertTrue(result.issues().isEmpty());assertEquals(3,progress.size());
    }
    @Test void backgroundRefreshStopsOnTimeoutAndCancellation() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var result=KizCrossMarketplaceProof.refreshAllDeferred(List.of("1","2"),()->false,ignored->{},remaining->{calls.incrementAndGet();return new KizCrossMarketplaceProof.RefreshResult(1,0,Map.of("1","NETWORK_TIMEOUT","2","KIZ_CROSS_REFRESH_DEFERRED"));});
        assertEquals(1,calls.get());assertEquals("NETWORK_TIMEOUT",result.issues().get("1"));assertEquals("KIZ_CROSS_REFRESH_DEFERRED",result.issues().get("2"));
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(java.io.InterruptedIOException.class,()->KizCrossMarketplaceProof.refreshAllDeferred(List.of("1","2"),cancelled::get,ignored->{},remaining->{cancelled.set(true);return new KizCrossMarketplaceProof.RefreshResult(1,1,Map.of("2","KIZ_CROSS_REFRESH_DEFERRED"));}));
    }
    @Test void legacyBroadMappingCannotReactivateCrossOnlyReservation() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(2,'"+GTIN+"','fixture')");}
        var repository=new OzonProductGtinMappingRepository();repository.put(2,"9001",GTIN);
        assertEquals(GTIN,repository.findGtin(2,"9001"));
        assertNull(repository.findResolvedBySku(2).get("9001"));
        assertNull(KizPurchaseMappingProof.resolve(ozon,List.of(selected())).get(selected()));
    }
    private static String scalar(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
