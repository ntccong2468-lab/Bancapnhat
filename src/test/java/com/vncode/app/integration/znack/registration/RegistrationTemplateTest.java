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

    @Test void signedReferenceStructureKeepsEachVariantsSizeSystemArticleAndPermitIndependent(){
        String[] gtins={"00000000000017","00000000000024","00000000000031"};
        String[] sizes={"52","176-104","XL"};
        String[] systems={"РОССИЯ","РОСТ-ОГ","МЕЖДУНАРОДНЫЙ"};
        String[] articleTypes={"Артикул","Модель","Артикул"};
        for(int index=0;index<sizes.length;index++){
            var draft=new Draft("6103220000","6103",30733,"Synthetic garment "+index,"Synthetic brand "+index,
                    Map.of(35L,sizes[index],13914L,"SYNTHETIC-ARTICLE-"+index,13933L,"6103220000",23557L,"SYNTHETIC-PERMIT-"+index+":::2026-01-01"),
                    Map.of(35L,systems[index],13914L,articleTypes[index]));
            var payload=RegistrationTemplate.defaults().build(gtins[index],draft,"");
            assertEquals(gtins[index],payload.get("gtin").getAsString());
            var attrs=payload.getAsJsonArray("good_attrs").asList().stream().map(e->e.getAsJsonObject())
                    .collect(java.util.stream.Collectors.toMap(a->a.get("attr_id").getAsLong(),a->a));
            assertEquals(sizes[index],attrs.get(35L).get("attr_value").getAsString());
            assertEquals(systems[index],attrs.get(35L).get("attr_value_type").getAsString());
            assertEquals(articleTypes[index],attrs.get(13914L).get("attr_value_type").getAsString());
            assertEquals("SYNTHETIC-ARTICLE-"+index,attrs.get(13914L).get("attr_value").getAsString());
            assertEquals("SYNTHETIC-PERMIT-"+index+":::2026-01-01",attrs.get(23557L).get("attr_value").getAsString());
            assertEquals("6103220000",attrs.get(13933L).get("attr_value").getAsString());
            assertFalse(attrs.containsKey(3959L));
            for(String responseOnly:java.util.List.of("good_id","good_status","good_signed","producer_inn","producer_name","create_date","first_sign_date","update_date"))
                assertFalse(payload.has(responseOnly),responseOnly);
        }
    }
}
