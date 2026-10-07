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
        var other=new RegisteredGtin("00000000000024","PANTS-1","Đen","XL",true,true,"NK",Instant.now());
        assertFalse(matcher.match(product("PANTS-1","Đen","XL"),List.of(good,other)).exact());
    }
    @Test void validatesIdentityAndCopiesCollections() {
        assertThrows(IllegalArgumentException.class,()->new ProductKey(0,Marketplace.OZON,"1","1"));
        var p=product("PANTS-1","Đen","XL");
        assertThrows(UnsupportedOperationException.class,()->p.barcodes().add("bad"));
    }
}
