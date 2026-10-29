package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vncode.app.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonSyncServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;
    private OzonApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("vncode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client-1','secret')");
        }
        api = new OzonApiClient(
                1,
                new OzonCredentials("client-1", "secret"),
                server.url("/"),
                new OkHttpClient.Builder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .writeTimeout(2, TimeUnit.SECONDS)
                        .callTimeout(5, TimeUnit.SECONDS)
                        .build(),
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("vncode.appdata.dir");
        server.shutdown();
    }

    @Test void registrationSnapshotRejectsMalformedOrSilentlySkippedProductLists() throws Exception {
        for(String payload:java.util.List.of("{}","{\"result\":{\"items\":[{}]}}")) {
            server.enqueue(json(payload));
            assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncForRegistration(p->{}));
        }
        assertEquals(0,count("SELECT count(*) FROM ozon_products"));
    }
    @Test void aCompletedCatalogSurvivesReopeningAndVersionUpgradeWithoutAnotherNetworkScan()throws Exception{
        var old=new OzonProductDto("101","A","501","Saved product","",false,"",java.util.List.of("saved-barcode"));
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(old),"saved-cursor",true);
        try(var ignored=com.vncode.app.shared.LocalDataMigrationGate.prepare(temporaryDirectory,"1.5.16","javafx")){}
        try(var ignored=com.vncode.app.shared.LocalDataMigrationGate.prepare(temporaryDirectory,"1.5.17","javafx")){
            assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));assertEquals(0,new OzonCatalogSyncService(1,api).syncIfNeeded());
            var saved=new OzonCatalogRepository().findAll(1).getFirst();assertEquals(old.name(),saved.name());assertEquals(old.barcodes(),saved.barcodes());assertEquals("saved-cursor",new OzonSyncStateRepository().find(1).productsLastId());
        }
        assertEquals(0,server.getRequestCount());
    }
    @Test void anOlderSavedCatalogResumesItsCursorOnceInsteadOfStartingAgain()throws Exception{
        var old=new OzonProductDto("101","A","501","Saved product","",false,"",java.util.List.of("saved-barcode"));
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(old),"saved-cursor");
        server.enqueue(json("{\"result\":{\"items\":[],\"total\":1,\"last_id\":\"saved-cursor\"}}"));
        assertEquals(0,new OzonCatalogSyncService(1,api).syncIfNeeded());assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
        var request=server.takeRequest(1,TimeUnit.SECONDS);assertTrue(request.getPath().endsWith("/v3/product/list"));
        assertEquals("saved-cursor",com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("last_id").getAsString());
        assertEquals(0,new OzonCatalogSyncService(1,api).syncIfNeeded());assertEquals(1,server.getRequestCount());
    }
    @Test void completedCatalogReceiptCannotBeUsedByARotatedAccount()throws Exception{
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(),"saved-cursor",true);assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        assertTrue(!new OzonSyncStateRepository().productCatalogComplete(1));assertThrows(SecurityException.class,()->new OzonCatalogSyncService(1,api).syncIfNeeded());assertEquals(0,server.getRequestCount());
    }
    @Test void anIncompleteSavedCatalogCannotBePromotedToCompleteByAnEmptyTail()throws Exception{
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(new OzonProductDto("101","A","501","Saved product","",false,"",java.util.List.of())),"saved-cursor");
        server.enqueue(json("{\"result\":{\"items\":[],\"total\":2}}"));
        assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncIfNeeded());assertTrue(!new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals(1,count("SELECT count(*) FROM ozon_products"));assertEquals("saved-cursor",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test void aShortCatalogPageWithRemainingTotalMustReadTheNextPage()throws Exception {
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){
            @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){
                var body=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();String path=request.getPath();
                if(path.endsWith("/v3/product/list")){
                    boolean first=!body.has("last_id")||body.get("last_id").getAsString().isBlank();String id=first?"101":"102",offer=first?"A":"B";
                    return json("{\"result\":{\"total\":2,\"items\":[{\"product_id\":\""+id+"\",\"offer_id\":\""+offer+"\"}],\"last_id\":\"cursor-"+id+"\"}}");
                }
                if(path.endsWith("/v3/product/info/list")){
                    String id=body.getAsJsonArray("product_id").get(0).getAsString();String offer=id.equals("101")?"A":"B";
                    return json("{\"items\":[{\"id\":\""+id+"\",\"offer_id\":\""+offer+"\",\"name\":\"Fixture\",\"sku\":\""+id+"\"}]}");
                }
                if(path.endsWith("/v4/product/info/attributes")){
                    String id=body.getAsJsonObject("filter").getAsJsonArray("product_id").get(0).getAsString();String offer=id.equals("101")?"A":"B";
                    return json("{\"result\":[{\"id\":\""+id+"\",\"offer_id\":\""+offer+"\",\"description_category_id\":1,\"type_id\":2,\"attributes\":[]}]}");
                }
                return json("{\"result\":[]}");
            }
        });
        var observed=new java.util.ArrayList<OzonProductDto>();assertEquals(2,new OzonCatalogSyncService(1,api).syncForRegistration(observed::addAll));
        assertEquals(2,observed.size());assertEquals(2,count("SELECT count(*) FROM ozon_products"));
        assertEquals("cursor-102",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test void anEmptyCatalogPageCannotClaimSuccessWithProductsStillMissing()throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[],\"total\":2,\"last_id\":\"\"}}"));
        assertEquals("OZON_INCOMPLETE_PRODUCT_PAGE",assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncForRegistration(p->failSnapshot())).getMessage());
    }

    private void rotateCredentialsDuringResponse(String body) {
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){
            @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-synthetic-key' WHERE id=1");}
                catch(java.sql.SQLException e){throw new AssertionError(e);}
                return json(body);
            }
        });
    }
    @Test void aCatalogResponseCannotBeSavedAfterTheShopCredentialWasChanged()throws Exception{
        rotateCredentialsDuringResponse("{\"result\":{\"items\":[{\"product_id\":\"101\",\"offer_id\":\"A\"}],\"last_id\":\"next\"}}");
        assertThrows(SecurityException.class,()->new OzonCatalogSyncService(1,api).syncFromStart());
        assertEquals(1,server.getRequestCount());assertEquals(0,count("SELECT count(*) FROM ozon_products"));
        assertEquals("",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test void aPostingResponseCannotBeSavedAfterTheShopCredentialWasChanged()throws Exception{
        rotateCredentialsDuringResponse("{\"result\":{\"posting_number\":\"P-1\",\"status\":\"awaiting_packaging\",\"products\":[]}}");
        assertThrows(SecurityException.class,()->new OzonPostingSyncService(1,api).refresh("P-1",false));
        assertEquals(1,server.getRequestCount());assertEquals(0,count("SELECT count(*) FROM ozon_postings"));
    }
    @Test void registrationSnapshotCannotTurnMissingDetailsIntoAPlaceholderProduct() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"101\",\"offer_id\":\"A\"}],\"last_id\":\"next\"}}"));
        server.enqueue(json("{\"items\":[]}"));server.enqueue(json("{\"result\":[]} "));
        assertEquals("OZON_INCOMPLETE_PRODUCT_PAGE",assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncForRegistration(p->failSnapshot())).getMessage());
        assertEquals(0,count("SELECT count(*) FROM ozon_products"));assertEquals("",new OzonSyncStateRepository().find(1).productsLastId());
    }
    private static void failSnapshot(){throw new AssertionError("Partial details cannot emit a completed page");}
    @Test void postingHistoryCannotAdvanceItsWindowOnMalformedSuccess()throws Exception{
        server.enqueue(json("{}"));
        assertThrows(IOException.class,()->new OzonPostingSyncService(1,api).sync());
        assertEquals("",new OzonSyncStateRepository().find(1).postingsChangedSince());assertEquals(0,count("SELECT count(*) FROM ozon_postings"));
    }
    @Test void unfulfilledQueueCannotIgnoreMalformedProducts()throws Exception{
        server.enqueue(json("{\"postings\":[{\"posting_number\":\"P-1\",\"status\":\"awaiting_packaging\",\"products\":[{}]}],\"has_next\":false}"));
        assertThrows(IOException.class,()->new OzonPostingSyncService(1,api).syncUnfulfilled());assertEquals(0,count("SELECT count(*) FROM ozon_postings"));
    }
    @Test void refreshRejectsAForeignPostingWithoutWritingIt()throws Exception{
        server.enqueue(json("{\"result\":{\"posting_number\":\"OTHER\",\"status\":\"awaiting_deliver\",\"products\":[]}}"));
        assertEquals("FBS_FOREIGN_POSTING",assertThrows(IOException.class,()->new OzonPostingSyncService(1,api).refresh("EXPECTED",false)).getMessage());assertEquals(0,count("SELECT count(*) FROM ozon_postings"));
    }
    @Test void missingQuantityCannotOverwriteThePreviouslyObservedOrderItems()throws Exception{
        new OzonPostingRepository().upsertDetail(1,OzonJson.parsePostingDetail(com.google.gson.JsonParser.parseString(posting()).getAsJsonObject()));
        server.enqueue(json("{\"result\":{\"posting_number\":\"POST-TEXT-1\",\"status\":\"awaiting_packaging\",\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\"}]}}"));
        assertThrows(IOException.class,()->new OzonPostingSyncService(1,api).refresh("POST-TEXT-1",false));assertEquals(2,new OzonPostingRepository().find(1,"POST-TEXT-1").items().getFirst().quantity());
    }
    @Test void ordinaryRefreshRejectsMalformedListInsteadOfRecordingAnEmptySuccess()throws Exception{
        for(String payload:java.util.List.of("{}","{\"result\":{\"items\":[{}]}}")){
            server.enqueue(json(payload));assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncFromStart());
        }
        assertEquals("",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test void ordinaryRefreshCannotEraseAnExistingProductWhenDetailsAreMissing()throws Exception{
        var old=new OzonProductDto("101","A","501","Existing product","https://cdn.example/old.jpg",false,"",java.util.List.of("existing-barcode")).withCardAttributes("A","Blue","M","Clothing","Male");
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(old),"previous-cursor");
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"101\",\"offer_id\":\"A\"}],\"last_id\":\"next\"}}"));
        server.enqueue(json("{\"items\":[]}"));server.enqueue(json("{\"result\":[]}"));
        assertThrows(IOException.class,()->new OzonCatalogSyncService(1,api).syncFromStart());
        var retained=new OzonCatalogRepository().findAll(1).getFirst();assertEquals(old.name(),retained.name());assertEquals(old.barcodes(),retained.barcodes());assertEquals(old.sku(),retained.sku());assertEquals("previous-cursor",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test void explicitSizeMigrationPreservesOldProductsAndBarcodesWithoutGuessingTheirSizeSystem()throws Exception{
        var product=new OzonProductDto("101","A-52","501","Synthetic","",false,"",java.util.List.of("legacy-code")).withCardAttributes("A","Blue","52","Clothing","Male","L","52");
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(product),"old-cursor");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("ALTER TABLE ozon_products DROP COLUMN manufacturer_size");s.execute("ALTER TABLE ozon_products DROP COLUMN russian_size");
            OzonSchemaSupport.initialize(c);OzonSchemaSupport.initialize(c);
        }
        var restored=new OzonCatalogRepository().findAll(1).getFirst();assertEquals("52",restored.size());assertEquals("",restored.manufacturerSize());assertEquals("",restored.russianSize());
        assertEquals(java.util.List.of("legacy-code"),restored.barcodes());assertEquals("501",restored.sku());assertEquals("",new OzonSyncStateRepository().find(1).productsLastId());
    }
    @Test
    void catalogDoesNotAdvanceCursorWhenDetailFetchFails() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"90071992547409931234\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        assertEquals("", new OzonSyncStateRepository().find(1).productsLastId());
        assertEquals("upstream", new OzonSyncStateRepository().find(1).lastError());
    }

    @Test
    void postingPageCommitsButRollingCursorDoesNotAdvanceAfterLaterPageFailure() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"cursor-2\",\"has_next\":true}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonPostingSyncService(1, api).sync());

        assertEquals(1, count("SELECT COUNT(*) FROM ozon_postings"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_posting_items"));
        assertEquals("", new OzonSyncStateRepository().find(1).postingsChangedSince());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()
                .contains("\"cursor\":\"cursor-2\""));
    }

    @Test
    void invalidPostingCursorIsRecordedWithoutAdvancingRollingWindow() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting() + "],\"cursor\":\"\",\"has_next\":true}"));

        assertThrows(IOException.class, () -> new OzonPostingSyncService(1, api).sync());

        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.postingsChangedSince());
        assertEquals("invalid_response", syncState.lastError());
    }

    @Test
    void activePostingDetailsReplaceListRequirementsBeforeKizReadinessChecks() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"\",\"has_next\":false}"));
        server.enqueue(json("{\"result\":{\"posting_number\":\"POST-TEXT-1\","
                + "\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},"
                + "\"available_actions\":[]}}"));
        OzonPostingSyncService service = new OzonPostingSyncService(1, api);

        service.sync();
        assertEquals(1, service.refreshActiveDetails());

        OzonPostingDto stored = new OzonPostingRepository().find(1, "POST-TEXT-1");
        assertEquals(java.util.List.of("101"), stored.requirements().mandatoryMarkProductIds());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/posting/fbs/get", server.takeRequest(1, TimeUnit.SECONDS).getPath());
    }

    @Test
    void unfulfilledQueueMovesPostingChangedOnOzonWebsiteIntoLocalPackingState() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"\",\"has_next\":false}"));
        OzonPostingSyncService service = new OzonPostingSyncService(1, api);
        service.sync();

        server.enqueue(json("{\"postings\":[{\"posting_number\":\"POST-TEXT-1\","
                + "\"status\":\"awaiting_deliver\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":[]}],"
                + "\"cursor\":\"\",\"has_next\":false}"));

        assertEquals(1, service.syncUnfulfilled());

        OzonPostingDto stored = new OzonPostingRepository().find(1, "POST-TEXT-1");
        assertEquals("awaiting_deliver", stored.status());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v4/posting/fbs/unfulfilled/list", server.takeRequest(1, TimeUnit.SECONDS).getPath());
    }

    @Test void activeOrdersContinueAcrossFourHundredRowPagesWithoutLosingTheTail()throws Exception {
        for(int page=0;page<4;page++) {
            var result=new com.google.gson.JsonObject();var rows=new com.google.gson.JsonArray();
            for(int i=page*100;i<Math.min(page*100+100,303);i++)rows.add(com.google.gson.JsonParser.parseString(posting().replace("POST-TEXT-1","POST-"+i)));
            result.add("postings",rows);result.addProperty("has_next",page<3);result.addProperty("cursor",page<3?"cursor-"+(page+1):"");
            server.enqueue(json(result.toString()));
        }
        assertEquals(303,new OzonPostingSyncService(1,api).syncUnfulfilled());
        assertEquals(303,count("SELECT COUNT(*) FROM ozon_postings"));
        assertEquals("awaiting_packaging",new OzonPostingRepository().find(1,"POST-302").status());
        for(int page=0;page<4;page++) {
            var request=server.takeRequest(1,TimeUnit.SECONDS);assertEquals("/v4/posting/fbs/unfulfilled/list",request.getPath());
            var body=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
            assertEquals(100,body.get("limit").getAsInt());assertEquals(page==0?"":"cursor-"+page,body.get("cursor").getAsString());
        }
        assertEquals(4,server.getRequestCount());
    }

    @Test
    void successfulCatalogPageKeepsExternalIdsAsTextAndAdvancesCursorAtomically() throws Exception {
        String externalId = "90071992547409931234";
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\",\"sku\":\"sku-1\",\"name\":\"Item\","
                + "\"primary_image\":\"https://cdn.example/item.jpg\"}]}"));
        server.enqueue(json("{\"result\":[{\"id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\",\"description_category_id\":\"17000001\","
                + "\"type_id\":\"90001\",\"attributes\":["
                + "{\"id\":10096,\"values\":[{\"value\":\"deep black\"}]},"
                + "{\"id\":4295,\"values\":[{\"value\":\"176\"}]},{\"id\":9533,\"values\":[{\"value\":\"XXL\"}]},"
                + "{\"id\":9163,\"values\":[{\"value\":\"Women\"}]},"
                + "{\"id\":9024,\"values\":[{\"value\":\"seller-article\"}]}]}]}"));
        server.enqueue(json("{\"result\":[{\"description_category_id\":17000001,"
                + "\"category_name\":\"Clothing\",\"children\":[{\"type_id\":90001,"
                + "\"type_name\":\"Sportswear\",\"children\":[]}]}]}"));
        server.enqueue(json("{\"result\":["
                + "{\"id\":10096,\"name\":\"Цвет товара\"},"
                + "{\"id\":4295,\"name\":\"Российский размер\"},{\"id\":9533,\"name\":\"Размер производителя\"},"
                + "{\"id\":9163,\"name\":\"Пол\"},"
                + "{\"id\":9024,\"name\":\"Код продавца\"}]}"));

        assertEquals(1, new OzonCatalogSyncService(1, api).sync());

        assertEquals(externalId, scalar("SELECT product_id FROM ozon_products"));
        assertEquals("https://cdn.example/item.jpg", scalar("SELECT primary_image_url FROM ozon_products"));
        assertEquals("seller-article", scalar("SELECT article FROM ozon_products"));
        assertEquals("deep black", scalar("SELECT color FROM ozon_products"));
        assertEquals("176", scalar("SELECT size FROM ozon_products"));
        assertEquals("176",scalar("SELECT russian_size FROM ozon_products"));assertEquals("XXL",scalar("SELECT manufacturer_size FROM ozon_products"));
        assertEquals("176",new OzonCatalogRepository().findAll(1).getFirst().russianSize());assertEquals("XXL",new OzonCatalogRepository().findAll(1).getFirst().manufacturerSize());
        assertEquals("Sportswear", scalar("SELECT category FROM ozon_products"));
        assertEquals("Women", scalar("SELECT gender FROM ozon_products"));
        assertEquals("cursor-2", new OzonSyncStateRepository().find(1).productsLastId());
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/list"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/info/list"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v4/product/info/attributes"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath()
                .endsWith("/v1/description-category/tree"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath()
                .endsWith("/v1/description-category/attribute"));
    }

    @Test
    void catalogPageWithoutForwardCursorFailsClosedBeforePersistingProducts() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"101\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"101\",\"offer_id\":\"offer-1\"}]}"));

        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.productsLastId());
        assertEquals("invalid_response", syncState.lastError());
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
    @Test void activeDetailsDoNotStopAtThousandOrSkipRowsAsStatusesDisappear()throws Exception{
        var rows=new java.util.ArrayList<OzonPostingDto>();for(int i=0;i<1001;i++)rows.add(new OzonPostingDto(String.format("POST-%04d",i),"","","awaiting_packaging","","","","","","",new OzonRequirements(java.util.List.of(),java.util.List.of(),java.util.List.of()),java.util.List.of(),false,java.util.List.of()));new OzonPostingRepository().upsertPage(1,rows);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){String number=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("posting_number").getAsString();return json("{\"result\":{\"posting_number\":\""+number+"\",\"status\":\"cancelled\",\"products\":[]}}");}});
        assertEquals(1001,new OzonPostingSyncService(1,api).refreshActiveDetails());
        assertEquals(0,count("SELECT count(*) FROM ozon_postings WHERE status='awaiting_packaging'"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String posting() {
        return "{\"posting_number\":\"POST-TEXT-1\",\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":[]}";
    }
}
