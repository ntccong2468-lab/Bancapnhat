package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonGtinAdapter;
import com.vncode.app.integration.wb.WbGtinAdapter;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory HTTP boundaries; real Catalog parser, marketplace adapters, queue and SQLite. */
final class PublishedCardGtinFixture {
    record Result(GtinMarketplaceAdapter adapter,ProductKey key){}
    static Result synchronize(Shop shop,String gtin,String product,String variant,String sku)throws Exception {
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),product,variant);
        var repository=new GtinSyncRepository();var transport=new Transport(shop.getMarketplace(),product,variant,sku,gtin);
        GtinMarketplaceAdapter adapter=shop.getMarketplace()==Marketplace.OZON?new OzonGtinAdapter(shop,transport,id->{}):new WbGtinAdapter(shop,transport);
        var preview=new GtinPreviewService(repository,k->adapter);
        boolean[] published={false};
        var source=new NationalCatalogGtinSource(shop.getId(),page->JsonParser.parseString("{\"results\":[{\"gtin\":\""+gtin+"\"}],\"total\":1}"),code->JsonParser.parseString("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_status\":\""+(published[0]?"published":"draft")+"\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\""+gtin+"\",\"level\":\"trade-unit\"}]}]}"),RegisteredGtinProof.source(shop));
        var refresh=new RegisteredGtinSyncService(source,repository);refresh.refresh(shop.getId(),shop.getMarketplace());
        assertThrows(IllegalArgumentException.class,()->preview.create(key,Operation.ADD,"",gtin));assertEquals(0,transport.writes);
        published[0]=true;refresh.refresh(shop.getId(),shop.getMarketplace());
        var before=adapter.read(key);var request=preview.create(key,Operation.ADD,"",gtin);
        var queue=new GtinSyncCoordinator(repository,k->adapter,Clock.systemUTC(),1,Duration.ZERO);
        var job=queue.confirm(List.of(request));assertEquals(0,transport.writes);
        queue.runJob(job);assertEquals(1,transport.writes);assertEquals(Status.SUCCEEDED,repository.jobItems(job).getFirst().status());
        assertTrue(RegisteredGtinProof.contains(adapter.read(key).barcodes(),gtin));assertTrue(adapter.read(key).barcodes().containsAll(before.barcodes()));
        try(var connection=com.vncode.app.config.Database.getConnection();var statement=connection.prepareStatement("SELECT gtin FROM gtin_sync_verified_mappings WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            statement.setInt(1,shop.getId());statement.setString(2,shop.getMarketplace().name());statement.setString(3,product);statement.setString(4,variant);
            try(var rows=statement.executeQuery()){assertTrue(rows.next(),"Remote readback must persist verified evidence");assertEquals(gtin,rows.getString(1));assertFalse(rows.next());}
        }
        queue.runJob(job);assertEquals(1,transport.writes);
        return new Result(adapter,key);
    }
    private static final class Transport implements ListingTransport {
        final Marketplace market;final String product,gtin;JsonObject card;int writes;
        Transport(Marketplace market,String product,String variant,String sku,String gtin){
            this.market=market;this.product=product;this.gtin=gtin;
            card=new JsonObject();card.addProperty(market==Marketplace.OZON?"id":"nmID",Long.parseLong(product));
            card.addProperty("name","Fixture");card.addProperty("title","Fixture");card.addProperty("vendorCode","Fixture");
            if(market==Marketplace.OZON){card.addProperty("offer_id",variant);card.addProperty("sku",Long.parseLong(sku));var codes=new JsonArray();codes.add("legacy");card.add("barcodes",codes);}
            else{card.add("dimensions",JsonParser.parseString("{\"length\":1,\"width\":2,\"height\":3,\"weightBrutto\":0.1}"));card.add("characteristics",new JsonArray());var sizes=new JsonArray();var size=new JsonObject();size.addProperty("chrtID",Long.parseLong(variant));size.addProperty("techSize","XL");var codes=new JsonArray();codes.add("legacy");size.add("skus",codes);sizes.add(size);card.add("sizes",sizes);}
        }
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){writes++;if(market==Marketplace.OZON){assertEquals("v1/barcode/add",path);assertEquals(card.get("sku"),body.getAsJsonObject().getAsJsonArray("barcodes").get(0).getAsJsonObject().get("sku"));card.getAsJsonArray("barcodes").add(gtin);return JsonParser.parseString("{\"errors\":[]}");}
                assertEquals("content/v2/cards/update",path);card=body.getAsJsonArray().get(0).getAsJsonObject().deepCopy();return JsonParser.parseString("{\"error\":false}");}
            if(market==Marketplace.WILDBERRIES){assertEquals("content/v2/get/cards/list",path);var rows=new JsonArray();if(body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("filter").get("textSearch").getAsString().equals(product))rows.add(card.deepCopy());var result=new JsonObject();result.add("cards",rows);return result;}
            if(path.equals("v3/product/list")){var result=new JsonObject();result.add("items",JsonParser.parseString("[{\"product_id\":"+product+"}]"));var response=new JsonObject();response.add("result",result);return response;}
            assertEquals("v3/product/info/list",path);var result=new JsonObject();var rows=new JsonArray();rows.add(card.deepCopy());result.add("items",rows);return result;
        }
    }
}
