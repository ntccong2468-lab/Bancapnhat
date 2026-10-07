package com.vncode.app.integration.ozon;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class OzonGtinProductReader {
    private final Shop shop;private final OzonApiClient api;
    public OzonGtinProductReader(Shop shop,OzonApiClient api){this.shop=MarketplaceGuard.requireOzon(shop);this.api=api;}
    public List<ProductSnapshot> list() throws IOException {
        var result=new ArrayList<ProductSnapshot>();String cursor="";
        for(int page=0;page<20_000;page++) {
            var products=OzonJson.parseProductPage(api.listProducts(cursor,500));
            if(products.items().isEmpty())return List.copyOf(result);
            var ids=products.items().stream().map(OzonJson.ProductReference::productId).toList();
            var detailed=OzonJson.parseProductInfo(api.productInfo(ids));
            for(var p:detailed)result.add(fromProduct(new ProductKey(shop.getId(),Marketplace.OZON,p.productId(),p.offerId()),p));
            if(detailed.size()!=ids.size())throw new IOException("incomplete_ozon_catalog");
            if(products.items().size()<500)return List.copyOf(result);
            if(products.lastId().isBlank() || products.lastId().equals(cursor))throw new IOException("invalid_ozon_cursor");
            cursor=products.lastId();
        }
        throw new IOException("ozon_page_limit");
    }
    public ProductSnapshot read(ProductKey key) throws IOException {
        if(key.shopId()!=shop.getId() || key.marketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");
        var products=OzonJson.parseProductInfo(api.productInfo(List.of(key.productId())));
        var p=products.stream().filter(product->product.productId().equals(key.productId())).findFirst().orElseThrow(()->new IOException("product_not_found"));
        return fromProduct(key,p);
    }
    public static ProductSnapshot fromProduct(ProductKey key,OzonProductDto product) {
        if(key.marketplace()!=Marketplace.OZON || !key.productId().equals(product.productId()) || !key.variantId().equals(product.offerId()))throw new IllegalArgumentException("product_mismatch");
        return ProductFingerprints.snapshot(key,product.article(),product.color(),product.size(),product.barcodes());
    }
}
