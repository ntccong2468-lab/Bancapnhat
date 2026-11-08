package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPermitReadFailureTest {
    final GoodsDocument permit=new GoodsDocument("CONFORMITY_DECLARATION","SYNTHETIC-PERMIT","2025-01-01");
    JsonObject rejection(){
        var error=new JsonObject();error.addProperty("code","14");error.addProperty("number",permit.number());error.addProperty("from_date",permit.date());error.addProperty("message","SYNTHETIC_DECLARATION_NOT_FOUND");
        var errors=new JsonArray();errors.add(error);var result=new JsonObject();result.add("documents",new JsonArray());result.add("errors",errors);
        var response=new JsonObject();response.addProperty("apiversion",4);response.add("result",result);return response;
    }
    ZnackRegistrationDocuments.Selection selection(GoodsDocument... values){return new ZnackRegistrationDocuments.Selection(List.of(values),null,ZnackRegistrationDocuments.Source.PRODUCT,true);}
    ZnackNationalCatalogService service(ZnackApiClient api){return new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());}
    JsonElement active(JsonArray requested){
        var docs=requested.deepCopy();for(var item:docs){var d=item.getAsJsonObject();d.addProperty("status_group",1);d.addProperty("product_tnved","6110");d.addProperty("to_date","2099-01-01");}
        var result=new JsonObject();result.add("documents",docs);var root=new JsonObject();root.add("result",result);return root;
    }
    @Test void http400PermitRejectionIsActionableAndDoesNotAbortOtherDocuments()throws Exception {
        var good=new GoodsDocument(permit.type(),"OTHER-SYNTHETIC-PERMIT",permit.date());var reads=new AtomicInteger();
        var catalog=service(new ZnackApiClient(){@Override public JsonElement registrationPermitInfo(String b,String t,JsonArray request)throws IOException{
            reads.incrementAndGet();if(request.get(0).getAsJsonObject().get("number").getAsString().equals(permit.number()))
                throw new ZnackApiException("Registry rejected permit",400,rejection().toString(),"POST","https://example.test/v4/rd-info");
            return active(request);
        }});
        var checked=catalog.reviewDocuments("fixture",selection(permit,good),"6110209100");
        assertEquals(List.of(good),checked.candidates());assertEquals(good,checked.automatic());assertFalse(checked.requiresReview());
        assertTrue(checked.issues().getFirst().contains("ZNACK_DOCUMENT_REGISTRY_REJECTED"));assertTrue(checked.issues().getFirst().contains("SYNTHETIC_DECLARATION_NOT_FOUND"));assertEquals(2,reads.get());
        var rejected=catalog.reviewDocuments("fixture",selection(permit),"6110209100");
        assertTrue(rejected.candidates().isEmpty());assertNull(rejected.automatic());assertTrue(rejected.requiresReview());assertEquals(3,reads.get(),"Rejected lookup must be checked again, never cached as proof");
    }
    @Test void http200BusinessRejectionOverridesAnOtherwiseActiveLookingDocument(){
        var response=rejection();var request=new JsonArray();request.add(RegistrationPermitValidator.request(permit));
        response.getAsJsonObject("result").add("documents",active(request).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("documents"));
        var problem=RegistrationPermitValidator.problem(response,permit,"6110209100",LocalDate.of(2026,11,8));
        assertTrue(problem.contains("ZNACK_DOCUMENT_REGISTRY_REJECTED"));assertTrue(problem.contains("SYNTHETIC_DECLARATION_NOT_FOUND"));
    }
    @Test void serverMessageCannotTurnARegistryRejectionIntoTariffAdvice()throws Exception {
        for(String token:List.of("ZNACK_DOCUMENT_TNVED_MISMATCH","ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN")){
            var response=rejection();response.getAsJsonObject("result").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("message","SYNTHETIC_DETAIL "+token);
            String problem=RegistrationPermitValidator.problem(response,permit,"6110209100",LocalDate.of(2026,11,8));
            assertFalse(RegistrationPermitValidator.advisory(problem));
            assertFalse(RegistrationPermitValidator.advisory(permit.number()+": "+problem));
            var catalog=service(new ZnackApiClient(){@Override public JsonElement registrationPermitInfo(String b,String t,JsonArray request)throws IOException{throw new ZnackApiException("fixture rejection",400,response.toString());}});
            var checked=catalog.reviewDocuments("fixture",selection(permit),"6110209100");
            assertTrue(checked.candidates().isEmpty());assertNull(checked.automatic());assertTrue(checked.requiresReview());
            var draft=new ZnackCardRegistrationModels.Draft("6110209100",1,"Fixture","Fixture",java.util.Map.of(23557L,permit.number()+":::"+permit.date()));
            var blocked=assertThrows(RegistrationValidationException.class,()->catalog.validateDocuments("fixture",draft));
            assertTrue(blocked.getMessage().contains("ZNACK_DOCUMENT_REGISTRY_REJECTED"));
        }
    }
    @Test void unknownOrMismatchedErrorsAndTransportFailuresKeepTheirOriginalException(){
        var mismatched=rejection();mismatched.getAsJsonObject("result").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("number","UNRELATED");
        var wrongDate=rejection();wrongDate.getAsJsonObject("result").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("from_date","2025-01-02");
        var unknown=rejection();unknown.getAsJsonObject("result").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("code","99");
        var wrongType=rejection();wrongType.getAsJsonObject("result").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("attr_id",23561);
        for(int status:new int[]{400,401,403,429,500})for(String body:List.of("not json",mismatched.toString(),wrongDate.toString(),unknown.toString(),wrongType.toString())){
            var expected=new ZnackApiClient.ZnackApiException("fixture failure",status,body);
            var catalog=service(new ZnackApiClient(){@Override public JsonElement registrationPermitInfo(String b,String t,JsonArray r)throws IOException{throw expected;}});
            assertSame(expected,assertThrows(ZnackApiClient.ZnackApiException.class,()->catalog.reviewDocuments("fixture",selection(permit),"6110209100")));
        }
        for(int status:new int[]{401,403,429,500}){
            var expected=new ZnackApiClient.ZnackApiException("fixture failure",status,rejection().toString());
            var catalog=service(new ZnackApiClient(){@Override public JsonElement registrationPermitInfo(String b,String t,JsonArray r)throws IOException{throw expected;}});
            assertSame(expected,assertThrows(ZnackApiClient.ZnackApiException.class,()->catalog.reviewDocuments("fixture",selection(permit),"6110209100")));
        }
    }
}
