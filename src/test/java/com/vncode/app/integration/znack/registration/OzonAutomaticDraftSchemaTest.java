package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public Catalog schema plus synthetic Ozon-shaped product data; no production mutations. */
class OzonAutomaticDraftSchemaTest {
    @TempDir Path directory;
    private final ProductKey key = new ProductKey(2, Marketplace.OZON, "501", "152DASH-52");
    private Shop shop;
    private OzonCardRegistrationRepository cards;
    private OzonCardRegistrationWorkflow workflow;
    private ProductSnapshot snapshot;
    private JsonObject schema, allSchema;
    private HttpServer server;
    private final AtomicInteger allocations = new AtomicInteger(), posts = new AtomicInteger(), feedReads = new AtomicInteger(), cardReads = new AtomicInteger();
    private final AtomicReference<JsonObject> payload = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        shop = GtinWriteTestFixture.shop(Marketplace.OZON);
        cards = new OzonCardRegistrationRepository();
        try (var input = getClass().getResourceAsStream("catalog-clothing-schema-31326.json")) {
            assertNotNull(input);
            schema = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        try (var input = getClass().getResourceAsStream("catalog-clothing-all-attributes-31326.json")) {
            assertNotNull(input);
            allSchema = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        snapshot = snapshot("3XL", "52", true, false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        var api = ZnackApiClient.forCatalogKey("fixture-catalog-key", () -> {});
        var auth = new ZnackAuthService(api, null) {
            @Override public String trueApiToken(ZnackModels.Settings ignored) { return "fixture-token"; }
        };
        var catalogSettings = new ZnackModels.Settings("http://127.0.0.1:" + server.getAddress().getPort(),
                "", "", "", "", "", "", "", "", "[]", "", "", "", false);
        var catalog = new ZnackNationalCatalogService(api, auth, (bytes, context) -> {
            throw new AssertionError("Submitting a draft must not sign or wait for moderation");
        }, catalogSettings);
        var settings = new ZnackRepository(new ZnackModels.ShopContext(2, shop.getName())).getSettings();
        var scope = ZnackRegistrationScope.fromSettings(settings);
        workflow = new OzonCardRegistrationWorkflow(cards,
                ignored -> new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token", catalog, scope),
                (owner, selected) -> snapshot, Runnable::run, (owner, selected, sku, gtin) -> false);
    }

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void liveSchemaWithThreeArticleAndFiftyFourSizeTypesUsesExplicitRussianVariant() throws Exception {
        var required = ZnackNationalCatalogService.parseAttributes(schema);
        assertEquals(3, required.stream().filter(a -> a.id() == 13914).findFirst().orElseThrow().valueTypes().size());
        assertEquals(54, required.stream().filter(a -> a.id() == 35).findFirst().orElseThrow().valueTypes().size());
        var draft = OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).orElseThrow();
        assertEquals("152DASH-52", draft.attributes().get(13914L));
        assertEquals("Артикул", draft.attributeTypes().get(13914L));
        assertEquals("52", draft.attributes().get(35L));
        assertEquals("РОССИЯ", draft.attributeTypes().get(35L));
        assertEquals("КОРИЧНЕВЫЙ", draft.attributes().get(36L));
        assertTrue(required.stream().filter(a -> a.id() == 36L).findFirst().orElseThrow()
                .presets().contains(draft.attributes().get(36L)));
        assertEquals("МУЖСКОЙ", draft.attributes().get(14013L));
        assertEquals("хлопок 80%, полиэстер 20%", draft.attributes().get(2483L));
        assertEquals("КОСТЮМ СПОРТИВНЫЙ", draft.attributes().get(12L));
        assertEquals(0, allocations.get());
        assertEquals(0, posts.get());
    }

    @Test void manufacturerInternationalSizeUsesItsOwnLiteralSystem() throws Exception {
        snapshot = snapshot("3XL", "", true, false);
        var draft = OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).orElseThrow();
        assertEquals("3XL", draft.attributes().get(35L));
        assertEquals("МЕЖДУНАРОДНЫЙ", draft.attributeTypes().get(35L));
    }

    @Test void numericManufacturerSizeWithoutAnExplicitSystemRemainsForSellerReview() throws Exception {
        snapshot = snapshot("52", "", true, false);
        assertTrue(OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).isEmpty());
        assertEquals(0, allocations.get());
        assertEquals(0, posts.get());
    }

    @Test void conflictingSourceGenderCannotBeResolvedByTakingTheFirstValue() throws Exception {
        snapshot = snapshot("3XL", "52", true, true);
        assertTrue(OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).isEmpty());
        assertEquals(0, allocations.get());
    }

    @Test void missingTechnicalRegulationDoesNotSelectTheFirstOfEightPresets() throws Exception {
        snapshot = snapshot("3XL", "52", false, false);
        assertTrue(OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).isEmpty());
        assertEquals(0, allocations.get());
    }

    @Test void unknownMultiTypeAttributeStillRequiresExplicitReview() throws Exception {
        schema.getAsJsonArray("result").add(JsonParser.parseString("""
                {"attr_id":99999,"attr_name":"Неизвестный атрибут","attr_field_type":"text",
                 "attr_value_type":["Первый тип","Другой тип"],"attr_preset":[]}
                """));
        var attributes = new ArrayList<>(snapshot.attributes());
        attributes.add(new ProductAttribute("fixture-unknown", "Неизвестный атрибут", List.of("Значение"), null));
        snapshot = new ProductSnapshot(snapshot.key(), snapshot.revision(), snapshot.title(), snapshot.description(),
                snapshot.brand(), snapshot.vendorArticle(), snapshot.categoryId(), snapshot.media(), attributes,
                snapshot.variants(), snapshot.dimensions(), snapshot.documents(), snapshot.checkedAt(), snapshot.metadata());
        assertTrue(OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).isEmpty());
        assertEquals(0, allocations.get());
    }

    @Test void selectedConditionalGoodsKindCannotSkipItsRequiredChild() throws Exception {
        addSourceAttributes(List.of(new ProductAttribute("kind", "Вид товара", List.of("НЕТ В СПРАВОЧНИКЕ"), null)));
        var preparation = workflow.prepare(shop, key);
        var kind = preparation.required().stream().filter(a -> a.id() == 12).findFirst().orElseThrow();
        var child = kind.dependencies().get("НЕТ В СПРАВОЧНИКЕ").getFirst();
        assertEquals(23828L, child.id());
        assertEquals("Вид товара (при отсутствии в справочнике)", child.name());
        assertEquals("text", child.fieldType());
        assertTrue(OzonCardRegistrationWorkflow.suggestedDraft(preparation).isEmpty());
        assertEquals(0, allocations.get()); assertEquals(0, posts.get());
    }

    @Test void conditionalChildUsesItsResolvedPublicSchemaAndActualSourceText() throws Exception {
        addSourceAttributes(List.of(new ProductAttribute("kind", "Вид товара", List.of("НЕТ В СПРАВОЧНИКЕ"), null),
                new ProductAttribute("custom-kind", "Вид товара (при отсутствии в справочнике)", List.of("Синтетический вид для проверки"), null)));
        var draft = OzonCardRegistrationWorkflow.suggestedDraft(workflow.prepare(shop, key)).orElseThrow();
        assertEquals("НЕТ В СПРАВОЧНИКЕ", draft.attributes().get(12L));
        assertEquals("Синтетический вид для проверки", draft.attributes().get(23828L));
        assertEquals(0, allocations.get()); assertEquals(0, posts.get());
    }

    private void addSourceAttributes(List<ProductAttribute> additions) {
        var attributes = new ArrayList<>(snapshot.attributes()); attributes.addAll(additions);
        snapshot = new ProductSnapshot(snapshot.key(), snapshot.revision(), snapshot.title(), snapshot.description(),
                snapshot.brand(), snapshot.vendorArticle(), snapshot.categoryId(), snapshot.media(), attributes,
                snapshot.variants(), snapshot.dimensions(), snapshot.documents(), snapshot.checkedAt(), snapshot.metadata());
    }

    @Test void oneBatchStartReachesActualFeedPostAndPersistsCorrectAttributeTypes() throws Exception {
        new OzonCatalogRepository().upsertPage(shop.getId(), List.of(new OzonProductDto("501", key.variantId(), "9001",
                snapshot.title(), "", key.variantId(), "коричневый", "3XL", "Костюм спортивный", "Мужской", false,
                Instant.now().toString(), List.of())), "");
        RegistrationSourceFixture.ready(shop);
        new ProductRepository().saveVerifiedSnapshot(shop, snapshot);
        var batches = new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(), cards, workflow);
        String run = batches.start(shop, List.of(key), false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        OzonRegistrationBatchService.Progress progress;
        do {
            progress = batches.latest(shop);
            if (progress != null && Set.of(OzonRegistrationBatchService.State.COMPLETED,
                    OzonRegistrationBatchService.State.FAILED).contains(progress.state())) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertNull(serverFailure.get(), () -> "HTTP fixture failed: " + serverFailure.get());
        assertNotNull(progress);
        assertEquals(1, progress.submitted(), () -> "Batch did not reach Catalog: " + batches.latest(shop));
        assertEquals(0, progress.failed());
        assertEquals(run, progress.runId());
        assertEquals(1, allocations.get());
        assertEquals(1, posts.get(), "One register action must submit without a second click");
        assertEquals(Status.PROCESSING, cards.find(key).status());
        assertEquals(91L, cards.find(key).goodId());
        assertEquals("CATALOG_CARD_IN_MODERATION", cards.find(key).error());
        assertEquals(1, feedReads.get()); assertEquals(1, cardReads.get(), "Created count requires the exact remote card readback");
        assertEquals("wire-ozon-feed", cards.find(key).feedId());
        assertEquals("Артикул", attribute(payload.get(), 13914).get("attr_value_type").getAsString());
        assertEquals("РОССИЯ", attribute(payload.get(), 35).get("attr_value_type").getAsString());
        assertEquals("52", attribute(payload.get(), 35).get("attr_value").getAsString());
        assertFalse(batches.resumePending(shop));
        assertEquals(1, posts.get());
    }

    @Test void sellerCorrectionRebuildsKnownUnsentValidationFailureWithTheSameGtin() throws Exception {
        var settings = new ZnackRepository(new ZnackModels.ShopContext(2, shop.getName())).getSettings();
        var scope = ZnackRegistrationScope.fromSettings(settings);
        assertTrue(cards.claim(shop, key, "9001", "", scope, false));
        assertTrue(cards.transition(key, Status.CHECKING, Status.ALLOCATING_GTIN));
        var oldDraft = new Draft("6211331000", "6211331000", 31326, "Old incomplete card", "Old brand",
                Map.of(ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID, "OLD-EXPIRED:::2025-01-01", 35L, "WRONG"), Map.of());
        String oldPayload = ZnackNationalCatalogService.buildPayload("00000000000024", oldDraft, "").toString();
        cards.generated(key, "00000000000024", oldPayload);
        assertTrue(workflow.resumeForBatch(shop, key, false));
        assertEquals(Status.PRE_SUBMIT_ERROR, cards.find(key).status(), "A definite permit validation failure occurs before HTTP send");
        assertEquals(0, allocations.get()); assertEquals(0, posts.get());

        // The seller has corrected the source data/document before clicking Register once again.
        snapshot = snapshot("3XL", "52", true, false);
        new OzonCatalogRepository().upsertPage(shop.getId(), List.of(new OzonProductDto("501", key.variantId(), "9001",
                snapshot.title(), "", key.variantId(), "коричневый", "3XL", "Костюм спортивный", "Мужской", false,
                Instant.now().toString(), List.of())), "");
        RegistrationSourceFixture.ready(shop);
        new ProductRepository().saveVerifiedSnapshot(shop, snapshot);
        var batches = new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(), cards, workflow);
        batches.start(shop, List.of(key), false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var progress = batches.latest(shop);
            if (progress != null && Set.of(OzonRegistrationBatchService.State.COMPLETED,
                    OzonRegistrationBatchService.State.FAILED).contains(progress.state())) break;
            Thread.sleep(20);
        }
        assertNull(serverFailure.get(), () -> "HTTP fixture failed: " + serverFailure.get());
        assertEquals(1, batches.latest(shop).submitted(), () -> "Repaired card not submitted: " + batches.latest(shop));
        assertEquals(0, batches.latest(shop).failed());
        assertEquals("00000000000024", cards.find(key).gtin());
        assertEquals(Status.PROCESSING, cards.find(key).status());
        assertEquals(91L, cards.find(key).goodId());
        assertEquals("CATALOG_CARD_IN_MODERATION", cards.find(key).error());
        assertEquals(1, feedReads.get()); assertEquals(1, cardReads.get(), "Created count requires the exact remote card readback");
        assertEquals(0, allocations.get(), "Repairing a known-unsent card must not request a new GS1 identity");
        assertEquals(1, posts.get());
        assertFalse(cards.find(key).payload().contains("OLD-EXPIRED"));
        assertFalse(cards.find(key).payload().contains("WRONG"));
        assertEquals("D:::2025-01-01", attribute(payload.get(), ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID).get("attr_value").getAsString());
        assertEquals("52", attribute(payload.get(), 35).get("attr_value").getAsString());
        assertEquals("РОССИЯ", attribute(payload.get(), 35).get("attr_value_type").getAsString());
        try (var connection = Database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT payload_json FROM ozon_card_registration_history WHERE gtin='00000000000024'")) {
            assertTrue(rows.next()); assertEquals(oldPayload, rows.getString(1), "Preserve the old invalid unsent payload in history");
        }
    }

    private ProductSnapshot snapshot(String manufacturer, String russian, boolean regulation, boolean conflictingGender) {
        var attrs = new ArrayList<ProductAttribute>();
        attrs.add(new ProductAttribute("tnved", "ТН ВЭД", List.of("6211331000"), null));
        attrs.add(new ProductAttribute("color", "Цвет товара", List.of("коричневый"), null));
        attrs.add(new ProductAttribute("gender", "Пол", conflictingGender ? List.of("Мужской", "Женский") : List.of("Мужской"), null));
        attrs.add(new ProductAttribute("material", "Состав материала", List.of("хлопок 80%", "полиэстер 20%"), null));
        attrs.add(new ProductAttribute("size", "Размер производителя", List.of(manufacturer), null));
        if (!russian.isBlank()) attrs.add(new ProductAttribute("russian", "Российский размер", List.of(russian), null));
        if (regulation) attrs.add(new ProductAttribute("regulation", "Технический регламент", List.of("017/2011"), null));
        var document = new ProductDocument("fixture-document", "DECLARATION", "D", LocalDate.of(2025, 1, 1),
                LocalDate.now().plusYears(1), "", "", null, Marketplace.OZON, DocumentState.VALID,
                "https://api-seller.ozon.ru/v1/product/certificate/list", Instant.now());
        return new ProductSnapshot(key, "live:fixture", "Костюм спортивный мужской", "", "Fixture Brand", key.variantId(),
                "1:2", List.of(), attrs, List.of(new ProductVariant(key.variantId(), key.variantId(), "коричневый",
                manufacturer, "", false, russian)), null, List.of(document), Instant.now(),
                Map.of("sku", "9001", "owner", ListingIdentity.fingerprint(shop), "source.category.name", "Спортивный костюм"));
    }

    private static JsonObject attribute(JsonObject body, long id) {
        return body.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("attr_id").getAsLong() == id).findFirst().orElseThrow();
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        int status = 200;
        JsonObject response;
        try {
            String path = exchange.getRequestURI().getPath(), query = Objects.toString(exchange.getRequestURI().getQuery(), "");
            response = switch (path) {
                case "/v3/categories" -> JsonParser.parseString("{\"result\":[{\"cat_id\":31326,\"cat_name\":\"Костюм спортивный\",\"category_active\":true}]}").getAsJsonObject();
                case "/v3/attributes" -> query.contains("attr_type=a") ? allSchema.deepCopy() : schema.deepCopy();
                case "/v3/generate-gtins" -> {
                    if (query.contains("quantity=1")) {
                        assertEquals(Status.ALLOCATING_GTIN, cards.find(key).status());
                        allocations.incrementAndGet();
                        yield JsonParser.parseString("{\"result\":{\"drafts\":[{\"gtin\":\"00000000000017\"}]}}").getAsJsonObject();
                    }
                    yield JsonParser.parseString("{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}").getAsJsonObject();
                }
                case "/v4/rd-info" -> {
                    var documents = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
                    for (var document : documents) {
                        document.getAsJsonObject().addProperty("status_group", document.getAsJsonObject().get("number").getAsString().equals("OLD-EXPIRED") ? 3 : 1);
                        document.getAsJsonObject().addProperty("product_tnved", "6101, 6201, 6202, 6211");
                    }
                    var result = new JsonObject(); result.add("documents", documents);
                    var root = new JsonObject(); root.add("result", result); yield root;
                }
                case "/v3/feed" -> {
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals(Status.FEED_SUBMITTING, cards.find(key).status());
                    try (var connection = Database.getConnection(); var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT count(*) FROM ozon_registration_batch_items")) {
                        assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                    }
                    payload.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                    posts.incrementAndGet();
                    yield JsonParser.parseString("{\"result\":{\"feed_id\":\"wire-ozon-feed\"}}").getAsJsonObject();
                }
                case "/v3/feed-status" -> {
                    assertEquals("GET",exchange.getRequestMethod());assertTrue(query.contains("feed_id=wire-ozon-feed"));
                    assertEquals(1,posts.get());assertNotNull(payload.get());feedReads.incrementAndGet();
                    String gtin=payload.get().get("gtin").getAsString();
                    yield JsonParser.parseString("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\""+gtin+"\",\"good_id\":91}]}}").getAsJsonObject();
                }
                case "/v3/feed-product" -> {
                    assertEquals("GET",exchange.getRequestMethod());assertEquals(1,posts.get());assertNotNull(payload.get());
                    String gtin=payload.get().get("gtin").getAsString();assertEquals("gtins="+gtin,java.net.URLDecoder.decode(query,StandardCharsets.UTF_8));cardReads.incrementAndGet();
                    yield JsonParser.parseString("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_id\":91,\"tnved\":\"6211331000\",\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}").getAsJsonObject();
                }
                default -> throw new AssertionError("Unexpected Catalog request: " + path);
            };
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
            status = 422; response = new JsonObject(); response.addProperty("error", "Fixture invariant failed");
        }
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body); exchange.close();
    }
}
