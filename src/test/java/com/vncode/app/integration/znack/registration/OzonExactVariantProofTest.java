package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Local persistence/native proof tests; no seller API, purchase or real signature. */
class OzonExactVariantProofTest {
    @TempDir Path root;
    Shop shop;ProductSnapshot product;GtinSyncModels.RegisteredGtin card;
    final String offer="152 nau-2XL";
    GtinSyncModels.ProductKey key(){return new GtinSyncModels.ProductKey(2,Marketplace.OZON,"501",offer);}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        product=snapshot("152 nau-2XL","2XL","52","Black","Ozon brand",Instant.now());
        card=catalog(GtinWriteTestFixture.GTIN,91,"152NAU","XXL","Blue",Map.of(),activeDocuments());
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));cache(product,false);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot snapshot(String article,String size,String russian,String color,String brand,Instant time){
        return new ProductSnapshot(new ProductKey(2,Marketplace.OZON,"501",offer),"live:fixture","Ozon title","",brand,article,"77:88",List.of(),
                List.of(new ProductAttribute("1","Размер производителя",List.of(size),"","0",0),new ProductAttribute("2","Российский размер",List.of(russian),"","0",0),new ProductAttribute("3","Пол",List.of("Male"),"","0",0)),
                List.of(new ProductVariant(offer,article,color,size,"",false,russian,List.of("native-barcode"),ProductVariant.GtinEvidence.UNVERIFIED)),null,List.of(),time,Map.of("owner",ListingIdentity.fingerprint(shop),"sku","9001"));
    }
    List<CatalogProductData.Document> activeDocuments(){return List.of(new CatalogProductData.Document("DECLARATION","FIXTURE-DECL","2020-01-01","2099-12-31",false,"",Map.of("registryStatus","active")));}
    GtinSyncModels.RegisteredGtin catalog(String gtin,long id,String article,String size,String color,Map<String,List<String>> additional,List<CatalogProductData.Document> documents){
        var fields=new HashMap<>(additional);fields.put("article",List.of(article));fields.put("size",List.of(size));fields.put("@catalog_id",List.of(Long.toString(id)));fields.put("@catalog_status",List.of("published"));fields.putIfAbsent("tnved",List.of("6112110000"));fields.put("title",List.of("Catalog title"));
        return new GtinSyncModels.RegisteredGtin(gtin,article,color,size,true,true,RegisteredGtinProof.source(shop),Instant.now(),new CatalogProductData(fields,List.of(),documents));
    }
    void cache(ProductSnapshot p,boolean archived){
        var v=p.variants().getFirst();new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("501",offer,p.metadata().get("sku"),p.title(),"",p.vendorArticle(),v.color(),v.size(),"Vests","Male",archived,p.checkedAt().minusSeconds(2).toString(),List.of("native-barcode"),"77","88",v.size(),v.russianSize())),"");
    }
    OzonReviewedGtinLinks.Link bind(){return new OzonExistingCardLinkService().bindExactUnique(shop,product,List.of(card));}
    Map<KizPurchaseMappingProof.Product,String> resolved(){return KizPurchaseMappingProof.resolve(shop,List.of(new KizPurchaseMappingProof.Product("501","9001",card.gtin())));}

    @Test void explicitExactMappingEnablesTheNativePurchaseProofBeforeBarcodeWriteback(){
        var link=bind();assertTrue(OzonReviewedVariantProof.exactProof(link.revision()));assertFalse(OzonReviewedVariantProof.version2(link.revision()));
        assertTrue(OzonReviewedVariantProof.matches(shop,product,card));assertEquals(1,resolved().size());
        assertEquals(card.gtin(),OzonReviewedVariantProof.matchingIndex(shop).get(product.key()));
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("501","9001",card.gtin())),Set.of(card.gtin())));
        assertTrue(new GtinSyncRepository().verifiedMappings(shop).isEmpty(),"A model/size link is not an observed marketplace barcode");
        assertEquals(List.of("native-barcode"),new OzonCatalogRepository().findBySkus(2,List.of("9001")).get("9001").barcodes());
    }
    @Test void observingOrBuildingAMatchingIndexAloneNeverCreatesAuthority(){
        OzonReviewedVariantProof.observeExact(shop,product,card);
        assertFalse(OzonReviewedVariantProof.matches(shop,product,card));assertTrue(resolved().isEmpty());
        assertTrue(OzonReviewedVariantProof.matchingIndex(shop).isEmpty());assertNull(OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void ignoredMarketplaceAndCatalogFieldsDoNotInvalidateExactModelAndSize(){
        bind();String revision=OzonReviewedVariantProof.exactRevision(product,card);
        product=snapshot("152 NAU-XXL","XXL","52","Green","Different brand",Instant.now());cache(product,false);
        card=catalog(card.gtin(),91,"152 nau","2XL","Red",Map.of("brand",List.of("Another"),"gender",List.of("Female"),"tnved",List.of("6204420000")),activeDocuments());
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        assertEquals(revision,OzonReviewedVariantProof.exactRevision(product,card));assertTrue(OzonReviewedVariantProof.matches(shop,product,card));
        OzonReviewedVariantProof.observeForLink(shop,product,card);assertEquals(1,resolved().size());
    }
    @Test void aNewerNativeModelChangeCannotReuseTheOldProof(){
        bind();var changed=snapshot("B3152NAU-2XL","2XL","52","Black","Ozon brand",Instant.now().plusSeconds(1));
        assertFalse(OzonReviewedVariantProof.matches(shop,changed,card));new ProductRepository().saveVerifiedSnapshot(shop,changed);
        assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void staleManufacturerAttributesCannotHideACachedSizeChange()throws Exception{
        bind();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET manufacturer_size='3XL',size='3XL',russian_size='54' WHERE product_id='501'");}
        assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void anOldExplicitArticleAttributeCannotHideACachedNativeModelChange()throws Exception{
        var attributes=new ArrayList<>(product.attributes());attributes.add(new ProductAttribute("4","Артикул продавца",List.of("152NAU"),"","0",0));
        product=new ProductSnapshot(product.key(),product.revision(),product.title(),product.description(),product.brand(),product.vendorArticle(),product.categoryId(),product.media(),attributes,product.variants(),null,List.of(),product.checkedAt(),product.metadata());
        bind();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET article='B3152NAU-2XL',updated_at='' WHERE product_id='501'");}
        assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void russianSizeUsesTheSameNativeVariantInsteadOfAGlobalTwoXlChart(){
        product=snapshot("152 nau-2XL","2XL","54","Black","Ozon brand",Instant.now());cache(product,false);
        card=catalog(card.gtin(),91,"152NAU","52","Blue",Map.of("@size_type",List.of("RUSSIA")),activeDocuments());
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        assertEquals("GTIN_CARD_NOT_FOUND",assertThrows(IllegalStateException.class,this::bind).getMessage());
        assertNull(OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void aSelectedSingletonCannotHideAnotherGtinForTheSameFullModelAndSize(){
        var second=catalog("00000000000024",92,"152NAU","2XL","Blue",Map.of(),activeDocuments());
        new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(second));
        assertEquals("GTIN_AMBIGUOUS",assertThrows(IllegalStateException.class,this::bind).getMessage());assertNull(OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void discoveringAnotherExactGtinInvalidatesAnEarlierMachineChoice(){
        bind();var second=catalog("00000000000024",92,"152NAU","2XL","Blue",Map.of(),activeDocuments());new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(second));
        assertFalse(OzonReviewedVariantProof.matches(shop,product,card));assertThrows(KizAssignmentException.class,this::resolved);
    }
    void registration(String gtin,long id,String sku)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,good_id,scope_json,owner,created_at,updated_at) VALUES(2,'501',?,?,'PUBLISHED',?,?,?, ?,?,?)")){
            p.setString(1,offer);p.setString(2,sku);p.setString(3,gtin);p.setLong(4,id);p.setString(5,"{}");p.setString(6,ListingIdentity.fingerprint(shop));p.setString(7,Instant.now().toString());p.setString(8,Instant.now().toString());p.executeUpdate();
        }
    }
    @Test void anExistingRegisteredNativeCardCannotBeReplacedByAutomaticMatching()throws Exception{
        registration("00000000000024",92,"9001");assertEquals("GTIN_REVIEW_CHANGED",assertThrows(IllegalStateException.class,this::bind).getMessage());assertNull(OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void aLaterRegisteredNativeCardInvalidatesAnEarlierMachineChoice()throws Exception{
        bind();registration("00000000000024",92,"9001");assertFalse(OzonReviewedVariantProof.matches(shop,product,card));assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void automaticLinkingNeverOverwritesAnExistingSellerReviewedPair(){
        var compatible=catalog(card.gtin(),91,"Unrelated seller approved model","2XL","Black",Map.of(),activeDocuments());new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(compatible));
        OzonReviewedVariantProof.observe(shop,product,compatible);var manual=new OzonReviewedGtinLinks.Link(compatible.gtin(),91,"9001",ListingIdentity.fingerprint(shop),RegisteredGtinProof.source(shop),OzonReviewedVariantProof.revision(product,compatible));
        OzonReviewedGtinLinks.save(shop,key(),manual);
        new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(card));
        assertEquals("GTIN_REVIEW_CHANGED",assertThrows(IllegalStateException.class,this::bind).getMessage());assertEquals(manual,OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void anExistingExactLinkCannotSilentlySwitchToAnotherGtin(){
        var original=bind();card=catalog("00000000000024",92,"152NAU","XXL","Blue",Map.of(),activeDocuments());new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        assertEquals("GTIN_REVIEW_CHANGED",assertThrows(IllegalStateException.class,this::bind).getMessage());assertEquals(original,OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void restartPreservesOneExactLinkWithoutCreatingAnyBarcodeReceipt()throws Exception{
        var original=bind();Database.initDatabase();assertEquals(original,OzonReviewedGtinLinks.read(shop,key()));assertEquals(1,resolved().size());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ozon_reviewed_gtin_links")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        assertTrue(new GtinSyncRepository().verifiedMappings(shop).isEmpty());
    }
    @Test void aRevokedPermitStillBlocksThePaidPathAfterTheExactIdentityMatches(){
        bind();var revoked=new CatalogProductData.Document("DECLARATION","FIXTURE-DECL","2020-01-01","2099-12-31",false,"",Map.of("registryStatus","revoked"));
        card=catalog(card.gtin(),91,"152NAU","XXL","Blue",Map.of(),List.of(revoked));new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));
        assertTrue(OzonReviewedVariantProof.matches(shop,product,card));assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void anotherNativeProductAlreadyCarryingTheGtinBlocksTheAutomaticLink(){
        new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("502","other","9002","Other","",false,Instant.now().toString(),List.of(card.gtin()))),"");
        assertEquals("GTIN_ON_ANOTHER_PRODUCT",assertThrows(IllegalStateException.class,this::bind).getMessage());assertNull(OzonReviewedGtinLinks.read(shop,key()));
    }
    @Test void archivedOrChangedNativeSkuCannotReceiveANewLink(){
        cache(product,true);assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",assertThrows(IllegalStateException.class,this::bind).getMessage());
        cache(product,false);var bad=new ProductSnapshot(product.key(),product.revision(),product.title(),product.description(),product.brand(),product.vendorArticle(),product.categoryId(),product.media(),product.attributes(),product.variants(),null,List.of(),product.checkedAt(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","9002"));
        assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",assertThrows(IllegalStateException.class,()->new OzonExistingCardLinkService().bindExactUnique(shop,bad,List.of(card))).getMessage());
    }
    @Test void changedSignerOrIncompleteCatalogReadCannotReuseTheProof()throws Exception{
        bind();GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(KizAssignmentException.class,this::resolved);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO app_config(key,value) VALUES('catalog.read.revision.2','changed') ON CONFLICT(key) DO UPDATE SET value='changed'");}
        assertEquals("SOURCE_SYNC_REQUIRED",assertThrows(IllegalStateException.class,this::bind).getMessage());
    }
}
