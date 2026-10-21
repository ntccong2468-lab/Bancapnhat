package com.vncode.app.integration.wb;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class WbGtinProductReader {
    private final Shop shop;
    private final WbApiClient api;
    public WbGtinProductReader(Shop shop,WbApiClient api){MarketplaceGuard.requireWildberries(shop);this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.api=Objects.requireNonNull(api);}
    public List<ProductSnapshot> list() throws IOException {
        ListingIdentity.require(shop);var result=new ArrayList<ProductSnapshot>();String updated=null;Long nm=null;
        var cards=new HashSet<Long>();var variants=new HashSet<String>();var cursors=new HashSet<String>();
        for(int page=0;page<20_000;page++) {
            ListingIdentity.require(shop);var response=api.getProductCards(shop.getApiKey(),"ru",updated,nm,100);ListingIdentity.require(shop);
            if(response==null || response.getCards()==null)throw new IOException("invalid_wb_catalog");
            var cursor=response.getCursor();
            if(cursor==null||cursor.getTotal()==null||cursor.getTotal()<0||cursor.getTotal()>100||cursor.getTotal()!=response.getCards().size())throw new IOException("invalid_wb_catalog");
            for(var card:response.getCards()){
                if(card==null||card.getNmID()==null||card.getNmID()<=0||!cards.add(card.getNmID())||card.getSizes()==null||card.getSizes().isEmpty())throw new IOException("invalid_wb_catalog");
                for(var size:card.getSizes()){
                    if(size==null||size.getChrtID()==null||size.getChrtID()<=0||!variants.add(card.getNmID()+":"+size.getChrtID()))throw new IOException("invalid_wb_variant");
                    try{result.add(fromCard(new ProductKey(shop.getId(),Marketplace.WILDBERRIES,String.valueOf(card.getNmID()),String.valueOf(size.getChrtID())),card));}
                    catch(IllegalArgumentException invalid){throw new IOException("invalid_wb_variant",invalid);}
                }
            }
            if(response.getCards().size()<100)return List.copyOf(result);
            if(cursor.getUpdatedAt()==null || cursor.getUpdatedAt().isBlank() || cursor.getNmID()==null || cursor.getNmID()<=0 || !cursors.add(cursor.getUpdatedAt()+":"+cursor.getNmID()))throw new IOException("invalid_wb_cursor");
            updated=cursor.getUpdatedAt();nm=cursor.getNmID();
        }
        throw new IOException("wb_page_limit");
    }
    public ProductSnapshot read(ProductKey key) throws IOException {
        if(key.shopId()!=shop.getId() || key.marketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("shop_mismatch");
        return list().stream().filter(p->p.key().equals(key)).findFirst().orElseThrow(()->new IOException("product_not_found"));
    }
    public static ProductSnapshot fromCard(ProductKey key,WbProductCard card) {
        if(card==null||card.getNmID()==null||card.getNmID()<=0||key.marketplace()!=Marketplace.WILDBERRIES || !key.productId().equals(String.valueOf(card.getNmID())))throw new IllegalArgumentException("product_mismatch");
        if(card.getSizes()==null)throw new IllegalArgumentException("variant_not_found");
        var matching=card.getSizes().stream().filter(s->s!=null&&key.variantId().equals(String.valueOf(s.getChrtID()))).toList();
        if(matching.isEmpty())throw new IllegalArgumentException("variant_not_found");
        if(matching.size()!=1)throw new IllegalArgumentException("ambiguous_wb_variant");var size=matching.getFirst();
        if(size.getSkus()==null||size.getSkus().stream().anyMatch(code->code==null||code.isBlank()))throw new IllegalArgumentException("invalid_wb_barcode");
        var colors=new LinkedHashSet<String>();
        if(card.getCharacteristics()!=null)for(var c:card.getCharacteristics()){
            if(c==null)throw new IllegalArgumentException("invalid_wb_characteristic");
            if(!com.vncode.app.features.products.ProductFieldNames.matches("color",c.getName()))continue;
            if(c.getValue() instanceof List<?> values){for(var value:values){if(!(value instanceof String text))throw new IllegalArgumentException("invalid_wb_characteristic");if(!text.isBlank())colors.add(text.strip());}}
            else if(c.getValue() instanceof String value&&!value.isBlank())colors.add(value.strip());
        }
        String color=colors.size()==1?colors.iterator().next():"";
        return ProductFingerprints.snapshot(key,card.getVendorCode(),color,size.getTechSize(),size.getSkus());
    }
}
