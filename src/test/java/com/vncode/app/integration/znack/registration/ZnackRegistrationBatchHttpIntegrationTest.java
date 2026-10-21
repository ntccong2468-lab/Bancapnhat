package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HTTP transport; the server represents Catalog, never production. */
class ZnackRegistrationBatchHttpIntegrationTest {
    @TempDir Path directory;
    private final Shop shop = new Shop(1, "Fixture WB", "fixture-owner");
    private final List<String> gtins = List.of("04631993764370", "04631993764363", "04631993764387");
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger acceptedResponses = new AtomicInteger();
    private final AtomicInteger feedReads = new AtomicInteger();
    private final AtomicInteger cardReads = new AtomicInteger();
    private final Set<String> verifiedCards = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<ZnackRegistrationBatchService> runningBatch = new AtomicReference<>();
    private final List<String> postedGtins = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverWorkers;
    private ZnackCardRegistrationRepository repository;
    private int responseWithoutFeedId;
    private volatile boolean cardsVisible = true;
    private volatile int cardReadFailure;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture-owner')");
            for (int i = 1; i <= 3; i++) {
                statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"
                        + (100 + i) + ",'ARTICLE-" + i + "','Fixture','2026-10-06T00:00:00Z')");
                statement.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"
                        + (10 + i) + "," + (100 + i) + ",'XL')");
            }
        }
        repository = RegistrationWorkflowTestFixtures.repository(shop);
        for (int i = 0; i < 3; i++) {
            var row = repository.find(1, 11 + i);
            var draft = new Draft("6204", 1, "Fixture " + (i + 1), "Brand", Map.of());
            repository.saveReadyToSubmitGenerated(1, row, gtins.get(i), "6204", 1, draft.goodName(),
                    ZnackNationalCatalogService.buildPayload(gtins.get(i), draft, "").toString());
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newFixedThreadPool(3);
        server.setExecutor(serverWorkers);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void sequentialBatchPostsTheSavedPayloadsAndCountsOnlyReadBackCards() throws Exception {
        var batch = batch();
        batch.start(shop, selection(), false);

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get(), "Each selected card must reach the actual HTTP POST endpoint");
        assertEquals(gtins, postedGtins);
        assertEquals(3, feedReads.get());
        assertEquals(3, cardReads.get());
        var progress = batch.latest(shop);
        assertEquals(3, progress.submitted());
        assertEquals(0, progress.failed());
        assertEquals(0, progress.remaining());
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE, progress.state());
        for (int i = 0; i < 3; i++) {
            var card = repository.find(1, 11 + i);
            assertEquals(Status.PROCESSING, card.status());
            assertEquals("wire-feed-" + (i + 1), card.feedId());
            assertEquals(100 + i, card.goodId());
            assertFalse(card.wbUpdated());
        }
        assertFalse(batch.resumePending(shop));
        assertEquals(3, posts.get(), "A completed durable batch must not be replayed");
    }

    @Test void http200WithoutFeedIdDoesNotCountAsCreatedOrReplayTheRequest() throws Exception {
        responseWithoutFeedId = 2;
        var batch = batch();
        batch.start(shop, selection(), false);

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get());
        assertEquals(gtins, postedGtins);
        var progress = batch.latest(shop);
        assertEquals(2, progress.submitted(), "HTTP 200 alone does not prove Catalog accepted the card");
        assertEquals(1, progress.failed());
        assertEquals(0, progress.remaining());
        assertEquals(ZnackRegistrationBatchService.State.NEEDS_ACTION, progress.state());
        var uncertain = repository.find(1, 12);
        assertEquals(Status.FEED_REVIEW_REQUIRED, uncertain.status());
        assertFalse(uncertain.hasFeed());
        assertEquals(gtins.get(1), uncertain.gtin(), "Preserve the allocated identity while reconciling");
        assertFalse(batch.resumePending(shop));
        assertEquals(3, posts.get(), "An unknown feed result must not cause another POST");
    }

    @Test void acceptedHttpFeedsWithoutCardsRemainPendingUntilReadbackConfirmsThem() throws Exception {
        cardsVisible = false;
        var first = batch();
        String run = first.start(shop, selection(), false);

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get());
        assertEquals(3, acceptedResponses.get());
        assertEquals(0, first.latest(shop).submitted(), "Three receipt IDs are not three created cards");
        assertEquals(3, first.latest(shop).remaining());
        assertEquals(0, first.latest(shop).failed());
        assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE, first.latest(shop).state());
        for (int i = 0; i < 3; i++) {
            var row = repository.find(1, 11 + i);
            assertEquals(gtins.get(i), row.gtin());
            assertEquals("wire-feed-" + (i + 1), row.feedId());
            assertNull(row.goodId());
        }

        cardsVisible = true;
        retryNow(run);
        var resumed = batch();
        assertTrue(resumed.resumePending(shop));
        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, resumed.latest(shop).submitted());
        assertEquals(0, resumed.latest(shop).remaining());
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE, resumed.latest(shop).state());
        assertEquals(3, posts.get(), "Late card visibility must only read the original feeds");
        assertEquals(6, feedReads.get());
        assertEquals(6, cardReads.get());
    }

    @Test void readbackRateLimitAfterAcceptedPostPreservesIdentityAndNeverReplaysPost() throws Exception {
        cardReadFailure = 429;
        var first = batch();
        String run = first.start(shop, List.of(selection().getFirst()), false);
        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(1, posts.get());
        assertEquals(0, first.latest(shop).submitted());
        assertEquals(1, first.latest(shop).remaining());
        assertEquals(0, first.latest(shop).failed());
        var waiting = repository.find(1, 11);
        assertEquals(gtins.getFirst(), waiting.gtin());
        assertEquals("wire-feed-1", waiting.feedId());
        assertNotEquals(Status.FEED_REVIEW_REQUIRED, waiting.status());

        cardReadFailure = 0;
        retryNow(run);
        assertTrue(first.resumePending(shop));
        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(1, first.latest(shop).submitted());
        assertEquals(0, first.latest(shop).remaining());
        assertEquals(1, posts.get());
    }

    private void retryNow(String run) throws Exception {
        try (var connection = Database.getConnection(); var query = connection.prepareStatement(
                "UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")) {
            query.setString(1, run);
            query.executeUpdate();
        }
    }

    private ZnackRegistrationBatchService batch() {
        var api = new ZnackApiClient();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings = new ZnackModels.Settings(base, "", "", "", "", "", "", "", "", "[]", "", "", "", false);
        var auth = new ZnackAuthService(api, null) {
            @Override public String trueApiToken(ZnackModels.Settings ignored) { return "fixture-token"; }
        };
        var catalog = new ZnackNationalCatalogService(api, auth, (bytes, context) -> {
            throw new AssertionError("Submission must not wait for moderation or sign a card");
        }, settings);
        var workflow = RegistrationWorkflowTestFixtures.workflow(repository,
                ignored -> new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token", catalog), Runnable::run);
        var service = new ZnackRegistrationBatchService(repository, workflow, (owner, row, sign) -> {
            throw new AssertionError("Resume the exact saved payload without preparing or allocating again");
        }, Runnable::run, false);
        runningBatch.set(service);
        return service;
    }

    private List<Sku> selection() {
        return gtins.stream().map(gtin -> repository.search(new SearchCriteria(1, "", List.of(), "ALL", 20, 0))
                .stream().filter(row -> gtin.equals(row.gtin())).findFirst().orElseThrow()).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int code = 200;
        String response;
        try {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET")) {
                response = readback(exchange, path);
                if (path.endsWith("/nk/feed-product") && cardReadFailure != 0) {
                    code = cardReadFailure;
                    exchange.getResponseHeaders().set("Retry-After", "0");
                }
                writeResponse(exchange, code, response);
                return;
            }
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/v3/feed", path,
                    "Saved submissions need no allocation, signing or unrelated mutation");
            int number = posts.incrementAndGet();
            assertEquals(verifiedCards.size(), runningBatch.get().latest(shop).submitted(),
                    "Progress must not advance until the exact card was read back");
            try (var connection = Database.getConnection(); var query = connection.createStatement();
                 var result = query.executeQuery("SELECT count(*) FROM znack_registration_batch_items")) {
                assertTrue(result.next());
                assertEquals(runningBatch.get().latest(shop).total(), result.getInt(1), "Persist the entire selected set before the first POST");
            }
            var payload = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(gtins.get(number - 1), payload.get("gtin").getAsString());
            assertEquals(JsonParser.parseString(repository.payload(1, 10 + number)), payload,
                    "Send the exact persisted GTIN payload through ZnackApiClient");
            assertEquals(Status.FEED_SUBMITTING, repository.find(1, 10 + number).status(),
                    "The uncertain-send checkpoint must precede the real POST");
            postedGtins.add(payload.get("gtin").getAsString());
            if (number > 1 && number - 1 != responseWithoutFeedId) {
                assertEquals("wire-feed-" + (number - 1), repository.find(1, 9 + number).feedId(),
                        "Save the preceding accepted card before dispatching the next one");
            }
            var result = new JsonObject();
            if (number != responseWithoutFeedId) {
                result.addProperty("feed_id", "wire-feed-" + number);
                acceptedResponses.incrementAndGet();
            }
            var body = new JsonObject();
            body.add("result", result);
            response = body.toString();
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
            code = 422;
            response = "{\"error\":\"Fixture invariant failed\"}";
        }
        writeResponse(exchange, code, response);
    }

    private String readback(HttpExchange exchange, String path) {
        Map<String, String> query = new java.util.HashMap<>();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
        }
        if(path.equals("/api/v3/true-api/nk/attributes")){
            assertEquals("1",query.get("cat_id"));assertTrue(Set.of("m","a").contains(query.get("attr_type")));
            return "{\"result\":[{\"attr_id\":2478,\"attr_name\":\"Наименование товара\",\"attr_field_type\":\"text\"},{\"attr_id\":2504,\"attr_name\":\"Бренд\",\"attr_field_type\":\"text\"}]}";
        }
        if (path.equals("/v3/feed-status")) {
            feedReads.incrementAndGet();
            String feed = query.get("feed_id");
            int index = Integer.parseInt(feed.substring("wire-feed-".length())) - 1;
            assertEquals(feed, repository.find(1, 11 + index).feedId(), "Save the accepted feed before GET readback");
            return "{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\"" + gtins.get(index) + "\"}]}}";
        }
        assertEquals("/api/v3/true-api/nk/feed-product", path);
        cardReads.incrementAndGet();
        String gtin = query.get("gtins");
        assertTrue(gtins.contains(gtin), "Read the exact allocated GTIN, not an unrelated list");
        if (cardReadFailure != 0) return "{\"error\":\"readback temporarily unavailable\"}";
        if (!cardsVisible) return "{\"result\":[]}";
        verifiedCards.add(gtin);
        return "{\"result\":[{\"gtin\":\"" + gtin + "\",\"good_id\":" + (100 + gtins.indexOf(gtin))
                + ",\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}";
    }

    private void writeResponse(HttpExchange exchange, int code, String response) throws IOException {
        try {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
