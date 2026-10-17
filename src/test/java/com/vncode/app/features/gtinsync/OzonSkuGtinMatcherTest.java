package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogProductData;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonSkuGtinMatcherTest {
    RegisteredGtin card(String gtin,String article,String color,String size,boolean published){
        return new RegisteredGtin(gtin,article,color,size,true,published,"fixture",Instant.now(),new CatalogProductData(Map.of("@size_type",List.of("РОССИЯ"),"title",List.of("Костюм спортивный"),"brand",List.of("DASHOA")),List.of(),List.of()));
    }
    List<RegisteredGtin> match(List<RegisteredGtin> cards,String sku,String color,String makerSize,String ru){
        return new OzonSkuGtinMatcher(cards).candidates(sku,sku,color,makerSize,ru,"Костюм спортивный","Костюмы","DASHOA");
    }
    @Test void sellerExampleRequiresModelColorAndExplicitRussianSize(){
        var c=card("00000000000017","152DASHOA","Коричневый","52",true);
        assertEquals(List.of(c),match(List.of(c)," 152DASH-52 ","nâu","XL","52"));
        assertEquals(List.of(c),match(List.of(c),"152DASH_52","Brown","XL","52"));
        assertTrue(match(List.of(c),"152DASH-50","Brown","XL","52").isEmpty());
        assertTrue(match(List.of(c),"152DASH-52","Black","XL","52").isEmpty());
        assertTrue(match(List.of(c),"152DASH-52","Brown","XL","50").isEmpty());
        assertTrue(match(List.of(c),"152DASH-52","Brown","XL","").isEmpty());
    }
    @Test void modelNumbersPrefixesAndNumericOzonSkusNeverBecomeFuzzyMatches(){
        var c=card("00000000000017","152DASH","Brown","52",true);
        for(String sku:List.of("1152DASH-52","152DASHX-52","152-DASH-52","152DASH52","12345678","52","DASH-52"))
            assertTrue(match(List.of(c),sku,"Brown","XL","52").isEmpty(),sku);
        assertTrue(match(List.of(card(c.gtin(),"15 2DASH","Brown","52",true)),"152DASH-52","Brown","XL","52").isEmpty());
    }
    @Test void draftsAmbiguityDifferentBrandAndDifferentProductKindRemainBlocked(){
        var a=card("00000000000017","152DASH","Brown","52",true);var b=card("00000000000024","152DASHOA","Brown","52",true);
        assertEquals(2,match(List.of(a,b),"152DASH-52","Brown","XL","52").size());
        assertTrue(match(List.of(card(a.gtin(),a.article(),a.color(),a.size(),false)),"152DASH-52","Brown","XL","52").isEmpty());
        var m=new OzonSkuGtinMatcher(List.of(a));
        assertTrue(m.candidates("152DASH-52","152DASH-52","Brown","XL","52","Костюм","Костюмы","OTHER").isEmpty());
        assertTrue(m.candidates("152DASH-52","152DASH-52","Brown","XL","52","Брюки","Брюки","DASHOA").isEmpty());
        assertTrue(m.candidates("152DASH-52","999DASH-52","Brown","XL","52","Костюм","Костюмы","DASHOA").isEmpty());
    }
    @Test void detectingAnExistingDraftDoesNotMakeItEligibleForGtinWriting(){
        var draft=card("00000000000017","152DASH","Brown","52",false);
        assertEquals(List.of(draft),new OzonSkuGtinMatcher(List.of(draft),true).candidates("152DASH-52","152DASH-52","Brown","XL","52","Костюм","Костюмы","DASHOA"));
        assertTrue(new GtinCatalogIndex(List.of(draft)).attributes("152DASH","Brown","52").isEmpty());
    }
}
