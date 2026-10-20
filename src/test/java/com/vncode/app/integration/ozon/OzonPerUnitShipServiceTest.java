package com.vncode.app.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.features.kiz.status.Kn3Repository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OzonPerUnitShipServiceTest {
    @TempDir Path directory;
    private MockWebServer server;
    private Shop shop;
    private final ConcurrentLinkedDeque<MockResponse> responses = new ConcurrentLinkedDeque<>();
    private final AtomicInteger shipRequests = new AtomicInteger();
    private final AtomicReference<Throwable> dispatcherFailure = new AtomicReference<>();
    private final AtomicReference<JsonObject> sentPackages = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','fixture-client','fixture-key')");
        }
        shop = new Shop(1, "Ozon", Marketplace.OZON, "fixture-client", "fixture-key");
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    assertEquals("POST", request.getMethod());
                    if ("/v4/posting/fbs/ship".equals(request.getPath())) {
                        shipRequests.incrementAndGet();
                        assertEquals("PENDING", receiptState(), "Durable intent must exist before sending the mutation");
                        assertEquals(1, count("SELECT count(*) FROM ozon_action_log WHERE action_type='ship_units' AND status='pending'"));
                        sentPackages.set(JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject());
                    } else {
                        assertEquals("/v3/posting/fbs/get", request.getPath());
                        var body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                        assertTrue(body.getAsJsonObject("with").get("product_exemplars").getAsBoolean());
                        assertTrue(body.getAsJsonObject("with").get("related_postings").getAsBoolean());
                    }
                    var response = responses.pollFirst();
                    assertNotNull(response, "Unexpected extra request must not silently succeed");
                    return response;
                } catch (Throwable failure) {
                    dispatcherFailure.compareAndSet(null, failure);
                    return new MockResponse().setResponseCode(422).setBody("{\"code\":\"FIXTURE_FAILED\"}");
                }
            }
        });
        server.start();
    }

    @AfterEach void cleanup() throws Exception {
        try {
            server.shutdown();
            assertNull(dispatcherFailure.get(), () -> "HTTP fixture invariant failed: " + dispatcherFailure.get());
        } finally {
            System.clearProperty("vncode.appdata.dir");
        }
    }

    @Test void sameSkuUnitsAreShippedSeparatelyAndEachActualChildIsPersisted() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", "posting_transferring_to_delivery"));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", "posting_transferring_to_delivery"));
        var result = service().shipPerUnit(shop, "PARENT", true);

        assertEquals("awaiting_deliver", result.status());
        assertEquals(List.of("CHILD-A", "CHILD-B"), result.resultingPostings());
        assertEquals(1, shipRequests.get());
        assertEquals("COMPLETE", receiptState());
        assertUnitPackages(101, 101);
        var postings = new OzonPostingRepository();
        assertNotNull(postings.find(1, "CHILD-A"));
        assertNotNull(postings.find(1, "CHILD-B"));
        assertEquals(1, postings.find(1, "CHILD-A").items().getFirst().quantity());
        assertEquals(0, count("SELECT count(*) FROM kn3_marks"), "Shipping cannot fabricate accepted KIZ");
    }

    @Test void differentSkusConserveTheExactSourceQuantities() throws Exception {
        enqueue(source(true));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 102, "awaiting_deliver", ""));
        assertEquals(2, service().shipPerUnit(shop, "PARENT", true).resultingPostings().size());
        assertUnitPackages(101, 102);
        assertEquals("COMPLETE", receiptState());
    }

    @Test void lostMutationResponseRecoversRelatedChildrenAndRestartNeverPostsAgain() throws Exception {
        enqueue(source(false));
        responses.add(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        enqueue(withRelated(source(false), "CHILD-A", "CHILD-B"));
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        assertEquals(2, service().shipPerUnit(shop, "PARENT", true).resultingPostings().size());
        assertEquals("COMPLETE", receiptState());
        assertEquals(1, shipRequests.get());

        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        var restarted = service().shipPerUnit(shop, "PARENT", true);
        assertTrue(restarted.reconciledAfterAmbiguousResponse());
        assertEquals(List.of("CHILD-A", "CHILD-B"), restarted.resultingPostings());
        assertEquals(1, shipRequests.get(), "Reopening the service must only read the saved child identities");
    }

    @Test void partial200CannotReportSuccessOrPermitAnotherMutation() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\"]}");
        enqueue(source(false));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        enqueue(source(false));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertEquals(1, shipRequests.get());
        assertEquals(0, count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
    }

    @Test void malformed200RemainsAmbiguousUntilChildrenCanBeRead() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":42}");
        enqueue(source(false));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        assertEquals(1, shipRequests.get());
    }

    @Test void childFromAnotherOrderCannotBeAcceptedOrWrittenToThePostingCache() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "FOREIGN-ORDER", "WH", 101, "awaiting_deliver", ""));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        assertEquals(0, count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
        assertEquals(1, shipRequests.get());
    }

    @Test void childSkuMismatchCannotSatisfySourceQuantityConservation() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 999, "awaiting_deliver", ""));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        assertEquals(0, count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
    }

    @Test void shipFailedChildKeepsTheDurableBatchPending() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", "ship_failed"));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        assertEquals(1, shipRequests.get());
    }

    @Test void locallyBoundKn3CannotBeSplitOrReturnedToStock() throws Exception {
        new Kn3Repository().observe(shop, "PARENT", List.of(
                new Kn3Mark("PARENT", "101", "fixture-exemplar", "fixture-bound-mark", Kn3Mark.State.ACCEPTED, "")));
        enqueue(source(false));
        assertEquals("OZON_SPLIT_HAS_BOUND_KIZ", assertThrows(IllegalStateException.class,
                () -> service().shipPerUnit(shop, "PARENT", true)).getMessage());
        assertEquals(0, shipRequests.get());
        assertEquals(0, count("SELECT count(*) FROM ozon_unit_ship_receipts"));
        var stored = new Kn3Repository().read(shop, "PARENT");
        assertEquals(1, stored.size());
        assertEquals(Kn3Mark.State.ACCEPTED, stored.getFirst().state());
        assertEquals("fixture-bound-mark", stored.getFirst().code());
    }

    @Test void ordinaryStatusTickCompletesAsyncAssemblyWithoutAnotherAcceptClick() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_packaging", "posting_created"));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertNotEquals("COMPLETE", receiptState());
        int beforeTick = server.getRequestCount();
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        assertEquals(1, service().reconcilePendingReadOnly(shop, api(), 3));
        assertEquals("COMPLETE", receiptState());
        assertTrue(server.getRequestCount() - beforeTick <= 3);
        assertEquals(1, shipRequests.get(), "Normal status sync must only read, never accept again");
    }

    @Test void readbackBudgetPersistsCursorAndCompletedChildrenAcrossStatusTicks() throws Exception {
        var source = JsonParser.parseString(source(false)).getAsJsonObject();
        source.getAsJsonObject("result").getAsJsonArray("products").get(0).getAsJsonObject().addProperty("quantity", 4);
        enqueue(source.toString());
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\",\"CHILD-C\",\"CHILD-D\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_packaging", "posting_created"));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        int firstTickStart = server.getRequestCount();
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-C", "ORDER", "WH", 101, "awaiting_deliver", ""));
        assertEquals(0, service().reconcilePendingReadOnly(shop, api(), 3));
        assertTrue(server.getRequestCount() - firstTickStart <= 3, "One status tick must respect the read budget");
        assertNotEquals("COMPLETE", receiptState());
        enqueue(child("CHILD-D", "ORDER", "WH", 101, "awaiting_deliver", ""));
        int secondTickStart = server.getRequestCount();
        assertEquals(1, service().reconcilePendingReadOnly(shop, api(), 3),
                "A new service must resume the persisted child cursor and prior readbacks");
        assertEquals(1, server.getRequestCount() - secondTickStart, "Do not re-read the three already verified children");
        assertEquals("COMPLETE", receiptState());
        assertEquals(1, shipRequests.get());
        assertEquals(4, count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
    }

    @Test void oldCandidateReceiptSchemaMigratesDuringBackgroundReadbackAfterRestart() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_packaging", "posting_created"));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        // The earlier candidate persisted the intent/children but had no bounded
        // readback checkpoint columns. No new accept click initializes this table.
        try (var connection=Database.getConnection();var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE ozon_unit_ship_receipts DROP COLUMN readback_json");
            statement.execute("ALTER TABLE ozon_unit_ship_receipts DROP COLUMN next_child");
        }
        assertEquals(0,count("SELECT count(*) FROM pragma_table_info('ozon_unit_ship_receipts') WHERE name IN ('readback_json','next_child')"));
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        int before=server.getRequestCount();
        assertEquals(1,service().reconcilePendingReadOnly(shop,api(),3));
        assertEquals(2,server.getRequestCount()-before);
        assertEquals(2,count("SELECT count(*) FROM pragma_table_info('ozon_unit_ship_receipts') WHERE name IN ('readback_json','next_child')"));
        assertEquals("COMPLETE",receiptState());assertEquals(1,shipRequests.get());
    }

    @Test void inconsistentCompleteReadbackIsInvalidatedAndCorrectedByNextBoundedTick() throws Exception {
        enqueue(source(false));
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_packaging", "posting_created"));
        assertThrows(OzonApiException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 999, "awaiting_deliver", ""));
        assertEquals(0,service().reconcilePendingReadOnly(shop,api(),3));
        assertNotEquals("COMPLETE",receiptState());
        assertEquals(0,count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
        assertEquals(1,count("SELECT count(*) FROM ozon_unit_ship_receipts WHERE readback_json='{}'"));
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        int before=server.getRequestCount();
        assertEquals(1,service().reconcilePendingReadOnly(shop,api(),3));
        assertEquals(2,server.getRequestCount()-before,"A new service must read Ozon's corrected children rather than retain the inconsistent cache");
        assertEquals("COMPLETE",receiptState());assertEquals(1,shipRequests.get());
        assertEquals(2,count("SELECT count(*) FROM ozon_postings WHERE posting_number LIKE 'CHILD-%'"));
    }

    @Test void remoteAssignedMarkBlocksSplitBeforeAnyMutation() throws Exception {
        var source = JsonParser.parseString(source(false)).getAsJsonObject();
        source.getAsJsonObject("result").add("product_exemplars", JsonParser.parseString("""
                {"products":[{"product_id":101,"exemplars":[{"exemplar_id":1,
                    "marks":[{"mark_type":"cis","mark":"fixture-remote-mark"}]}]}]}
                """));
        enqueue(source.toString());
        assertThrows(IllegalStateException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertEquals(0, shipRequests.get());
        assertEquals(0, count("SELECT count(*) FROM ozon_unit_ship_receipts"));
    }

    @Test void emptyExemplarIdsAndBlankMarksDoNotPretendThatKizWasAssigned() throws Exception {
        var source = JsonParser.parseString(source(false)).getAsJsonObject();
        source.getAsJsonObject("result").add("product_exemplars", JsonParser.parseString("""
                {"products":[{"product_id":101,"exemplars":[{"exemplar_id":0,
                    "marks":[{"mark_type":"cis","mark":""}]}]}]}
                """));
        source.getAsJsonObject("result").getAsJsonArray("products").get(0).getAsJsonObject()
                .add("mandatory_mark", JsonParser.parseString("[\"\"]"));
        enqueue(source.toString());
        enqueue("{\"result\":[\"CHILD-A\",\"CHILD-B\"]}");
        enqueue(child("CHILD-A", "ORDER", "WH", 101, "awaiting_deliver", ""));
        enqueue(child("CHILD-B", "ORDER", "WH", 101, "awaiting_deliver", ""));
        assertEquals(2, service().shipPerUnit(shop, "PARENT", true).resultingPostings().size());
        assertEquals("COMPLETE", receiptState());
        assertEquals(1, shipRequests.get());
    }

    @Test void explicitlyMalformedBooleanMarksCannotAuthorizeAutomaticSplit() throws Exception {
        var source = JsonParser.parseString(source(false)).getAsJsonObject();
        source.getAsJsonObject("result").add("product_exemplars", JsonParser.parseString("""
                {"products":[{"product_id":101,"exemplars":[{"exemplar_id":0,"marks":false}]}]}
                """));
        enqueue(source.toString());
        assertThrows(IllegalStateException.class, () -> service().shipPerUnit(shop, "PARENT", true));
        assertEquals(0, shipRequests.get(), "An invalid mark schema is not evidence that the source is unmarked");
        assertEquals(0, count("SELECT count(*) FROM ozon_unit_ship_receipts"));
    }

    private void assertUnitPackages(long... skus) {
        var request = sentPackages.get();
        assertNotNull(request);
        assertTrue(request.getAsJsonObject("with").get("additional_data").getAsBoolean());
        var packages = request.getAsJsonArray("packages");
        assertEquals(skus.length, packages.size());
        for (int i = 0; i < skus.length; i++) {
            var products = packages.get(i).getAsJsonObject().getAsJsonArray("products");
            assertEquals(1, products.size());
            var item = products.get(0).getAsJsonObject();
            assertEquals(skus[i], item.get("product_id").getAsLong());
            assertEquals(1, item.get("quantity").getAsInt());
        }
    }

    private OzonShipService service() {
        return new OzonShipService(new OzonPostingRepository(), new OzonProductGtinMappingRepository(),
                new OzonExemplarJobRepository(), (id, credentials) -> api());
    }

    private OzonApiClient api() {
        return new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()),
                server.url("/"), new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(300, TimeUnit.MILLISECONDS).readTimeout(300, TimeUnit.MILLISECONDS)
                .writeTimeout(300, TimeUnit.MILLISECONDS).callTimeout(1000, TimeUnit.MILLISECONDS).build(),
                new OzonApiRateLimiter(Duration.ZERO), new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private void enqueue(String body) {
        responses.add(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body));
    }

    private static String source(boolean differentSkus) {
        var root = JsonParser.parseString(child("PARENT", "ORDER", "WH", 101, "awaiting_packaging", "posting_created"))
                .getAsJsonObject();
        var posting = root.getAsJsonObject("result");
        var products = posting.getAsJsonArray("products");
        if (differentSkus) {
            var second = products.get(0).getAsJsonObject().deepCopy();
            second.addProperty("sku", 102);
            second.addProperty("offer_id", "ARTICLE-102");
            products.add(second);
        } else {
            products.get(0).getAsJsonObject().addProperty("quantity", 2);
        }
        return root.toString();
    }

    private static String child(String number, String order, String warehouse, long sku, String status, String substatus) {
        var posting = new JsonObject();
        posting.addProperty("posting_number", number);
        posting.addProperty("order_id", order);
        posting.addProperty("order_number", "ORDER-NO");
        posting.addProperty("status", status);
        posting.addProperty("substatus", substatus);
        var delivery = new JsonObject();
        delivery.addProperty("warehouse_id", warehouse);
        posting.add("delivery_method", delivery);
        var products = new JsonArray();
        var product = new JsonObject();
        product.addProperty("sku", sku);
        product.addProperty("quantity", 1);
        product.addProperty("name", "Fixture");
        product.addProperty("offer_id", "ARTICLE-" + sku);
        products.add(product);
        posting.add("products", products);
        var actions = new JsonArray();
        if ("awaiting_packaging".equals(status)) actions.add("ship");
        posting.add("available_actions", actions);
        posting.add("requirements", new JsonObject());
        posting.add("product_exemplars", new JsonObject());
        var related = new JsonObject();
        related.add("related_posting_numbers", new JsonArray());
        posting.add("related_postings", related);
        var response = new JsonObject();
        response.add("result", posting);
        return response.toString();
    }

    private static String withRelated(String body, String... numbers) {
        var root = JsonParser.parseString(body).getAsJsonObject();
        var related = new JsonArray();
        for (String number : numbers) related.add(number);
        root.getAsJsonObject("result").getAsJsonObject("related_postings").add("related_posting_numbers", related);
        return root.toString();
    }

    private static int count(String sql) throws Exception {
        try (var connection = Database.getConnection(); var query = connection.createStatement(); var result = query.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static String receiptState() throws Exception {
        try (var connection = Database.getConnection(); var query = connection.createStatement();
             var result = query.executeQuery("SELECT state FROM ozon_unit_ship_receipts WHERE posting_number='PARENT'")) {
            return result.next() ? result.getString(1) : "";
        }
    }
}
