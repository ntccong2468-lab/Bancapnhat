package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRepairImageEvidenceTest {
    private static JsonObject card(String url){
        var card=new JsonObject();card.addProperty("good_id",91);card.addProperty("gtin",GtinWriteTestFixture.GTIN);
        var image=new JsonObject();image.addProperty("photo_type","left");image.addProperty("photo_url",url);image.addProperty("barcode",GtinWriteTestFixture.GTIN);
        image.addProperty("photo_date","2026-01-01");var images=new JsonArray();images.add(image);card.add("good_images",images);return card;
    }
    @Test void preservesCatalogPhotoAndBindsTheExactIdentifier(){
        var result=CatalogRepairImageEvidence.fromCatalog(card("https://example.test/a.jpg"),GtinWriteTestFixture.GTIN,91);
        var image=result.values().get(0).getAsJsonObject();assertEquals("7",image.get("photo_type").getAsString());
        assertEquals("https://example.test/a.jpg",image.get("photo_url").getAsString());
        assertEquals(GtinWriteTestFixture.GTIN,image.get("identifier").getAsString());assertEquals("gtin",image.get("identifier_type").getAsString());
        assertFalse(image.has("photo_date"));assertNotEquals(result.hash(),CatalogRepairImageEvidence.fromCatalog(card("https://example.test/b.jpg"),GtinWriteTestFixture.GTIN,91).hash());
    }
    @Test void missingImageEvidenceAndDifferentProductAreBlocked(){
        var missing=card("https://example.test/a.jpg");missing.remove("good_images");assertThrows(RegistrationValidationException.class,()->CatalogRepairImageEvidence.fromCatalog(missing,GtinWriteTestFixture.GTIN,91));
        var wrong=card("https://example.test/a.jpg");wrong.addProperty("good_id",92);assertThrows(RegistrationValidationException.class,()->CatalogRepairImageEvidence.fromCatalog(wrong,GtinWriteTestFixture.GTIN,91));
        var type=card("https://example.test/a.jpg");type.getAsJsonArray("good_images").get(0).getAsJsonObject().addProperty("photo_type","unknown");assertThrows(RegistrationValidationException.class,()->CatalogRepairImageEvidence.fromCatalog(type,GtinWriteTestFixture.GTIN,91));
        var bad=card("https://example.test/a.jpg");bad.getAsJsonArray("good_images").get(0).getAsJsonObject().addProperty("photo_url","file:///private");assertThrows(RegistrationValidationException.class,()->CatalogRepairImageEvidence.fromCatalog(bad,GtinWriteTestFixture.GTIN,91));
    }
}
