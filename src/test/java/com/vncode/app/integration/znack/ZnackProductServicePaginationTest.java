package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackProductServicePaginationTest {
    @TempDir Path profile;String previous;ZnackRepository repository;Settings settings;
    @BeforeEach void setup()throws Exception {
        previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture shop','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Fixture shop"));settings=new Settings("","","","","","","","fixture-signer","fixture-certificate","[]","","","",false,"","[]","fixture-metadata",Instant.now());repository.saveSettings(settings);
    }
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void catalogKeyUsesOneThousandForEveryPageAndPersistsBothPages()throws Exception {
        var api=new FixtureApi(true);var first=technicalRows(999);first.add(product("04601234567890"));api.pages.add(page(first,1001));api.pages.add(page(rows(product("04601234567891")),1001));
        var products=service(api).sync(settings);
        assertEquals(List.of("0:1000","1:1000"),api.requests);assertEquals(Set.of("04601234567890","04601234567891"),products.stream().map(Product::gtin).collect(java.util.stream.Collectors.toSet()));assertEquals(1,api.cardReads);
    }
    @Test void legacyBearerKeepsTheExistingTenThousandRequestSize()throws Exception {
        var api=new FixtureApi(false);api.pages.add(page(rows(product("04601234567890")),1));
        assertEquals(1,service(api).sync(settings).size());assertEquals(List.of("0:10000"),api.requests);
    }
    @Test void catalogTotalBeyondTheDocumentedWindowCannotOverwriteSavedProducts(){
        repository.upsertProducts(List.of(new Product("04601234567892","Previously verified","6110209100",null,null,null,null)));
        var api=new FixtureApi(true);api.pages.add(page(rows(product("04601234567890")),10001));
        var error=assertThrows(IllegalStateException.class,()->service(api).sync(settings));assertEquals("CATALOG_LIST_RANGE_UNSUPPORTED",error.getMessage());assertEquals(List.of("0:1000"),api.requests);assertEquals(0,api.cardReads);assertEquals("Previously verified",repository.findProducts().getFirst().productName());assertEquals(1,repository.findProducts().size());
    }
    @Test void earlyEmptyPageIsUnknownAndCannotPublishAPartialSnapshot(){
        var api=new FixtureApi(true);api.pages.add(page(technicalRows(1000),1001));api.pages.add(page(new JsonArray(),1001));
        var error=assertThrows(IllegalStateException.class,()->service(api).sync(settings));assertEquals("CATALOG_LIST_RESPONSE_INCOMPLETE",error.getMessage());assertEquals(List.of("0:1000","1:1000"),api.requests);assertEquals(0,api.cardReads);assertTrue(repository.findProducts().isEmpty());
    }
    private ZnackProductService service(FixtureApi api){return new ZnackProductService(api,new ZnackAuthService(api,null){@Override public String trueApiToken(Settings ignored){return "synthetic-bearer";}},repository);}
    private static JsonObject product(String gtin){var product=new JsonObject();product.addProperty("gtin",gtin);product.addProperty("productName","Current product");return product;}
    private static JsonArray technicalRows(int count){var rows=new JsonArray();for(int i=0;i<count;i++)rows.add(product(String.format(Locale.ROOT,"029%011d",i)));return rows;}
    private static JsonArray rows(JsonObject... products){var rows=new JsonArray();for(var product:products)rows.add(product);return rows;}
    private static JsonObject page(JsonArray rows,int total){var page=new JsonObject();page.add("results",rows);page.addProperty("total",total);return page;}
    static final class FixtureApi extends ZnackApiClient {
        final boolean catalogKey;final List<JsonObject> pages=new ArrayList<>();final List<String> requests=new ArrayList<>();int cardReads;
        FixtureApi(boolean catalogKey){this.catalogKey=catalogKey;}
        @Override public boolean hasCatalogKey(){return catalogKey;}
        @Override public JsonElement products(String base,String token,int page,int limit){requests.add(page+":"+limit);return pages.get(page);}
        @Override public JsonElement productCards(String base,String token,String gtins){
            cardReads++;var response=new JsonObject();var cards=new JsonArray();for(String gtin:gtins.split(";")){
                var card=product(gtin);card.addProperty("good_name","Catalog product");card.addProperty("good_status","published");card.addProperty("tnved","6110209100");
                var attributes=new JsonArray();var document=new JsonObject();document.addProperty("attr_id",23557);document.addProperty("certificate_number","FIXTURE-DECLARATION");document.addProperty("certificate_issued_date","2026-01-10");attributes.add(document);card.add("good_attrs",attributes);cards.add(card);
            }response.add("result",cards);return response;
        }
    }
}
