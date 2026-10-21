package com.vncode.app.features.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.kizmapping.OzonCatalogMappingService.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonCatalogMappingServiceTest {
    static final String SOURCE="National Catalog@fixture";
    final Shop owner=new Shop(2,"Fixture",Marketplace.OZON,"client","fixture");
    @TempDir Path directory;
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}

    @Test void refreshesBothSourcesBeforeMappingAndPreservesExemptProducts()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));
        op.targets.add(target(1,List.of(code),false,""));op.targets.add(target(2,List.of(gtin(2)),true,""));
        var progress=new ArrayList<NationalCatalogGtinSource.Progress>();var result=new OzonCatalogMappingService(op).synchronize(owner,progress::add);
        assertEquals(List.of("synchronize","targets:receipt","catalog","enrich","verified:1"),op.calls);
        assertEquals(1,result.mapped());assertEquals(1,result.exempt());assertTrue(result.issues().isEmpty());
        assertEquals(Map.of("1",code),op.verified);assertEquals("MAPPING_COMPLETE",progress.getLast().phase());
    }
    @Test void registryOnlyActiveCertificateCanAuthorizeTheObservedBarcode()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code,List.of()));op.targets.add(target(1,List.of(code),false,""));
        var registry=new CatalogProductData.Document("CERTIFICATE_OF_CONFORMITY","fixture","2020-01-01","",false,"",Map.of("registryStatus","active"));
        op.details.put(code,List.of(card(code,List.of(registry))));
        var result=run(op);assertEquals(1,result.mapped());assertTrue(result.issues().isEmpty());assertTrue(op.calls.contains("details:"+code));
    }
    @Test void everyUnmappedProductHasAnIssueEvenWhenNoUsableBarcodeExists()throws Exception{
        var op=new Fake();op.targets.add(target(1,List.of(),false,""));op.targets.add(target(2,List.of("not-a-code","123"),false,""));
        var result=run(op);assertEquals(0,result.mapped());assertEquals(2,result.issues().size());assertTrue(result.issues().stream().allMatch(i->i.reason().equals("GTIN_BARCODE_MISSING")));
        assertEquals(Set.of("sku1","sku2"),result.issues().stream().map(Issue::sku).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void anotherCatalogScopeAndStaleObservationsNeverBecomeVerifiedMappings()throws Exception{
        var op=new Fake();String a=gtin(1),b=gtin(2);
        var foreign=card(a);op.cards.add(new RegisteredGtin(a,foreign.article(),foreign.color(),foreign.size(),true,true,"National Catalog@another",Instant.now(),foreign.details()));
        var old=card(b);op.cards.add(new RegisteredGtin(b,old.article(),old.color(),old.size(),true,true,SOURCE,Instant.now().minusSeconds(86401),old.details()));
        op.targets.add(target(1,List.of(a),false,""));op.targets.add(target(2,List.of(b),false,""));
        var result=run(op);assertEquals(List.of("GTIN_OWNER_NOT_VERIFIED","GTIN_CATALOG_STALE"),reasons(result));assertTrue(op.verified.isEmpty());
    }
    @Test void aGtinOnMultipleOzonProductsIsReportedForEveryProduct()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));op.targets.add(target(1,List.of(code),false,""));op.targets.add(target(2,List.of(code),false,""));
        var result=run(op);assertEquals(List.of("GTIN_MULTIPLE_PRODUCTS","GTIN_MULTIPLE_PRODUCTS"),reasons(result));assertTrue(op.verified.isEmpty());
    }
    @Test void repeatedListingsForTheSameProvenPhysicalVariantMayShareOneGtin()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));
        op.targets.add(target(1,List.of(code),false,"","same-physical-model","Brown","52"));
        op.targets.add(target(2,List.of(code),false,"","same-physical-model","коричневый","52"));
        var result=run(op);assertEquals(2,result.mapped());assertTrue(result.issues().isEmpty());assertEquals(Map.of("1",code,"2",code),op.verified);
    }
    @Test void conflictingExistingRulesCannotBypassDuplicateVariantChecks()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));
        op.targets.add(target(1,List.of("seller-alias-a"),false,code));op.targets.add(target(2,List.of("seller-alias-b"),false,code));
        var result=run(op);assertEquals(0,result.mapped());assertEquals(List.of("GTIN_MULTIPLE_PRODUCTS","GTIN_MULTIPLE_PRODUCTS"),reasons(result));assertTrue(op.verified.isEmpty());
    }
    @Test void aSavedRuleDoesNotHideFailedVerificationOfTheCurrentMarketplaceBarcode()throws Exception{
        var op=new Fake();String saved=gtin(1),observed=gtin(2);op.cards.add(card(saved));
        op.targets.add(target(1,List.of(observed),false,saved));op.failures.put(observed,new java.net.SocketTimeoutException("fixture timeout"));
        var result=run(op);assertEquals(0,result.mapped());assertEquals(List.of("NETWORK_TIMEOUT"),reasons(result));assertTrue(op.verified.isEmpty());
    }
    @Test void missingExplicitColorOrSizeCannotBeTreatedAsAMatchingVariant()throws Exception{
        var op=new Fake();String color=gtin(1),size=gtin(2);
        var complete=card(color);op.cards.add(new RegisteredGtin(color,complete.article(),"",complete.size(),true,true,SOURCE,Instant.now(),complete.details()));
        complete=card(size);op.cards.add(new RegisteredGtin(size,complete.article(),complete.color(),"",true,true,SOURCE,Instant.now(),complete.details()));
        op.targets.add(target(1,List.of(color),false,"","article1","","52"));
        op.targets.add(target(2,List.of(size),false,"","article2","Brown",""));
        var result=run(op);assertEquals(0,result.mapped());assertEquals(List.of("GTIN_COLOR_MISMATCH","GTIN_SIZE_MISMATCH"),reasons(result));assertTrue(op.verified.isEmpty());
    }
    @Test void multipleEligibleCatalogBarcodesRemainAmbiguousButUnknownExtraBarcodeDoesNotHideOneProvenMatch()throws Exception{
        var op=new Fake();String a=gtin(1),b=gtin(2),c=gtin(3);op.cards.add(card(a));op.cards.add(card(b));
        op.targets.add(target(1,List.of(a,b),false,""));op.targets.add(target(2,List.of(c,gtin(4)),false,""));op.cards.add(card(c));
        var result=run(op);assertEquals(1,result.mapped());assertEquals(List.of("GTIN_AMBIGUOUS"),reasons(result));assertEquals(Map.of("2",c),op.verified);
    }
    @Test void mismatchedSizeColorAndTnvedRemainBlocked()throws Exception{
        var op=new Fake();for(int i=1;i<=3;i++)op.targets.add(target(i,List.of(gtin(i)),false,""));
        var base=card(gtin(1));op.cards.add(new RegisteredGtin(base.gtin(),base.article(),"Brown","54",true,true,SOURCE,Instant.now(),base.details()));
        base=card(gtin(2));op.cards.add(new RegisteredGtin(base.gtin(),base.article(),"Black","52",true,true,SOURCE,Instant.now(),base.details()));
        base=card(gtin(3));op.cards.add(new RegisteredGtin(base.gtin(),base.article(),"Brown","52",true,true,SOURCE,Instant.now(),new CatalogProductData(Map.of("tnved",List.of("6201000000")),List.of(),base.details().documents())));
        assertEquals(List.of("GTIN_SIZE_MISMATCH","GTIN_COLOR_MISMATCH","GTIN_TNVED_GROUP_CHANGED"),reasons(run(op)));assertTrue(op.verified.isEmpty());
    }
    @Test void unsignedRejectedAndExpiredDocumentsAreNotReportedAsMapped()throws Exception{
        var op=new Fake();for(int i=1;i<=3;i++)op.targets.add(target(i,List.of(gtin(i)),false,""));
        var base=card(gtin(1));op.cards.add(new RegisteredGtin(base.gtin(),base.article(),base.color(),base.size(),true,false,SOURCE,Instant.now(),base.details()));
        base=card(gtin(2));op.cards.add(new RegisteredGtin(base.gtin(),base.article(),base.color(),base.size(),true,false,SOURCE,Instant.now(),new CatalogProductData(Map.of("@catalog_detailed_status",List.of("errors")),List.of(),base.details().documents())));
        var expired=new CatalogProductData.Document("DECLARATION","fixture","2020-01-01","2020-02-01",false,"",Map.of());op.cards.add(card(gtin(3),List.of(expired)));
        assertEquals(List.of("GTIN_CARD_UNSIGNED","GTIN_CARD_REJECTED","GTIN_DOCUMENT_MISSING"),reasons(run(op)));assertTrue(op.verified.isEmpty());
    }
    @Test void accessDeniedIsAnIndividualIssueAndOtherProductsStillMap()throws Exception{
        var op=new Fake();String a=gtin(1),b=gtin(2);op.targets.add(target(1,List.of(a),false,""));op.targets.add(target(2,List.of(b),false,""));op.cards.add(card(b));
        op.failures.put(a,new ZnackApiException("Forbidden",403,""));
        var result=run(op);assertEquals(1,result.mapped());assertEquals(List.of("GTIN_ACCESS_DENIED"),reasons(result));assertEquals(Map.of("2",b),op.verified);
    }
    @Test void rateLimitStopsThePassBeforeAnyMappingIsAdopted()throws Exception{
        var op=new Fake();String a=gtin(1),b=gtin(2);op.targets.add(target(1,List.of(a),false,""));op.targets.add(target(2,List.of(b),false,""));op.cards.add(card(b));
        var rate=new ZnackApiException("Too many requests",429,"");op.failures.put(a,rate);
        assertSame(rate,assertThrows(ZnackApiException.class,()->run(op)));assertTrue(op.verified.isEmpty());assertFalse(op.calls.contains("enrich"));
    }
    @Test void ownershipChangeDuringRegistryReadStopsWithoutSavingAnOldScope()throws Exception{
        var op=new Fake(){@Override public List<RegisteredGtin> details(Shop shop,String code){scope="National Catalog@rotated";return List.of(card(code));}};
        op.targets.add(target(1,List.of(gtin(1)),false,""));assertThrows(SecurityException.class,()->run(op));assertTrue(op.verified.isEmpty());
    }
    @Test void aValidExistingManualRuleIsKeptWithoutManufacturingBarcodeProof()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));op.targets.add(target(1,List.of("old-ozon-alias"),false,code));
        assertEquals(1,run(op).mapped());assertTrue(op.verified.isEmpty());
    }
    @Test void missingSkuAndWrongMarketplaceCannotAuthorizeMapping()throws Exception{
        var op=new Fake();String code=gtin(1);op.cards.add(card(code));var target=target(1,List.of(code),false,"");op.targets.add(new Target(target.row(),"",false,""));
        assertEquals(List.of("OZON_SKU_MISSING"),reasons(run(op)));
        assertThrows(IllegalArgumentException.class,()->new OzonCatalogMappingService(op).synchronize(new Shop(1,"WB","fixture"),p->{}));assertTrue(op.verified.isEmpty());
    }
    @Test void nativeVariantMappingResolvesOnlyItsOwnSkuAfterTheReadback()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.OZON);String code=gtin(1);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,article,synced_at) VALUES(2,'1','offer1','sku1','same','fixture'),(2,'2','offer2','sku2','same','fixture')");
            s.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'1','"+code+"')");
        }
        var base=card(code);var local=new LocalOperations();var legacy=new RegisteredGtin(code,base.article(),base.color(),base.size(),true,true,RegisteredGtinProof.source(shop),Instant.now(),base.details());new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(legacy));assertThrows(IllegalArgumentException.class,()->local.verified(shop,target(1,List.of(code),false,""),code));
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),base.details().attributes(),base.details().documents());var source=new RegisteredGtin(code,base.article(),base.color(),base.size(),true,true,RegisteredGtinProof.source(shop),Instant.now(),details);new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(source));
        var op=new Fake(){
            @Override public String scope(Shop owner){return local.scope(owner);}
            @Override public List<RegisteredGtin> catalog(Shop owner){return local.catalog(owner);}
            @Override public void enrich(Shop owner,List<RegisteredGtin> cards){local.enrich(owner,cards);}
            @Override public void verified(Shop owner,Target target,String gtin){local.verified(owner,target,gtin);}
        };op.targets.add(target(1,List.of(code),false,""));
        assertEquals(1,new OzonCatalogMappingService(op).synchronize(shop,p->{}).mapped());
        try(var c=Database.getConnection()){assertEquals(Map.of("sku1",code),VerifiedKizMappings.read(c,shop.getId(),Marketplace.OZON));}
    }
    @Test void moreThanTenThousandProductsAreAllReviewedWithoutATenThousandRowCutoff()throws Exception{
        var op=new Fake();for(int i=1;i<=10001;i++){String code=gtin(i);op.cards.add(card(code));op.targets.add(target(i,List.of(code),false,""));}
        var result=run(op);assertEquals(10001,result.mapped());assertEquals(10001,op.verified.size());assertTrue(result.issues().isEmpty());
    }

    Result run(Fake operations)throws Exception{return new OzonCatalogMappingService(operations).synchronize(owner,p->{});}
    List<String> reasons(Result result){return result.issues().stream().map(Issue::reason).toList();}
    static Target target(int id,List<String> codes,boolean exempt,String existing){
        return target(id,codes,exempt,existing,"article"+id,"Brown","52");
    }
    static Target target(int id,List<String> codes,boolean exempt,String existing,String article,String color,String size){
        var row=new ProductRow(new ProductKey(2,Marketplace.OZON,Integer.toString(id),"offer"+id),"Fixture",article,"sku"+id,"Sets","",color,size,codes,"","","cotton","6103109000",DocumentState.VALID,"",true);
        return new Target(row,"sku"+id,exempt,existing);
    }
    static RegisteredGtin card(String gtin){return card(gtin,List.of(new CatalogProductData.Document("DECLARATION","fixture","2020-01-01","",true,"",Map.of())));}
    static RegisteredGtin card(String gtin,List<CatalogProductData.Document> documents){return new RegisteredGtin(gtin,"Fixture","Brown","52",true,true,SOURCE,Instant.now(),new CatalogProductData(Map.of(),List.of(),documents));}
    static String gtin(int id){String base=String.format(Locale.ROOT,"%013d",id);int sum=0;for(int i=0;i<13;i++)sum+=(base.charAt(i)-'0')*(i%2==0?3:1);return base+((10-sum%10)%10);}
    static class Fake implements Operations {
        String scope=SOURCE;final List<Target> targets=new ArrayList<>();final List<RegisteredGtin> cards=new ArrayList<>();final Map<String,List<RegisteredGtin>> details=new HashMap<>();final Map<String,Exception> failures=new HashMap<>();final Map<String,String> verified=new LinkedHashMap<>();final List<String> calls=new ArrayList<>();
        public String scope(Shop shop){return scope;}
        public String synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress){calls.add("synchronize");return "receipt";}
        public List<Target> targets(Shop shop,String receipt){calls.add("targets:"+receipt);return targets;}
        public List<RegisteredGtin> catalog(Shop shop){calls.add("catalog");return cards;}
        public List<RegisteredGtin> details(Shop shop,String code)throws Exception{calls.add("details:"+code);if(failures.containsKey(code))throw failures.get(code);return details.getOrDefault(code,List.of());}
        public void enrich(Shop shop,List<RegisteredGtin> cards){calls.add("enrich");}
        public void verified(Shop shop,Target target,String gtin){calls.add("verified:"+target.row().key().cardId());verified.put(target.row().key().cardId(),gtin);}
    }
}
