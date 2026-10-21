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
        boolean[] published={false};String catalogArticle=shop.getMarketplace()==Marketplace.OZON?variant:"Fixture";
        var source=new NationalCatalogGtinSource(shop.getId(),page->JsonParser.parseString("{\"results\":[{\"gtin\":\""+gtin+"\"}],\"total\":1}"),code->JsonParser.parseString("{\"result\":[{\"good_id\":91,\"gtin\":\""+gtin+"\",\"good_status\":\""+(published[0]?"published":"draft")+"\",\"good_attrs\":[{\"attr_id\":13914,\"attr_value\":\""+catalogArticle+"\"},{\"attr_id\":36,\"attr_value\":\"Black\"},{\"attr_id\":35,\"attr_value\":\"XL\"},{\"attr_id\":13933,\"attr_value\":\"6204420000\"}],\"identified_by\":[{\"type\":\"gtin\",\"value\":\""+gtin+"\",\"level\":\"trade-unit\"}]}]}"),RegisteredGtinProof.source(shop));
        var refresh=new RegisteredGtinSyncService(source,repository);refresh.refresh(shop.getId(),shop.getMarketplace());
        assertThrows(IllegalArgumentException.class,()->preview.create(key,Operation.ADD,"",gtin));assertEquals(0,transport.writes);
        published[0]=true;refresh.refresh(shop.getId(),shop.getMarketplace());
        var before=adapter.read(key);var request=preview.create(key,Operation.ADD,"",gtin);
        var queue=new GtinSyncCoordinator(repository,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->refresh.refresh(shop.getId(),shop.getMarketplace()));
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
            else{card.add("dimensions",JsonParser.parseString("{\"length\":1,\"width\":2,\"height\":3,\"weightBrutto\":0.1}"));card.add("characteristics",JsonParser.parseString("[{\"id\":99,\"name\":\"Цвет\",\"value\":[\"Black\"]},{\"id\":15000001,\"name\":\"ТН ВЭД\",\"value\":[\"6204420000\"]}]"));var sizes=new JsonArray();var size=new JsonObject();size.addProperty("chrtID",Long.parseLong(variant));size.addProperty("techSize","XL");var codes=new JsonArray();codes.add("legacy");size.add("skus",codes);sizes.add(size);card.add("sizes",sizes);}
        }
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){writes++;if(market==Marketplace.OZON){assertEquals("v1/barcode/add",path);assertEquals(card.get("sku"),body.getAsJsonObject().getAsJsonArray("barcodes").get(0).getAsJsonObject().get("sku"));card.getAsJsonArray("barcodes").add(gtin);return JsonParser.parseString("{\"errors\":[]}");}
                assertEquals("content/v2/cards/update",path);var updated=body.getAsJsonArray().get(0).getAsJsonObject().deepCopy();for(var next:updated.getAsJsonArray("characteristics"))for(var old:card.getAsJsonArray("characteristics"))if(next.getAsJsonObject().get("id").equals(old.getAsJsonObject().get("id"))&&old.getAsJsonObject().has("name"))next.getAsJsonObject().add("name",old.getAsJsonObject().get("name"));card=updated;return JsonParser.parseString("{\"error\":false}");}
            if(market==Marketplace.WILDBERRIES){assertEquals("content/v2/get/cards/list",path);var rows=new JsonArray();if(body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("filter").get("textSearch").getAsString().equals(product))rows.add(card.deepCopy());var result=new JsonObject();result.add("cards",rows);var cursor=new JsonObject();cursor.addProperty("total",rows.size());cursor.addProperty("updatedAt","2026-10-21T00:00:00Z");cursor.addProperty("nmID",Long.parseLong(product));result.add("cursor",cursor);return result;}
            if(path.equals("v3/product/list")){
                boolean archived="ARCHIVED".equals(body.getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString());
                var result=new JsonObject();result.add("items",archived?new JsonArray():JsonParser.parseString("[{\"product_id\":"+product+"}]"));result.addProperty("total_items",archived?0:1);result.addProperty("last_id","");var response=new JsonObject();response.add("result",result);return response;
            }
            if(path.equals("v4/product/info/attributes")){
                var actual=new JsonObject();actual.add("id",card.get("id"));actual.add("offer_id",card.get("offer_id"));actual.addProperty("name","Fixture");actual.addProperty("description_category_id",77);actual.addProperty("type_id",88);actual.addProperty("dimension_unit","mm");actual.addProperty("weight_unit","g");
                actual.add("attributes",JsonParser.parseString("[{\"id\":99,\"values\":[{\"value\":\"Black\"}]},{\"id\":100,\"values\":[{\"value\":\"XL\"}]},{\"id\":88,\"values\":[{\"value\":\"6204420000\"}]}]"));
                var rows=new JsonArray();rows.add(actual);var response=new JsonObject();response.add("result",rows);return response;
            }
            if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":99,\"name\":\"Цвет товара\"},{\"id\":100,\"name\":\"Размер производителя\"},{\"id\":88,\"name\":\"ТН ВЭД\"}]}");
            assertEquals("v3/product/info/list",path);var result=new JsonObject();var rows=new JsonArray();rows.add(card.deepCopy());result.add("items",rows);return result;
        }
    }
}
