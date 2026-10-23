package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.license.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationCountryModelTest {
    @TempDir Path profile;
    final FixtureClock clock=new FixtureClock();
    FixtureApi api;ZnackNationalCatalogService catalog;ZnackNationalCatalogService.CountryReadScope read;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",profile.toString());api=new FixtureApi();catalog=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty(),clock);read=catalog.registrationCountryReadScope();}
    @AfterEach void cleanup(){read.close();System.clearProperty("vncode.appdata.dir");}
    void nextOperation(){read.close();read=catalog.registrationCountryReadScope();}

    @Test void addsOnlyAnnouncedCountryAndPinsOneOfficialDictionaryForTheOperation()throws Exception {
        var model=catalog.registrationAttributes(1,"fixture");
        assertEquals(List.of(1L,2630L),model.stream().map(Attribute::id).toList());
        var country=model.getLast();assertTrue(country.presetOnly());assertTrue(country.multiple());assertEquals(List.of("RU","CN"),country.presets());assertTrue(country.valueTypes().isEmpty());
        assertEquals(List.of(1L),catalog.requiredAttributes(1,"fixture").stream().map(Attribute::id).toList());
        assertEquals(model,catalog.registrationAttributes(1,"fixture"));catalog.registrationAttributes(2,"fixture");
        assertEquals(2,api.allReads);assertEquals(1,api.dictionaryReads);assertEquals(2,api.requiredReads);
    }
    @Test void resolvesOnlyOfficialNamesAndIsoCodesFromThisOperationsVerifiedModel()throws Exception {
        var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals("RU",catalog.registrationCountryCode("Россия",schema));assertEquals("RU",catalog.registrationCountryCode(" ru ",schema));
        assertEquals("CN",catalog.registrationCountryCode("Китай",schema));assertEquals("CN",catalog.registrationCountryCode("CN",schema));
        assertEquals("",catalog.registrationCountryCode("Неизвестная страна",schema));assertEquals("",catalog.registrationCountryCode("Сделано в России",schema));
        assertEquals("",catalog.registrationCountryCode("RU",List.of(new Attribute(2630,"Foreign model","text",true,false,false,false,List.of("RU")))));
        read.close();assertEquals("",catalog.registrationCountryCode("RU",schema),"No raw-ISO fallback may reuse a dictionary outside its operation");
    }
    @Test void rejectsFlatRawAndGuessedWrappersInsteadOfSearchingForAnArray()throws Exception {
        for(String response:List.of("{\"apiversion\":3,\"result\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"}]}","[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"}]","{\"data\":{\"_list\":[]}}")){
            nextOperation();api.response=JsonParser.parseString(response);
            var failure=assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));assertEquals("CATALOG_COUNTRY_DICTIONARY_INVALID",failure.getMessage());assertNotNull(failure.getCause());
        }
        assertTrue(catalog.countryDictionaryEvidence().isEmpty());
    }
    @Test void doesNotInventCountryOrFactoryWhenCountryIsNotAnnounced()throws Exception {
        api.countryAnnounced=false;var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals(List.of(1L),schema.stream().map(Attribute::id).toList());assertEquals(0,api.dictionaryReads);assertEquals("",catalog.registrationCountryCode("RU",schema));
    }
    @Test void reusesTheFullModelAlreadyReadForConditionalRequirements()throws Exception {
        api.conditional=true;var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals(1,api.allReads);assertEquals(1,api.dictionaryReads);assertTrue(schema.getFirst().dependencies().containsKey("OTHER"));assertEquals(2630L,schema.getLast().id());
    }
    @Test void longBatchKeepsOneSnapshotAndANewOperationAlwaysReadsFreshNoStoreData()throws Exception {
        var first=catalog.registrationAttributes(1,"fixture");var evidence=catalog.countryDictionaryEvidence().orElseThrow();
        clock.now=clock.now.plusSeconds(3600);api.russiaName="Российская Федерация";
        try(var nested=catalog.registrationCountryReadScope()){
            var second=catalog.registrationAttributes(2,"fixture");assertEquals("RU",catalog.registrationCountryCode("Россия",first));assertEquals("RU",catalog.registrationCountryCode("Россия",second));assertEquals(1,api.dictionaryReads);
            assertEquals(evidence,catalog.countryDictionaryEvidence().orElseThrow());
        }
        nextOperation();var current=catalog.registrationAttributes(1,"fixture");assertEquals(2,api.dictionaryReads);
        assertEquals("",catalog.registrationCountryCode("Россия",current));assertEquals("RU",catalog.registrationCountryCode("Российская Федерация",current));
        assertNotEquals(evidence.operationId(),catalog.countryDictionaryEvidence().orElseThrow().operationId());assertNotEquals(evidence.contentHash(),catalog.countryDictionaryEvidence().orElseThrow().contentHash());
    }
    @Test void evidenceRecordsSafeOriginScopeVersionHashAndLoadTime()throws Exception {
        catalog.registrationAttributes(1,"fixture");var evidence=catalog.countryDictionaryEvidence().orElseThrow();
        assertEquals(api.nationalCatalogCountrySource(ZnackModels.Settings.empty().resolvedTrueApiBaseUrl()),evidence.source());assertTrue(evidence.source().startsWith("https://"));assertTrue(evidence.environment().startsWith("https://"));assertEquals(3,evidence.apiVersion());
        assertEquals("country-dictionary-fixture",evidence.etag());assertTrue(evidence.scopeFingerprint().matches("[0-9a-f]{64}"));assertTrue(evidence.contentHash().matches("[0-9a-f]{64}"));
        assertEquals(clock.now,evidence.loadedAt());assertEquals(2,evidence.entries());assertTrue(evidence.reusePolicy().contains("no-store"));assertFalse(evidence.toString().contains("fixture-secret-token"));
        nextOperation();catalog.registrationAttributes(1,"fixture");assertEquals(2,api.dictionaryReads,"Even immediate later operations must freshly read a no-store dictionary");
    }
    @Test void shortWbChinaNameResolvesOnlyAgainstTheLiveOfficialCnEntry()throws Exception {
        api.chinaName="КНР (Китайская Народная Республика)";
        var schema=catalog.registrationAttributes(1,"fixture");
        assertEquals("CN",catalog.registrationCountryCode("Китай",schema));
        assertEquals("CN",catalog.registrationCountryCode(api.chinaName,schema));
        assertEquals("",catalog.registrationCountryCode("Тайвань (Китай)",schema));
        nextOperation();api.chinaName="Unrecognized official name";
        assertEquals("",catalog.registrationCountryCode("Китай",catalog.registrationAttributes(1,"fixture")));
    }
    @Test void ambiguousNamesStayUnmappedButOfficialCodesNeedNotBelongToTheJdkLocaleList()throws Exception {
        api.russiaName="Одинаковое название";api.chinaName="Одинаковое название";
        var schema=catalog.registrationAttributes(1,"fixture");assertEquals("",catalog.registrationCountryCode("Одинаковое название",schema));assertEquals("",catalog.registrationCountryCode("XX",schema));assertEquals("CN",catalog.registrationCountryCode("CN",schema));
        nextOperation();api.extraCode="ZZ";var current=catalog.registrationAttributes(1,"fixture");assertEquals("ZZ",catalog.registrationCountryCode("ZZ",current));
    }
    @Test void emptyDictionaryHasAnExplicitCauseAndIsNeverCached()throws Exception {
        api.emptyDictionary=true;var failure=assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));
        assertEquals("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",failure.getMessage());assertNotNull(failure.getCause());assertEquals(1,api.dictionaryReads);assertTrue(catalog.countryDictionaryEvidence().isEmpty());
    }
    @Test void malformedRowsAreRejectedAsAWholeInsteadOfCachingTheirValidSubset()throws Exception {
        for(String badRow:List.of("null","5","{}","{\"country_iso\":\"ru\",\"country_name\":\"Россия\"}","{\"country_iso\":\" RU \",\"country_name\":\"Россия\"}","{\"country_iso\":643,\"country_name\":\"Россия\"}","{\"country_iso\":\"RU\",\"country_name\":false}","{\"country_iso\":\"RU\",\"country_name\":\"Россия\"}")){
            nextOperation();var response=api.validResponse();response.getAsJsonObject("result").getAsJsonArray("_list").add(JsonParser.parseString(badRow));api.response=response;
            assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));assertTrue(catalog.countryDictionaryEvidence().isEmpty());
        }
    }
    @Test void missingVersionEtagOrUnsupportedPaginationCannotBeTreatedAsComplete()throws Exception {
        for(String field:List.of("apiversion","_etag","_list","partial-pagination")){
            nextOperation();var response=api.validResponse();
            if(field.equals("apiversion"))response.remove(field);else if(field.equals("partial-pagination"))response.getAsJsonObject("result").addProperty("total",100);else response.getAsJsonObject("result").remove(field);
            api.response=response;assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));assertTrue(catalog.countryDictionaryEvidence().isEmpty());
        }
    }
    @Test void businessErrorEnvelopeCannotAuthorizeCountryEvenWhenItAlsoContainsValidRows()throws Exception {
        var response=api.validResponse();response.addProperty("errorCode",401);api.response=response;
        var failure=assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));
        assertEquals("CATALOG_COUNTRY_DICTIONARY_INVALID",failure.getMessage());assertTrue(failure.getCause().getMessage().contains("401"));assertTrue(catalog.countryDictionaryEvidence().isEmpty());
    }
    @Test void originalHttpAndNetworkFailuresArePreservedAndNeverFallBackToLastGoodData()throws Exception {
        var previous=catalog.registrationAttributes(1,"fixture");var evidence=catalog.countryDictionaryEvidence().orElseThrow();
        for(IOException failure:List.of(new ZnackApiClient.ZnackApiException("fixture",401,"{}"),new ZnackApiClient.ZnackApiException("fixture",403,"{}"),new ZnackApiClient.ZnackApiException("fixture",429,"{}"),new java.net.SocketTimeoutException("fixture deadline"))){
            nextOperation();api.failure=failure;assertSame(failure,assertThrows(IOException.class,()->catalog.registrationAttributes(1,"fixture")));
            assertEquals("",catalog.registrationCountryCode("RU",previous));assertEquals(evidence,catalog.countryDictionaryEvidence().orElseThrow());
            int reads=api.dictionaryReads;assertSame(failure,assertThrows(IOException.class,()->catalog.registrationAttributes(1,"fixture")));assertEquals(reads,api.dictionaryReads,"A failed operation cannot retry once for every SKU");
        }
    }
    @Test void malformedRefreshDoesNotReplaceGoodEvidenceAndAFollowingOperationCanRecover()throws Exception {
        catalog.registrationAttributes(1,"fixture");var before=catalog.countryDictionaryEvidence().orElseThrow();
        nextOperation();api.response=JsonParser.parseString("{\"apiversion\":3,\"result\":{\"_etag\":\"partial\",\"_list\":[{}]}}");
        assertThrows(IllegalStateException.class,()->catalog.registrationAttributes(1,"fixture"));assertEquals(before,catalog.countryDictionaryEvidence().orElseThrow());
        api.response=null;nextOperation();var after=catalog.registrationAttributes(1,"fixture");assertEquals("RU",catalog.registrationCountryCode("Россия",after));assertEquals(3,api.dictionaryReads);
    }
    @Test void guardsAreCheckedBeforeEveryPinnedReadAndDifferentTokensCannotShareAnOperation()throws Exception {
        var schema=catalog.registrationAttributes(1,"fixture");api.revoked=true;
        assertThrows(SecurityException.class,()->catalog.registrationAttributes(1,"fixture"));assertThrows(SecurityException.class,()->catalog.registrationCountryCode("RU",schema));assertEquals(1,api.dictionaryReads);
        api.revoked=false;assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->catalog.registrationAttributes(1,"different-account-token"));assertEquals("",catalog.registrationCountryCode("RU",schema));
    }
    @Test void cachedOperationCannotBypassLicenseOrProfileScope()throws Exception {
        var schema=catalog.registrationAttributes(1,"fixture");var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(profile.resolve("denied"))){
            try{LicenseRuntime.install(denied);assertThrows(LicenseGate.LicenseDeniedException.class,()->catalog.registrationAttributes(1,"fixture"));assertThrows(LicenseGate.LicenseDeniedException.class,()->catalog.registrationCountryCode("RU",schema));}finally{LicenseRuntime.install(prior);}
        }
        System.setProperty("vncode.appdata.dir",profile.resolve("other").toString());assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->catalog.registrationAttributes(1,"fixture"));assertEquals("",catalog.registrationCountryCode("RU",schema));assertTrue(catalog.countryDictionaryEvidence().isEmpty());assertEquals(1,api.dictionaryReads);
    }
    @Test void dictionaryFailureOrInvalidCountryStopsBeforeSendMarkerAndActualFeedPost()throws Exception {
        var marker=new AtomicInteger();var payload=feed("RU");read.close();api.emptyDictionary=true;
        assertThrows(IllegalStateException.class,()->catalog.submit("fixture",payload,marker::incrementAndGet));assertEquals(0,marker.get());assertEquals(0,api.posts);
        api.emptyDictionary=false;assertThrows(IllegalStateException.class,()->catalog.submit("fixture",feed("XX"),marker::incrementAndGet));assertEquals(0,marker.get());assertEquals(0,api.posts);
        assertEquals("fixture-feed",catalog.submit("fixture",payload,marker::incrementAndGet));assertEquals(1,marker.get());assertEquals(1,api.posts);
    }
    @Test void mandatoryCountryCannotBeOmittedEvenIfAnOlderPreparedPayloadHasNoCountry()throws Exception {
        api.mandatoryCountry=true;read.close();var payload=feed(null);var marker=new AtomicInteger();
        var failure=assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload,marker::incrementAndGet));assertTrue(failure.getMessage().contains("2630"));assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    @Test void aNewSubmissionReadsFreshDataAndRejectsACountryRemovedSincePlanning()throws Exception {
        catalog.registrationAttributes(1,"fixture");read.close();api.removeRussia=true;
        assertThrows(IllegalStateException.class,()->catalog.submit("fixture",feed("RU")));assertEquals(2,api.dictionaryReads);assertEquals(0,api.posts);
    }
    @Test void aNewAttemptDoesNotReuseAnOptionalCountryModelWhenCountryBecomesMandatory()throws Exception {
        catalog.registrationAttributes(1,"fixture");read.close();api.mandatoryCountry=true;
        var marker=new AtomicInteger();
        var failure=assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",feed(null),marker::incrementAndGet));
        assertTrue(failure.getMessage().contains("2630"));assertEquals(2,api.requiredReads);assertEquals(2,api.allReads);assertEquals(2,api.dictionaryReads);assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    @Test void aNewAttemptDoesNotReuseACountryRemovedFromTheCurrentCompleteModel()throws Exception {
        catalog.registrationAttributes(1,"fixture");read.close();api.countryAnnounced=false;
        var marker=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->catalog.submit("fixture",feed("RU"),marker::incrementAndGet));
        assertEquals(2,api.requiredReads);assertEquals(2,api.allReads);assertEquals(1,api.dictionaryReads);assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    private JsonObject feed(String country){var values=new LinkedHashMap<Long,String>();values.put(1L,"Fixture kind");if(country!=null)values.put(2630L,country);return ZnackNationalCatalogService.buildPayload("04631993764370",new Draft("6204",1,"Fixture","Brand",values),"");}
    static final class FixtureClock extends Clock {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    static final class FixtureApi extends ZnackApiClient {
        int requiredReads,allReads,dictionaryReads,posts;boolean countryAnnounced=true,conditional,emptyDictionary,mandatoryCountry,revoked,removeRussia;
        String russiaName="Россия",chinaName="Китай",extraCode="";JsonElement response;IOException failure;
        @Override public void assertCatalogReadScope(){if(revoked)throw new SecurityException("fixture credential scope changed");}
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long id){requiredReads++;var response=json(conditional?"{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\",\"dependent_attributes\":[{\"value\":\"OTHER\",\"attrs\":[{\"attr_id\":2}]}]}]}":"{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\"}]}").getAsJsonObject();if(mandatoryCountry)response.getAsJsonArray("result").add(countryField());return response;}
        @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long id){
            allReads++;var attributes=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\"},{\"attr_id\":2,\"attr_name\":\"Other kind\",\"attr_field_type\":\"text\"},{\"attr_id\":2502,\"attr_name\":\"Factory\",\"attr_value_type\":[\"RU\"]},{\"attr_id\":2503,\"attr_name\":\"Producer\"}]}");
            if(countryAnnounced)attributes.getAsJsonObject().getAsJsonArray("result").add(countryField());return attributes;
        }
        private JsonElement countryField(){return json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_multiplicity\":true,\"attr_preset\":[],\"attr_value_type\":[]}");}
        @Override public JsonElement nationalCatalogCountries(String base,String token)throws IOException {dictionaryReads++;if(failure!=null)throw failure;return response==null?validResponse():response;}
        JsonObject validResponse(){
            var rows=new JsonArray();if(!emptyDictionary){if(!removeRussia)rows.add(country("RU",russiaName));rows.add(country("CN",chinaName));if(!extraCode.isBlank())rows.add(country(extraCode,"Additional official country"));}
            var dictionary=new JsonObject();dictionary.addProperty("_etag","country-dictionary-fixture");dictionary.add("_list",rows);var response=new JsonObject();response.addProperty("apiversion",3);response.add("result",dictionary);return response;
        }
        private JsonObject country(String code,String name){var country=new JsonObject();country.addProperty("country_iso",code);country.addProperty("country_name",name);return country;}
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement payload){posts++;return json("{\"result\":{\"feed_id\":\"fixture-feed\"}}");}
        static JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
