package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import com.vncode.app.ui.znackregistration.ZnackRegistrationQueryRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic scale acceptance: local SQLite and localhost HTTP, never a seller/Catalog account. */
class RegistrationLargeBatchDurabilityTest {
    @TempDir Path directory;
    private final Shop shop = new Shop(1, "Synthetic WB", "fixture-large-batch-owner");
    private final Draft draft = new Draft("6204", "6204", 1, "Fixture", "Brand", Map.of(1L, "Fixture"), Map.of(1L, ""));
    private final List<Attribute> schema = ZnackNationalCatalogService.parseAttributes(JsonParser.parseString(
            "{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}"));
    private final Map<Long, ProductSnapshot> snapshots = new HashMap<>();
    private final Map<String, JsonObject> feeds = new ConcurrentHashMap<>();
    private final Map<String, Long> goodIds = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> postsPerGtin = new ConcurrentHashMap<>();
    private final AtomicInteger posts = new AtomicInteger(), allocations = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<String> runId = new AtomicReference<>();
    private final AtomicReference<ZnackRegistrationBatchService> currentBatch = new AtomicReference<>();
    private ZnackCardRegistrationRepository repository;
    private ZnackCardRegistrationWorkflow workflow;
    private HttpServer server;
    private ExecutorService serverWorkers;
    private boolean cardsVisible;
    private int expectedTotal;

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test @Timeout(600)
    void threeHundredWireSubmissionsPreserveEveryReceiptAndIsolateRejectedAndUnknownResultsAfterRestart() throws Exception {
        initialize(300, true);
        var selected = new ZnackRegistrationQueryRepository().allEligible(criteria(300, 0));
        assertEquals(300, selected.size());
        var workers = new ArrayDeque<Runnable>();
        var disconnectOnce = new AtomicBoolean(true);
        long disconnectedVariant = selected.get(199).chrtId();
        var first = service((owner, row, sign) -> {
            if (row.chrtId() == disconnectedVariant && disconnectOnce.compareAndSet(true, false))
                throw new UnknownHostException("Synthetic offline preparation, before any send");
            return draft;
        }, workers::add);
        currentBatch.set(first);
        String run = startReviewed(first, selected);
        runId.set(run);
        assertEquals(0, posts.get());
        assertEquals(300, new ZnackRegistrationBatchStore().items(shop, run).size());
        assertEquals(300, count("SELECT count(*) FROM znack_source_consents WHERE run_id=?", run));
        assertEquals(300, count("SELECT count(*) FROM sync_run_items WHERE run_id=?", run));

        workers.removeFirst().run();

        assertNull(serverFailure.get(), () -> "Local HTTP invariant failed: " + serverFailure.get());
        assertEquals(199, posts.get(), "Pause before preparing the 200th selected variant");
        assertEquals(ZnackRegistrationBatchService.State.WAITING_NETWORK, first.latest(shop).state());
        var saved = new ZnackRegistrationBatchStore().items(shop, run);
        var nativeBefore = nativeCheckpoints(selected);
        assertEquals(300, saved.size());
        assertEquals(198, first.latest(shop).acceptedFeeds(), "The unacknowledged HTTP 200 supplies no feed_id");
        assertEquals(2, first.latest(shop).failed(), "One rejected feed and one unknown response remain separate");
        assertEquals(0, first.latest(shop).submitted(), "Receipt IDs do not prove that cards exist");
        assertEquals(1, selected.stream().map(row -> repository.find(shop.getId(), row.chrtId()))
                .filter(row -> row.status() == Status.FEED_REVIEW_REQUIRED).count());

        // A new service reads the same durable run; no second registration approval or enqueue.
        clearRetry(run);
        var reopened = service((owner, row, sign) -> draft, Runnable::run);
        currentBatch.set(reopened);
        assertTrue(reopened.resumePending(shop));
        assertEquals(run, reopened.latest(shop).runId());
        assertEquals(300, posts.get());
        assertEquals(300, allocations.get());
        assertEquals(299, reopened.latest(shop).acceptedFeeds());
        assertEquals(298, reopened.latest(shop).remaining());
        assertEquals(0, reopened.latest(shop).submitted());
        for (var entry : nativeBefore.entrySet()) if (!entry.getValue().isBlank())
            assertEquals(entry.getValue(), checkpoint(repository.find(shop.getId(), entry.getKey())),
                    "Restart must retain each previously allocated GTIN and accepted feed");

        cardsVisible = true;
        clearRetry(run);
        assertTrue(reopened.resumePending(shop));

        var result = reopened.latest(shop);
        assertEquals(ZnackRegistrationBatchService.State.NEEDS_ACTION, result.state());
        assertEquals(298, result.submitted());
        assertEquals(2, result.failed());
        assertEquals(0, result.remaining());
        assertEquals(300, result.total());
        assertEquals(300, posts.get());
        assertEquals(300, allocations.get());
        assertEquals(300, postsPerGtin.size());
        assertTrue(postsPerGtin.values().stream().allMatch(value -> value.get() == 1),
                "Lost responses, rejected cards and restart must not replay a POST");
        assertEquals(300, new ZnackRegistrationBatchStore().items(shop, run).size());
        assertEquals(300, new SyncRunRepository().items(run, 0, 1000).size());
        assertFalse(reopened.resumePending(shop), "The two actionable outcomes need review, not blind resubmission");
        assertNull(serverFailure.get(), () -> "Local HTTP invariant failed: " + serverFailure.get());
        assertFalse(ZnackRegistrationBatchService.hasPendingJobs());
    }

    @Test @Timeout(600)
    void tenThousandApprovedVariantsSurvivePageBoundariesAppendAndReopenWithoutDispatchingNetworkWrites() throws Exception {
        initialize(10_000, false);
        var query = new ZnackRegistrationQueryRepository();
        assertEquals(300, query.page(criteria(10_000, 0)).rows().size(), "A visible page remains bounded");
        var selected = query.allEligible(criteria(10_000, 0));
        assertEquals(10_000, selected.size(), "Select all must include rows beyond the first 300");
        var workers = new ArrayDeque<Runnable>();
        var first = service((owner, row, sign) -> { throw new AssertionError("No preparation/network in queue-storage test"); }, workers::add);
        String run = startReviewed(first, selected.subList(0, 300));
        assertEquals(run, startReviewed(first, selected), "Append only separately approved, not already queued variants");
        assertEquals(1, workers.size());
        assertEquals(10_000, first.latest(shop).total());
        assertEquals(10_000, count("SELECT count(*) FROM znack_source_consents WHERE run_id=?", run));
        assertEquals(10_000, count("SELECT count(*) FROM sync_run_items WHERE run_id=?", run));
        var store = new ZnackRegistrationBatchStore();
        var items = store.items(shop, run);
        assertEquals(10_000, items.size());
        assertEquals(selected.stream().map(Sku::chrtId).toList(), items.stream().map(item -> item.sku().chrtId()).toList());
        for (int i = 0; i < items.size(); i++) assertEquals(i, items.get(i).position());

        // These are synthetic persisted receipt markers, not claims about remote card existence.
        store.item(shop, items.getFirst(), "WAITING_REMOTE", "CATALOG_REQUEST_ACCEPTED_CARD_PENDING", null, "fixture-saved-feed-1");
        store.item(shop, items.get(299), "WAITING_REMOTE", "CATALOG_REQUEST_ACCEPTED_CARD_PENDING", null, "fixture-saved-feed-300");
        assertTrue(first.pause(shop));
        workers.removeFirst().run();
        var reopenedStore = new ZnackRegistrationBatchStore();
        assertEquals("PAUSED", reopenedStore.latest(shop).state());
        assertEquals(items.stream().map(item -> item.sku().chrtId()).toList(), reopenedStore.items(shop, run).stream().map(item -> item.sku().chrtId()).toList());
        assertEquals(2, reopenedStore.summary(shop, run).acceptedFeeds());
        assertEquals(0, reopenedStore.summary(shop, run).submitted());
        assertEquals(2, count("SELECT count(*) FROM znack_registration_batch_items WHERE run_id=? AND feed_id IN ('fixture-saved-feed-1','fixture-saved-feed-300')", run));
        var resumed = service((owner, row, sign) -> { throw new AssertionError("Paused queue must not dispatch during storage acceptance"); }, workers::add);
        assertTrue(resumed.resumePaused(shop));
        assertEquals(run, resumed.latest(shop).runId());
        assertEquals(10_000, resumed.latest(shop).total());
        assertEquals(2, resumed.latest(shop).acceptedFeeds());
        assertEquals(1, workers.size());
        assertEquals(0, allocations.get());
        assertEquals(0, posts.get());
        assertTrue(resumed.cancel(shop));
        workers.removeFirst().run();
        assertEquals(10_000, reopenedStore.items(shop, run).size());
        assertEquals(2, reopenedStore.summary(shop, run).acceptedFeeds(), "Cancellation retains saved receipt markers");
        assertEquals(0, posts.get());
        assertFalse(ZnackRegistrationBatchService.hasPendingJobs());
    }

    private void initialize(int count, boolean wire) throws Exception {
        expectedTotal = count;
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Synthetic WB','WILDBERRIES','fixture-large-batch-owner')");
            }
            try (var cards = connection.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,?,?,'Fixture','2026-11-10T00:00:00Z')");
                 var variants = connection.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,?,?,'XL')")) {
                for (int i = 0; i < count; i++) {
                    cards.setLong(1, 100_000 + i); cards.setString(2, String.format(Locale.ROOT, "ARTICLE-%05d", i)); cards.addBatch();
                    variants.setLong(1, 200_000 + i); variants.setLong(2, 100_000 + i); variants.addBatch();
                }
                cards.executeBatch(); variants.executeBatch();
            }
            connection.commit();
        }
        repository = RegistrationWorkflowTestFixtures.repository(shop);
        RegistrationSourceFixture.ready(shop);
        String owner = ListingIdentity.fingerprint(shop);
        var provenance = new ArrayList<RegistrationFieldProvenance.Prepared>();
        for (int i = 0; i < count; i++) {
            var key = new ProductKey(shop.getId(), Marketplace.WILDBERRIES, Long.toString(100_000 + i), Long.toString(200_000 + i));
            var variant = new ProductVariant(key.variantId(), String.format(Locale.ROOT, "ARTICLE-%05d", i), "Blue", "XL", "", false, "52");
            var snapshot = new ProductSnapshot(key, "fixture", "Fixture", "", "Brand", variant.sellerArticle(), "category", List.of(), List.of(), List.of(variant), null, List.of(), Instant.now(), Map.of("owner", owner));
            snapshots.put(200_000L + i, snapshot);
            provenance.add(RegistrationFieldProvenance.capture(snapshot, snapshot, draft.withProductCategory(""), schema, Map.of(), null, false));
        }
        RegistrationFieldProvenance.save(shop, provenance);
        String base = ZnackModels.PRODUCTION_TRUE_API;
        if (wire) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            serverWorkers = Executors.newFixedThreadPool(2);
            server.setExecutor(serverWorkers); server.createContext("/", this::feedPost); server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }
        var api = new ScaleCatalogApi();
        var settings = new ZnackModels.Settings(base, "", "", "", "", "", "", "", "", "[]", "", "", "", false);
        var auth = new ZnackAuthService(api, null) { @Override public String trueApiToken(ZnackModels.Settings ignored) { return "fixture-token"; } };
        var catalog = new ZnackNationalCatalogService(api, auth, (bytes, context) -> { throw new AssertionError("No signing in synthetic scale acceptance"); }, settings);
        var scope = ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings());
        var session = new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token", catalog, scope);
        workflow = new ZnackCardRegistrationWorkflow(repository, ignored -> session, Runnable::run, false, (a,b,c,d) -> false,
                RegistrationWorkflowTestFixtures::emptyLookup, (ignored, key) -> snapshots.get(Long.parseLong(key.variantId())));
    }

    private ZnackRegistrationBatchService service(ZnackRegistrationBatchService.DraftFactory drafts, Executor executor) {
        return new ZnackRegistrationBatchService(repository, workflow, drafts, executor, false);
    }
    private SearchCriteria criteria(int limit, int offset) { return new SearchCriteria(shop.getId(), "", List.of(), "ALL", limit, offset); }
    private String startReviewed(ZnackRegistrationBatchService batch, List<Sku> selected) {
        var plans = new ArrayList<RegistrationDynamicPreflight.Plan>();
        var targets = new LinkedHashMap<ProductKey, RegistrationSourceConsent.Target>();
        for (var row : selected) {
            var source = snapshots.get(row.chrtId());
            plans.add(new RegistrationDynamicPreflight.Plan(source, draft.withProductCategory(row.subjectName()), schema, null));
            targets.put(source.key(), RegistrationSourceConsent.forIdentity(row.gtin(), row.goodId()));
        }
        var consent = RegistrationSourceConsent.prepare(shop, UUID.randomUUID(), plans, targets, Map.of(), RegistrationFieldProvenance.readBatch(shop, plans), false);
        return batch.start(shop, selected, false, consent);
    }
    private static int count(String sql, String run) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, run); try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
        }
    }
    private static void clearRetry(String run) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")) {
            statement.setString(1, run); assertEquals(1, statement.executeUpdate());
        }
    }
    private Map<Long, String> nativeCheckpoints(List<Sku> selected) {
        var result = new HashMap<Long, String>();
        for (var row : selected) result.put(row.chrtId(), checkpoint(repository.find(shop.getId(), row.chrtId())));
        return result;
    }
    private static String checkpoint(Sku row) { return row.hasIdentity() ? Objects.toString(row.gtin(), "") + "|" + Objects.toString(row.feedId(), "") : ""; }

    private void feedPost(HttpExchange exchange) throws IOException {
        int status = 200; String response;
        try {
            assertEquals("POST", exchange.getRequestMethod()); assertEquals("/v3/feed", exchange.getRequestURI().getPath());
            assertEquals(expectedTotal, count("SELECT count(*) FROM znack_registration_batch_items WHERE run_id=?", runId.get()));
            var payload = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String gtin = payload.get("gtin").getAsString();
            long variant = 200_000L + goodIds.get(gtin) - 1_000;
            assertEquals(Status.FEED_SUBMITTING, repository.find(shop.getId(), variant).status());
            assertEquals(1, postsPerGtin.computeIfAbsent(gtin, ignored -> new AtomicInteger()).incrementAndGet());
            int number = posts.incrementAndGet();
            feeds.put("scale-feed-" + number, payload.deepCopy());
            response = number == 75 ? "{\"result\":{}}" : "{\"result\":{\"feed_id\":\"scale-feed-" + number + "\"}}";
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure); status = 422; response = "{\"error\":\"Synthetic scale invariant failed\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private final class ScaleCatalogApi extends ZnackApiClient {
        @Override public JsonElement generatedGtins(String base, String token) { return json("{\"result\":{\"drafts\":[]}}"); }
        @Override public JsonElement generateGtins(String base, String token, int quantity) {
            assertEquals(1, quantity); int index = allocations.getAndIncrement();
            String body = String.format(Locale.ROOT, "046319937%04d", index), gtin = null;
            for (int digit = 0; digit < 10; digit++) if (com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(body + digit)) { gtin = body + digit; break; }
            assertNotNull(gtin); goodIds.put(gtin, 1_000L + index);
            return json("{\"result\":{\"drafts\":[{\"gtin\":\"" + gtin + "\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base, String token, String tnved) { return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}"); }
        @Override public JsonElement nationalCatalogAttributes(String base, String token, long id) { return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}"); }
        @Override public JsonElement nationalCatalogAllAttributes(String base, String token, long id) { return nationalCatalogAttributes(base, token, id); }
        @Override public JsonElement submitNationalCatalogFeed(String base, String token, JsonElement payload) throws IOException {
            assertNotNull(server, "Queue-only acceptance must never enter any HTTP transport");
            return super.submitNationalCatalogFeed(base, token, payload);
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
            JsonObject payload = Objects.requireNonNull(feeds.get(feed)); String gtin = payload.get("gtin").getAsString();
            long variant = 200_000L + goodIds.get(gtin) - 1_000;
            assertEquals(feed, repository.find(shop.getId(), variant).feedId(), "Persist a feed before its first GET readback");
            assertEquals(posts.get() - (posts.get() >= 75 ? 1 : 0), currentBatch.get().latest(shop).acceptedFeeds());
            return json("{\"result\":{\"status\":\"" + (feed.equals("scale-feed-150") ? "Rejected" : "Processing") + "\",\"item\":[{\"gtin\":\"" + gtin + "\"}]}}");
        }
        @Override public JsonElement productCards(String base, String token, String gtin) {
            if (!cardsVisible) return json("{\"result\":[]}");
            return json("{\"result\":[{\"gtin\":\"" + gtin + "\",\"good_id\":" + goodIds.get(gtin) + ",\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}");
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base, String token, JsonObject body) { throw new AssertionError("No signing"); }
        private JsonElement json(String text) { return JsonParser.parseString(text); }
    }
}
