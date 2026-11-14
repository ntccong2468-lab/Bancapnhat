package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OzonExactCatalogIndexTest {
    @Test void findsTheWholeModelAndExplicitRussianSizeDespiteDifferentCatalogColor() {
        var expected=card("00000000000017","152 NAU","Другой цвет","52");
        var prefixed=card("00000000000024","B3152NAU","Brown","52");
        var suffixed=card("00000000000031","152NAU DASH","Brown","52");
        var index=new GtinCatalogIndex(List.of(expected,prefixed,suffixed));
        assertEquals(List.of(expected),index.ozon(product("152nau-2xl","Brown","2XL","52","XXL")));
        assertTrue(index.attributes("152NAU","Brown","2XL","52").isEmpty(),"WB attribute lookup remains strict");
    }
    @Test void preservesAmbiguityInsteadOfSelectingOneGtin() {
        var first=card("00000000000017","152NAU","Brown","52");
        var second=card("00000000000024","152 NAU","Black","52");
        assertEquals(Set.of(first,second),new HashSet<>(new GtinCatalogIndex(List.of(first,second))
                .ozon(product("152NAU","Brown","2XL","52","2XL"))));
    }
    @Test void doesNotInventRussianSizeConversionWithoutEvidenceFromTheVariant() {
        var index=new GtinCatalogIndex(List.of(card("00000000000017","152NAU","Brown","52")));
        assertTrue(index.ozon(product("152NAU","Brown","2XL","","2XL")).isEmpty());
        assertTrue(index.ozon(product("152NAU","Brown","52","54","52")).isEmpty(),"manufacturer 52 is not Russian 52");
    }
    private static GtinSyncModels.RegisteredGtin card(String gtin,String model,String color,String size) {
        return new GtinSyncModels.RegisteredGtin(gtin,model,color,size,true,true,"National Catalog@fixture",Instant.now(),
                new CatalogProductData(Map.of("@size_type",List.of("РОССИЯ")),List.of(),List.of()));
    }
    private static ProductRow product(String article,String color,String size,String russian,String manufacturer) {
        return new ProductRow(new ProductKey(7,Marketplace.OZON,"123","offer-123"),"Product",article,"12345","Category","Other brand",color,size,List.of(),"","","","6100000000",DocumentState.UNVERIFIED,"",true,russian,manufacturer);
    }
}
