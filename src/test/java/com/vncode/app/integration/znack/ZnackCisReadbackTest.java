package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.integration.znack.ZnackModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCisReadbackTest {
    static final String GTIN="04601234567890";
    static final String FIRST="01"+GTIN+"210000000000001",SECOND="01"+GTIN+"210000000000002",FOREIGN="01"+GTIN+"210000000000003";
    static List<KizCode> codes(){return List.of(code(1,FIRST),code(2,SECOND));}
    private static KizCode code(long id,String cis){return new KizCode(id,1,cis+"\u001d91ABCD\u001d92signature",cis,GTIN,"block",null,null,KizInventoryStatus.AVAILABLE,KizLegalStatus.RECEIVED);}
    static JsonObject row(String cis,String gtin,String status){var info=new JsonObject();info.addProperty("requestedCis",cis);info.addProperty("cis",cis);info.addProperty("gtin",gtin);info.addProperty("status",status);info.addProperty("statusEx","EMPTY");info.addProperty("productName","Product");var row=new JsonObject();row.add("cisInfo",info);return row;}
    static JsonArray rows(JsonElement... rows){var result=new JsonArray();for(var row:rows)result.add(row);return result;}
    static Stream<Object[]> unknownResponses(){
        var conflict=row(FIRST,GTIN,"INTRODUCED");conflict.getAsJsonObject("cisInfo").addProperty("cis",FOREIGN);
        var outerConflict=row(FIRST,GTIN,"INTRODUCED");outerConflict.addProperty("requestedCis",FOREIGN);
        var error=row(FIRST,GTIN,"INTRODUCED");error.addProperty("errorCode",404);
        var malformed=row(FIRST,GTIN,"INTRODUCED");malformed.addProperty("cisInfo","not an object");
        var wrapper=new JsonObject();wrapper.add("result",rows(row(FIRST,GTIN,"INTRODUCED"),row(SECOND,GTIN,"INTRODUCED")));
        var typed=row(FIRST,GTIN,"INTRODUCED");typed.getAsJsonObject("cisInfo").addProperty("cis",123);
        return Stream.of(
            new Object[]{"status-only",JsonParser.parseString("[{\"status\":\"INTRODUCED\"},{\"status\":\"INTRODUCED\"}]")},
            new Object[]{"duplicate CIS",rows(row(FIRST,GTIN,"INTRODUCED"),row(FIRST,GTIN,"INTRODUCED"))},
            new Object[]{"foreign CIS",rows(row(FIRST,GTIN,"INTRODUCED"),row(FOREIGN,GTIN,"INTRODUCED"))},
            new Object[]{"wrong GTIN",rows(row(FIRST,"04601234567891","INTRODUCED"),row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"inner identity disagreement",rows(conflict,row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"outer identity disagreement",rows(outerConflict,row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"error carrying success status",rows(error,row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"missing CIS",rows(row(FIRST,GTIN,"INTRODUCED"))},
            new Object[]{"unexpected wrapper",wrapper},
            new Object[]{"malformed cisInfo",rows(malformed,row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"non-string identity",rows(typed,row(SECOND,GTIN,"INTRODUCED"))},
            new Object[]{"unrecognised status",rows(row(FIRST,GTIN,"EMITTED"),row(SECOND,GTIN,"EMITTED"))}
        );
    }
    @ParameterizedTest(name="{0}") @MethodSource("unknownResponses") void incompleteOrContradictoryProofNeverConfirmsAll(String name,JsonElement response){
        var result=ZnackCisReadback.inspect(response,codes(),GTIN);assertNotEquals(2,result.introduced(),name);assertTrue(result.pending()>0,name);assertNotNull(result.reason(),name);
    }
    @Test void exactReorderedCodesAndScannerPrefixesAreConfirmed(){
        var first=row("]d2"+FIRST,GTIN,"INTRODUCED");var result=ZnackCisReadback.inspect(rows(row(SECOND,GTIN,"INTRODUCED"),first),codes(),GTIN);
        assertEquals(2,result.introduced());assertEquals(0,result.pending());assertNull(result.reason());
    }
    @Test void documentedOuterRequestedCisAndDirectSingleObjectHaveExactIdentity(){
        var nested=row(FIRST,GTIN,"APPLIED");nested.getAsJsonObject("cisInfo").remove("requestedCis");nested.addProperty("requestedCis",FIRST);
        var result=ZnackCisReadback.inspect(nested,List.of(codes().getFirst()),GTIN);assertEquals(1,result.applied());
        var direct=row(FIRST,GTIN,"INTRODUCED").getAsJsonObject("cisInfo");assertEquals(1,ZnackCisReadback.inspect(direct,List.of(codes().getFirst()),GTIN).introduced());
    }
    @Test void exactGs1CisCanEstablishGtinWhenRedundantGtinFieldIsOmitted(){
        var first=row(FIRST,GTIN,"INTRODUCED");first.getAsJsonObject("cisInfo").remove("gtin");assertEquals(1,ZnackCisReadback.inspect(first,List.of(codes().getFirst()),GTIN).introduced());
    }
    @Test void duplicateRequestedNormalizedCisAndForeignOrderCannotBecomeAValidRequest(){
        assertFalse(ZnackCisReadback.validRequests(List.of(codes().getFirst(),code(3,"]d2"+FIRST)),GTIN,null));
        assertFalse(ZnackCisReadback.validRequests(codes(),GTIN,2L));assertFalse(ZnackCisReadback.validRequests(codes(),"04601234567891",null));
    }
    static Stream<Object[]> errorCodes(){return Stream.of(
        new Object[]{"number zero","0",true},new Object[]{"string zero","\"0\"",true},new Object[]{"optional null","null",true},
        new Object[]{"false is not a code","false",false},new Object[]{"fraction","0.1",false},new Object[]{"padded zero","\"00\"",false},new Object[]{"decimal string","\"0.0\"",false},
        new Object[]{"unknown type","{}",false},new Object[]{"nonzero","404",false});}
    @ParameterizedTest(name="{0}") @MethodSource("errorCodes") void onlyLiteralZeroOrAbsentErrorCanAccompanyAnExactSuccessfulRead(String name,String json,boolean accepted){
        var first=row(FIRST,GTIN,"INTRODUCED");first.add("errorCode",JsonParser.parseString(json));first.addProperty("errorMessage","");
        var result=ZnackCisReadback.inspect(rows(first,row(SECOND,GTIN,"INTRODUCED")),codes(),GTIN);assertEquals(accepted,result.introduced()==2,name);
    }
    @Test void zeroNeverSuppressesARealErrorMessageOrAConflictingNestedError(){
        var first=row(FIRST,GTIN,"INTRODUCED");first.addProperty("errorCode",0);first.addProperty("errorMessage","Read rejected");assertTrue(ZnackCisReadback.inspect(rows(first,row(SECOND,GTIN,"INTRODUCED")),codes(),GTIN).pending()>0);
        first.remove("errorMessage");first.getAsJsonObject("cisInfo").addProperty("errorCode",404);assertTrue(ZnackCisReadback.inspect(rows(first,row(SECOND,GTIN,"INTRODUCED")),codes(),GTIN).pending()>0);
    }
    @Test void knownPendingStatusRemainsVisibleWithoutClaimingCirculation(){
        var result=ZnackCisReadback.inspect(rows(row(FIRST,GTIN,"EMITTED"),row(SECOND,GTIN,"EMITTED")),codes(),GTIN);
        assertEquals(0,result.applied());assertEquals(0,result.introduced());assertEquals(2,result.pending());assertTrue(result.reason().contains("EMITTED"));
    }
    @Test void appliedAndIntroducedAreCountedOnlyForTheirExactDistinctCodes(){
        var result=ZnackCisReadback.inspect(rows(row(FIRST,GTIN,"APPLIED"),row(SECOND,GTIN,"INTRODUCED")),codes(),GTIN);assertEquals(1,result.applied());assertEquals(1,result.introduced());assertEquals(0,result.pending());
    }
}
