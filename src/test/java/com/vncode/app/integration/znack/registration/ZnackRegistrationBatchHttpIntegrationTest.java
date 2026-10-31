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
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import java.time.Instant;
import java.util.*;
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
    private final AtomicInteger preCreateReads = new AtomicInteger();
    private final Set<String> verifiedCards = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<ZnackRegistrationBatchService> runningBatch = new AtomicReference<>();
    private final List<String> postedGtins = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverWorkers;
    private ZnackCardRegistrationRepository repository;
    private int responseWithoutFeedId;
    private volatile boolean cardsVisible = true;
    private boolean visibleAfterAllPosts;
    private volatile int cardReadFailure;
    private String readRetryAfter="0";
    private boolean rejectOptional,rejectFirstFeed;
    private final Map<String,JsonObject> feedPayloads=new ConcurrentHashMap<>();
    private final Map<Long,ProductSnapshot> snapshots=new HashMap<>();
    private final Map<Long,Draft> drafts=new HashMap<>();
    private final AtomicInteger allocations=new AtomicInteger();
    private List<Attribute> schema;

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
        RegistrationSourceFixture.ready(shop);
        schema=ZnackNationalCatalogService.parseAttributes(JsonParser.parseString("{\"result\":[{\"attr_id\":2478,\"attr_name\":\"Name\",\"attr_field_type\":\"text\"},{\"attr_id\":2504,\"attr_name\":\"Brand\",\"attr_field_type\":\"text\"}]}"));
        for (int i = 0; i < 3; i++) {
            var draft = new Draft("6204", 1, "Fixture " + (i + 1), "Brand", Map.of());
            long variant=11+i;var key=new ProductKey(1,Marketplace.WILDBERRIES,Long.toString(101+i),Long.toString(variant));
            var productVariant=new ProductVariant(Long.toString(variant),"ARTICLE-"+(i+1),"Blue","XL","",false,"52");
            var snapshot=new ProductSnapshot(key,"fixture","Fixture","","Brand","ARTICLE-"+(i+1),"category",List.of(),List.of(),List.of(productVariant),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
            snapshots.put(variant,snapshot);drafts.put(variant,draft);
            RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
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
        startReviewed(batch,selection());

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get(), "Each selected card must reach the actual HTTP POST endpoint");
        assertEquals(gtins, postedGtins);
        assertEquals(3, feedReads.get());
        assertEquals(6, cardReads.get(),"Submission and final reconciliation each read the exact card");
        assertEquals(0, preCreateReads.get());
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
    @Test void legacySavedPayloadWithoutSellerApprovalNeverReachesCatalog() throws Exception {
        for(int i=0;i<3;i++){
            var row=repository.find(1,11+i);var draft=drafts.get(row.chrtId());
            repository.saveReadyToSubmitGenerated(1,row,gtins.get(i),"6204",1,draft.goodName(),ZnackNationalCatalogService.buildPayload(gtins.get(i),draft,"").toString());
        }
        var batch=batch();batch.start(shop,selection(),false);
        assertNull(serverFailure.get());
        assertEquals(0,posts.get(),"A saved payload alone must not authorize a Catalog write");
        assertEquals(0,acceptedResponses.get());
        for(int i=0;i<gtins.size();i++){
            var card=repository.find(1,11+i);
            assertEquals(gtins.get(i),card.gtin(),"Keep the old identity for explicit review");
            assertFalse(card.hasFeed());
        }
    }
    @Test void aLongCatalogCooldownYieldsTheWorkerAndResumesTheSameSavedBatch()throws Exception{
        cardReadFailure=429;readRetryAfter="30";var batch=batch();
        String run=assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->startReviewed(batch,selection()));
        assertEquals(1,posts.get());assertEquals(1,batch.latest(shop).acceptedFeeds());assertEquals(0,batch.latest(shop).submitted());
        assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE,batch.latest(shop).state());
        cardReadFailure=0;retryNow(run);assertTrue(batch.resumePending(shop));
        assertEquals(3,posts.get());assertEquals(3,batch.latest(shop).submitted());assertEquals(3,new java.util.HashSet<>(postedGtins).size());
    }
    @Test void actualHttp429RetriesTheSameSavedGtinAfterItsPersistedDeadline()throws Exception{
        rejectFirstFeed=true;readRetryAfter="3600";var batch=batch();String run=startReviewed(batch,selection());
        assertNull(serverFailure.get());assertEquals(1,posts.get());assertEquals(0,acceptedResponses.get());
        assertEquals(ZnackRegistrationBatchService.State.WAITING_NETWORK,batch.latest(shop).state());
        assertEquals(Status.READY_TO_SUBMIT,repository.find(1,11).status());assertEquals(gtins.getFirst(),repository.find(1,11).gtin());
        assertTrue(new ZnackRegistrationBatchStore().latest(shop).retryAt().isAfter(java.time.Instant.now().plusSeconds(3590)));
        assertTrue(batch.resumePending(shop));assertEquals(1,posts.get(),"A future deadline cannot dispatch another request");
        retryNow(run);assertTrue(batch.resumePending(shop));assertNull(serverFailure.get());
        assertEquals(4,posts.get());assertEquals(3,acceptedResponses.get());assertEquals(3,batch.latest(shop).submitted());
        assertEquals(List.of(gtins.get(0),gtins.get(0),gtins.get(1),gtins.get(2)),postedGtins);
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE,batch.latest(shop).state());assertFalse(batch.resumePending(shop));
    }

    @Test void http200WithoutFeedIdDoesNotCountAsCreatedOrReplayTheRequest() throws Exception {
        responseWithoutFeedId = 2;
        var batch = batch();
        startReviewed(batch,selection());

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
        String run = startReviewed(first,selection());

        assertNull(serverFailure.get(), () -> "HTTP fixture assertion failed: " + serverFailure.get());
        assertEquals(3, posts.get());
        assertEquals(3, acceptedResponses.get());
        assertEquals(0, first.latest(shop).submitted(), "Three receipt IDs are not three created cards");
        assertEquals(3, first.latest(shop).remaining());
        assertEquals(0, first.latest(shop).failed());
        assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE, first.latest(shop).state());
        var queries=new com.vncode.app.ui.znackregistration.ZnackRegistrationQueryRepository();
        assertEquals(0,queries.page(new SearchCriteria(1,"",List.of(),"IN_PROGRESS",20,0)).filtered(),
                "Receipt IDs cannot move products to the Catalog processing filter");
        assertEquals(3,queries.page(new SearchCriteria(1,"",List.of(),"NOT_CREATED",20,0)).filtered());
        assertTrue(queries.allEligible(new SearchCriteria(1,"",List.of(),"NOT_CREATED",20,0)).isEmpty(),
                "Select all must not re-submit requests that are awaiting Catalog readback");
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
        assertEquals(9, feedReads.get());
        assertEquals(12, cardReads.get(),"The new submission path reads each exact card twice before later reconciliation");
    }

    @Test void readbackRateLimitAfterAcceptedPostPreservesIdentityAndNeverReplaysPost() throws Exception {
        cardReadFailure = 429;
        var first = batch();
        String run = startReviewed(first,List.of(selection().getFirst()));
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

    @Test void realHttpBatchKeepsMixedKitAndPhotoRejectionsForReview()throws Exception {
        rejectOptional=true;
        for(int i=0;i<gtins.size();i++){
            long variant=11+i;var draft=new Draft("6103109000",1,"Fixture","Brand",Map.of()).withKit(true);drafts.put(variant,draft);
            var old=snapshots.get(variant);String image="https://images.example.invalid/image.jpg";
            var snapshot=new ProductSnapshot(old.key(),old.revision(),old.title(),old.description(),old.brand(),old.vendorArticle(),old.categoryId(),List.of(new ProductMedia(java.net.URI.create(image),"",null,null,"image/jpeg")),old.attributes(),old.variants(),old.dimensions(),old.documents(),old.checkedAt(),old.metadata());
            snapshots.put(variant,snapshot);RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_photos(shop_id,nm_id,photo_index,c246x328_url) VALUES(1,?,0,?)")){p.setLong(1,101+i);p.setString(2,image);p.executeUpdate();}
        }
        var batch=batch();String run=startReviewed(batch,selection());
        assertNull(serverFailure.get(),()->"HTTP fixture assertion failed: "+serverFailure.get());
        assertEquals(0,batch.latest(shop).submitted());assertEquals(3,batch.latest(shop).failed(),()->batch.latest(shop).toString());assertEquals(3,posts.get(),()->batch.latest(shop)+" / "+selection());
        assertEquals(gtins,postedGtins);
        for(var row:selection()){
            var sent=feedPayloads.get(row.feedId());assertTrue(sent.has("is_kit"),()->"kit missing: "+sent);assertTrue(sent.has("good_images"),()->"image missing: "+sent);assertEquals("6103109000",sent.get("tnved").getAsString());assertEquals(Status.ERROR,row.status());
        }
        assertFalse(batch.resumePending(shop));assertEquals(3,posts.get());
        assertEquals(3,new com.vncode.app.features.sync.SyncRunRepository().items(run,0,20).size());
    }

    @Test void finalReadbackConfirmsDelayedCardsWithoutAnotherRegisterClickOrPost() throws Exception {
        visibleAfterAllPosts=true;
        var batch=batch();startReviewed(batch,selection());
        assertNull(serverFailure.get());
        assertEquals(3,posts.get());assertEquals(3,batch.latest(shop).submitted());
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE,batch.latest(shop).state());
        assertEquals(8,cardReads.get(),"Read the two delayed imports again after the last POST and check each accepted card twice");
        var queries=new com.vncode.app.ui.znackregistration.ZnackRegistrationQueryRepository();
        assertEquals(3,queries.page(new SearchCriteria(1,"",List.of(),"IN_PROGRESS",20,0)).filtered());
        assertEquals(0,queries.page(new SearchCriteria(1,"",List.of(),"NOT_CREATED",20,0)).filtered());
        assertFalse(batch.resumePending(shop));assertEquals(3,posts.get());
    }

    private void retryNow(String run) throws Exception {
        try (var connection = Database.getConnection(); var query = connection.prepareStatement(
                "UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")) {
            query.setString(1, run);
            query.executeUpdate();
        }
    }

    private ZnackRegistrationBatchService batch() throws IOException {
        var api = ZnackApiClient.forCatalogKey("fixture",()->{});
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings = new ZnackModels.Settings(base, "", "", "", "", "", "", "", "", "[]", "", "", "", false);
        var auth = new ZnackAuthService(api, null) {
            @Override public String trueApiToken(ZnackModels.Settings ignored) { return "fixture-token"; }
        };
        var catalog = new ZnackNationalCatalogService(api, auth, (bytes, context) -> {
            throw new AssertionError("Submission must not wait for moderation or sign a card");
        }, settings);
        var scope=ZnackRegistrationScope.fromSettings(new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(1,shop.getName())).getSettings());
        var session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope);
        var workflow = new ZnackCardRegistrationWorkflow(repository,ignored -> session,Runnable::run,false,(owner,nm,variant,gtin)->false,RegistrationWorkflowTestFixtures::emptyLookup,(owner,key)->snapshots.get(Long.parseLong(key.variantId())));
        var service = new ZnackRegistrationBatchService(repository, workflow, (owner, row, sign) -> drafts.get(row.chrtId()), Runnable::run, false);
        runningBatch.set(service);
        return service;
    }

    private List<Sku> selection() {
        return java.util.stream.LongStream.range(11,14).mapToObj(variant->repository.find(1,variant)).toList();
    }

    private String startReviewed(ZnackRegistrationBatchService batch,List<Sku> selected) {
        var plans=new ArrayList<RegistrationDynamicPreflight.Plan>();var targets=new HashMap<ProductKey,RegistrationSourceConsent.Target>();
        for(var sku:selected){var snapshot=snapshots.get(sku.chrtId());plans.add(new RegistrationDynamicPreflight.Plan(snapshot,drafts.get(sku.chrtId()),schema,null));targets.put(snapshot.key(),new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null));}
        var images=new HashMap<ProductKey,Set<String>>();
        for(var plan:plans)if(!plan.snapshot().media().isEmpty())images.put(plan.snapshot().key(),plan.snapshot().media().stream().map(media->media.uri().toString()).collect(java.util.stream.Collectors.toSet()));
        var consent=RegistrationSourceConsent.prepare(shop,java.util.UUID.randomUUID(),plans,targets,images,RegistrationFieldProvenance.readBatch(shop,plans),false);
        return batch.start(shop,selected,false,consent);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int code = 200;
        String response;
        try {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET")) {
                response = readback(exchange, path);
                if (path.endsWith("/feed-product") && cardReadFailure != 0 && postedGtins.stream().anyMatch(gtin->java.util.Objects.toString(exchange.getRequestURI().getRawQuery(), "").contains("gtins="+gtin))) {
                    code = cardReadFailure;
                    exchange.getResponseHeaders().set("Retry-After", readRetryAfter);
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
            int variant=gtins.indexOf(payload.get("gtin").getAsString());assertTrue(variant>=0);
            if(!rejectOptional&&!rejectFirstFeed)assertEquals(gtins.get(number - 1), payload.get("gtin").getAsString());
            assertEquals(JsonParser.parseString(repository.payload(1, 11 + variant)), payload,
                    "Send the exact persisted GTIN payload through ZnackApiClient");
            assertEquals(Status.FEED_SUBMITTING, repository.find(1, 11 + variant).status(),
                    "The uncertain-send checkpoint must precede the real POST");
            postedGtins.add(payload.get("gtin").getAsString());
            if(rejectFirstFeed&&number==1){exchange.getResponseHeaders().set("Retry-After",readRetryAfter);writeResponse(exchange,429,"{\"error\":\"rate limit\"}");return;}
            feedPayloads.put("wire-feed-"+number,payload.deepCopy());
            if (!rejectOptional && !rejectFirstFeed && number > 1 && number - 1 != responseWithoutFeedId) {
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
        for (String part : java.util.Objects.toString(exchange.getRequestURI().getRawQuery(), "").split("&")) {
            String[] pair = part.split("=", 2);
            query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
        }
        if(path.equals("/v3/categories"))return "{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}";
        if(path.equals("/v3/generate-gtins")){
            if("1".equals(query.get("quantity")))return "{\"result\":{\"drafts\":[{\"gtin\":\""+gtins.get(allocations.getAndIncrement())+"\"}]}}";
            return "{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}";
        }
        if(path.equals("/v3/attributes")){
            assertEquals("1",query.get("cat_id"));assertTrue(Set.of("m","a").contains(query.get("attr_type")));
            return "{\"result\":[{\"attr_id\":2478,\"attr_name\":\"Name\",\"attr_field_type\":\"text\"},{\"attr_id\":2504,\"attr_name\":\"Brand\",\"attr_field_type\":\"text\"}]}";
        }
        if (path.equals("/v3/feed-status")) {
            feedReads.incrementAndGet();
            String feed = query.get("feed_id");
            var payload=feedPayloads.get(feed);int index=gtins.indexOf(payload.get("gtin").getAsString());
            assertEquals(feed, repository.find(1, 11 + index).feedId(), "Save the accepted feed before GET readback");
            if(rejectOptional&&(payload.has("is_kit")||payload.has("good_images"))){
                var item=new JsonObject();item.addProperty("gtin",gtins.get(index));var errors=new com.google.gson.JsonArray();
                if(payload.has("is_kit")){var error=new JsonObject();error.addProperty("text","Создание комплектов в товарной группе с ТНВЭД:6103109000 не допускается. Исключите параметр is_kit.");errors.add(error);}
                if(payload.has("good_images")){var error=new JsonObject();error.addProperty("text","Изображение не доступно по URL https://images.example.invalid/image.jpg");errors.add(error);}
                item.add("errors",errors);var items=new com.google.gson.JsonArray();items.add(item);var details=new JsonObject();details.add("items",items);
                var result=new JsonObject();result.addProperty("status","Rejected");result.add("error_details",details);var identity=new JsonObject();identity.addProperty("gtin",gtins.get(index));var identities=new com.google.gson.JsonArray();identities.add(identity);result.add("item",identities);var root=new JsonObject();root.add("result",result);return root.toString();
            }
            return "{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\"" + gtins.get(index) + "\"}]}}";
        }
        assertEquals("/v3/feed-product", path);
        cardReads.incrementAndGet();
        String gtin = query.get("gtins");
        assertTrue(gtins.contains(gtin), "Read the exact allocated GTIN, not an unrelated list");
        if(!postedGtins.contains(gtin)){preCreateReads.incrementAndGet();return "{\"result\":[]}";}
        if (cardReadFailure != 0) return "{\"error\":\"readback temporarily unavailable\"}";
        if (!cardsVisible || visibleAfterAllPosts && posts.get()<gtins.size()) return "{\"result\":[]}";
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
