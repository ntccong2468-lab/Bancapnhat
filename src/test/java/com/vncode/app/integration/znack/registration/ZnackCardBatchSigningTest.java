package com.vncode.app.integration.znack.registration;

import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic WB/Catalog identities; production workflow and signature array construction. */
class ZnackCardBatchSigningTest {
    @TempDir Path root;
    Shop shop;
    ZnackCardRegistrationRepository repository;
    FixtureApi api;
    ZnackCardRegistrationWorkflow workflow;
    final Map<String, Long> identities = new LinkedHashMap<>();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        repository = new ZnackCardRegistrationRepository();
        api = new FixtureApi();
        var catalog = new ZnackNationalCatalogService(api, null,
                (xml, context) -> new CryptoProSigningResult(new byte[] {1}, ""), ZnackModels.Settings.empty());
        workflow = new ZnackCardRegistrationWorkflow(repository,
                ignored -> new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token", catalog), Runnable::run);
    }

    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    List<Sku> seed(int count, boolean consent) throws Exception {
        var draft = new Draft("6204", 1, "Fixture", "Brand", Map.of());
        for (long id = 1; id <= count; id++) {
            try (var connection = Database.getConnection();
                    var card = connection.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(?,?,?,?,?)");
                    var size = connection.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(?,?,?,?)")) {
                card.setInt(1, shop.getId()); card.setLong(2, 500 + id); card.setString(3, "fixture-" + id);
                card.setString(4, "Fixture"); card.setString(5, "2026-10-20T00:00:00Z"); card.executeUpdate();
                size.setInt(1, shop.getId()); size.setLong(2, id); size.setLong(3, 500 + id);
                size.setString(4, "XL"); size.executeUpdate();
            }
            String gtin = gtin(id);
            identities.put(gtin, id);
            var sku = repository.find(shop.getId(), id);
            repository.saveGenerated(shop.getId(), sku, gtin, "6204", 1, "Fixture",
                    ZnackNationalCatalogService.buildPayload(gtin, draft, "").toString());
            repository.updateProgress(shop.getId(), id, Status.READY_TO_SIGN, "feed-" + id, id, "", false);
            RegistrationSigningConsent.set(shop, key(id), gtin, consent);
        }
        return current();
    }

    List<Sku> current() { return identities.values().stream().map(id -> repository.find(shop.getId(), id)).toList(); }
    Sku row(long id) { return repository.find(shop.getId(), id); }
    ProductKey key(long id) { return new ProductKey(shop.getId(), Marketplace.WILDBERRIES, Long.toString(500 + id), Long.toString(id)); }
    static String gtin(long id) {
        String digits = String.format(Locale.ROOT, "%013d", id);
        int sum = 0, weight = 3;
        for (int at = digits.length() - 1; at >= 0; at--) { sum += (digits.charAt(at) - '0') * weight; weight = 4 - weight; }
        return digits + (10 - sum % 10) % 10;
    }

    @Test void twentyFiveApprovedCardsUseTenTenFiveAndPersistEachSigningCheckpoint() throws Exception {
        assertTrue(workflow.signApproved(shop, seed(25, false), true, null));
        assertEquals(List.of(10, 10, 5), api.batchSizes);
        assertEquals(25, api.signedDocuments);
        for (var row : current()) {
            assertEquals(Status.PUBLISHED, row.status());
            assertEquals("CONFIRMED", repository.signingCheckpoint(shop.getId(), row.chrtId()).state());
            assertTrue(RegistrationSigningConsent.allowed(shop, key(row.chrtId()), row.gtin()));
        }
    }

    @Test void signatureReceiptWithoutPublicationRemainsUncertainAndIsNotPostedAgain() throws Exception {
        seed(3, true); api.publishOnPost = false;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(3), api.batchSizes);
        for (var row : current()) {
            assertEquals(Status.SIGNING, row.status());
            assertEquals("RECONCILE_REQUIRED", repository.signingCheckpoint(shop.getId(), row.chrtId()).state());
        }
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(3), api.batchSizes);
        api.published.addAll(identities.values());
        assertTrue(workflow.signConsented(shop, current(), null));
        assertTrue(current().stream().allMatch(row -> row.status() == Status.PUBLISHED));
        assertEquals(List.of(3), api.batchSizes);
    }

    @Test void unknownArrayPostRetainsFrozenXmlAndNeverReplaysTheSignature() throws Exception {
        seed(10, true); api.timeout = true;
        assertTrue(workflow.signConsented(shop, current(), null));
        var hashes = new HashMap<Long, String>();
        for (var row : current()) {
            var checkpoint = repository.signingCheckpoint(shop.getId(), row.chrtId());
            assertEquals(Status.SIGNING, row.status());
            assertEquals("RECONCILE_REQUIRED", checkpoint.state());
            assertArrayEquals(xml(row.chrtId(), false).getBytes(StandardCharsets.UTF_8), checkpoint.document().xml());
            hashes.put(row.chrtId(), checkpoint.document().sha256());
        }
        api.timeout = false;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(10), api.batchSizes);
        for (var row : current()) assertEquals(hashes.get(row.chrtId()), repository.signingCheckpoint(shop.getId(), row.chrtId()).document().sha256());
        api.published.addAll(identities.values());
        assertTrue(workflow.signConsented(shop, current(), null));
        assertTrue(current().stream().allMatch(row -> row.status() == Status.PUBLISHED));
        assertEquals(List.of(10), api.batchSizes);
    }

    @Test void oneMissingSigningDocumentDoesNotBlockTheOtherSelectedCards() throws Exception {
        seed(3, true); api.missingDocument = 2;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(2), api.batchSizes);
        assertEquals(Set.of(1L, 3L), api.published);
        assertNotEquals(Status.PUBLISHED, row(2).status());
        assertTrue(row(2).errorMessage().contains("CATALOG_SIGNING_DOCUMENT_PENDING"));
    }

    @Test void aDocumentDisappearingDuringCollectiveRecheckExcludesOnlyThatCard() throws Exception {
        seed(3, true); api.missingOnRecheck = 2;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(2), api.batchSizes);
        assertEquals(Set.of(1L, 3L), api.published);
        assertNull(repository.signingCheckpoint(shop.getId(), 2),"A known-unsent WB review is released when its document disappears");
    }

    @Test void changedXmlCannotBeSentWithItsOldDurableCheckpoint() throws Exception {
        seed(3, true); api.changedOnRecheck = 2;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(2), api.batchSizes);
        assertEquals(Set.of(1L, 3L), api.published);
        assertEquals(Status.READY_TO_SIGN, row(2).status());
        assertNull(repository.signingCheckpoint(shop.getId(), 2),"The next review must be allowed to freeze the changed XML");
        assertTrue(row(2).errorMessage().contains("SIGNING_DOCUMENT_CHANGED"));
    }

    @Test void rateLimitedFirstPostStopsBeforeAnyLaterGroup() throws Exception {
        seed(25, true); api.rateLimited = true;
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(10), api.batchSizes);
        assertEquals(10, api.signedDocuments);
        assertTrue(api.published.isEmpty());
        for (long id = 1; id <= 25; id++) assertEquals(id <= 10 ? Status.SIGNING : Status.READY_TO_SIGN, row(id).status());
    }

    @Test void automaticAggregationUsesOnlyExistingExactCardConsent() throws Exception {
        seed(3, false);
        RegistrationSigningConsent.set(shop, key(3), row(3).gtin(), true);
        assertTrue(workflow.signConsented(shop, current(), null));
        assertEquals(List.of(1), api.batchSizes);
        assertEquals(Set.of(3L), api.published);
        for (long id = 1; id <= 2; id++) {
            assertEquals(Status.READY_TO_SIGN, row(id).status());
            assertFalse(RegistrationSigningConsent.allowed(shop, key(id), row(id).gtin()));
        }
    }

    @Test void unknownSignatureWithTemporarilyMissingCardReturnsToTheMonitorWithoutAnotherPost()throws Exception {
        seed(3,true);api.timeout=true;assertTrue(workflow.signConsented(shop,current(),null));
        api.timeout=false;api.missingCard=2;assertTrue(workflow.signConsented(shop,current(),null));
        assertEquals(List.of(3),api.batchSizes);
        assertEquals(Status.SIGNING,row(2).status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row(2).errorMessage());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(shop.getId(),2).state());
        assertTrue(repository.pendingStatus(shop.getId(),30).stream().anyMatch(row->row.chrtId()==2));
    }

    @Test void permanentIdentityMismatchDoesNotRestoreAnUncertainCardAsMerelyPending()throws Exception {
        seed(3,true);api.timeout=true;assertTrue(workflow.signConsented(shop,current(),null));
        api.timeout=false;api.wrongCardIdentity=2;assertTrue(workflow.signConsented(shop,current(),null));
        assertEquals(List.of(3),api.batchSizes);assertEquals(Status.ERROR,row(2).status());
        assertTrue(row(2).errorMessage().contains("CATALOG_PUBLICATION_ID_CHANGED"));
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(shop.getId(),2).state());
    }

    static String xml(long id, boolean changed) { return "<good id='" + id + "'>" + (changed ? "changed" : "reviewed") + "</good>"; }

    final class FixtureApi extends ZnackApiClient {
        final List<Integer> batchSizes = new ArrayList<>();
        final Set<Long> published = new HashSet<>();
        final Map<Long, Integer> documentReads = new HashMap<>();
        long missingDocument, missingOnRecheck, changedOnRecheck;
        long missingCard,wrongCardIdentity;
        boolean timeout, rateLimited, publishOnPost = true;
        int signedDocuments;

        @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
            long id = Long.parseLong(feed.substring("feed-".length()));
            return json("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\"" + gtin(id) + "\",\"good_id\":" + id + "}]}}");
        }
        @Override public JsonElement productCards(String base, String token, String gtin)throws IOException {
            long id = identities.get(gtin); boolean complete = published.contains(id);
            if(id==missingCard)throw new ZnackApiException("fixture card temporarily missing",404,"{}");
            return json("{\"result\":[{\"gtin\":\"" + gtin + "\",\"good_id\":" + (id==wrongCardIdentity?999:id) + ",\"good_status\":\"" + (complete ? "published" : "draft") + "\",\"good_detailed_status\":[\"" + (complete ? "published" : "notsigned") + "\"]}]}");
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base, String token, JsonObject body) throws IOException {
            long id = identities.get(body.getAsJsonArray("gtins").get(0).getAsString());
            int count = documentReads.merge(id, 1, Integer::sum);
            if (id == missingDocument || id == missingOnRecheck && count >= 2) throw new ZnackApiException("fixture document missing", 404, "{}");
            var document = new JsonObject(); document.addProperty("goodId", id);
            document.addProperty("xml", xml(id, id == changedOnRecheck && count >= 2));
            var documents = new JsonArray(); documents.add(document);
            var result = new JsonObject(); result.add("xmls", documents);
            var response = new JsonObject(); response.add("result", result); return response;
        }
        @Override public JsonElement signNationalCatalogProduct(String base, String token, JsonArray body) throws IOException {
            assertTrue(body.size() > 0 && body.size() <= 10);
            batchSizes.add(body.size()); signedDocuments += body.size(); var signed = new JsonArray();
            for (var entry : body) {
                var value = entry.getAsJsonObject(); long id = value.get("goodId").getAsLong();
                assertEquals(Status.SIGNING, row(id).status());
                var checkpoint = repository.signingCheckpoint(shop.getId(), id);
                assertEquals("SENDING", checkpoint.state());
                assertTrue(RegistrationSigningConsent.allowed(shop, key(id), row(id).gtin()));
                byte[] xml = Base64.getDecoder().decode(value.get("base64Xml").getAsString());
                assertArrayEquals(xml(id, false).getBytes(StandardCharsets.UTF_8), xml);
                assertArrayEquals(checkpoint.document().xml(), xml);
                assertArrayEquals(new byte[] {1}, Base64.getDecoder().decode(value.get("signature").getAsString()));
                signed.add(id);
            }
            if (timeout) throw new IOException("fixture unknown batch result");
            if (rateLimited) throw new ZnackApiException("fixture signing deferred", 429, "{}");
            if (publishOnPost) for (var id : signed) published.add(id.getAsLong());
            var result = new JsonObject(); result.add("signed", signed);
            var response = new JsonObject(); response.add("result", result); return response;
        }
        JsonElement json(String text) { return JsonParser.parseString(text); }
    }
}
