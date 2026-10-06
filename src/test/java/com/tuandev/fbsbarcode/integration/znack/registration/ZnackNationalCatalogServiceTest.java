package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZnackNationalCatalogServiceTest {
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
        assertEquals(url, ZnackCardRegistrationWorkflow.retryImageUrl(url, "Размер одежды не заполнен"));
        assertEquals(url, ZnackCardRegistrationWorkflow.retryImageUrl(url, null));
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
        Draft draft = new Draft("6109100000", 30068, "Брюки, арт. WB-1, размер 44", "WCode",
                Map.of(2478L, "Брюки, арт. WB-1, размер 44", 2504L, "WCode",
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

    private static ZnackNationalCatalogService service(ZnackApiClient api) {
        return new ZnackNationalCatalogService(api, null, null, ZnackModels.Settings.empty());
    }
}
