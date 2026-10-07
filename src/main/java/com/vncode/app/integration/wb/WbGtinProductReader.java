package com.vncode.app.integration.wb;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class WbGtinProductReader {
    private final Shop shop;
    private final WbApiClient api;
    public WbGtinProductReader(Shop shop,WbApiClient api){this.shop=MarketplaceGuard.requireWildberries(shop);this.api=api;}
    public List<ProductSnapshot> list() throws IOException {
        var result=new ArrayList<ProductSnapshot>();String updated=null;Long nm=null;
        for(int page=0;page<20_000;page++) {
            var response=api.getProductCards(shop.getApiKey(),"ru",updated,nm,100);
            if(response==null || response.getCards()==null)throw new IOException("invalid_wb_catalog");
            for(var card:response.getCards())if(card.getSizes()!=null)for(var size:card.getSizes())
                result.add(fromCard(new ProductKey(shop.getId(),Marketplace.WILDBERRIES,String.valueOf(card.getNmID()),String.valueOf(size.getChrtID())),card));
            if(response.getCards().size()<100)return List.copyOf(result);
            var cursor=response.getCursor();
            if(cursor==null || cursor.getUpdatedAt()==null || cursor.getNmID()==null || Objects.equals(nm,cursor.getNmID())&&Objects.equals(updated,cursor.getUpdatedAt()))throw new IOException("invalid_wb_cursor");
            updated=cursor.getUpdatedAt();nm=cursor.getNmID();
        }
        throw new IOException("wb_page_limit");
    }
    public ProductSnapshot read(ProductKey key) throws IOException {
        if(key.shopId()!=shop.getId() || key.marketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("shop_mismatch");
        return list().stream().filter(p->p.key().equals(key)).findFirst().orElseThrow(()->new IOException("product_not_found"));
    }
    public static ProductSnapshot fromCard(ProductKey key,WbProductCard card) {
        if(key.marketplace()!=Marketplace.WILDBERRIES || !key.productId().equals(String.valueOf(card.getNmID())))throw new IllegalArgumentException("product_mismatch");
        var size=card.getSizes().stream().filter(s->key.variantId().equals(String.valueOf(s.getChrtID()))).findFirst().orElseThrow(()->new IllegalArgumentException("variant_not_found"));
        String color="";
        if(card.getCharacteristics()!=null)for(var c:card.getCharacteristics())if("Цвет".equalsIgnoreCase(c.getName())||"Color".equalsIgnoreCase(c.getName())) {
            if(c.getValue() instanceof List<?> values && values.size()==1)color=String.valueOf(values.getFirst());
            else if(c.getValue() instanceof String value)color=value;
        }
        return ProductFingerprints.snapshot(key,card.getVendorCode(),color,size.getTechSize(),size.getSkus());
    }
}
