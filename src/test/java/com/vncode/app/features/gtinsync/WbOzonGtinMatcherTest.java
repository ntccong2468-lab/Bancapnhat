package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import static org.junit.jupiter.api.Assertions.*;

class WbOzonGtinMatcherTest {
    private static final String GTIN="04640738621342", GTIN2="04640738621311", SCOPE="ozon-catalog-scope";
    private static final Instant NOW=Instant.parse("2026-10-31T00:00:00Z");
    private final WbOzonGtinMatcher matcher=new WbOzonGtinMatcher();

    @Test void sameArticleAndVariantMatchesRegardlessOfTitleAndRequiresFullEvidenceForAuto(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB title","черный","XL",List.of(GTIN),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","O-XL"," abc  100 ","A completely different Ozon title","đen","XL",List.of(),"6112110000");
        var result=matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).getFirst();
        assertEquals("",result.reason());assertEquals(1,result.candidates().size());
        assertEquals(WbOzonGtinMatcher.Evidence.EXACT_ARTICLE,result.candidates().getFirst().evidence());
        assertTrue(result.candidates().getFirst().autoEligible());
    }
    @Test void exactBarcodeFindsCandidateEvenWhenArticlesDifferButManualPairIsNeverAuto(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","WB 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","offer","OZ 999","Ozon","черный","XL",List.of(GTIN.substring(1)),"6112110000");
        var card=card(GTIN,SCOPE,NOW);
        var exact=matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW).getFirst();
        assertEquals(WbOzonGtinMatcher.Evidence.EXACT_BARCODE,exact.candidates().getFirst().evidence());
        var manual=matcher.evaluatePair(wb,oz,List.of(card),SCOPE,NOW);
        assertEquals("SELLER_REVIEW_REQUIRED",manual.reason());assertFalse(manual.candidates().getFirst().autoEligible());
    }
    @Test void titleAloneCannotMatchAndSellerCanReviewDifferentArticles(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","WB 100","Identical title","черный","XL",List.of(GTIN),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","offer","OZ 999","Identical title","черный","XL",List.of(),"6112110000");
        var card=card(GTIN,SCOPE,NOW);
        assertEquals("NO_SAFE_MATCH",matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW).getFirst().reason());
        assertEquals("SELLER_REVIEW_REQUIRED",matcher.evaluatePair(wb,oz,List.of(card),SCOPE,NOW).reason());
    }
    @Test void noGuessingWhenColorSizeOrTnvedDiffer(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        for(var oz:List.of(row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","белый","XL",List.of(),"6112110000"),
                row(Marketplace.OZON,2,"22","b","ABC 100","Ozon","черный","L",List.of(),"6112110000"),
                row(Marketplace.OZON,2,"23","c","ABC 100","Ozon","черный","XL",List.of(),"6112120000"))){
            assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).getFirst().candidates().isEmpty());
        }
        assertEquals("COLOR_MISMATCH",matcher.evaluatePair(wb,row(Marketplace.OZON,2,"21","a","OTHER","Ozon","белый","XL",List.of(),"6112110000"),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).reason());
        assertEquals("SIZE_MISMATCH",matcher.evaluatePair(wb,row(Marketplace.OZON,2,"21","a","OTHER","Ozon","черный","L",List.of(),"6112110000"),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).reason());
        assertEquals("TNVED_MISMATCH",matcher.evaluatePair(wb,row(Marketplace.OZON,2,"21","a","OTHER","Ozon","черный","XL",List.of(),"6112120000"),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).reason());
    }
    @Test void staleWrongScopeAndUnpublishedCardsCannotBeMatched(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","offer","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,"wrong",NOW)),SCOPE,NOW).getFirst().candidates().isEmpty());
        assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW.minus(Duration.ofDays(2)))),SCOPE,NOW).getFirst().candidates().isEmpty());
        var good=card(GTIN,SCOPE,NOW);var draft=new RegisteredGtin(good.gtin(),good.article(),good.color(),good.size(),true,false,SCOPE,NOW,good.details());
        assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(draft),SCOPE,NOW).getFirst().candidates().isEmpty());
    }
    @Test void ambiguousWbCandidatesAndSharedGtinNeverGetAutomaticApproval(){
        var wb1=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var wb2=row(Marketplace.WILDBERRIES,1,"13","14","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var oz1=row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        var oz2=row(Marketplace.OZON,2,"22","b","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        var card=card(GTIN,SCOPE,NOW);
        var ambiguous=matcher.match(List.of(wb1,wb2),List.of(oz1),List.of(card),SCOPE,NOW).getFirst();
        assertEquals("AMBIGUOUS",ambiguous.reason());assertEquals(2,ambiguous.candidates().size());assertTrue(ambiguous.candidates().stream().noneMatch(WbOzonGtinMatcher.Candidate::autoEligible));
        var multi=matcher.match(List.of(wb1),List.of(oz1,oz2),List.of(card),SCOPE,NOW);
        assertTrue(multi.stream().allMatch(r->r.reason().equals("GTIN_MULTIPLE_OZON_VARIANTS")&&!r.candidates().getFirst().autoEligible()));
    }
    @Test void existingOtherCatalogGtinCannotBeSilentlyOverridden(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","черный","XL",List.of(GTIN2),"6112110000");
        assertEquals("EXISTING_GTIN_CONFLICT",matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW),card(GTIN2,SCOPE,NOW)),SCOPE,NOW).getFirst().reason());
    }
    @Test void missingSourceFactsNeverAutomaticAndWrongShopScopesRejected(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"");
        var oz=row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        var candidate=matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).getFirst().candidates().getFirst();
        assertFalse(candidate.autoEligible());
        assertThrows(IllegalArgumentException.class,()->matcher.match(List.of(oz),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW));
        assertThrows(IllegalArgumentException.class,()->matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),"",NOW));
    }
    @Test void wbPublishedJournalCanSupplyGtinWithoutPretendingItIsWbBarcode(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(),"6112110000");
        var oz=row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        var card=card(GTIN,SCOPE,NOW);
        assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW).getFirst().candidates().isEmpty());
        var evidence=new WbOzonGtinMatcher.NativeAssociation(wb.key(),GTIN,123,"wb-owner","wb-scope","WB_PUBLISHED_JOURNAL");
        var result=matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW,Map.of(wb.key(),evidence)).getFirst();
        assertEquals(1,result.candidates().size());assertEquals(evidence,result.candidates().getFirst().nativeAssociation());
        assertTrue(result.candidates().getFirst().wb().barcodes().isEmpty());
        var wrongId=new WbOzonGtinMatcher.NativeAssociation(wb.key(),GTIN,999,"wb-owner","wb-scope","WB_PUBLISHED_JOURNAL");
        assertTrue(matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW,Map.of(wb.key(),wrongId)).getFirst().candidates().isEmpty());
        var other=row(Marketplace.WILDBERRIES,1,"99","98","ABC 100","other","черный","XL",List.of(),"6112110000");
        assertThrows(IllegalArgumentException.class,()->matcher.match(List.of(wb),List.of(oz),List.of(card),SCOPE,NOW,Map.of(wb.key(),new WbOzonGtinMatcher.NativeAssociation(other.key(),GTIN,123,"wb-owner","wb-scope","WB_PUBLISHED_JOURNAL"))));
    }
    @Test void differingDescriptionsAreExplicitSellerReviewNeverAutomatic(){
        var wb=row(Marketplace.WILDBERRIES,1,"11","12","ABC 100","WB","черный","XL",List.of(GTIN),"6112110000");
        var old=row(Marketplace.OZON,2,"21","a","ABC 100","Ozon","черный","XL",List.of(),"6112110000");
        var oz=new ProductRow(old.key(),old.title(),old.vendorArticle(),old.marketArticle(),old.category(),old.brand(),old.color(),old.size(),old.barcodes(),old.verifiedGtin(),old.thumbnailUrl(),"хлопок 80%, полиэстер 20%",old.tnved(),old.documentState(),old.gender(),true,old.russianSize(),old.manufacturerSize());
        var result=matcher.match(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW).getFirst();
        assertEquals("SELLER_REVIEW_REQUIRED",result.reason());assertFalse(result.candidates().getFirst().autoEligible());
        assertEquals(List.of("MATERIAL_MISMATCH"),result.candidates().getFirst().reviewWarnings());
        var reviewed=matcher.evaluateManualBatch(List.of(wb),List.of(oz),List.of(card(GTIN,SCOPE,NOW)),SCOPE,NOW,Map.of()).getFirst();
        assertEquals("SELLER_REVIEW_REQUIRED",reviewed.reason());assertEquals(List.of("MATERIAL_MISMATCH"),reviewed.candidates().getFirst().reviewWarnings());
    }
    @Test void manualBatchIndexesTenThousandVariantsAndNeverAutoConfirms(){
        var wb=new ArrayList<ProductRow>();var oz=new ArrayList<ProductRow>();var catalog=new ArrayList<RegisteredGtin>();
        for(int i=0;i<10_000;i++){
            String gtin=gtin(i),color="COLOR "+i;
            wb.add(row(Marketplace.WILDBERRIES,1,"model",Integer.toString(i),"WB model","WB",color,"XL",List.of(gtin),"6112110000"));
            oz.add(row(Marketplace.OZON,2,"p"+i,"o"+i,"Ozon model","Ozon",color,"XL",List.of(),"6112110000"));
            var details=new CatalogProductData(Map.of("@catalog_id",List.of(Integer.toString(i+1)),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),List.of());
            catalog.add(new RegisteredGtin(gtin,"WB model",color,"XL",true,true,SCOPE,NOW,details));
        }
        var results=matcher.evaluateManualBatch(wb,oz,catalog,SCOPE,NOW,Map.of());
        assertEquals(10_000,results.size());assertTrue(results.stream().allMatch(r->r.candidates().size()==1&&!r.candidates().getFirst().autoEligible()));
        assertEquals(10_000,results.stream().map(r->r.candidates().getFirst().catalog().gtin()).distinct().count());
    }
    private static String gtin(int i){
        String prefix=String.format(Locale.ROOT,"460%010d",i);int sum=0,weight=3;
        for(int p=prefix.length()-1;p>=0;p--){sum+=(prefix.charAt(p)-'0')*weight;weight=4-weight;}
        return prefix+((10-sum%10)%10);
    }
    private static ProductRow row(Marketplace m,int shop,String product,String variant,String article,String title,String color,String size,List<String> barcodes,String tnved){
        return new ProductRow(new ProductKey(shop,m,product,variant),title,article,"","костюмы","BRAND",color,size,barcodes,"","","хлопок",tnved,DocumentState.UNVERIFIED,"мужской",true,"",size);
    }
    private static RegisteredGtin card(String gtin,String scope,Instant at){
        var details=new CatalogProductData(Map.of("@catalog_id",List.of(gtin.equals(GTIN)?"123":"124"),"@catalog_status",List.of("published"),"tnved",List.of("6112110000"),"brand",List.of("BRAND"),"material",List.of("хлопок"),"gender",List.of("мужской")),List.of(),List.of());
        return new RegisteredGtin(gtin,"ABC 100","черный","XL",true,true,scope,at,details);
    }
}
