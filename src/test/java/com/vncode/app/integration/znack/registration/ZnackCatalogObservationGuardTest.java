package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCatalogObservationGuardTest {
    static final String GTIN="04631993764370";
    @TempDir Path profile;String previous;FixtureApi api;ZnackNationalCatalogService catalog;
    @BeforeEach void setup(){previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());api=new FixtureApi();catalog=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());}
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void malformedSuccessfulFeedReadsCannotBecomeNotFoundOrProcessing(){
        for(String raw:List.of("null","[]","{}","{\"result\":[]}","{\"result\":{}}","{\"result\":{\"status\":null}}","{\"result\":{\"status\":false}}","{\"result\":{\"status\":\" \"}}","{\"result\":{\"status\":\"Received\",\"item\":{}}}","{\"result\":{\"status\":\"Received\",\"item\":[null]}}")){
            api.feed=JsonParser.parseString(raw);var failure=assertThrows(IllegalStateException.class,()->catalog.submissionStatus("fixture","feed",GTIN));
            assertEquals("CATALOG_FEED_RESPONSE_INVALID",failure.getMessage());assertEquals(0,api.cardReads);
        }
    }
    @Test void exactObservedCardExposesRemoteIdentityStatusAndReadTime()throws Exception {
        api.card=remote(payload());var observed=catalog.observedCardStatus("fixture",GTIN,91L);
        assertEquals(91,observed.goodId());assertEquals(GTIN,observed.gtin());assertEquals("draft",observed.rawStatus());assertTrue(observed.progress().readyToSign());assertNotNull(observed.checkedAt());assertEquals("Fixture brand",observed.details().single("brand"));assertEquals(1,api.cardReads);
    }
    @Test void tradeUnitEvidenceComesOnlyFromTheMatchingRemoteGtinIdentifier()throws Exception{
        api.card=remote(payload());api.card.remove("identified_by");assertFalse(catalog.observedCardStatus("fixture",GTIN,91L).tradeUnit());
        for(String value:List.of("{\"type\":\"gtin\",\"value\":\""+GTIN+"\",\"level\":\"trade-unit\"}","{\"type\":\"gtin\",\"value\":\"04631993764363\",\"level\":\"trade-unit\"}","{\"type\":\"gtin\",\"value\":\""+GTIN+"\",\"level\":\"case\"}")){
            api.card.add("identified_by",JsonParser.parseString("["+value+"]"));assertEquals(value.contains(GTIN)&&value.contains("trade-unit"),catalog.observedCardStatus("fixture",GTIN,91L).tradeUnit());
        }
        api.card.add("identified_by",JsonParser.parseString("[{\"type\":\"gtin\",\"value\":\""+GTIN+"\",\"level\":\"trade-unit\"},{\"type\":\"gtin\",\"value\":\""+GTIN+"\",\"level\":\"case\"}]"));assertFalse(catalog.observedCardStatus("fixture",GTIN,91L).tradeUnit());
    }
    @Test void aPublishedCardCanBeIdentifiedByItsActualIdentifierWithoutATopLevelGtin()throws Exception{
        api.card=remote(payload());api.card.remove("gtin");api.card.addProperty("good_status","published");api.card.addProperty("good_signed",true);api.card.add("good_detailed_status",JsonParser.parseString("[\"published\"]"));api.card.add("identified_by",JsonParser.parseString("[{\"type\":\"gtin\",\"value\":\""+GTIN+"\",\"level\":\"trade-unit\"}]"));var observed=catalog.observedCardStatus("fixture",GTIN,91L);assertTrue(observed.tradeUnit());assertTrue(observed.progress().signed());assertEquals("91",observed.details().single("@catalog_id"));assertEquals("published",observed.details().single("@catalog_status"));
    }
    @Test void onlyAValidEmptyExactCardReadConfirmsAbsence()throws Exception {
        api.card=null;assertNull(catalog.observedCardStatus("fixture",GTIN,null));
        api.responseOverride=JsonParser.parseString("{\"result\":{}}");assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,null));
        api.responseOverride=null;api.failure=new ZnackApiClient.ZnackApiException("fixture",404,"{}");assertSame(api.failure,assertThrows(IOException.class,()->catalog.observedCardStatus("fixture",GTIN,null)));
    }
    @Test void expiredFeedAndUnavailableCardReadStayUnknownInsteadOfNotFound(){
        api.feedFailure=new ZnackApiClient.ZnackApiException("fixture",404,"{}");api.failure=new ZnackApiClient.ZnackApiException("fixture",404,"{}");
        assertSame(api.failure,assertThrows(IOException.class,()->catalog.progress("fixture","expired-feed",GTIN)));
    }
    @Test void changedIdentityOwnerOrMissingStatusCannotBeAdopted(){
        api.card=remote(payload());api.card.addProperty("good_id",92);assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
        api.card=remote(payload());api.card.addProperty("owner_inn","7700000000");assertThrows(RegistrationValidationException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
        api.card=remote(payload());api.card.remove("good_status");assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
        api.card=remote(payload());api.card.addProperty("good_status","future_status");assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
        api.card=remote(payload());api.card.addProperty("good_id",91.5);assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
        api.card=remote(payload());api.card.addProperty("goodId",92);assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GTIN,91L));
    }
    @Test void unchangedReviewedBusinessValuesCanBindTheCurrentSigningDocument()throws Exception {
        var approved=payload();api.card=remote(approved);assertDoesNotThrow(()->catalog.validateApprovedSigningCard("fixture",GTIN,91L,approved));
    }
    @Test void changedBusinessContentUnderTheSameGtinRequiresFreshApproval(){
        var approved=payload();
        for(String field:List.of("brand","good_name","tnved")){
            api.card=remote(approved);api.card.addProperty(field,"changed");assertReviewRequired(approved);
        }
        api.card=remote(approved);api.card.getAsJsonArray("good_attrs").get(0).getAsJsonObject().addProperty("attr_value","54");assertReviewRequired(approved);
        api.card=remote(approved);api.card.getAsJsonArray("good_attrs").get(0).getAsJsonObject().addProperty("attr_value_type","РОССИЯ");assertReviewRequired(approved);
        api.card=remote(approved);api.card.remove("categories");assertReviewRequired(approved);
        api.card=remote(approved);api.card.addProperty("is_kit",true);assertReviewRequired(approved);
        api.card=remote(approved);var extra=new JsonObject();extra.addProperty("attr_id",2483);extra.addProperty("attr_value","Cotton");api.card.getAsJsonArray("good_attrs").add(extra);assertReviewRequired(approved);
    }
    @Test void anUnknownRemoteAttributeShapeRequiresManualReviewInsteadOfGuessedEquivalence(){
        var approved=payload();api.card=remote(approved);var value=api.card.getAsJsonArray("good_attrs").get(0).getAsJsonObject();value.remove("attr_value");value.add("showValue",JsonParser.parseString("{\"name\":\"52\"}"));assertReviewRequired(approved);
    }
    @Test void currentCardMustStillBeReadyAndBelongToTheApprovedIdentity(){
        var approved=payload();api.card=remote(approved);api.card.add("good_detailed_status",JsonParser.parseString("[\"moderation\"]"));assertReviewRequired(approved);
        api.card=remote(approved);assertThrows(RegistrationValidationException.class,()->catalog.validateApprovedSigningCard("fixture",GTIN,null,approved));
        var other=approved.deepCopy();other.addProperty("gtin","04631993764363");assertThrows(RegistrationValidationException.class,()->catalog.validateApprovedSigningCard("fixture",GTIN,91L,other));
    }
    private void assertReviewRequired(JsonObject approved){var failure=assertThrows(RegistrationValidationException.class,()->catalog.validateApprovedSigningCard("fixture",GTIN,91L,approved));assertEquals("CATALOG_SIGNING_REVIEW_REQUIRED",failure.getMessage());}
    private static JsonObject payload(){return ZnackNationalCatalogService.buildPayload(GTIN,new Draft("6110209100",1,"Fixture product","Fixture brand",Map.of(35L,"52")),"");}
    private static JsonObject remote(JsonObject payload){var card=payload.deepCopy();card.addProperty("good_id",91);card.addProperty("good_status","draft");card.addProperty("good_signed",false);card.add("good_detailed_status",JsonParser.parseString("[\"notsigned\"]"));return card;}
    static final class FixtureApi extends ZnackApiClient {
        JsonElement feed=JsonParser.parseString("{\"result\":{\"status\":\"Received\"}}"),responseOverride;JsonObject card;IOException failure,feedFailure;int cardReads;
        @Override public JsonElement nationalCatalogFeedStatus(String b,String t,String f)throws IOException{if(feedFailure!=null)throw feedFailure;return feed;}
        @Override public JsonElement productCards(String b,String t,String gtin)throws IOException{cardReads++;if(failure!=null)throw failure;if(responseOverride!=null)return responseOverride;var response=new JsonObject();var cards=new JsonArray();if(card!=null)cards.add(card);response.add("result",cards);return response;}
    }
}
