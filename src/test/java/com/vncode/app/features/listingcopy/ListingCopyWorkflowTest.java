package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.integration.wb.WbListingAdapter;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Actual destination adapters and durable jobs against synthetic marketplace responses. */
class ListingCopyWorkflowTest {
    @TempDir Path root;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var sql=c.createStatement()){sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Source WB','WILDBERRIES',NULL,'fixture'),(2,'Source Ozon','OZON','22','fixture'),(3,'Target WB','WILDBERRIES',NULL,'fixture'),(4,'Target Ozon','OZON','44','fixture')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void allFourDirectionsPreviewPublishReadBackAndResumeWithoutRepeatingTheWrite()throws Exception{
        for(var from:Marketplace.values())for(var to:Marketplace.values()){
            var sourceShop=new Shop(from==Marketplace.WILDBERRIES?1:2,"Source",from,from==Marketplace.OZON?"22":null,"fixture");
            var target=new Shop(to==Marketplace.WILDBERRIES?3:4,"Target",to,to==Marketplace.OZON?"44":null,"fixture");
            var fixture=ListingCopyMappingTest.source(from);String card=from+"-"+to;
            var snapshot=new ProductSnapshot(new ProductKey(sourceShop.getId(),from,card,"v1"),"live:"+card,fixture.title(),fixture.description(),fixture.brand(),fixture.vendorArticle(),fixture.categoryId(),List.of(),fixture.attributes(),fixture.variants(),fixture.dimensions(),List.of(),fixture.checkedAt(),Map.of("source.price.v1","100","source.price.v2","200","source.currency","RUB"));
            var remote=new Destination(to);ListingTargetAdapter adapter=to==Marketplace.OZON?new OzonListingAdapter(s->remote,image->{throw new java.io.IOException();}):new WbListingAdapter(s->remote,image->{throw new java.io.IOException();});
            var repo=new ListingCopyRepository();var service=new ListingCopyService(repo,(shop,key)->snapshot,shop->adapter);
            var initial=CopyDefaults.initial(snapshot,target,Set.of("v1","v2"),to==Marketplace.OZON?"99:88":"99");var fields=new HashSet<>(initial.fields());fields.remove("images");
            var options=new CopyOptions(fields,initial.variantIds(),initial.targetCategoryId(),Map.of(),Map.of(),Map.of(),null,"RUB",List.of(),false);
            var draft=service.draft(sourceShop,List.of(new ProductKey(sourceShop.getId(),from,card,"")),List.of(target),options).getFirst();
            var preview=service.preview(target,draft.id());assertTrue(preview.publishable(),from+" → "+to+": "+preview.issues());assertEquals(0,remote.writes);
            assertTrue(preview.requestJson().contains("Cotton"));assertTrue(preview.requestJson().contains("100"));assertTrue(preview.requestJson().contains("200"));
            assertEquals(CopyState.REMOTE_PROCESSING,service.publish(target,draft.id(),preview.requestHash(),true).state());assertEquals(1,remote.writes);
            remote.exposeWrongPrice=true;assertNotEquals(CopyState.RECONCILED,service.reconcile(target,draft.id()).state());
            remote.exposeWrongPrice=false;assertEquals(CopyState.RECONCILED,service.reconcile(target,draft.id()).state());
            var restarted=new ListingCopyService(repo,(shop,key)->snapshot,shop->adapter);assertEquals(CopyState.RECONCILED,restarted.publish(target,draft.id(),preview.requestHash(),true).state());assertEquals(1,remote.writes);
        }
    }
    private static final class Destination implements ListingTransport {
        final Marketplace market;JsonElement submitted;int writes;boolean exposeWrongPrice;
        Destination(Marketplace market){this.market=market;}
        public JsonElement call(String path,JsonElement body,boolean mutation)throws java.io.IOException{
            if(mutation){assertTrue(path.equals("content/v2/cards/upload")||path.equals("v3/product/import"));writes++;submitted=body.deepCopy();return JsonParser.parseString(market==Marketplace.OZON?"{\"result\":{\"task_id\":123}}":"{\"error\":false}");}
            if(submitted==null)return ListingCopyMappingTest.fixture(market==Marketplace.OZON?"99:88":"99").call(path,body,false);
            if(market==Marketplace.WILDBERRIES){
                var listing=submitted.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("variants").get(0).getAsJsonObject().deepCopy();listing.addProperty("nmID",321);
                var prices=new JsonArray();int id=55;for(var value:array(listing,"sizes")){var size=object(value);size.addProperty("chrtID",id);var price=new JsonObject();price.addProperty("sizeID",id++);price.addProperty("price",exposeWrongPrice?1:size.get("price").getAsInt());prices.add(price);size.remove("price");}
                if(path.startsWith("api/v2/list/goods/filter")){var item=new JsonObject();item.addProperty("nmID",321);item.add("sizes",prices);var goods=new JsonArray();goods.add(item);var data=new JsonObject();data.add("listGoods",goods);var response=new JsonObject();response.add("data",data);return response;}
                var cards=new JsonArray();cards.add(listing);var response=new JsonObject();response.add("cards",cards);return response;
            }
            var items=array(submitted.getAsJsonObject(),"items");var response=new JsonObject();var values=new JsonArray();
            if(path.equals("v1/product/import/info")){for(var value:items){var item=new JsonObject();item.addProperty("offer_id",text(object(value),"offer_id"));item.addProperty("status","imported");item.add("errors",new JsonArray());values.add(item);}var result=new JsonObject();result.add("items",values);response.add("result",result);return response;}
            for(int i=0;i<items.size();i++){
                var item=object(items.get(i)).deepCopy();String article=text(item,"offer_id");int id=321+i;item.addProperty("id",id);item.addProperty("sku",900+i);
                if(path.equals("v3/product/info/list")){if(!strings(array(body.getAsJsonObject(),"offer_id")).contains(article))continue;if(exposeWrongPrice)item.addProperty("price","1");values.add(item);}
                else if(path.equals("v4/product/info/attributes")){if(!strings(array(obj(body.getAsJsonObject(),"filter"),"product_id")).contains(Integer.toString(id)))continue;var barcodes=new JsonArray();barcodes.add(text(item,"barcode"));item.add("barcodes",barcodes);values.add(item);}
            }
            response.add(path.equals("v3/product/info/list")?"items":"result",values);return response;
        }
    }
}
