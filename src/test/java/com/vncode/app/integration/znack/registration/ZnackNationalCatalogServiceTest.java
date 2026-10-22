package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZnackNationalCatalogServiceTest {
    @Test void existingLegacyFourDigitDraftReadsOnlyCategoriesAndNeverRequestsGs1Quota()throws Exception{
        var lookups=new ArrayList<String>();var api=new ZnackApiClient(){
            @Override public JsonElement generatedGtins(String base,String token){throw new AssertionError("Existing-card review cannot inspect allocation quota");}
            @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved){lookups.add(tnved);return JsonParser.parseString("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture category\",\"category_active\":true}]}");}
        };
        var preflight=service(api).preflightExisting("6110","fixture");assertEquals("6110",preflight.tnved());assertEquals("6110",preflight.categoryTnved());assertEquals(List.of("6110"),lookups);assertEquals(1,preflight.categories().size());
        assertThrows(IllegalArgumentException.class,()->service(api).preflightExisting("61102","fixture"));assertEquals(List.of("6110"),lookups);
    }
    @Test void conditionalRequirementsReadTheCompleteCurrentSchemaForNamesAndTypes()throws Exception{
        var allReads=new AtomicInteger();var api=new ZnackApiClient(){
            public JsonElement nationalCatalogAttributes(String b,String t,long c){return JsonParser.parseString("{\"result\":[{\"attr_id\":12,\"attr_name\":\"Вид товара\",\"attr_field_type\":\"text\",\"dependent_attributes\":[{\"value\":\"НЕТ В СПРАВОЧНИКЕ\",\"attrs\":[{\"attr_id\":23828,\"attr_type\":\"m\"}]}]}]}");}
            public JsonElement nationalCatalogAllAttributes(String b,String t,long c){allReads.incrementAndGet();return JsonParser.parseString("{\"result\":[{\"attr_id\":23828,\"attr_name\":\"Вид товара (при отсутствии в справочнике)\",\"attr_field_type\":\"text\"}]}");}
        };
        var catalog=service(api);var schema=catalog.requiredAttributes(30696,"fixture");var active=RegistrationAttributeRules.required(schema,Map.of(12L,"НЕТ В СПРАВОЧНИКЕ"));catalog.requiredAttributes(30696,"fixture");
        assertEquals(1,allReads.get());assertEquals(2,active.size());assertEquals("Вид товара (при отсутствии в справочнике)",active.getLast().name());assertEquals("text",active.getLast().fieldType());
    }
    @Test void publishedTariffFeedRulesNormalizeSavedDuplicatesAndRejectMixedBranches(){
        var old=JsonParser.parseString("{\"good_id\":91,\"tnved\":\"6103109000\",\"good_attrs\":[{\"attr_id\":3959,\"attr_value\":\"6103\"},{\"attr_id\":13933,\"attr_value\":\"6103109000\"},{\"attr_id\":35,\"attr_value\":\"52\"}]}").getAsJsonObject();
        var normalized=ZnackNationalCatalogService.normalizedFeed(old);assertEquals(91,normalized.get("good_id").getAsLong());assertEquals(1,normalized.getAsJsonArray("good_attrs").size());assertEquals(3,old.getAsJsonArray("good_attrs").size(),"Retain the original durable receipt");
        old.addProperty("tnved","6103");normalized=ZnackNationalCatalogService.normalizedFeed(old);assertEquals(2,normalized.getAsJsonArray("good_attrs").size());
        old.addProperty("tnved","6203");assertThrows(RegistrationValidationException.class,()->ZnackNationalCatalogService.normalizedFeed(old));
        old.addProperty("tnved","6103");old.addProperty("good_id",-1);assertThrows(RegistrationValidationException.class,()->ZnackNationalCatalogService.normalizedFeed(old));
    }
    @Test void exactTenDigitFeedDoesNotDuplicateTariffAttributes(){
        var payload=ZnackNationalCatalogService.buildPayload("00000000000017",new Draft("6103109000",1,"Suit","Fixture",Map.of(3959L,"6103",13933L,"6103109000",35L,"52")),"");
        assertEquals(1,payload.getAsJsonArray("good_attrs").size());assertEquals(35,payload.getAsJsonArray("good_attrs").get(0).getAsJsonObject().get("attr_id").getAsLong());
    }
    @Test void registryVerifiedSinglePermitIsAutomaticOnlyWithinItsTnvedScope()throws Exception{
        var calls=new AtomicInteger();var api=new ZnackApiClient(){
            @Override public JsonElement registrationPermitInfo(String base,String token,com.google.gson.JsonArray requested){
                calls.incrementAndGet();var documents=requested.deepCopy();for(var value:documents){var d=value.getAsJsonObject();d.addProperty("status_group",1);d.addProperty("product_tnved","6110, 6103");}
                var result=new JsonObject();result.add("documents",documents);var root=new JsonObject();root.add("result",result);return root;
            }
        };
        var permit=new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","FIXTURE","2026-06-07");
        var selection=new ZnackRegistrationDocuments.Selection(List.of(permit),null,ZnackRegistrationDocuments.Source.PRODUCT,true);
        var service=service(api);var checked=service.reviewDocuments("fixture",selection,"6110209100");
        assertEquals(permit,checked.automatic());assertFalse(checked.requiresReview());
        var wrong=service.reviewDocuments("fixture",selection,"6106909000");assertNull(wrong.automatic());assertEquals(List.of(permit),wrong.candidates());assertTrue(wrong.requiresReview());assertTrue(wrong.issues().getFirst().contains("ZNACK_DOCUMENT_TNVED_MISMATCH"));assertEquals(1,calls.get(),"Registry response can be cached, not the per-product TN VED decision");
    }
    @Test void readsRealCardStagesWhenTheFeedStillSaysReceived()throws Exception{
        for(String stage:List.of("draft","moderation","notsigned","errors")){
            var api=new ZnackApiClient(){
                @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Received\"}}");}
                @Override public JsonElement productCards(String base,String token,String gtin){return JsonParser.parseString("{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":91,\"good_status\":\"draft\",\"good_signed\":false,\"good_detailed_status\":[\""+stage+"\"]}]}");}
            };
            var result=service(api).progress("fixture","feed","04631993764370");
            assertEquals(switch(stage){case "draft"->"CardDraft";case "moderation"->"CardModeration";case "notsigned"->"Moderated";default->"Rejected";},result.status());
            assertEquals(stage.equals("errors"),result.failed());assertFalse(result.signed());assertEquals(91L,result.goodId());
        }
    }
    @Test void alreadySignedPublishedCardDoesNotRequireAnotherCryptoProSignature()throws Exception{
        var api=new ZnackApiClient(){
            @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91}]}}");}
            @Override public JsonElement productCards(String base,String token,String gtin){return JsonParser.parseString("{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":91,\"good_status\":\"published\",\"good_signed\":true,\"good_detailed_status\":[\"published\"]}]}");}
        };
        var result=service(api).progress("fixture","feed","04631993764370");assertTrue(result.signed());assertFalse(result.readyToSign());
    }
    @Test void aReportedValidationCountCannotBeMistakenForSuccessWhenDetailsAreUnknown()throws Exception{
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Signed\",\"error_details\":{\"totalErrors\":1}}}");}};
        var result=service(api).progress("fixture","feed","04631993764370");
        assertTrue(result.failed());assertFalse(result.signed());assertEquals("CATALOG_VALIDATION_ERRORS_UNAVAILABLE",result.errorMessage());
    }
    @Test void verboseObjectErrorsAndCommonErrorArePreservedAndScopedToTheRequestedGtin()throws Exception {
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("""
            {"result":{"status":"Rejected","error_details":{"commonError":{"code":12,"text":"Invalid content"},"items":[
              {"gtin":"04631993764370","errors":{"code":5,"attr_id":"23557","text":"Permit does not cover TN VED"}},
              {"gtin":"04631993764363","errors":[{"code":5,"text":"Foreign card error"}]}]}}}
            """);}};
        var result=service(api).progress("fixture","feed","04631993764370");
        assertTrue(result.failed());assertTrue(result.errorMessage().contains("23557: Permit does not cover TN VED"));
        assertTrue(result.errorMessage().contains("Invalid content"));assertFalse(result.errorMessage().contains("Foreign card"));
    }
    @Test void repeatedAttributeErrorsForOneGtinDoNotTurnIntoAnIdentityError()throws Exception {
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("""
            {"result":{"status":"Moderated","item":[
              {"gtin":"04631993764370","good_id":91,"attribute_name":"Размер","status_code":5,"message":"Invalid size"},
              {"gtin":"04631993764370","good_id":91,"attribute_name":"Состав","status_code":5,"message":"Missing composition"}]}}
            """);}};
        var result=service(api).progress("fixture","feed","04631993764370");
        assertTrue(result.failed());assertFalse(result.readyToSign());assertEquals(2,result.errors().size());
        assertTrue(result.errorMessage().contains("Размер: Invalid size"));
    }
    @Test void standardFeedErrorsWithoutAnIdentityLinkRetainTheirFeedScopeAndAreSanitized()throws Exception{
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("""
            {"result":{"status":"Rejected","result":{"0":["Неверный тип атрибута https://catalog.example.test/read?apikey=private-secret"],"totalErrors":"1"}}}
            """);}
            @Override public JsonElement productCards(String base,String token,String gtin){throw new AssertionError("Rejected feed cannot authorize a card read/sign/write");}
        };
        var result=service(api).progress("fixture","feed","04631993764370");
        assertTrue(result.failed());assertTrue(result.errorMessage().contains("CATALOG_FEED_ERROR: Неверный тип атрибута"));
        assertFalse(result.errorMessage().contains("private-secret"));assertFalse(result.readyToSign());
    }
    @Test void standardEntryErrorsUseOnlyAnExplicitItemIdGtinLinkAndDoNotRepeatVerboseErrors()throws Exception{
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("""
            {"result":{"status":"Rejected","item":[
              {"id":0,"gtin":"04631993764370","good_id":91},
              {"id":1,"gtin":"04631993764363","good_id":92}],
              "error_details":{"items":[{"gtin":"04631993764370","errors":{"text":"Invalid size"}}]},
              "result":{"0":["Invalid size"],"1":["Other product permit error"],"totalErrors":"2"}}}
            """);}};
        var result=service(api).progress("fixture","feed","04631993764370");
        assertEquals(List.of("Invalid size"),result.errors());assertEquals(91L,result.goodId());assertTrue(result.failed());
    }
    @Test void standardValidationCountWithoutDetailsCannotAuthorizeSigning()throws Exception{
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Signed\",\"result\":{\"totalErrors\":\"1\"}}}");}};
        var result=service(api).progress("fixture","feed","04631993764370");
        assertTrue(result.failed());assertFalse(result.signed());assertEquals("CATALOG_VALIDATION_ERRORS_UNAVAILABLE",result.errorMessage());
    }
    @Test void malformedStandardEntryErrorsRemainAnUnknownReadInsteadOfARejectionOrSuccess(){
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Rejected\",\"result\":{\"0\":{\"text\":\"Wrong shape\"}}}}");}};
        var failure=assertThrows(IllegalStateException.class,()->service(api).progress("fixture","feed","04631993764370"));
        assertEquals("CATALOG_FEED_RESPONSE_INVALID",failure.getMessage());
    }
    @Test void signedFeedWithItemFailureCannotCountAsPublishedEvenWithoutAnErrorMessage()throws Exception {
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91,\"status_code\":400}]}}");}};
        var result=service(api).progress("fixture-token","fixture-feed","04631993764370");assertTrue(result.failed());assertFalse(result.signed());
    }
    @Test void signedFeedWithForeignGtinDoesNotProveTheRequestedRegistration() {
        var api=new ZnackApiClient(){@Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\"04631993764363\",\"good_id\":91}]}}");}};
        assertThrows(IllegalStateException.class,()->service(api).progress("fixture-token","fixture-feed","04631993764370"));
    }
    private ZnackApiClient signedFeed(String cards) {
        return new ZnackApiClient(){
            @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){
                return JsonParser.parseString("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91}]}}");
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                assertEquals("04631993764370",gtins);return JsonParser.parseString(cards);
            }
        };
    }
    @Test void acceptedSignedFeedCannotPublishAnAbsentOrDraftCard()throws Exception {
        for(String response:List.of("{\"result\":[]}",
                "{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":91,\"good_status\":\"draft\"}]}")) {
            var progress=service(signedFeed(response)).progress("fixture-token","feed","04631993764370");
            assertFalse(progress.signed());assertFalse(progress.failed());assertEquals("PublicationPending",progress.status());
        }
    }
    @Test void publishedRevisionWithADraftIsValidButExplicitUnsignedStateIsNot()throws Exception{
        for(boolean signed:new boolean[]{true,false}){
            var result=service(signedFeed("{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":91,\"good_status\":\"published\",\"good_signed\":"+signed+",\"good_detailed_status\":[\"published\",\"draft\"]}]}"))
                    .progress("fixture","feed","04631993764370");
            assertEquals(signed,result.signed());
        }
    }
    @Test void publishedReadbackMustHaveTheExactCardIdentity() {
        for(String response:List.of("{\"result\":[{\"gtin\":\"04631993764363\",\"good_id\":91,\"good_status\":\"published\"}]}","{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":92,\"good_status\":\"published\"}]}",
                "{\"result\":[{\"gtin\":\"04631993764370\",\"good_status\":\"published\"}]}","{\"result\":{}}","null",
                "{\"result\":[{\"gtin\":\"04631993764370\",\"good_id\":91},{\"gtin\":\"04631993764370\",\"good_id\":91}]}"))
            assertThrows(IllegalStateException.class,()->service(signedFeed(response)).progress("fixture-token","feed","04631993764370"));
    }
    @Test void onlyThePublishedExactCardCompletesRegistration()throws Exception {
        var progress=service(signedFeed("{\"result\":[{\"good_id\":91,\"good_status\":\"published\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\"4631993764370\"}]}]}"))
                .progress("fixture-token","feed","04631993764370");
        assertTrue(progress.signed());assertEquals(91L,progress.goodId());
    }
    @Test void approvedFeedWithoutGoodIdReadsTheExactCardBeforeSigning()throws Exception{
        for(String state:List.of("draft","published")){
            var api=new ZnackApiClient(){
                @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\"04631993764370\"}]}}");}
                @Override public JsonElement productCards(String base,String token,String gtin){return JsonParser.parseString("{\"result\":[{\"good_id\":91,\"good_status\":\""+state+"\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\"04631993764370\"}]}]}");}
            };
            var progress=service(api).progress("fixture","feed","04631993764370");assertEquals(91L,progress.goodId());
            assertEquals(state.equals("published"),progress.signed());assertEquals(state.equals("draft"),progress.readyToSign());
        }
    }
    @Test void missingApprovedCardIdCannotBeBorrowedFromAnotherGtin(){
        var api=new ZnackApiClient(){
            @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\"04631993764370\"}]}}");}
            @Override public JsonElement productCards(String base,String token,String gtin){return JsonParser.parseString("{\"result\":[{\"gtin\":\"04631993764363\",\"good_id\":91,\"good_status\":\"published\"}]}");}
        };
        assertThrows(IllegalStateException.class,()->service(api).progress("fixture","feed","04631993764370"));
    }
    @Test void completedFeedWithoutACardDoesNotWaitForeverOrCountAsRegistrationSuccess()throws Exception {
        for(String status:List.of("Processed","Done","Completed")){
            var api=new ZnackApiClient(){
                @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){return JsonParser.parseString("{\"result\":{\"status\":\""+status+"\",\"item\":[{\"gtin\":\"04631993764370\"}]}}");}
                @Override public JsonElement productCards(String base,String token,String gtin){return JsonParser.parseString("{\"result\":[]}");}
            };
            var result=service(api).progress("fixture-token","feed","04631993764370");
            assertTrue(result.failed());assertFalse(result.signed());assertEquals("CATALOG_FEED_COMPLETED_WITHOUT_CARD",result.errorMessage());
        }
    }
    @Test void explicitFreshAllocationSkipsExistingUnclaimedDrafts() throws Exception {
        AtomicInteger allocations=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base,String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[{\"gtin\":\"04631993764363\"}]}}");
            }
            @Override public JsonElement generateGtins(String base,String token,int quantity) {
                allocations.incrementAndGet();
                return JsonParser.parseString("{\"result\":{\"drafts\":[{\"gtin\":\"04631993764370\"}]}}");
            }
        };
        assertEquals("04631993764370",service(api).generateFreshOne("token",Set.of()));
        assertEquals(1,allocations.get());
    }
    @Test void freshAllocationWithLostIdentityNeverFallsBackToAPreviousDraft() {
        AtomicInteger allocations=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base,String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[{\"gtin\":\"04631993764363\"}]}}");
            }
            @Override public JsonElement generateGtins(String base,String token,int quantity) {
                allocations.incrementAndGet();
                return JsonParser.parseString("{\"result\":{\"monthly-limit\":{\"limit\":100,\"usage\":2}}}");
            }
        };
        assertThrows(IllegalStateException.class,()->service(api).generateFreshOne("token",Set.of()));
        assertEquals(1,allocations.get());
    }
    @Test void freshAllocationDoesNotGuessWhenReadBackContainsMultipleNewCodes() {
        AtomicInteger reads=new AtomicInteger(),allocations=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base,String token) {
                return JsonParser.parseString(reads.getAndIncrement()==0?"{\"result\":{\"drafts\":[]}}":
                        "{\"result\":{\"drafts\":[{\"gtin\":\"04631993764363\"},{\"gtin\":\"04631993764370\"}]}}");
            }
            @Override public JsonElement generateGtins(String base,String token,int quantity) {
                allocations.incrementAndGet();return JsonParser.parseString("{\"result\":{}}");
            }
        };
        assertThrows(IllegalStateException.class,()->service(api).generateFreshOne("token",Set.of()));
        assertEquals(1,allocations.get());
    }
    @Test
    void retainsSchemaValueTypesAndSendsThemWithArticleAndSize() {
        var schema = ZnackNationalCatalogService.parseAttributes(JsonParser.parseString("""
                {"result":[
                  {"attr_id":13914,"attr_name":"Модель / артикул производителя",
                   "attr_field_type":"text","attr_value_type":["МОДЕЛЬ","АРТИКУЛ"]},
                  {"attr_id":35,"attr_name":"Размер одежды / изделия",
                   "attr_field_type":"text","attr_value_type":["РОССИЙСКИЙ","МЕЖДУНАРОДНЫЙ"]}
                ]}
                """));
        assertEquals(List.of("МОДЕЛЬ", "АРТИКУЛ"), schema.getFirst().valueTypes());
        Draft legacy = new Draft("6204510000", "6204", 30933, "Брюки", "Brand",
                Map.of(13914L, "01-besang", 35L, "L", 13933L, "6204510000"));
        Draft repaired = ZnackCardRegistrationWorkflow.withSchemaTypes(legacy, schema, "48");
        JsonObject payload = ZnackNationalCatalogService.buildPayload("4689039063727", repaired, "");
        Map<Long, String> types = new java.util.HashMap<>();
        payload.getAsJsonArray("good_attrs").forEach(item -> {
            JsonObject attr = item.getAsJsonObject();
            types.put(attr.get("attr_id").getAsLong(), attr.get("attr_value_type").getAsString());
        });
        assertEquals("АРТИКУЛ", types.get(13914L));
        assertEquals("МЕЖДУНАРОДНЫЙ", types.get(35L));
        assertEquals("", types.get(13933L));
        Draft restored = ZnackCardRegistrationWorkflow.draftFromPayload(payload);
        assertEquals("6204510000", restored.tnved());
        assertEquals("6204", restored.feedTnved());
        assertEquals(repaired.attributes(), restored.attributes());
        assertEquals("МЕЖДУНАРОДНЫЙ", restored.attributeTypes().get(35L));
        assertEquals("4689039063727", payload.get("gtin").getAsString());
    }

    @Test
    void usesRussianSizeTypeOnlyWhenTheWbRussianSizeConfirmsIt() {
        var schema = ZnackNationalCatalogService.parseAttributes(JsonParser.parseString("""
                {"result":[{"attr_id":35,"attr_name":"Размер одежды / изделия",
                "attr_value_type":["ЕВРОПЕЙСКИЙ","РОССИЙСКИЙ","МЕЖДУНАРОДНЫЙ"]}]}
                """));
        Draft draft = new Draft("6204", 30933, "Брюки", "Brand", Map.of(35L, "48"));
        assertEquals("РОССИЙСКИЙ", ZnackCardRegistrationWorkflow.withSchemaTypes(draft, schema, "48")
                .attributeTypes().get(35L));
        assertThrows(IllegalArgumentException.class,
                () -> ZnackCardRegistrationWorkflow.withSchemaTypes(draft, schema, "52"));
        var russianOnly = ZnackNationalCatalogService.parseAttributes(JsonParser.parseString("""
                {"result":[{"attr_id":35,"attr_name":"Размер одежды / изделия",
                "attr_value_type":["РОССИЙСКИЙ"]}]}
                """));
        Draft international = new Draft("6204", 30933, "Брюки", "Brand", Map.of(35L, "L"));
        assertThrows(IllegalArgumentException.class,
                () -> ZnackCardRegistrationWorkflow.withSchemaTypes(international, russianOnly, "48"));
    }

    @Test
    void mixedAttributeAndPhotoErrorsDoNotReintroduceTheRejectedPhotoOnRetry() {
        String url = "https://basket-26.wbbasket.ru/vol4858/part48586/485860001/images/big/1.webp";
        String mixed = "Не заполнено значение обязательного параметра Размер одежды; "
                + "Изображение не доступно по URL " + url
                + ". В ответе на запрос получен код отличный от кода 200.";
        assertEquals("", ZnackCardRegistrationWorkflow.retryImageUrl(url, mixed));
        assertEquals("", ZnackCardRegistrationWorkflow.retryImageUrl(url, "Размер одежды не заполнен"));
        assertEquals("", ZnackCardRegistrationWorkflow.retryImageUrl(url, null));
    }

    @Test
    void parsesDocumentedGeneratedGtinResponse() throws Exception {
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[]}}");
            }

            @Override public JsonElement generateGtins(String base, String token, int quantity) {
                return JsonParser.parseString("""
                        {"apiversion":3,"result":{"monthly-limit":{"limit":100,"usage":1},
                        "drafts":[{"gtin":"04631993764363"}]}}
                        """);
            }
        };

        var service = service(api);

        assertEquals("04631993764363", service.generateOne("token"));
    }

    @Test
    void reusesExistingUnclaimedDraftWithoutGeneratingAnotherGtin() throws Exception {
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return JsonParser.parseString("""
                        {"result":{"drafts":[{"gtin":"04631993764363"},{"gtin":"04631993764370"}]}}
                        """);
            }

            @Override public JsonElement generateGtins(String base, String token, int quantity) {
                fail("An existing unclaimed draft must be reused before allocating another GTIN.");
                return null;
            }
        };

        var service = service(api);

        assertEquals("04631993764370",
                service.generateOne("token", Set.of("04631993764363")));
    }

    @Test
    void reconcilesAllocationWhenSuccessResponseOmitsDrafts() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger allocations = new AtomicInteger();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return reads.getAndIncrement() == 0
                        ? JsonParser.parseString("{\"result\":{\"drafts\":[]}}")
                        : JsonParser.parseString("""
                                {"result":{"drafts":[{"gtin":"04631993764363"}]}}
                                """);
            }

            @Override public JsonElement generateGtins(String base, String token, int quantity) {
                allocations.incrementAndGet();
                return JsonParser.parseString("""
                        {"result":{"monthly-limit":{"limit":100,"usage":1}}}
                        """);
            }
        };

        var service = service(api);

        assertEquals("04631993764363", service.generateOne("token"));
        assertEquals(1, allocations.get(), "The state-changing allocation must not be repeated.");
    }

    @Test
    void includesResponseAndDoesNotRepeatAmbiguousAllocation() {
        AtomicInteger allocations = new AtomicInteger();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[]}}");
            }

            @Override public JsonElement generateGtins(String base, String token, int quantity) {
                allocations.incrementAndGet();
                return JsonParser.parseString("""
                        {"result":{"monthly-limit":{"limit":100,"usage":1}}}
                        """);
            }
        };

        var service = service(api);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generateOne("token"));
        assertTrue(error.getMessage().contains("did not repeat"));
        assertTrue(error.getMessage().contains("monthly-limit"));
        assertEquals(1, allocations.get());
    }

    @Test
    void parsesGs1QuotaWithoutConsumingDrafts() {
        var status = ZnackNationalCatalogService.parseGs1(JsonParser.parseString("""
                {"result":{"monthly-limit":{"limit":"100","usage":"17"},"drafts":[{"gtin":"04600000000001"}]}}
                """));
        assertEquals(83, status.remaining());
        assertEquals(1, status.existingDrafts());
        assertTrue(status.canGenerate());
        assertTrue(status.quotaKnown());
    }

    @Test
    void allowsPreflightToContinueWhenExistingDraftLookupHasNoQuotaBlock() {
        var status = ZnackNationalCatalogService.parseGs1(JsonParser.parseString("""
                {"result":{"drafts":[]}}
                """));
        assertFalse(status.quotaKnown());
        assertEquals(0, status.existingDrafts());
        assertTrue(status.canGenerate());
    }

    @Test
    void keepsLeadingZeroAndCertificateInFeed() {
        Draft draft = new Draft("6109100000", 30068, "Брюки, арт. WB-1, размер 44", "VN code",
                Map.of(2478L, "Брюки, арт. WB-1, размер 44", 2504L, "VN code",
                        ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID, "ЕАЭС RU C-RU.01:::2026-09-05"));
        JsonObject payload = ZnackNationalCatalogService.buildPayload("04627877922394", draft,
                "https://basket.example/product.jpg");

        assertEquals("04627877922394", payload.get("gtin").getAsString());
        assertEquals("шт", payload.getAsJsonArray("identified_by").get(0).getAsJsonObject()
                .get("unit").getAsString());
        assertEquals(30068, payload.getAsJsonArray("categories").get(0).getAsLong());
        assertTrue(payload.getAsJsonArray("good_attrs").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("attr_id").getAsLong()
                        == ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID));
    }

    @Test
    void prefersActiveLeafCategories() {
        var categories = ZnackNationalCatalogService.parseCategories(JsonParser.parseString("""
                {"result":[
                  {"cat_id":30068,"cat_name":"Одежда","category_active":false},
                  {"cat_id":30933,"cat_name":"Брюки","category_active":true}
                ]}
                """));
        assertEquals(1, categories.size());
        assertEquals(30933, categories.get(0).id());
    }

    @Test
    void looksUpTheRegisteredFourDigitGroupAfterAnUnmappedTenDigitTnved() {
        assertEquals(List.of("6104690001", "6104"),
                ZnackNationalCatalogService.categoryLookupCodes("6104690001"));
        assertEquals(List.of("6104"), ZnackNationalCatalogService.categoryLookupCodes("6104"));
    }

    @Test
    void preflightRetainsTheFullTnvedWhenItsFourDigitGroupProvidesTheCategory() throws Exception {
        List<String> lookups = new ArrayList<>();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[]}}");
            }

            @Override public JsonElement nationalCatalogCategories(String base, String token, String tnved)
                    throws IOException {
                lookups.add(tnved);
                return "6104".equals(tnved)
                        ? JsonParser.parseString("{\"result\":[{\"cat_id\":30933,\"cat_name\":\"Брюки\",\"category_active\":true}]}")
                        : JsonParser.parseString("{\"result\":[]}");
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, null) {
            @Override public String trueApiToken(ZnackModels.Settings settings) {
                return "shop-token";
            }
        };
        var service = new ZnackNationalCatalogService(api, auth, null, ZnackModels.Settings.empty());

        var result = service.preflight("6104 69 000 1");

        assertEquals(List.of("6104690001", "6104"), lookups);
        assertEquals("6104690001", result.tnved());
        assertEquals("6104", result.categoryTnved());
        assertEquals(30933, result.categories().get(0).id());
    }

    @Test
    void sendsTheRegisteredGroupAndKeepsTheFullTnvedOnlyAsAttribute() {
        Draft draft = new Draft("6204510000", "6204", 30933, "Брюки", "Brand",
                Map.of(13933L, "6204510000"));

        JsonObject payload = ZnackNationalCatalogService.buildPayload("04631993764363", draft, "");

        assertEquals("6204", payload.get("tnved").getAsString());
        assertTrue(payload.getAsJsonArray("good_attrs").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("attr_id").getAsLong() == 13933L));
        assertFalse(payload.has("good_images"));
    }

    @Test
    void automaticallyChoosesTheLightIndustryLeaf() {
        var selected = ZnackNationalCatalogService.selectLightIndustryCategory(List.of(
                new ZnackCardRegistrationModels.Category(1, "Прочие товары"),
                new ZnackCardRegistrationModels.Category(2, "Одежда второго и третьего слоя")
        ), "Брюки");

        assertEquals(2, selected.id());
    }

    @Test void v3PublicationMustMatchTheReviewedBusinessPayloadNotAnOlderPublishedCard()throws Exception {
        var desired=JsonParser.parseString("{\"gtin\":\"04631993764370\",\"good_name\":\"Áo mới\",\"brand\":\"VN\",\"tnved\":\"6109100000\",\"categories\":[1],\"good_attrs\":[{\"attr_id\":35,\"attr_value\":\"46\",\"attr_value_type\":\"RU\"}]}").getAsJsonObject();
        var card=desired.deepCopy();card.addProperty("good_id",91);card.addProperty("good_status","published");card.addProperty("good_signed",true);
        var api=new ZnackApiClient(){@Override public JsonElement productCards(String base,String token,String gtins){var result=new JsonObject();var rows=new com.google.gson.JsonArray();rows.add(card.deepCopy());result.add("result",rows);return result;}};
        assertTrue(service(api).verifyReviewedPublication("fixture","04631993764370",91L,desired));
        card.addProperty("good_name","Áo phiên bản cũ");assertFalse(service(api).verifyReviewedPublication("fixture","04631993764370",91L,desired));
        card.addProperty("good_name","Áo mới");card.addProperty("good_signed",false);assertFalse(service(api).verifyReviewedPublication("fixture","04631993764370",91L,desired));
        card.addProperty("good_status","draft");assertTrue(service(api).matchesReviewedCard("fixture","04631993764370",91L,desired));
        card.addProperty("good_name","Không phải nội dung đã duyệt");assertFalse(service(api).matchesReviewedCard("fixture","04631993764370",91L,desired));
    }

    private static ZnackNationalCatalogService service(ZnackApiClient api) {
        return new ZnackNationalCatalogService(api, null, null, ZnackModels.Settings.empty());
    }
}
