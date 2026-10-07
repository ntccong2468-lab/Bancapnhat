package com.vncode.app.features.gtinsync;

import com.google.gson.Gson;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinProductReaderTest {
    @Test void wbReadsExactSizeAndStableFingerprint() {
        var card=new Gson().fromJson("""
            {"nmID":101,"vendorCode":"PANTS","characteristics":[{"name":"Цвет","value":["Đen"]}],
            "sizes":[{"chrtID":11,"techSize":"XL","skus":["00000000000017","other"]},
            {"chrtID":12,"techSize":"L","skus":["00000000000024"]}]}
            """,WbProductCard.class);
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");
        var a=WbGtinProductReader.fromCard(key,card);
        assertEquals("XL",a.size());assertEquals("Đen",a.color());assertEquals(List.of("00000000000017","other"),a.barcodes());
        assertEquals(a.fingerprint(),WbGtinProductReader.fromCard(key,card).fingerprint());
        assertThrows(IllegalArgumentException.class,()->WbGtinProductReader.fromCard(new ProductKey(1,Marketplace.OZON,"101","11"),card));
    }
    @Test void ozonBindsProductAndOfferIdentity() {
        var p=new OzonProductDto("101","offer-1","999","Name","","PANTS","Đen","XL",false,"",List.of("00000000000017"));
        var key=new ProductKey(2,Marketplace.OZON,"101","offer-1");
        assertEquals("00000000000017",OzonGtinProductReader.fromProduct(key,p).barcodes().getFirst());
        assertThrows(IllegalArgumentException.class,()->OzonGtinProductReader.fromProduct(new ProductKey(2,Marketplace.OZON,"101","wrong"),p));
    }
}
