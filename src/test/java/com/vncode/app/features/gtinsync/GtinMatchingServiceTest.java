package com.vncode.app.features.gtinsync;

import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinMatchingServiceTest {
    final ProductKey key = new ProductKey(1, Marketplace.WILDBERRIES, "101", "11");
    ProductSnapshot product(String article, String color, String size) {
        return new ProductSnapshot(key, article, color, size, List.of("other-barcode"), "fp", Instant.now());
    }
    RegisteredGtin candidate(String article, String size, boolean published, boolean unit) {
        return new RegisteredGtin("00000000000017", article, "Đen", size, unit, published, "NK", Instant.now());
    }
    @Test void matchesExactlyAndKeepsUnrelatedBarcodes() {
        var p=product("PANTS-1", " đen ", "XL");
        var result=new GtinMatchingService().match(p,List.of(candidate("PANTS-1","XL",true,true)));
        assertTrue(result.exact());
        assertEquals("00000000000017",result.candidates().getFirst().gtin());
        assertEquals(List.of("other-barcode"),p.barcodes());
    }
    @Test void neverInfersModelPunctuationOrSizeConversion() {
        var matcher=new GtinMatchingService();
        assertFalse(matcher.match(product("PANTS1","Đen","XL"),List.of(candidate("PANTS-1","XL",true,true))).exact());
        assertFalse(matcher.match(product("PANTS-1","Đen","XL"),List.of(candidate("PANTS-1","48",true,true))).exact());
    }
    @Test void blocksMissingAttributesDraftsPackagingAndAmbiguity() {
        var matcher=new GtinMatchingService();
        var good=candidate("PANTS-1","XL",true,true);
        assertFalse(matcher.match(product("PANTS-1","","XL"),List.of(good)).exact());
        assertFalse(matcher.match(product("PANTS-1","Đen","XL"),List.of(candidate("PANTS-1","XL",false,true))).exact());
        assertFalse(matcher.match(product("PANTS-1","Đen","XL"),List.of(candidate("PANTS-1","XL",true,false))).exact());
        var other=new RegisteredGtin("00000000000024","PANTSố-1","Đen","XL",true,true,"NK",Instant.now());
        assertFalse(matcher.match(product("PANTS-1","Đen","XL"),List.of(good,other)).exact());
    }
    @Test void charactersRemovedByCatalogDoNotCauseFalseDifferentProductButCollisionsStayAmbiguous() {
        var product=product("PANTSấ-1","Đen","XL");var service=new GtinMatchingService();
        var card=new RegisteredGtin("00000000000017","PANTS-1","Đen","XL",true,true,"fixture",java.time.Instant.now());assertTrue(service.match(product,List.of(card)).exact());
        var duplicate=new RegisteredGtin("00000000000024","PANTSố-1","Đen","XL",true,true,"fixture",java.time.Instant.now());assertFalse(service.match(product,List.of(card,duplicate)).exact());
        assertFalse(service.match(product("PANTS2","Đen","XL"),List.of(card)).exact());assertFalse(service.match(product("ấố","Đen","XL"),List.of(card)).exact());
    }
    @Test void validatesIdentityAndCopiesCollections() {
        assertThrows(IllegalArgumentException.class,()->new ProductKey(0,Marketplace.OZON,"1","1"));
        var p=product("PANTS-1","Đen","XL");
        assertThrows(UnsupportedOperationException.class,()->p.barcodes().add("bad"));
    }
    @Test void colorSpellingTranslationAndSetOrderDoNotCreateFalseDifferentVariants(){
        for(var pair:List.of(List.of("серый","grey"),List.of("GRAY","xám"),
                List.of("зеленый","green"),List.of("тёмно–серый","темно-серый"),
                List.of("чёрный / белый","white; black"),List.of("BLACK / чёрный","black")))
            assertTrue(OzonSkuGtinMatcher.sameColor(pair.getFirst(),pair.getLast()),pair.toString());
        for(var pair:List.of(List.of("серый","светло-серый"),List.of("blue","light blue"),
                List.of("black / white","black"),List.of("black / white","black / grey"),List.of("","black")))
            assertFalse(OzonSkuGtinMatcher.sameColor(pair.getFirst(),pair.getLast()),pair.toString());
    }
    @Test void separatorOnlyValuesAreMissingColorAndCannotSelectACatalogCard(){
        var matcher=new GtinMatchingService();
        for(String color:List.of("/","; / + | ,")){
            var card=new RegisteredGtin("00000000000017","PANTS-1",color,"XL",true,true,"fixture",Instant.now());
            assertFalse(matcher.match(product("PANTS-1",color,"XL"),List.of(card)).exact());
        }
    }
}
