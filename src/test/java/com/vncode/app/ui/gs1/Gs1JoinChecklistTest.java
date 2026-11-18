package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client.Choice;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Gs1JoinChecklistTest {
    private static final List<Choice> DICTIONARY=List.of(new Choice("ROLE_A","Verified A"),new Choice("ROLE_B","Verified B"));
    @Test void aNewChecklistUsesTheOfficialPayloadKeysAndDoesNotChooseForTheSeller(){
        var values=Gs1JoinChecklist.normalize(new JsonArray(),DICTIONARY);assertEquals(2,values.size());
        var first=values.get(0).getAsJsonObject();assertEquals("ROLE_A",first.get("value").getAsString());assertEquals("Verified A",first.get("label").getAsString());
        assertFalse(first.get("checked").getAsBoolean());assertFalse(first.has("id"));assertFalse(first.has("text"));assertFalse(values.get(1).getAsJsonObject().get("checked").getAsBoolean());
    }
    @Test void anExactUniqueDictionaryIdentifierRepairsLegacyKeysWithoutDroppingUnknownData(){
        var original=array("[{\"id\":\"ROLE_A\",\"text\":\"Old label\",\"checked\":true,\"serverField\":{\"value\":\"keep\"}}]");var before=original.deepCopy();
        var normalized=Gs1JoinChecklist.normalize(original,DICTIONARY);var first=normalized.get(0).getAsJsonObject();
        assertEquals("ROLE_A",first.get("value").getAsString());assertEquals("Verified A",first.get("label").getAsString());assertTrue(first.get("checked").getAsBoolean());
        assertEquals(before,original);assertEquals("keep",first.getAsJsonObject("serverField").get("value").getAsString());
        first.getAsJsonObject("serverField").addProperty("value","local edit");assertEquals("keep",original.get(0).getAsJsonObject().getAsJsonObject("serverField").get("value").getAsString());
    }
    @Test void canonicalEntriesKeepTheirSelectionAndReceiveTheVerifiedDictionaryLabel(){
        var result=Gs1JoinChecklist.normalize(array("[{\"value\":\"ROLE_A\",\"label\":\"Old label\",\"checked\":true}]"),DICTIONARY);
        assertEquals("Verified A",result.get(0).getAsJsonObject().get("label").getAsString());assertTrue(result.get(0).getAsJsonObject().get("checked").getAsBoolean());
    }
    @Test void matchingDisplayTextCannotInventAMappingForAnUnknownIdentifier(){
        var original=array("[{\"id\":\"UNKNOWN\",\"text\":\"Verified A\",\"checked\":true}]");var result=Gs1JoinChecklist.normalize(original,DICTIONARY);
        assertEquals(original.get(0),result.get(result.size()-1));assertFalse(result.get(result.size()-1).getAsJsonObject().has("value"));assertFalse(result.get(0).getAsJsonObject().get("checked").getAsBoolean());
    }
    @Test void identifiersAreMatchedExactlyWithoutChangingTheirCase(){
        var original=array("[{\"id\":\"role_a\",\"text\":\"Verified A\",\"checked\":true}]");var result=Gs1JoinChecklist.normalize(original,DICTIONARY);
        assertEquals(original.get(0),result.get(result.size()-1));assertFalse(result.get(result.size()-1).getAsJsonObject().has("value"));
    }
    @Test void unknownCanonicalServerDataRemainsReadableAndIsNotRenamed(){
        var original=array("[{\"value\":\"OLD_SERVER_VALUE\",\"label\":\"Observed server label\",\"checked\":false,\"metadata\":17}]");
        var result=Gs1JoinChecklist.normalize(original,DICTIONARY);assertEquals(original.get(0),result.get(result.size()-1));assertEquals(3,result.size());
    }
    @Test void anUnrelatedServerRowIdIsPreservedBesideTheCanonicalValue(){
        var result=Gs1JoinChecklist.normalize(array("[{\"value\":\"ROLE_A\",\"label\":\"A\",\"id\":\"server-row-uuid\",\"checked\":true}]"),DICTIONARY);
        assertEquals("server-row-uuid",result.get(0).getAsJsonObject().get("id").getAsString());assertEquals("ROLE_A",result.get(0).getAsJsonObject().get("value").getAsString());
    }
    @Test void duplicateDictionaryIdentifiersAreAmbiguousEvenWithIdenticalLabels(){
        for(var choices:List.of(List.of(new Choice("ROLE_A","A"),new Choice("ROLE_A","B")),List.of(new Choice("ROLE_A","A"),new Choice("ROLE_A","A")))){
            var failure=assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(new JsonArray(),choices));assertEquals("GS1_CHECKLIST_AMBIGUOUS",failure.getMessage());
        }
    }
    @Test void duplicateObservedIdentifiersDoNotCreateConflictingCheckboxes(){
        assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(array("[{\"value\":\"ROLE_A\",\"label\":\"A\",\"checked\":true},{\"id\":\"ROLE_A\",\"text\":\"A\",\"checked\":false}]"),DICTIONARY));
    }
    @Test void conflictingRecognizedLegacyAndCanonicalIdentifiersRequireReview(){
        assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(array("[{\"value\":\"ROLE_A\",\"label\":\"A\",\"id\":\"ROLE_B\",\"checked\":true}]"),DICTIONARY));
    }
    @Test void stringBooleansAreNeverPromotedToActualConsent(){
        var result=Gs1JoinChecklist.normalize(array("[{\"id\":\"ROLE_A\",\"text\":\"A\",\"checked\":\"true\"}]"),DICTIONARY);
        assertTrue(result.get(0).getAsJsonObject().get("checked").getAsJsonPrimitive().isString());
        var form=Gs1JoinFormValidationContractTest.validForm();form.getAsJsonObject("companyDetails").add("partisipationInGovernmentProjects",result);
        assertEquals("partisipationInGovernmentProjects",Gs1JoinFormValidation.first(form,"7701234567").orElseThrow().field());
    }
    @Test void aMissingCheckedFieldBecomesUnselectedRatherThanSelected(){
        var result=Gs1JoinChecklist.normalize(array("[{\"id\":\"ROLE_A\",\"text\":\"A\"}]"),DICTIONARY);assertFalse(result.get(0).getAsJsonObject().get("checked").getAsBoolean());
    }
    @Test void malformedRowsOrDictionariesCannotBeSilentlyDropped(){
        assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(array("[true]"),DICTIONARY));
        assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(new JsonArray(),List.of(new Choice("","Missing identifier"))));
        assertThrows(IllegalArgumentException.class,()->Gs1JoinChecklist.normalize(new JsonArray(),List.of(new Choice("ROLE_A",""))));
    }
    @Test void labelsPreferCanonicalTextAndRetainALegacyFallback(){
        assertEquals("Canonical",Gs1JoinChecklist.label(object("{\"label\":\"Canonical\",\"text\":\"Legacy\"}")));
        assertEquals("Legacy",Gs1JoinChecklist.label(object("{\"text\":\"Legacy\"}")));
        assertEquals("UNKNOWN",Gs1JoinChecklist.label(object("{\"value\":\"UNKNOWN\"}")));
    }
    @Test void verifiedRowsFollowDictionaryOrderWhileFutureRowsKeepTheirDataAndSelection(){
        var original=array("[{\"value\":\"ROLE_B\",\"label\":\"B\",\"checked\":false,\"serverField\":{\"value\":\"keep-B\"}},{\"value\":\"ROLE_A\",\"label\":\"A\",\"checked\":true,\"serverField\":{\"value\":\"keep-A\"}},{\"value\":\"FUTURE_ROLE\",\"label\":\"Future server value\",\"checked\":true,\"metadata\":{\"revision\":17}}]");var before=original.deepCopy();
        var result=Gs1JoinChecklist.normalize(original,DICTIONARY);
        assertEquals(List.of("ROLE_A","ROLE_B","FUTURE_ROLE"),result.asList().stream().map(item->item.getAsJsonObject().get("value").getAsString()).toList());
        assertTrue(result.get(0).getAsJsonObject().get("checked").getAsBoolean());assertFalse(result.get(1).getAsJsonObject().get("checked").getAsBoolean());
        assertEquals("keep-A",result.get(0).getAsJsonObject().getAsJsonObject("serverField").get("value").getAsString());assertEquals("keep-B",result.get(1).getAsJsonObject().getAsJsonObject("serverField").get("value").getAsString());
        assertEquals(before.get(2),result.get(2));assertTrue(result.get(2).getAsJsonObject().get("checked").getAsBoolean());assertEquals(before,original);
    }
    private static JsonArray array(String value){return JsonParser.parseString(value).getAsJsonArray();}
    private static JsonObject object(String value){return JsonParser.parseString(value).getAsJsonObject();}
}
