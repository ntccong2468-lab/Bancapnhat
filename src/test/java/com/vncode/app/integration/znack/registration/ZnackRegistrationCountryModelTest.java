package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationCountryModelTest {
    @TempDir Path profile;
    final FixtureClock clock=new FixtureClock();
    FixtureApi api;ZnackNationalCatalogService catalog;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",profile.toString());api=new FixtureApi();catalog=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty(),clock);}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void addsOnlyTheCurrentCountryFieldAndCachesItsOfficialDictionary()throws Exception {
        var model=catalog.registrationAttributes(1,"fixture");
        assertEquals(List.of(1L,2630L),model.stream().map(Attribute::id).toList());
        var country=model.getLast();assertTrue(country.presetOnly());assertTrue(country.multiple());assertEquals(List.of("RU","CN"),country.presets());assertTrue(country.valueTypes().isEmpty());
        assertEquals(List.of(1L),catalog.requiredAttributes(1,"fixture").stream().map(Attribute::id).toList(),"Optional country does not alter the mandatory API model");
        assertEquals(model,catalog.registrationAttributes(1,"fixture"));catalog.registrationAttributes(2,"fixture");
        assertEquals(2,api.allReads);assertEquals(1,api.dictionaryReads);assertEquals(2,api.requiredReads);
    }
    @Test void resolvesExplicitNamesAndIsoCodesWithoutReplacingAnotherCountryWithRussia()throws Exception {
        var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals("RU",catalog.registrationCountryCode("Россия",schema));assertEquals("RU",catalog.registrationCountryCode(" ru ",schema));
        assertEquals("CN",catalog.registrationCountryCode("Китай",schema));assertEquals("CN",catalog.registrationCountryCode("CN",schema));
        assertEquals("",catalog.registrationCountryCode("Неизвестная страна",schema));assertEquals("",catalog.registrationCountryCode("Сделано в России",schema));
        assertEquals(1,api.dictionaryReads);
    }
    @Test void stillAcceptsTheFlatResultArrayWithoutChangingOfficialCountryValues()throws Exception {
        api.flatDictionary=true;
        var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals(List.of("RU","CN"),schema.getLast().presets());
        assertEquals("RU",catalog.registrationCountryCode("Россия",schema));
        assertEquals("CN",catalog.registrationCountryCode("Китай",schema));
    }
    @Test void doesNotInventCountryOrFactoryFieldsWhenCountryIsNotAnnounced()throws Exception {
        api.countryAnnounced=false;var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals(List.of(1L),schema.stream().map(Attribute::id).toList());assertEquals(0,api.dictionaryReads);
        assertEquals("",catalog.registrationCountryCode("RU",schema));
    }
    @Test void reusesTheFullModelAlreadyReadForConditionalRequirements()throws Exception {
        api.conditional=true;var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals(1,api.allReads);assertEquals(1,api.dictionaryReads);
        assertTrue(schema.getFirst().dependencies().containsKey("OTHER"));assertEquals(2630L,schema.getLast().id());
    }
    @Test void dictionaryAndFullSchemaExpireWithoutSleeping()throws Exception {
        catalog.registrationAttributes(1,"fixture");clock.now=clock.now.plusSeconds(121);api.russiaName="Российская Федерация";
        var current=catalog.registrationAttributes(1,"fixture");assertEquals(2,api.allReads);assertEquals(2,api.dictionaryReads);
        assertEquals("",catalog.registrationCountryCode("Россия",current));assertEquals("RU",catalog.registrationCountryCode("Российская Федерация",current));
    }
    @Test void ambiguousNamesAndUnsupportedCountryCodesCannotBecomeDefaults()throws Exception {
        api.russiaName="Одинаковое название";api.chinaName="Одинаковое название";
        var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals("",catalog.registrationCountryCode("Одинаковое название",schema));assertEquals("",catalog.registrationCountryCode("XX",schema));
        assertEquals("CN",catalog.registrationCountryCode("CN",schema));
    }
    @Test void missingDictionaryFailsClosedInsteadOfTreatingAnEmptyPresetAsAnAllowedValue()throws Exception {
        api.emptyDictionary=true;
        var failure=assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));
        assertEquals("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",failure.getMessage());assertEquals(1,api.dictionaryReads);
    }
    @Test void aServiceCannotReadCountryMetadataForAnotherProfile()throws Exception {
        catalog.registrationAttributes(1,"fixture");System.setProperty("vncode.appdata.dir",profile.resolve("other").toString());
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->catalog.registrationAttributes(1,"fixture"));
        assertEquals(1,api.dictionaryReads);
    }
    static final class FixtureClock extends Clock {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    static final class FixtureApi extends ZnackApiClient {
        int requiredReads,allReads,dictionaryReads;boolean countryAnnounced=true,conditional,emptyDictionary,flatDictionary;
        String russiaName="Россия",chinaName="Китай";
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long id){requiredReads++;return json(conditional?"{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\",\"dependent_attributes\":[{\"value\":\"OTHER\",\"attrs\":[{\"attr_id\":2}]}]}]}":"{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\"}]}");}
        @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long id){
            allReads++;var attributes=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\"},{\"attr_id\":2,\"attr_name\":\"Other kind\",\"attr_field_type\":\"text\"},{\"attr_id\":2502,\"attr_name\":\"Factory\",\"attr_value_type\":[\"RU\"]},{\"attr_id\":2503,\"attr_name\":\"Producer\"}]}");
            if(countryAnnounced)attributes.getAsJsonObject().getAsJsonArray("result").add(json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_multiplicity\":true,\"attr_preset\":[],\"attr_value_type\":[]}"));return attributes;
        }
        @Override public JsonElement nationalCatalogCountries(String base,String token){
            dictionaryReads++;var result=new JsonArray();
            if(!emptyDictionary)for(var pair:List.of(List.of("RU",russiaName),List.of("CN",chinaName))){var country=new JsonObject();country.addProperty("country_iso",pair.getFirst());country.addProperty("country_name",pair.getLast());result.add(country);}
            var response=new JsonObject();var dictionary=new JsonObject();
            dictionary.addProperty("_etag","country-dictionary-fixture");dictionary.add("_list",result);
            response.add("result",flatDictionary?result:dictionary);return response;
        }
        static JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
