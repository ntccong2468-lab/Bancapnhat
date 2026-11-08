package com.vncode.app.integration.znack.registration;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZnackCatalogSubmissionReadbackTest {
    private static final String GTIN = "04631993764370";

    @Test void ownerVerifiedApiKeyAvoidsTrueApiAuthenticationButBearerFallbackStillAuthenticates() throws Exception {
        for (boolean key : new boolean[] {true, false}) {
            var authentication = new AtomicInteger();
            var api = new FixtureApi() { @Override public boolean hasCatalogKey() { return key; } };
            var auth = new ZnackAuthService(api, null) {
                @Override public String trueApiToken(ZnackModels.Settings settings) {
                    authentication.incrementAndGet();
                    if (key) throw new AssertionError("Catalog API-key reads do not need a new CryptoPro challenge");
                    return "fixture-bearer";
                }
            };
            var catalog = new ZnackNationalCatalogService(api, auth, null, ZnackModels.Settings.empty());
            assertEquals("6204", catalog.preflight("6204").categoryTnved());
            assertEquals(key ? 0 : 1, authentication.get());
            assertEquals(key ? "" : "fixture-bearer", api.lastToken);
        }
    }

    @Test void catalogApiKeyDoesNotReplaceTheSignerForAProductDocument() throws Exception {
        var api = new FixtureApi() { @Override public boolean hasCatalogKey() { return true; } };
        var signing = new AtomicInteger();
        var catalog = new ZnackNationalCatalogService(api, null, (bytes, context) -> {
            signing.incrementAndGet();
            throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED,"Fixture CryptoPro unavailable");
        }, ZnackModels.Settings.empty());
        var document = new ZnackNationalCatalogService.SigningDocument(91, "<fixture/>".getBytes(StandardCharsets.UTF_8));
        assertThrows(CryptoProException.class, () -> catalog.sendReviewedSigning("", document, null));
        assertEquals(1, signing.get());
    }

    @Test void acceptedFeedWithoutCardWaitsAndLaterVisibleCardConfirmsCreation() throws Exception {
        var api = new FixtureApi();
        var catalog = catalog(api);
        var pending = catalog.submissionStatus("fixture", "feed", GTIN);
        assertEquals("CardNotFound", pending.status());
        assertFalse(pending.cardVerified());
        assertFalse(pending.failed());
        api.visible = true;
        var created = catalog.submissionStatus("fixture", "feed", GTIN);
        assertEquals("CardModeration", created.status());
        assertTrue(created.cardVerified());
        assertEquals(91L, created.goodId());
        assertEquals(2, api.feedReads);
        assertEquals(2, api.cardReads, "One exact-card read for each receipt reconciliation");
    }

    @Test void failedAndErrorFeedsWithoutDetailedMessagesAreRejectedWithoutInventingACard() throws Exception {
        for (String status : new String[] {"Failed", "Error", "Rejected"}) {
            var api = new FixtureApi();
            api.status = status;
            var progress = catalog(api).submissionStatus("fixture", "feed", GTIN);
            assertTrue(progress.failed());
            assertFalse(progress.cardVerified());
            assertTrue(CatalogFeedback.confirmedRejection(progress));
            assertEquals(0, api.cardReads);
        }
    }

    @Test void moderatedReceiptWithoutAnActualCardCannotAuthorizeSigning() throws Exception {
        var api = new FixtureApi();
        api.status = "Moderated";
        var progress = catalog(api).submissionStatus("fixture", "feed", GTIN);
        assertEquals("CardNotFound", progress.status());
        assertFalse(progress.readyToSign());
        assertFalse(progress.cardVerified());
    }

    @Test void aFeedForAnotherGtinCannotBorrowAnExistingCardAsCreationProof() {
        var api = new FixtureApi() {
            @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
                return json("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\"04631993764363\",\"good_id\":91}]}}");
            }
        };
        api.visible = true;
        var error = assertThrows(IllegalStateException.class, () -> catalog(api).submissionStatus("fixture", "foreign-feed", GTIN));
        assertEquals("CATALOG_FEED_IDENTITY_NOT_CONFIRMED", error.getMessage());
        assertEquals(0, api.cardReads);
    }

    @Test void aResponseForAnotherFeedCannotAdvanceOrRejectThePersistedRequest() {
        for (String status : new String[] {"Received", "Processing", "Moderated", "Processed", "Signed", "Rejected", "Failed", "Error"}) {
            for (boolean submissionRead : new boolean[] {true, false}) {
                var api = new FixtureApi() {
                    @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
                        assertEquals("persisted-feed", feed);
                        return json("{\"result\":{\"feed_id\":\"different-feed\",\"status\":\"" + status
                                + "\",\"item\":[{\"gtin\":\"" + GTIN + "\",\"good_id\":91}]}}");
                    }
                };
                api.visible = true;
                var catalog = catalog(api);
                var error = assertThrows(IllegalStateException.class, () -> {
                    if (submissionRead) catalog.submissionStatus("fixture", "persisted-feed", GTIN);
                    else catalog.progress("fixture", "persisted-feed", GTIN);
                }, status + "/" + submissionRead);
                assertEquals("CATALOG_FEED_IDENTITY_NOT_CONFIRMED", error.getMessage());
                assertEquals(0, api.cardReads, "A foreign receipt cannot borrow the current card");
            }
        }
    }

    @Test void matchingFeedIdentityAndLegacyResponsesWithoutIdentityRemainReadable() throws Exception {
        for (boolean includeFeed : new boolean[] {true, false}) {
            for (boolean submissionRead : new boolean[] {true, false}) {
                var api = new FixtureApi() {
                    @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
                        return json("{\"result\":{" + (includeFeed ? "\"feed_id\":\"persisted-feed\"," : "")
                                + "\"status\":\"Processing\",\"item\":[{\"gtin\":\"" + GTIN + "\",\"good_id\":91}]}}");
                    }
                };
                api.visible = true;
                var catalog = catalog(api);
                var progress = submissionRead ? catalog.submissionStatus("fixture", "persisted-feed", GTIN)
                        : catalog.progress("fixture", "persisted-feed", GTIN);
                assertEquals("CardModeration", progress.status());
                assertTrue(progress.cardVerified());
                assertFalse(progress.failed());
                assertFalse(progress.signed());
            }
        }
    }

    @Test void anOlderPublishedVersionCannotCompleteOrAuthorizeSigningOfAnActiveRevision() throws Exception {
        for(String feedStatus:new String[]{"Received","Processing","Moderated"}){
            for(String revision:new String[]{"draft","moderation","errors"}){
                var api=new FixtureApi(){
                    @Override public JsonElement productCards(String base,String token,String gtin){
                        return json("{\"result\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91,\"good_status\":\"published\",\"good_signed\":true,\"good_detailed_status\":[\"published\",\""+revision+"\"]}]}");
                    }
                };
                api.status=feedStatus;
                var progress=catalog(api).submissionStatus("fixture","active-feed",GTIN);
                assertFalse(progress.signed(),feedStatus+"/"+revision);
                assertFalse(progress.readyToSign(),feedStatus+"/"+revision);
                assertEquals(switch(revision){case "draft"->"CardDraft";case "moderation"->"CardModeration";default->"Rejected";},progress.status());
            }
        }
    }

    private static ZnackNationalCatalogService catalog(FixtureApi api) {
        return new ZnackNationalCatalogService(api, null, (bytes, context) -> new CryptoProSigningResult(new byte[] {1}, ""), ZnackModels.Settings.empty());
    }

    private static class FixtureApi extends ZnackApiClient {
        String status = "Processing", lastToken;
        boolean visible;
        int feedReads, cardReads;
        @Override public JsonElement generatedGtins(String base, String token) {
            lastToken = token;
            return json("{\"result\":{\"monthly-limit\":{\"limit\":100,\"usage\":1},\"drafts\":[]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base, String token, String tnved) {
            return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base, String token, String feed) {
            feedReads++;
            return json("{\"result\":{\"status\":\"" + status + "\",\"item\":[{\"gtin\":\"" + GTIN + "\"}]}}");
        }
        @Override public JsonElement productCards(String base, String token, String gtin) {
            cardReads++;
            assertEquals(GTIN, gtin);
            return json(visible ? "{\"result\":[{\"gtin\":\"" + GTIN + "\",\"good_id\":91,\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}" : "{\"result\":[]}");
        }
        static JsonElement json(String value) { return JsonParser.parseString(value); }
    }
}
