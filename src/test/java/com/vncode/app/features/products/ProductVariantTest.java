package com.vncode.app.features.products;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductVariantTest {
    @Test void legacyUnverifiedBarcodeMovesToItsOwnFieldWithoutLosingLeadingZeros(){
        var value=ProductJson.GSON.fromJson("{\"id\":\"001\",\"sellerArticle\":\"A\",\"color\":\"Blue\",\"size\":\"52\",\"gtin\":\"00000000000017\",\"gtinVerified\":false}",ProductVariant.class);
        assertEquals("001",value.id());assertEquals("",value.gtin());assertEquals(List.of("00000000000017"),value.barcodes());assertFalse(value.gtinVerified());assertEquals(ProductVariant.Verification.UNVERIFIED,value.gtinEvidence().status());
        assertEquals(value,ProductJson.GSON.fromJson(ProductJson.GSON.toJson(value),ProductVariant.class));
    }
    @Test void oldVerifiedFlagRetainsHistoricalCodeButCannotBecomeCurrentProof(){
        var value=ProductJson.GSON.fromJson("{\"id\":\"v\",\"gtin\":\"00000000000017\",\"gtinVerified\":true}",ProductVariant.class);
        assertEquals("00000000000017",value.gtin());assertFalse(value.gtinVerified());assertEquals(ProductVariant.Verification.HISTORICAL,value.gtinEvidence().status());assertNull(value.gtinEvidence().checkedAt());
    }
    @Test void separateBarcodesAndCurrentProofRoundTripWithoutNormalizingTheirDigits(){
        var evidence=new ProductVariant.GtinEvidence(ProductVariant.Verification.VERIFIED,"National Catalog@"+"a".repeat(64),Instant.now(),"b".repeat(64),"123","published");
        var value=new ProductVariant("v","A","Blue","52","00000000000017",true,"52",List.of("0000000000017","OZON-RAW"),evidence);
        assertTrue(value.gtinVerified());assertEquals(value,ProductJson.GSON.fromJson(ProductJson.GSON.toJson(value),ProductVariant.class));assertThrows(UnsupportedOperationException.class,()->value.barcodes().add("bad"));
        var invalid=new ProductVariant("v","A","Blue","52","00000000000018",true,"52",List.of("RAW"),evidence);
        assertFalse(invalid.gtinVerified());assertEquals(ProductVariant.Verification.HISTORICAL,invalid.gtinEvidence().status());assertEquals("00000000000018",invalid.gtin());
    }
    @Test void staleProofIsReadableAsHistoricalAndPreservesItsOriginalEvidence(){
        var evidence=new ProductVariant.GtinEvidence(ProductVariant.Verification.VERIFIED,"National Catalog@"+"a".repeat(64),Instant.now().minusSeconds(90000),"b".repeat(64),"123","published");
        var value=new ProductVariant("v","A","Blue","52","00000000000017",true,"52",List.of("RAW"),evidence);
        assertFalse(value.gtinVerified());assertEquals("00000000000017",value.gtin());assertEquals(ProductVariant.Verification.HISTORICAL,value.gtinEvidence().status());assertEquals(evidence.checkedAt(),value.gtinEvidence().checkedAt());assertEquals("123",value.gtinEvidence().catalogId());
    }
}
