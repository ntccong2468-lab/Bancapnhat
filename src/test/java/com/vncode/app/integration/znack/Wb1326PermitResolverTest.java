package com.vncode.app.integration.znack;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Wb1326PermitResolverTest {
    private static final String OWNER="7700000001",PARTICIPANT="7800000002";
    private static final JsonElement EMPTY=JsonParser.parseString("{\"result\":{\"documents\":[]}}");
    private static final JsonElement ACTIVE=JsonParser.parseString("{\"result\":{\"documents\":[{\"attr_id\":23557,\"number\":\"SYNTHETIC-DOC\",\"from_date\":\"2026-01-01\",\"status_group\":1}]}}");
    private static final JsonElement PENDING=JsonParser.parseString("{\"result\":{\"errors\":[{\"code\":18}]}}");

    @Test void emptyConfiguredOwnerLookupFallsBackToAuthenticatedParticipant()throws Exception{
        var api=new Lookup(EMPTY,ACTIVE);
        var result=resolve(api);
        assertFalse(result.pending());assertEquals(1,result.documents().size());
        assertEquals(List.of(OWNER,PARTICIPANT),api.owners);
    }
    @Test void pendingOneOwnerDoesNotDiscardAnAuthoritativeActiveParticipantResult()throws Exception{
        var api=new Lookup(PENDING,ACTIVE);var result=resolve(api);
        assertFalse(result.pending());assertEquals("SYNTHETIC-DOC",result.documents().getFirst().number());
    }
    @Test void unresolvedRegistryResultRemainsPendingRatherThanConfirmedMissing()throws Exception{
        var api=new Lookup(PENDING,EMPTY,EMPTY);var result=resolve(api);
        assertTrue(result.pending());assertTrue(result.documents().isEmpty());assertEquals(3,api.owners.size());
    }
    @Test void malformedHttpSuccessIsAnUnknownReadAndNotProofOfMissingDocuments(){
        var api=new Lookup(new JsonObject(),new JsonObject(),new JsonObject());
        IOException failure=assertThrows(IOException.class,()->resolve(api));
        assertEquals(2,failure.getSuppressed().length);assertEquals(3,api.owners.size());
    }
    @Test void onlyAuthoritativeEmptyListsCanConfirmNoActiveDocuments()throws Exception{
        var api=new Lookup(EMPTY,EMPTY,EMPTY);var result=resolve(api);
        assertFalse(result.pending());assertTrue(result.documents().isEmpty());
    }
    @Test void equalOwnerAndParticipantDoNotIssueTheSameLookupTwice()throws Exception{
        var api=new Lookup(EMPTY,EMPTY);
        ZnackPurchasePermitResolver.resolve(api,"unused","fixture","04600000000015",OWNER,OWNER);
        assertEquals(List.of(OWNER,""),api.owners);
    }
    @Test void registryReadFailureIsRetainedRatherThanConvertedToAnEmptySuccess(){
        var api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String b,String t,String g,String i)throws IOException{
                throw new ZnackApiException("Synthetic forbidden",403,"{}");
            }
        };
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->resolve(api));
        assertEquals(403,failure.statusCode());assertEquals(2,failure.getSuppressed().length);
    }

    private static ZnackPurchasePermitResolver.Result resolve(ZnackApiClient api)throws IOException{
        return ZnackPurchasePermitResolver.resolve(api,"unused","fixture","04600000000015",OWNER,PARTICIPANT);
    }
    private static final class Lookup extends ZnackApiClient{
        final List<JsonElement> replies;final List<String> owners=new ArrayList<>();
        Lookup(JsonElement...replies){this.replies=List.of(replies);}
        @Override public JsonElement permitDocuments(String b,String t,String g,String inn){
            owners.add(inn);return replies.get(owners.size()-1).deepCopy();
        }
    }
}
