package com.vncode.app.features.kiz;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** Synthetic accepted registration, deliberately absent from marketplace GTINs and published Catalog. */
public final class PendingKizFixture {
    public static final String GTIN=GtinWriteTestFixture.GTIN;
    private PendingKizFixture(){}
    public static Shop create(Marketplace market)throws Exception {
        var shop=GtinWriteTestFixture.shop(market);
        sql("DELETE FROM gtin_sync_catalog");
        if(market==Marketplace.WILDBERRIES){
            sql("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,synced_at) VALUES(1,101,'Fixture','fixture')");
            sql("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M'),(1,12,101,'L')");
            sql("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
            sql("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'S-M'),(1,12,'S-L')");
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,source_barcode,gtin,feed_id,payload_json,status,created_at,updated_at) VALUES(1,11,101,'S-M',?,'accepted-feed',?,'PROCESSING','fixture','fixture')")){
                p.setString(1,GTIN);p.setString(2,payload().toString());p.executeUpdate();
            }
            new ZnackCardRegistrationRepository().bindScope(shop,11,ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings()));
        }else{
            new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto("9001","offer-M","S-M","Synthetic","","Fixture","Black","M","Fashion","",false,"",List.of()),new OzonProductDto("9002","offer-L","S-L","Synthetic","","Fixture","Black","L","Fashion","",false,"",List.of())),"");
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,feed_id,payload_json,scope_json,owner,created_at,updated_at) VALUES(2,'9001','offer-M','S-M','PROCESSING',?,'accepted-feed',?,?,?,'fixture','fixture')")){
                p.setString(1,GTIN);p.setString(2,payload().toString());p.setString(3,new Gson().toJson(ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings())));p.setString(4,ListingIdentity.fingerprint(shop));p.executeUpdate();
            }
        }
        return shop;
    }
    public static JsonObject payload(){
        var body=new JsonObject();body.addProperty("gtin",GTIN);body.addProperty("tnved","6109100000");body.addProperty("good_name","Synthetic");body.addProperty("is_kit",false);
        var attrs=new JsonArray();
        Map.of(13914,"Fixture",36,"Black",35,"M",23557,"Fixture declaration:::2025-01-01").forEach((id,value)->{var a=new JsonObject();a.addProperty("attr_id",id);a.addProperty("attr_value",value);attrs.add(a);});body.add("good_attrs",attrs);return body;
    }
    public static void sql(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.execute(sql);}}
}
