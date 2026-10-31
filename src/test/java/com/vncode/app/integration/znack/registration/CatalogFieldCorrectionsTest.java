package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import static org.junit.jupiter.api.Assertions.*;

class CatalogFieldCorrectionsTest {
    private static final String GTIN="00000000000017";
    private static Attribute field(long id,String name,String type,boolean preset,List<String> choices){return new Attribute(id,name,type,preset,false,true,false,choices,List.of());}
    private static JsonObject payload(long id,String value){var payload=new JsonObject();payload.addProperty("gtin",GTIN);payload.addProperty("good_id",91);payload.addProperty("tnved","6103109000");var attr=new JsonObject();attr.addProperty("attr_id",id);attr.addProperty("attr_value",value);attr.addProperty("attr_value_type","");var attrs=new JsonArray();attrs.add(attr);payload.add("good_attrs",attrs);return payload;}
    private static ZnackNationalCatalogService.FeedProgress rejected(String...errors){return new ZnackNationalCatalogService.FeedProgress("Rejected",91L,List.of(errors));}
    private static String value(CatalogAutomaticRepair.Proposal proposal){return proposal.payload().getAsJsonArray("good_attrs").get(0).getAsJsonObject().get("attr_value").getAsString();}

    @Test void exactRejectedAttributeMayTrimOnlyEdgesAndKeepsIdentity(){
        var source=payload(900,"  normal text  ");var proposal=CatalogFieldCorrections.suggest(rejected("900: недопустимы пробелы в начале и конце"),source,GTIN,91,true,List.of(field(900,"Описание","text",false,List.of())));
        assertNotNull(proposal);assertEquals("normal text",value(proposal));assertEquals("  normal text  ",source.getAsJsonArray("good_attrs").get(0).getAsJsonObject().get("attr_value").getAsString());
        assertEquals(GTIN,proposal.payload().get("gtin").getAsString());assertEquals("6103109000",proposal.payload().get("tnved").getAsString());assertEquals(91,proposal.payload().get("good_id").getAsLong());
    }
    @Test void strictDateAndOnlyUnambiguousLiteralDictionaryMayBeNormalized(){
        var date=CatalogFieldCorrections.suggest(rejected("901: неверный формат даты"),payload(901,"28.05.2026"),GTIN,91,true,List.of(field(901,"Дата","date",false,List.of())));
        assertEquals("2026-05-28",value(Objects.requireNonNull(date)));
        assertNull(CatalogFieldCorrections.suggest(rejected("901: неверный формат даты"),payload(901,"31.02.2026"),GTIN,91,true,List.of(field(901,"Дата","date",false,List.of()))));
        var unique=field(902,"Отделка","text",true,List.of("Матовая","Глянцевая"));
        assertEquals("Матовая",value(Objects.requireNonNull(CatalogFieldCorrections.suggest(rejected("902: неверное значение"),payload(902,"матовая"),GTIN,91,true,List.of(unique)))));
        var ambiguous=field(902,"Отделка","text",true,List.of("Матовая","МАТОВАЯ"));
        assertNull(CatalogFieldCorrections.suggest(rejected("902: неверное значение"),payload(902,"матовая"),GTIN,91,true,List.of(ambiguous)));
    }
    @Test void removesOnlyTheIdentifiedInvisibleCharactersWhenCatalogExplicitlyRejectsThem(){
        var field=field(900,"Описание","text",false,List.of());var raw="one\u200Btwo";
        var repaired=CatalogFieldCorrections.suggest(rejected("900: недопустимые символы"),payload(900,raw),GTIN,91,true,List.of(field));assertEquals("onetwo",value(Objects.requireNonNull(repaired)));
        assertNull(CatalogFieldCorrections.suggest(rejected("900: недопустимые символы"),payload(900,"one@two"),GTIN,91,true,List.of(field)));
        assertNull(CatalogFieldCorrections.suggest(rejected("900: другое правило"),payload(900,raw),GTIN,91,true,List.of(field)));
    }
    @Test void unknownOwnershipRejectionIdentityAndProtectedFactsCannotBeChanged(){
        var name=field(900,"Описание","text",false,List.of());var original=payload(900," text ");var error=rejected("900: пробел");
        assertNull(CatalogFieldCorrections.suggest(error,original,GTIN,91,false,List.of(name)));
        assertNull(CatalogFieldCorrections.suggest(new ZnackNationalCatalogService.FeedProgress("Processing",91L,List.of()),original,GTIN,91,true,List.of(name)));
        assertNull(CatalogFieldCorrections.suggest(error,original,GTIN,92,true,List.of(name)));
        assertNull(CatalogFieldCorrections.suggest(rejected("unknown error"),original,GTIN,91,true,List.of(name)));
        assertNull(CatalogFieldCorrections.suggest(rejected("900: пробел","other: unknown"),original,GTIN,91,true,List.of(name)));
        assertNull(CatalogFieldCorrections.suggest(rejected("35: пробел"),payload(35," L "),GTIN,91,true,List.of(field(35,"Размер","text",false,List.of()))));
        assertNull(CatalogFieldCorrections.suggest(rejected("900: пробел"),original,GTIN,91,true,List.of(field(900,"Артикул производителя","text",false,List.of()))));
    }
    @Test void ambiguousFieldNameOrDuplicatePayloadCannotBeCorrected(){
        var a=field(900,"Description","text",false,List.of());var b=field(901,"Description","text",false,List.of());
        assertNull(CatalogFieldCorrections.suggest(rejected("Description: whitespace"),payload(900," text "),GTIN,91,true,List.of(a,b)));
        var repeated=payload(900," text ");repeated.getAsJsonArray("good_attrs").add(repeated.getAsJsonArray("good_attrs").get(0).deepCopy());
        assertNull(CatalogFieldCorrections.suggest(rejected("900: whitespace"),repeated,GTIN,91,true,List.of(a)));
        var sameIdDifferentSchema=field(900,"Other description","text",false,List.of());
        assertNull(CatalogFieldCorrections.suggest(rejected("900: whitespace"),payload(900," text "),GTIN,91,true,List.of(a,sameIdDifferentSchema)));
        var missing=payload(900," text ");missing.add("good_attrs",new JsonArray());
        assertNull(CatalogFieldCorrections.suggest(rejected("900: обязательное поле отсутствует"),missing,GTIN,91,true,List.of(a)));
    }
    @Test void anAbsentGoodIdIsRestoredOnlyWithIndependentProofAndAudited(){
        var field=field(900,"Description","text",false,List.of());var source=payload(900," text ");source.remove("good_id");
        var result=CatalogFieldCorrections.suggest(rejected("900: whitespace"),source,GTIN,91,true,List.of(field));
        assertNotNull(result);assertEquals(91,result.payload().get("good_id").getAsLong());
        assertTrue(result.changes().stream().anyMatch(c->c.field().equals("good_id")&&c.source().equals("CATALOG_VERIFIED_IDENTITY")));
        assertNull(CatalogFieldCorrections.suggest(rejected("900: whitespace"),source,GTIN,91,false,List.of(field)));
    }
}
