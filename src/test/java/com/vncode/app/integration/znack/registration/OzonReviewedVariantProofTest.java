package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.catalogsync.CatalogProductData;
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

class OzonReviewedVariantProofTest {
    @TempDir Path root;Shop shop;ProductSnapshot product;GtinSyncModels.RegisteredGtin card;
    final GtinSyncModels.ProductKey key=new GtinSyncModels.ProductKey(2,Marketplace.OZON,"501","seller-alias-L");
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var attrs=List.of(new ProductAttribute("10","Состав",List.of("Cotton"),"","0",0),new ProductAttribute("11","ТН ВЭД",List.of("6109100000"),"","0",0));
        product=new ProductSnapshot(new ProductKey(2,Marketplace.OZON,"501","seller-alias-L"),"live:fixture","Ozon different title","","Brand","seller-alias-L","77:88",List.of(),attrs,
                List.of(new ProductVariant("seller-alias-L","seller-alias-L","Blue","L","",false,"52",List.of("legacy"),ProductVariant.GtinEvidence.UNVERIFIED)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","9001"));
        var details=new CatalogProductData(Map.of("title",List.of("Catalog title"),"tnved",List.of("6112110000"),"@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"material",List.of("Cotton"),"brand",List.of("Brand")),List.of(),
                List.of(new CatalogProductData.Document("DECLARATION","FIXTURE-DECL","2026-01-01","2027-12-31",false,"",Map.of("registryStatus","active"))));
        card=new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"CATALOG-MODEL","Blue","L",true,true,RegisteredGtinProof.source(shop),Instant.now(),details);
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(card));cacheProduct();
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private void cacheProduct(){new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("501","seller-alias-L","9001",product.title(),"",product.vendorArticle(),"Blue","L","Vests","Male",false,Instant.now().minusSeconds(2).toString(),List.of("legacy"),"77","88","L","52")),"");}
    private void approve(){
        OzonReviewedVariantProof.observe(shop,product,card);
        OzonReviewedGtinLinks.save(shop,key,new OzonReviewedGtinLinks.Link(card.gtin(),91,"9001",ListingIdentity.fingerprint(shop),RegisteredGtinProof.source(shop),OzonReviewedVariantProof.revision(product,card)));
    }
    private Map<KizPurchaseMappingProof.Product,String> resolved(){var selected=new KizPurchaseMappingProof.Product("501","9001",card.gtin());return KizPurchaseMappingProof.resolve(shop,List.of(selected));}
    @Test void observingCompatibleNamesAndSizesNeverApprovesAnUnreviewedPair(){
        OzonReviewedVariantProof.observe(shop,product,card);assertFalse(OzonReviewedVariantProof.matches(shop,product,card));assertTrue(resolved().isEmpty());
    }
    @Test void reviewedDifferentArticlesAndTariffCodesAuthorizeExactPurchaseBeforeBarcodeWriteback(){
        approve();var selected=new KizPurchaseMappingProof.Product("501","9001",card.gtin());
        assertEquals(RegisteredGtinProof.canonicalCode(card.gtin()),resolved().get(selected));
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(selected),Set.of(card.gtin())));
        assertDoesNotThrow(()->GtinTnvedIdentity.requireCompatible(product,card));
        assertTrue(new GtinSyncRepository().verifiedMappings(shop).isEmpty(),"Approval cannot claim barcode has been written");
        assertFalse(new OzonCatalogRepository().findBySkus(2,List.of("9001")).get("9001").barcodes().contains(card.gtin()));
    }
    @Test void cosmeticTitleAndSellerArticleChangesKeepTheSameReviewedPhysicalVariant(){
        approve();String revision=OzonReviewedVariantProof.revision(product,card);
        product=copy("Translated title","changed presentation",product.brand(),product.attributes(),product.variants());cacheProduct();
        assertEquals(revision,OzonReviewedVariantProof.revision(product,card));assertTrue(OzonReviewedVariantProof.matches(shop,product,card));
        OzonReviewedVariantProof.observe(shop,product,card);assertEquals(1,resolved().size());
    }
    @Test void colorSizeBrandMaterialAndCategoryChangesCannotReuseAnApproval(){
        approve();var variants=List.of(new ProductVariant("seller-alias-L","seller-alias-L","Red","XL","",false,"54",List.of(),ProductVariant.GtinEvidence.UNVERIFIED));
        assertFalse(OzonReviewedVariantProof.matches(shop,copy(product.title(),product.vendorArticle(),product.brand(),product.attributes(),variants),card));
        assertFalse(OzonReviewedVariantProof.matches(shop,copy(product.title(),product.vendorArticle(),"Other brand",product.attributes(),product.variants()),card));
        assertFalse(OzonReviewedVariantProof.matches(shop,copy(product.title(),product.vendorArticle(),product.brand(),List.of(new ProductAttribute("10","Состав",List.of("Polyester"),"","0",0)),product.variants()),card));
    }
    @Test void barcodeAdditionDoesNotEraseTheSavedIdentity(){
        approve();String revision=OzonReviewedVariantProof.revision(product,card);var v=product.variants().getFirst();
        product=copy(product.title(),product.vendorArticle(),product.brand(),product.attributes(),List.of(new ProductVariant(v.id(),v.sellerArticle(),v.color(),v.size(),"",false,v.russianSize(),List.of("legacy",card.gtin()),ProductVariant.GtinEvidence.UNVERIFIED)));
        assertEquals(revision,OzonReviewedVariantProof.revision(product,card));assertTrue(OzonReviewedVariantProof.matches(shop,product,card));
    }
    @Test void aLegacyLinkCannotSilentlyBecomeNewPurchaseAuthority(){
        OzonReviewedVariantProof.observe(shop,product,card);OzonReviewedGtinLinks.save(shop,key,new OzonReviewedGtinLinks.Link(card.gtin(),91,"9001",ListingIdentity.fingerprint(shop),RegisteredGtinProof.source(shop),"a".repeat(64)));
        assertTrue(resolved().isEmpty());
    }
    @Test void reopeningTheDatabasePreservesTheExplicitPair(){approve();Database.initDatabase();assertTrue(OzonReviewedVariantProof.matches(shop,product,card));assertEquals(1,resolved().size());}
    @Test void aNewExplicitPairOverridesAnOldPublishedRegistrationWithoutRewritingItsHistory()throws Exception{
        String oldGtin="00000000000024";var d=card.details();var fields=new HashMap<>(d.fields());fields.put("@catalog_id",List.of("92"));
        var oldCard=new GtinSyncModels.RegisteredGtin(oldGtin,"seller-alias-L","Blue","L",true,true,card.source(),Instant.now(),new CatalogProductData(fields,d.attributes(),d.documents()));
        new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(oldCard));
        var scope=RegisteredGtinProof.current(shop);String scopeJson=new com.google.gson.Gson().toJson(new ZnackRegistrationScope(scope.inn(),scope.environment(),scope.fingerprint()));
        String payload="{\"gtin\":\""+oldGtin+"\",\"article\":\"seller-alias-L\",\"color\":\"Blue\",\"size\":\"L\",\"tnved\":\"6112110000\"}";
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,good_id,scope_json,owner,payload_json,created_at,updated_at) VALUES(2,'501','seller-alias-L','9001','PUBLISHED',?,92,?,?,?,?,?)")){
            p.setString(1,oldGtin);p.setString(2,scopeJson);p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,payload);p.setString(5,Instant.now().toString());p.setString(6,Instant.now().toString());p.executeUpdate();
        }
        assertEquals(oldGtin,new KizRegistrationIdentityRepository().readForPurchase(shop).select("501","9001").gtin());
        approve();assertEquals(RegisteredGtinProof.canonicalCode(card.gtin()),new KizRegistrationIdentityRepository().readForPurchase(shop).select("501","9001").gtin());
        assertEquals(1,resolved().size());
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("501","9001",oldGtin)),Set.of(oldGtin)));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT gtin FROM ozon_card_registrations WHERE product_id='501'")){assertTrue(r.next());assertEquals(oldGtin,r.getString(1));}
    }
    @Test void expiredEvidenceCannotFallBackToAnArticleRule()throws Exception{
        approve();try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE app_config SET value=json_set(value,'$.product.checkedAt','2026-01-01T00:00:00Z') WHERE key LIKE 'ozon.reviewed.variant.v2.%'");
        }assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void anotherProductHoldingTheSameGtinBlocksThePurchase()throws Exception{
        approve();new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("502","other","9002","Other","",false,Instant.now().toString(),List.of(card.gtin()))),"");
        assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void archivedProductsCannotPurchaseUsingTheirHistoricalLink()throws Exception{
        approve();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET archived=1 WHERE product_id='501'");}assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void aRevokedCatalogPermitBlocksPurchaseEvenWhileTheCardRemainsPublished(){
        approve();var d=card.details();var doc=d.documents().getFirst();var revoked=new CatalogProductData.Document(doc.type(),doc.number(),doc.from(),doc.until(),false,"",Map.of("registryStatus","revoked"));
        var fresh=new GtinSyncModels.RegisteredGtin(card.gtin(),card.article(),card.color(),card.size(),true,true,card.source(),Instant.now().plusSeconds(1),new CatalogProductData(d.fields(),d.attributes(),List.of(revoked)));
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(fresh));assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void theSameSizeCannotMapAnotherNativeProductOrSku(){
        approve();assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.resolve(shop,List.of(new KizPurchaseMappingProof.Product("502","9001",card.gtin()))));
        assertTrue(KizPurchaseMappingProof.resolve(shop,List.of(new KizPurchaseMappingProof.Product("501","9002",card.gtin()))).isEmpty());
    }
    @Test void aNewerLiveProductEditInvalidatesPurchaseBeforeTheNextFullSync()throws Exception{
        approve();var changed=copy(product.title(),product.vendorArticle(),product.brand(),List.of(new ProductAttribute("10","Состав",List.of("Polyester"),"","0",0)),product.variants());
        new ProductRepository().saveVerifiedSnapshot(shop,changed);assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void physicalCatalogAttributesCannotChangeBehindTheReviewedLink(){
        approve();var d=card.details();var changed=new GtinSyncModels.RegisteredGtin(card.gtin(),card.article(),card.color(),card.size(),true,true,card.source(),Instant.now().plusSeconds(1),new CatalogProductData(d.fields(),List.of(new CatalogProductData.Attribute("12","Состав",List.of("Polyester"))),d.documents()));
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(changed));assertThrows(KizAssignmentException.class,this::resolved);
    }
    @Test void theSharedMatchingIndexUsesTheNativeReviewAndNeverFallsBackWhenItBecomesInvalid(){
        approve();var row=new ProductRow(product.key(),product.title(),product.vendorArticle(),"9001","Vests",product.brand(),"Blue","L",List.of("legacy"),"","","Cotton","6109100000",DocumentState.UNVERIFIED,"",true,"52","L");
        var index=new GtinCatalogIndex(List.of(card),false,OzonReviewedVariantProof.matchingIndex(shop));assertEquals(List.of(card),index.ozon(row));
        var changed=new GtinSyncModels.RegisteredGtin(card.gtin(),card.article(),card.color(),"XL",true,true,card.source(),Instant.now().plusSeconds(1),card.details());new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,List.of(changed));
        var blocked=new GtinCatalogIndex(List.of(card),false,OzonReviewedVariantProof.matchingIndex(shop));assertTrue(blocked.reviewed(row));assertTrue(blocked.ozon(row).isEmpty());
    }
    @Test void registrationDisplayUsesTheCurrentReviewedCardInsteadOfAnOldNativeCheckpoint()throws Exception{
        approve();com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        String oldGtin="00000000000024";var old=new GtinSyncModels.RegisteredGtin(oldGtin,"seller-alias-L","Blue","L",true,true,card.source(),Instant.now(),card.details());
        new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(old));
        var row=new ProductRow(product.key(),product.title(),product.vendorArticle(),"9001","Vests",product.brand(),"Blue","L",List.of(oldGtin),"","","Cotton","6109100000",DocumentState.UNVERIFIED,"",true,"52","L");
        assertEquals(Map.of(product.key(),card.gtin()),OzonReviewedVariantProof.matchingIndex(shop),"The explicit reviewed native association must remain available to registration display");
        var index=new com.vncode.app.features.sync.RegistrationCardIndex(shop);
        var view=index.viewOzon(ZnackCardRegistrationModels.Status.PUBLISHED,oldGtin,row,true);
        assertTrue(view.published());assertEquals(card.gtin(),view.gtin());assertEquals(card,index.verifiedOzon(row,ZnackCardRegistrationModels.Status.PUBLISHED,oldGtin,true).orElseThrow());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE app_config SET value=json_set(value,'$.product.checkedAt','2026-01-01T00:00:00Z') WHERE key LIKE 'ozon.reviewed.variant.v2.%'");}
        var invalid=new com.vncode.app.features.sync.RegistrationCardIndex(shop).viewOzon(ZnackCardRegistrationModels.Status.PUBLISHED,oldGtin,row,true);
        assertFalse(invalid.published());assertTrue(invalid.blocked());assertEquals("GTIN_REVIEW_CHANGED",invalid.detail());
    }
    private ProductSnapshot copy(String title,String article,String brand,List<ProductAttribute> attrs,List<ProductVariant> variants){return new ProductSnapshot(product.key(),"live:changed",title,"",brand,article,product.categoryId(),List.of(),attrs,variants,null,List.of(),Instant.now(),product.metadata());}
}
