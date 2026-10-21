package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbGtinProductReaderTest {
    @TempDir Path profile;
    Shop shop;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void duplicateVariantIdentityNeverReturnsAnArbitraryFirstSize(){
        var card=new Gson().fromJson("{\"nmID\":101,\"vendorCode\":\"Fixture\",\"sizes\":[{\"chrtID\":11,\"techSize\":\"XL\",\"skus\":[\"first\"]},{\"chrtID\":11,\"techSize\":\"L\",\"skus\":[\"second\"]}]}",WbProductCard.class);
        var key=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","11");
        assertEquals("ambiguous_wb_variant",assertThrows(IllegalArgumentException.class,()->WbGtinProductReader.fromCard(key,card)).getMessage());
    }
    @Test void knownNativeColorAliasesAreReadAndConflictingAttributesRemainUnverified(){
        var json=card(101,11);json.add("characteristics",JsonParser.parseString("[{\"name\":\"Цвет товара\",\"value\":[\"Черный\"]}]"));
        var key=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","11");
        assertEquals("Черный",WbGtinProductReader.fromCard(key,new Gson().fromJson(json,WbProductCard.class)).color());
        json.getAsJsonArray("characteristics").add(JsonParser.parseString("{\"name\":\"Color\",\"value\":[\"Brown\"]}"));
        assertEquals("",WbGtinProductReader.fromCard(key,new Gson().fromJson(json,WbProductCard.class)).color());
    }
    @Test void ownerChangeDuringAReadDiscardsTheWholeResult()throws Exception{
        var api=new WbApiClient(){@Override public WbProductCardsResponse getProductCards(String token,String locale,String cursor,Long nm,int limit)throws IOException{
            try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='changed-owner' WHERE id=1");}catch(java.sql.SQLException e){throw new IOException(e);}
            return page(List.of(card(101,11)),"first",101L);
        }};
        assertThrows(SecurityException.class,()->new WbGtinProductReader(shop,api).list());
    }
    @Test void aLaterPageFailureCannotReturnTheFirstHundredProductsAsACompleteCatalog(){
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var api=new WbApiClient(){@Override public WbProductCardsResponse getProductCards(String token,String locale,String cursor,Long nm,int limit)throws IOException{
            if(reads.incrementAndGet()>1)throw new IOException("Listing API HTTP 429");
            return page(java.util.stream.IntStream.rangeClosed(1,100).mapToObj(n->card(n,n)).toList(),"first",100L);
        }};
        assertThrows(IOException.class,()->new WbGtinProductReader(shop,api).list());assertEquals(2,reads.get());
    }
    @Test void theSameCardOnTwoCursorPagesIsAnIncompleteChangingSnapshot(){
        var api=new WbApiClient(){@Override public WbProductCardsResponse getProductCards(String token,String locale,String cursor,Long nm,int limit){
            return cursor==null?page(java.util.stream.IntStream.rangeClosed(1,100).mapToObj(n->card(n,n)).toList(),"first",100L):page(List.of(card(1,1)),"second",1L);
        }};
        assertEquals("invalid_wb_catalog",assertThrows(IOException.class,()->new WbGtinProductReader(shop,api).list()).getMessage());
    }
    @Test void missingOrContradictoryTotalsCannotMasqueradeAsACompleteTwentyProductCatalog(){
        for(boolean missing:new boolean[]{false,true}){
            var api=new WbApiClient(){@Override public WbProductCardsResponse getProductCards(String token,String locale,String cursor,Long nm,int limit){
                var response=new JsonObject();response.add("cards",new Gson().toJsonTree(java.util.stream.IntStream.rangeClosed(1,20).mapToObj(n->card(n,n)).toList()));
                var next=new JsonObject();next.addProperty("updatedAt","first");next.addProperty("nmID",20);if(!missing)next.addProperty("total",100);response.add("cursor",next);
                return new Gson().fromJson(response,WbProductCardsResponse.class);
            }};
            assertEquals("invalid_wb_catalog",assertThrows(IOException.class,()->new WbGtinProductReader(shop,api).list()).getMessage());
        }
    }
    @Test void aCompleteMultiPageReadRetainsEveryExactVariantAndBarcode()throws Exception{
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var api=new WbApiClient(){@Override public WbProductCardsResponse getProductCards(String token,String locale,String cursor,Long nm,int limit){
            reads.incrementAndGet();return cursor==null?page(java.util.stream.IntStream.rangeClosed(1,100).mapToObj(n->card(n,n)).toList(),"first",100L):page(List.of(card(101,101)),"second",101L);
        }};
        var rows=new WbGtinProductReader(shop,api).list();assertEquals(101,rows.size());assertEquals(2,reads.get());
        assertEquals("101",rows.getLast().key().productId());assertEquals("101",rows.getLast().key().variantId());assertEquals(List.of("seller-101"),rows.getLast().barcodes());
    }
    private static JsonObject card(long product,long variant){return JsonParser.parseString("{\"nmID\":"+product+",\"vendorCode\":\"Fixture\",\"sizes\":[{\"chrtID\":"+variant+",\"techSize\":\"XL\",\"skus\":[\"seller-"+variant+"\"]}]}").getAsJsonObject();}
    private static WbProductCardsResponse page(List<JsonObject> cards,String cursor,long nm){
        var json=new JsonObject();json.add("cards",new Gson().toJsonTree(cards));var next=new JsonObject();next.addProperty("updatedAt",cursor);next.addProperty("nmID",nm);next.addProperty("total",cards.size());json.add("cursor",next);return new Gson().fromJson(json,WbProductCardsResponse.class);
    }
}
