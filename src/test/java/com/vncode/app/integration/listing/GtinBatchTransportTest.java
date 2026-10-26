package com.vncode.app.integration.listing;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GtinBatchTransportTest {
    @TempDir Path root;
    Shop shop;
    final ProductKey key=new ProductKey(1,Marketplace.OZON,"1","offer-1");
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','fixture-client','fixture-token')");
        }
        shop=new Shop(1,"Fixture",Marketplace.OZON,"fixture-client","fixture-token");
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void archivedProductStillOwnsItsBarcodeAndIsIncludedInTheConflictIndex()throws Exception {
        var visibility=new ArrayList<String>();
        ListingTransport api=(path,body,mutation)->{
            assertFalse(mutation);
            if(path.equals("v3/product/list")){
                String filter=body.getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString();visibility.add(filter);
                return page(List.of(filter.equals("ALL")?"1":"2"),1,"");
            }
            return details(path,body);
        };
        try(var batch=new GtinBatchTransport(shop,api)){
            batch.prepare(List.of(key));assertEquals(List.of("ALL","ARCHIVED"),visibility);
            assertTrue(batch.conflicts(key,"00000000000017"));assertEquals(2,batch.ownedProducts());
        }
    }
    @Test void declaredRemainingRowsContinueAfterAShortNonemptyPage()throws Exception {
        var pages=new AtomicInteger();
        ListingTransport api=(path,body,mutation)->{
            assertFalse(mutation);
            if(path.equals("v3/product/list")){
                if("ARCHIVED".equals(body.getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString()))return page(List.of(),0,"");
                return pages.getAndIncrement()==0?page(List.of("1"),2,"next"):page(List.of("2"),2,"");
            }
            return details(path,body);
        };
        try(var batch=new GtinBatchTransport(shop,api)){batch.prepare(List.of(key));assertEquals(2,pages.get());assertTrue(batch.conflicts(key,"00000000000017"));}
    }
    @Test void missingTotalCannotProveAnEmptyOwnedCatalog() {
        ListingTransport api=(path,body,mutation)->JsonParser.parseString("{\"result\":{\"items\":[],\"last_id\":\"\"}}");
        try(var batch=new GtinBatchTransport(shop,api)){assertThrows(IOException.class,()->batch.prepare(List.of(key)));assertThrows(IllegalStateException.class,()->batch.conflicts(key,"00000000000017"));}
    }
    @Test void earlyEmptyPageAndDuplicateProductAcrossPagesCannotAuthorizeALaterWrite() {
        for(boolean duplicate:List.of(false,true)){
            var pages=new AtomicInteger();var writes=new AtomicInteger();
            ListingTransport api=(path,body,mutation)->{
                if(mutation){writes.incrementAndGet();return new JsonObject();}
                if(path.equals("v3/product/list"))return pages.getAndIncrement()==0?page(List.of("1"),2,"next"):page(duplicate?List.of("1"):List.of(),2,"next");
                return details(path,body);
            };
            try(var batch=new GtinBatchTransport(shop,api)){
                assertThrows(IOException.class,()->batch.prepare(List.of(key)));
                assertThrows(IllegalStateException.class,()->batch.call("v1/barcode/add",new JsonObject(),true));assertEquals(0,writes.get());
            }
        }
    }
    @Test void contradictoryOrFractionalTotalsDoNotBecomeACatalogSnapshot() {
        for(String totals:List.of("\"total_items\":1,\"total\":2","\"total_items\":0.5","\"total_items\":-1")){
            ListingTransport api=(path,body,mutation)->JsonParser.parseString("{\"result\":{\"items\":[],"+totals+",\"last_id\":\"\"}}");
            try(var batch=new GtinBatchTransport(shop,api)){assertThrows(IOException.class,()->batch.prepare(List.of(key)));}
        }
    }
    @Test void cardPreparationAndRefreshRejectForeignScopeBeforeHttp() {
        var calls=new AtomicInteger();
        ListingTransport api=(path,body,mutation)->{calls.incrementAndGet();throw new AssertionError("Foreign card must not reach the API");};
        try(var batch=new GtinBatchTransport(shop,api)){
            for(var foreign:List.of(new com.vncode.app.features.products.ProductKey(2,Marketplace.OZON,"1",""),new com.vncode.app.features.products.ProductKey(1,Marketplace.WILDBERRIES,"1",""))){
                assertThrows(SecurityException.class,()->batch.prepareProducts(List.of(foreign)));
                assertThrows(SecurityException.class,()->batch.refreshProducts(List.of(foreign)));
            }
        }
        assertEquals(0,calls.get());
    }
    @Test void cardRefreshRetainsRealOfferIdentityAndReadsFreshBarcodes()throws Exception {
        var card=new com.vncode.app.features.products.ProductKey(1,Marketplace.OZON,"1","");
        var currentCode=new java.util.concurrent.atomic.AtomicReference<>("legacy-1");
        ListingTransport api=(path,body,mutation)->{
            assertFalse(mutation);
            if(path.equals("v3/product/list"))return page(List.of("ALL".equals(body.getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString())?"1":"2"),1,"");
            var result=details(path,body);
            if(path.equals("v3/product/info/list"))for(var row:result.getAsJsonArray("items"))if(row.getAsJsonObject().get("id").getAsInt()==1){
                var codes=new JsonArray();codes.add(currentCode.get());row.getAsJsonObject().add("barcodes",codes);
            }
            return result;
        };
        var batch=new GtinBatchTransport(shop,api);
        try(batch){
            batch.prepareProducts(List.of(card));
            assertFalse(batch.conflicts(key,"legacy-1"));assertTrue(batch.conflicts(key,"00000000000017"));
            currentCode.set("00000000000024");batch.refreshProducts(List.of(card));
            var result=batch.call("v3/product/info/list",JsonParser.parseString("{\"product_id\":[\"1\"]}"),false).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();
            assertEquals("offer-1",result.get("offer_id").getAsString());assertEquals("00000000000024",result.getAsJsonArray("barcodes").get(0).getAsString());
            assertFalse(batch.conflicts(key,"00000000000024"));assertTrue(batch.conflicts(new ProductKey(1,Marketplace.OZON,"3","offer-3"),"00000000000024"));
        }
        assertThrows(IllegalStateException.class,()->batch.prepareProducts(List.of(card)));
        assertThrows(IllegalStateException.class,()->batch.refreshProducts(List.of(card)));
    }
    private static JsonObject page(List<String> ids,long total,String cursor){
        var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();for(String id:ids){var row=new JsonObject();row.addProperty("product_id",Long.parseLong(id));rows.add(row);}
        data.add("items",rows);data.addProperty("total_items",total);data.addProperty("last_id",cursor);result.add("result",data);return result;
    }
    private static JsonObject details(String path,JsonElement body){
        boolean attributes=path.equals("v4/product/info/attributes");assertTrue(attributes||path.equals("v3/product/info/list"));
        var request=attributes?body.getAsJsonObject().getAsJsonObject("filter"):body.getAsJsonObject();var rows=new JsonArray();
        for(var id:request.getAsJsonArray("product_id")){
            var product=new JsonObject();product.addProperty("id",Long.parseLong(id.getAsString()));product.addProperty("offer_id","offer-"+id.getAsString());
            product.addProperty("is_archived",id.getAsString().equals("2"));var codes=new JsonArray();codes.add(id.getAsString().equals("2")?"00000000000017":"legacy-1");product.add("barcodes",codes);rows.add(product);
        }
        var result=new JsonObject();result.add(attributes?"result":"items",rows);return result;
    }
}
