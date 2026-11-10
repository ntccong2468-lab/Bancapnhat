package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.UpdateHandoff;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic worker/SQLite acceptance; there are no network calls or real signatures. */
class RegistrationBatchConnectionLifetimeTest {
    @TempDir Path directory;
    private final Shop shop = new Shop(1, "Fixture WB", "fixture-connection-owner");
    private final Draft draft = new Draft("6204", "6204", 1, "Fixture", "Brand", Map.of(1L, "Fixture"), Map.of(1L, ""));
    private final List<Attribute> schema = ZnackNationalCatalogService.parseAttributes(json(
            "{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}"));
    private final List<String> gtins = List.of("04631993764370", "04631993764363");
    private final Map<String, JsonObject> payloads = new ConcurrentHashMap<>();
    private final Map<Long, ProductSnapshot> snapshots = new HashMap<>();
    private final AtomicInteger posts = new AtomicInteger(), allocations = new AtomicInteger();
    private final CountDownLatch firstPostEntered = new CountDownLatch(1), allowFirstResponse = new CountDownLatch(1);
    private String previousDirectory;
    private ZnackCardRegistrationRepository repository;
    private ZnackCardRegistrationWorkflow workflow;

    @BeforeEach void setup() throws Exception {
        previousDirectory = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture WB','WILDBERRIES','fixture-connection-owner')");
            for (int i = 1; i <= 2; i++) {
                statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1," + (100 + i) + ",'ARTICLE-" + i + "','Fixture','2026-11-10T00:00:00Z')");
                statement.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1," + (10 + i) + "," + (100 + i) + ",'XL')");
            }
        }
        repository = RegistrationWorkflowTestFixtures.repository(shop);
        RegistrationSourceFixture.ready(shop);
        var facts = new ArrayList<RegistrationFieldProvenance.Prepared>();
        for (long id = 11; id <= 12; id++) {
            var key = new ProductKey(1, Marketplace.WILDBERRIES, Long.toString(90 + id), Long.toString(id));
            var variant = new ProductVariant(key.variantId(), "ARTICLE-" + (id - 10), "Blue", "XL", "", false, "52");
            var source = new ProductSnapshot(key, "fixture", "Fixture", "", "Brand", variant.sellerArticle(), "category", List.of(), List.of(), List.of(variant), null, List.of(), Instant.now(), Map.of("owner", ListingIdentity.fingerprint(shop)));
            snapshots.put(id, source);
            facts.add(RegistrationFieldProvenance.capture(source, source, draft, schema, Map.of(), null, false));
        }
        RegistrationFieldProvenance.save(shop, facts);
        var api = new FixtureApi();
        var auth = new ZnackAuthService(api, null) { @Override public String trueApiToken(ZnackModels.Settings ignored) { return "fixture-token"; } };
        var catalog = new ZnackNationalCatalogService(api, auth, (bytes, context) -> { throw new AssertionError("No signing"); }, ZnackModels.Settings.empty());
        var scope = ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1, shop.getName())).getSettings());
        var session = new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token", catalog, scope);
        workflow = new ZnackCardRegistrationWorkflow(repository, ignored -> session, Runnable::run, false,
                (a,b,c,d) -> false, RegistrationWorkflowTestFixtures::emptyLookup, (owner,key) -> snapshots.get(Long.parseLong(key.variantId())));
    }

    @AfterEach void cleanup() {
        allowFirstResponse.countDown();
        if (previousDirectory == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousDirectory);
    }

    @Test @Timeout(120)
    void cardKeepaliveClosesBeforeUpdaterIdleWhileItsReceiptRemainsCommitted() throws Exception {
        var complete = new CompletableFuture<Void>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            Executor tracked = task -> worker.execute(() -> {
                try { task.run(); complete.complete(null); }
                catch (Throwable failure) { complete.completeExceptionally(failure); }
            });
            var service = new ZnackRegistrationBatchService(repository, workflow, (owner,row,sign) -> draft, tracked, false);
            List<Sku> selected = repository.search(new SearchCriteria(1, "", List.of(), "ALL", 10, 0));
            String run = startReviewed(service, selected);
            try {
                assertTrue(firstPostEntered.await(30, TimeUnit.SECONDS));
                assertTrue(Files.exists(directory.resolve("database.db-wal")), "The card scope must keep WAL open across the remote boundary");
                // An independent writer must commit while the simulated remote request waits.
                try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO app_config(key,value) VALUES('during-registration-post','committed')");
                }
                try (var pause = UpdateHandoff.pause()) {
                    allowFirstResponse.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (!UpdateHandoff.awaitIdle(20) && System.nanoTime() < deadline) { }
                    assertTrue(UpdateHandoff.awaitIdle(20), "The saved first card must yield before starting the second");
                    assertFalse(complete.isDone(), "The queue is suspended at its durable boundary");
                    assertEquals(1, posts.get());
                    assertEquals("lifetime-feed-1", repository.find(1, 11).feedId());
                    assertEquals(gtins.getFirst(), repository.find(1, 11).gtin());
                    assertEquals(1, service.latest(shop).acceptedFeeds());
                    // Changing journal mode needs all old SQLite handles closed. A whole-pass
                    // keepalive would fail here even though awaitIdle reports no active work.
                    try {
                        assertEquals("delete", journalMode("DELETE"));
                    } finally {
                        assertEquals("wal", journalMode("WAL"));
                    }
                    try (var connection = Database.getConnection(); var statement = connection.createStatement();
                         var result = statement.executeQuery("SELECT value FROM app_config WHERE key='during-registration-post'")) {
                        assertTrue(result.next()); assertEquals("committed", result.getString(1));
                    }
                }
            } finally {
                allowFirstResponse.countDown();
                complete.get(30, TimeUnit.SECONDS);
            }
            assertEquals(run, service.latest(shop).runId());
            assertEquals(ZnackRegistrationBatchService.State.COMPLETE, service.latest(shop).state());
            assertEquals(2, service.latest(shop).submitted());
            assertEquals(2, posts.get()); assertEquals(2, allocations.get());
            assertFalse(ZnackRegistrationBatchService.hasPendingJobs());
            assertEquals("delete", journalMode("DELETE"), "No card scope survives a completed worker pass");
        }
    }

    private String startReviewed(ZnackRegistrationBatchService service, List<Sku> selected) {
        var plans = new ArrayList<RegistrationDynamicPreflight.Plan>();
        var targets = new LinkedHashMap<ProductKey, RegistrationSourceConsent.Target>();
        for (var row : selected) {
            var source = snapshots.get(row.chrtId());
            plans.add(new RegistrationDynamicPreflight.Plan(source, draft, schema, null));
            targets.put(source.key(), RegistrationSourceConsent.forIdentity(row.gtin(), row.goodId()));
        }
        var consent = RegistrationSourceConsent.prepare(shop, UUID.randomUUID(), plans, targets, Map.of(), RegistrationFieldProvenance.readBatch(shop, plans), false);
        return service.start(shop, selected, false, consent);
    }
    private String journalMode(String value) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA journal_mode=" + value)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    private final class FixtureApi extends ZnackApiClient {
        @Override public JsonElement generatedGtins(String base,String token) { return json("{\"result\":{\"drafts\":[]}}"); }
        @Override public JsonElement generateGtins(String base,String token,int quantity) {
            assertEquals(1, quantity); return json("{\"result\":{\"drafts\":[{\"gtin\":\"" + gtins.get(allocations.getAndIncrement()) + "\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved) { return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}"); }
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long category) { return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}"); }
        @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long category) { return nationalCatalogAttributes(base,token,category); }
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement body) throws java.io.IOException {
            int number = posts.incrementAndGet();
            if (number == 1) {
                firstPostEntered.countDown();
                try { if (!allowFirstResponse.await(30,TimeUnit.SECONDS)) throw new java.io.IOException("Synthetic first response was not released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Synthetic response interrupted"); }
            }
            String feed = "lifetime-feed-" + number; payloads.put(feed,body.getAsJsonObject().deepCopy());
            return json("{\"result\":{\"feed_id\":\"" + feed + "\"}}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed) {
            String gtin = payloads.get(feed).get("gtin").getAsString();
            assertEquals(feed,repository.find(1,11L + gtins.indexOf(gtin)).feedId(),"The independent read must see the accepted receipt");
            return json("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\"" + gtin + "\"}]}}");
        }
        @Override public JsonElement productCards(String base,String token,String gtin) {
            return json("{\"result\":[{\"gtin\":\"" + gtin + "\",\"good_id\":" + (100 + gtins.indexOf(gtin)) + ",\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}");
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body) { throw new AssertionError("No signing"); }
    }
}
