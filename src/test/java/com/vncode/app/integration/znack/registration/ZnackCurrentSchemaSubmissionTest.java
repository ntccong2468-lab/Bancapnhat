package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCurrentSchemaSubmissionTest {
    @TempDir Path profile;String previous;
    FixtureApi api;ZnackNationalCatalogService catalog;
    @BeforeEach void setup(){previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());api=new FixtureApi();catalog=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());}
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}

    @Test void newMandatoryFieldCannotUseAnEarlierCachedReview()throws Exception {
        catalog.requiredAttributes(1,"fixture");api.required=fields(field(1,"Kind"),field(2,"New required field"));var marker=new AtomicInteger();
        var failure=assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload(Map.of(1L,"Suit")),marker::incrementAndGet));
        assertTrue(failure.getMessage().contains("New required field [2]"));assertEquals(2,api.requiredReads);assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    @Test void preallocationDraftValidationNeverInventsMissingMandatoryValues(){
        api.required=fields(field(1,"Kind"),field(2,"Required composition"));
        var failure=assertThrows(RegistrationValidationException.class,()->catalog.validateCurrentRegistrationDraft("fixture",draft(Map.of(1L,"Suit"))));
        assertTrue(failure.getMessage().contains("[2]"));assertEquals(0,api.posts);
    }
    @Test void conditionalRequirementUsesTheFreshFullModelBeforeSending(){
        api.required=JsonParser.parseString("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Kind\",\"attr_field_type\":\"text\",\"dependent_attributes\":[{\"value\":\"OTHER\",\"attrs\":[{\"attr_id\":2}]}]}]}");
        api.all=fields(field(1,"Kind"),field(2,"Other kind description"));
        var error=assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload(Map.of(1L,"OTHER"))));
        assertTrue(error.getMessage().contains("Other kind description [2]"));assertEquals(1,api.allReads);assertEquals(0,api.posts);
    }
    @Test void currentPresetAndValueTypeAreValidatedWithoutRepairingThePayload(){
        var schema=field(1,"Kind");schema.addProperty("attr_preset_only",true);schema.add("attr_preset",JsonParser.parseString("[\"Suit\"]"));schema.add("attr_value_type",JsonParser.parseString("[\"Current type\"]"));api.required=fields(schema);
        var value=payload(Map.of(1L,"Suit"));var original=value.deepCopy();
        assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",value));assertEquals(original,value);assertEquals(0,api.posts);
        value.getAsJsonArray("good_attrs").get(0).getAsJsonObject().addProperty("attr_value_type","Current type");
        value.getAsJsonArray("good_attrs").get(0).getAsJsonObject().addProperty("attr_value","Unlisted kind");
        assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",value));assertEquals(0,api.posts);
    }
    @Test void repeatedSingleValueMandatoryFieldIsRejectedBeforeMarker(){
        var value=payload(Map.of(1L,"Suit"));value.getAsJsonArray("good_attrs").add(value.getAsJsonArray("good_attrs").get(0).deepCopy());var marker=new AtomicInteger();
        assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",value,marker::incrementAndGet));assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    @Test void invalidNumericFieldCannotReachTheCatalog(){
        var field=field(1,"Amount");field.addProperty("attr_field_type","number");api.required=fields(field);
        assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload(Map.of(1L,"many"))));assertEquals(0,api.posts);
    }
    @Test void currentSchemaAuthorizationAndNetworkFailuresNeverDispatch(){
        for(IOException error:List.of(new ZnackApiClient.ZnackApiException("fixture",401,"{}"),new ZnackApiClient.ZnackApiException("fixture",429,"{}"),new java.net.SocketTimeoutException("fixture deadline"))){
            api.failure=error;var marker=new AtomicInteger();assertSame(error,assertThrows(IOException.class,()->catalog.submit("fixture",payload(Map.of(1L,"Suit")),marker::incrementAndGet)));assertEquals(0,marker.get());assertEquals(0,api.posts);
        }
    }
    @Test void validReviewedPayloadIsSentExactlyOnceWithoutRewritingBusinessValues()throws Exception {
        var value=payload(Map.of(1L,"Suit"));var original=value.deepCopy();var marker=new AtomicInteger();
        assertEquals("fixture-feed",catalog.submit("fixture",value,marker::incrementAndGet));assertEquals(1,marker.get());assertEquals(1,api.posts);assertEquals(original,api.submitted);assertEquals(original,value);
    }
    @Test void finalSubmitRefreshesTheModelEvenInsideAnExistingOperation()throws Exception {
        try(var operation=catalog.registrationCountryReadScope()){
            catalog.validateCurrentRegistrationDraft("fixture",draft(Map.of(1L,"Suit")));api.required=fields(field(1,"Kind"),field(2,"Added after approval"));
            assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload(Map.of(1L,"Suit"))));assertEquals(2,api.requiredReads);assertEquals(0,api.posts);
        }
    }
    @Test void removedOptionalAttributeCannotBeAllocatedOrSentUsingAnEarlierSchema(){
        var values=Map.of(1L,"Suit",2L,"Cotton");
        var beforeAllocation=assertThrows(RegistrationValidationException.class,()->catalog.validateCurrentRegistrationDraft("fixture",draft(values)));
        assertTrue(beforeAllocation.getMessage().contains("[2]"));var marker=new AtomicInteger();
        assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",payload(values),marker::incrementAndGet));assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    @Test void optionalValuesMustMatchCurrentPresetsAndTypesBeforeAllocationOrPost(){
        var optional=field(2,"Composition");optional.addProperty("attr_preset_only",true);optional.add("attr_preset",JsonParser.parseString("[\"Cotton\"]"));optional.add("attr_value_type",JsonParser.parseString("[\"Current composition type\"]"));api.all=fields(field(1,"Kind"),optional);
        var wrongType=draft(Map.of(1L,"Suit",2L,"Cotton"));
        assertThrows(RegistrationValidationException.class,()->catalog.validateCurrentRegistrationDraft("fixture",wrongType));
        var wrongPreset=payload(Map.of(1L,"Suit",2L,"Unlisted material"));
        for(var raw:wrongPreset.getAsJsonArray("good_attrs"))if(raw.getAsJsonObject().get("attr_id").getAsLong()==2L)raw.getAsJsonObject().addProperty("attr_value_type","Current composition type");
        var marker=new AtomicInteger();assertThrows(RegistrationValidationException.class,()->catalog.submit("fixture",wrongPreset,marker::incrementAndGet));assertEquals(0,marker.get());assertEquals(0,api.posts);
    }
    private static Draft draft(Map<Long,String> values){return new Draft("6110209100",1,"Fixture product","Fixture brand",values);}
    private static JsonObject payload(Map<Long,String> values){return ZnackNationalCatalogService.buildPayload("04631993764370",draft(values),"");}
    private static JsonObject field(long id,String name){var field=new JsonObject();field.addProperty("attr_id",id);field.addProperty("attr_name",name);field.addProperty("attr_field_type","text");return field;}
    private static JsonElement fields(JsonObject... fields){var response=new JsonObject();var list=new JsonArray();for(var field:fields)list.add(field);response.add("result",list);return response;}
    static final class FixtureApi extends ZnackApiClient {
        int requiredReads,allReads,posts;IOException failure;JsonElement required=fields(field(1,"Kind")),all=fields(field(1,"Kind"));JsonElement submitted;
        @Override public JsonElement nationalCatalogAttributes(String b,String t,long c)throws IOException{requiredReads++;if(failure!=null)throw failure;return required.deepCopy();}
        @Override public JsonElement nationalCatalogAllAttributes(String b,String t,long c){allReads++;return all.deepCopy();}
        @Override public JsonElement submitNationalCatalogFeed(String b,String t,JsonElement payload){posts++;submitted=payload.deepCopy();return JsonParser.parseString("{\"result\":{\"feed_id\":\"fixture-feed\"}}");}
    }
}
