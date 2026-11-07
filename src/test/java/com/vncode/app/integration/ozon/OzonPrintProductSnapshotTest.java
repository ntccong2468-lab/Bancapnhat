package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonPrintProductSnapshotTest {
    @TempDir Path directory;Shop shop;MockWebServer server;OzonApiClient api;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        server=new MockWebServer();server.start();api=new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()),server.url("/"),
                new OkHttpClient(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
    }
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
    static OzonPostingDto posting(int index){return new OzonPostingDto("POST-"+index,"ORDER-"+index,"ORDER-"+index,"awaiting_deliver","","","","","","",
            new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,Integer.toString(1000+index),Integer.toString(2000+index),"offer-"+index,"Product",1,"RUB","1")));}
    static JsonObject product(int index){
        var row=new JsonObject();row.addProperty("id",1000+index);row.addProperty("sku",2000+index);row.addProperty("offer_id","offer-"+index);row.addProperty("name","Product");
        var codes=new JsonArray();codes.add("OZN"+(2000+index));row.add("barcodes",codes);return row;
    }
    static String response(JsonObject... products){var rows=new JsonArray();for(var product:products)rows.add(product);var body=new JsonObject();body.add("items",rows);return body.toString();}
    @Test void moreThanAThousandMissingProductsUseTwoSkuBatchesAndNeverChangeTheCatalogCursor()throws Exception{
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            assertEquals("/v3/product/info/list",request.getPath());assertEquals(shop.getClientId(),request.getHeader("Client-Id"));
            var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();assertFalse(body.has("product_id"));var skus=body.getAsJsonArray("sku");assertTrue(skus.size()<=1000);requests.incrementAndGet();
            var rows=new JsonArray();for(var sku:skus){assertTrue(sku.getAsJsonPrimitive().isNumber());rows.add(product(sku.getAsInt()-2000));}var result=new JsonObject();result.add("items",rows);
            return new MockResponse().setBody(result.toString());
        }});
        var selected=java.util.stream.IntStream.range(0,1001).mapToObj(OzonPrintProductSnapshotTest::posting).toList();var products=new LinkedHashMap<String,OzonProductDto>();
        long started=System.nanoTime();OzonPrintProductSnapshot.refreshMissing(shop,selected,products,api);
        assertEquals(2,requests.get());assertEquals(1001,products.size());assertEquals("OZN3000",products.get("3000").barcodes().getFirst());
        try(var c=Database.getConnection();var rows=c.createStatement().executeQuery("SELECT count(*) FROM ozon_products")){assertEquals(0,rows.getInt(1),"Print metadata is an isolated snapshot, not a new catalog sync");}
        System.out.println("OZON_PRINT_METADATA_FIXTURE products=1001 requests=2 elapsed_ms="+(System.nanoTime()-started)/1_000_000);
    }
    @Test void completeCachedProductNeedsNoNetworkReadAndRetainsAllVariantDetails()throws Exception{
        var cached=new OzonProductDto("1000","offer-0","2000","Product","","Article","Blue","XL",false,"",List.of("OZN2000"));
        var products=new LinkedHashMap<>(Map.of("2000",cached));OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0)),products,api);
        assertEquals(0,server.getRequestCount());assertEquals(cached,products.get("2000"));
    }
    @Test void anEmptyBarcodeCacheIsRepairedWithoutDiscardingKnownColorAndSize()throws Exception{
        var cached=new OzonProductDto("1000","offer-0","2000","Old title","","Article","Blue","XL",false,"",List.of());
        var products=new LinkedHashMap<>(Map.of("2000",cached));server.enqueue(new MockResponse().setBody(response(product(0))));
        OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0)),products,api);
        assertEquals("Blue",products.get("2000").color());assertEquals("XL",products.get("2000").size());assertEquals("Product",products.get("2000").name());assertEquals(List.of("OZN2000"),products.get("2000").barcodes());
    }
    @Test void partialResponseKeepsTheGoodSiblingAndIdentifiesTheStillMissingProduct()throws Exception{
        server.enqueue(new MockResponse().setBody(response(product(0))));var products=new LinkedHashMap<String,OzonProductDto>();
        var failure=assertThrows(IOException.class,()->OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0),posting(1)),products,api));
        assertEquals("OZON_LABEL_PRODUCT_RESPONSE_INCOMPLETE",failure.getMessage());assertEquals(Set.of("2000"),products.keySet());
    }
    @Test void aMalformedLegacySkuDoesNotPreventReadingTheOtherSelectedProducts()throws Exception{
        var bad=new OzonPostingDto("LEGACY","ORDER","ORDER","awaiting_deliver","","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,
                List.of(new OzonPostingItemDto(0,"legacy-product","old-sku","old-offer","Old product",1,"RUB","1")));
        server.enqueue(new MockResponse().setBody(response(product(0))));var products=new LinkedHashMap<String,OzonProductDto>();
        assertThrows(IOException.class,()->OzonPrintProductSnapshot.refreshMissing(shop,List.of(bad,posting(0)),products,api));
        assertEquals(Set.of("2000"),products.keySet());assertEquals(1,server.getRequestCount());
        assertEquals(List.of(2000),JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonArray("sku").asList().stream().map(JsonElement::getAsInt).toList());
    }
    @Test void anotherOfferOrDuplicateSkuCannotRepairThisVariantsMetadata()throws Exception{
        var wrong=product(0);wrong.addProperty("offer_id","another-variant");server.enqueue(new MockResponse().setBody(response(wrong)));var products=new LinkedHashMap<String,OzonProductDto>();
        assertThrows(IOException.class,()->OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0)),products,api));assertTrue(products.isEmpty());
        server.enqueue(new MockResponse().setBody(response(product(0),product(0))));
        assertThrows(IOException.class,()->OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0)),products,api));assertTrue(products.isEmpty());
    }
    @Test void responseFromAChangedShopOwnerIsNotUsed()throws Exception{
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='changed-fixture' WHERE id=?")){p.setInt(1,shop.getId());p.executeUpdate();}
            catch(Exception failure){throw new AssertionError(failure);}
            return new MockResponse().setBody(response(product(0)));
        }});
        var products=new LinkedHashMap<String,OzonProductDto>();assertThrows(SecurityException.class,()->OzonPrintProductSnapshot.refreshMissing(shop,List.of(posting(0)),products,api));assertTrue(products.isEmpty());
    }
}
