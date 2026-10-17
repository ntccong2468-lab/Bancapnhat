package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationTemplateTest {
    @Test void setsMatchTheCurrentWbcodePackagingRuleAndSurviveStoredPayloadRecovery(){
        var draft=new Draft("6112110000","6112",321,"Synthetic suit","Fixture",Map.of(35L,"52"),Map.of(35L,"---")).withProductCategory("Костюмы спортивные").withAutoSign(true);
        var payload=ZnackNationalCatalogService.buildPayload("00000000000017",draft,"");assertTrue(payload.get("is_kit").getAsBoolean());
        assertEquals("",payload.getAsJsonArray("good_attrs").get(0).getAsJsonObject().get("attr_value_type").getAsString());
        assertTrue(ZnackCardRegistrationWorkflow.draftFromPayload(payload).isKit());assertTrue(draft.withAutoSign(false).isKit());
        var exception=new Draft("6112120000","6112",321,"Synthetic suit","Fixture",Map.of(),Map.of()).withProductCategory("Костюмы спортивные");
        assertFalse(exception.isKit());assertFalse(ZnackNationalCatalogService.buildPayload("00000000000017",exception,"").has("is_kit"));
        assertFalse(new Draft("6109100000",321,"T-shirt","Fixture",Map.of()).withProductCategory("Футболки").isKit());
    }
    @Test void sharedDefaultUsesEachSelectedProductsValuesAndSchemaWithoutCopyingTheExample(){
        var template=RegistrationTemplate.defaults();assertFalse(template.prompt().isBlank());
        var draft=new Draft("6112110000","6112",321,"\u00a0 Bộ nâu 52 \u200b","  DASHOA\u00a0",
                Map.of(13914L," 152DASH-52 ",35L," 52 ",23557L," TEST-DOCUMENT:::2026-01-01 "),Map.of(35L," РОССИЯ "));
        var payload=ZnackNationalCatalogService.buildPayload(" 00000000000017 ",draft," https://example.invalid/product.jpg ");
        assertEquals("00000000000017",payload.get("gtin").getAsString());
        assertEquals("Bộ nâu 52",payload.get("good_name").getAsString());assertEquals("DASHOA",payload.get("brand").getAsString());
        assertEquals(321,payload.getAsJsonArray("categories").get(0).getAsInt());assertEquals(1,payload.get("moderation").getAsInt());
        var identity=payload.getAsJsonArray("identified_by").get(0).getAsJsonObject();assertEquals("00000000000017",identity.get("value").getAsString());assertEquals("trade-unit",identity.get("level").getAsString());
        var size=payload.getAsJsonArray("good_attrs").asList().stream().map(e->e.getAsJsonObject()).filter(a->a.get("attr_id").getAsLong()==35).findFirst().orElseThrow();
        assertEquals("52",size.get("attr_value").getAsString());assertEquals("РОССИЯ",size.get("attr_value_type").getAsString());
        assertFalse(payload.toString().contains("${"));assertFalse(payload.has("good_id"));assertFalse(payload.has("producer_inn"));assertFalse(payload.has("good_status"));
        payload.addProperty("brand","Mutated result");
        assertEquals("DASHOA",template.build("00000000000017",draft,"").get("brand").getAsString(),"A returned payload must not mutate the saved default");
    }
}
