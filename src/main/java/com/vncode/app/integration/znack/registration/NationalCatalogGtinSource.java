package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Complete, read-only catalog refresh. Unknown publication/packaging metadata stays ineligible. */
public final class NationalCatalogGtinSource implements RegisteredGtinSource {
    @FunctionalInterface public interface PageReader {JsonElement read(int page) throws Exception;}
    @FunctionalInterface public interface CardReader {JsonElement read(String gtin) throws Exception;}
    private final int shopId;
    private final PageReader pages;
    private final CardReader cards;
    public NationalCatalogGtinSource(int shopId,PageReader pages,CardReader cards){this.shopId=shopId;this.pages=pages;this.cards=cards;}
    public static RegisteredGtinSource forShop(Shop shop) {
        int id=shop.getId();String name=shop.getName();
        return requested -> {
            if(requested!=id) throw new IllegalArgumentException("shop_mismatch");
            var settings=new ZnackRepository(new ZnackModels.ShopContext(id,name)).getSettings();
            var api=new ZnackApiClient();
            var signer=new com.vncode.app.integration.znack.signature.CryptoProSignatureProvider(settings.cryptcpPath(),settings.signerCertificate(),java.time.Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
            var token=new ZnackAuthService(api,signer).trueApiToken(settings);
            return new NationalCatalogGtinSource(id,p->api.products(settings.resolvedTrueApiBaseUrl(),token,p,10_000),g->api.productCards(settings.resolvedTrueApiBaseUrl(),token,g)).read(id);
        };
    }
    @Override public List<RegisteredGtin> read(int requestedShop) throws Exception {
        if(requestedShop!=shopId) throw new IllegalArgumentException("shop_mismatch");
        var gtins=new LinkedHashSet<String>();int received=0;Integer total=null;
        boolean complete=false;
        for(int page=0;page<100;page++) {
            JsonElement response=pages.read(page);JsonArray rows;
            if(response!=null && response.isJsonArray()) rows=response.getAsJsonArray();
            else if(response!=null && response.isJsonObject() && response.getAsJsonObject().has("results") && response.getAsJsonObject().get("results").isJsonArray()) {
                var object=response.getAsJsonObject();rows=object.getAsJsonArray("results");
                if(object.has("total")) total=object.get("total").getAsInt();
            } else throw new IOException("invalid_catalog_page");
            for(var e:rows) {
                if(!e.isJsonObject())throw new IOException("invalid_catalog_product");
                String gtin=text(e.getAsJsonObject(),"gtin","productGtin");
                if(!RegisteredGtinValidator.isValid(gtin))throw new IOException("invalid_catalog_gtin");
                if(!gtins.add(gtin))throw new IOException("repeated_catalog_product");
            }
            received+=rows.size();
            if(total!=null && received>=total){complete=true;break;}
            if(rows.isEmpty() && total!=null && received<total)throw new IOException("incomplete_catalog");
            if(total==null && rows.size()<10_000){complete=true;break;}
        }
        if(!complete)throw new IOException("catalog_page_limit");
        var result=new ArrayList<RegisteredGtin>();var now=Instant.now();
        for(String gtin:gtins) {
            var response=cards.read(gtin);
            if(response==null || !response.isJsonObject() || !response.getAsJsonObject().has("result") || !response.getAsJsonObject().get("result").isJsonArray())throw new IOException("invalid_catalog_card");
            JsonObject selected=null;boolean unit=false;
            for(var e:response.getAsJsonObject().getAsJsonArray("result")) {
                if(!e.isJsonObject())continue;var card=e.getAsJsonObject();boolean belongs=gtin.equals(text(card,"gtin","productGtin"));boolean thisUnit=false;
                if(card.has("identified_by") && card.get("identified_by").isJsonArray())for(var id:card.getAsJsonArray("identified_by")) {
                    if(!id.isJsonObject())continue;var identifier=id.getAsJsonObject();
                    if("gtin".equalsIgnoreCase(text(identifier,"type")) && gtin.equals(text(identifier,"value","gtin"))) {
                        belongs=true;thisUnit="trade-unit".equalsIgnoreCase(text(identifier,"level"));
                    }
                }
                if(belongs){if(selected!=null)throw new IOException("ambiguous_catalog_card");selected=card;unit=thisUnit;}
            }
            if(selected==null)throw new IOException("missing_catalog_card");
            String article=attribute(selected,"article","артикул","артикул товара");
            String color=attribute(selected,"color","цвет");String size=attribute(selected,"size","размер");
            boolean published="published".equalsIgnoreCase(text(selected,"good_status","goodStatus","cardStatus"));
            result.add(new RegisteredGtin(gtin,article,color,size,unit,published,"National Catalog",now));
        }
        return List.copyOf(result);
    }
    private static String attribute(JsonObject card,String...names) {
        String direct=text(card,names);if(!direct.isBlank())return direct;
        if(card.has("good_attrs") && card.get("good_attrs").isJsonArray()) for(var e:card.getAsJsonArray("good_attrs")) {
            if(!e.isJsonObject())continue;var a=e.getAsJsonObject();String name=text(a,"attr_name","name");
            if(Arrays.stream(names).anyMatch(n->n.equalsIgnoreCase(name)))return text(a,"attr_value","value");
        }
        return "";
    }
    private static String text(JsonObject o,String...keys) {
        for(String key:keys)if(o.has(key) && o.get(key).isJsonPrimitive())return o.get(key).getAsString();return "";
    }
}
