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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<ZnackRegistrationBatchService> runningBatch = new AtomicReference<>();
    private final List<String> postedGtins = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverWorkers;
    private ZnackCardRegistrationRepository repository;
    private int responseWithoutFeedId;

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
        repository = new ZnackCardRegistrationRepository();
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

    @Test void sequentialBatchPostsTheSavedPayloadsAndCountsOnlyAcceptedFeedIds() throws Exception {
        var batch = batch();
        batch.start(shop, selection(), false);

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get(), "Each selected card must reach the actual HTTP POST endpoint");
        assertEquals(gtins, postedGtins);
        var progress = batch.latest(shop);
        assertEquals(3, progress.submitted());
        assertEquals(0, progress.failed());
        assertEquals(0, progress.remaining());
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE, progress.state());
        for (int i = 0; i < 3; i++) {
            var card = repository.find(1, 11 + i);
            assertEquals(Status.FEED_SUBMITTED, card.status());
            assertEquals("wire-feed-" + (i + 1), card.feedId());
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
        var workflow = new ZnackCardRegistrationWorkflow(repository,
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
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/v3/feed", exchange.getRequestURI().getPath(),
                    "No allocation, signing or moderation polling is needed to resume saved submissions");
            int number = posts.incrementAndGet();
            assertEquals(acceptedResponses.get(), runningBatch.get().latest(shop).submitted(),
                    "Progress must not advance until Catalog returned and the app saved a feed_id");
            try (var connection = Database.getConnection(); var query = connection.createStatement();
                 var result = query.executeQuery("SELECT count(*) FROM znack_registration_batch_items")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1), "Persist the entire selected set before the first POST");
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
