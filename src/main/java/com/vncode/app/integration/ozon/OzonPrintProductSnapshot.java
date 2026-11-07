package com.vncode.app.integration.ozon;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** Read-only print metadata repair. Never changes catalog cursors, GTIN mappings or barcode proof. */
final class OzonPrintProductSnapshot {
    private OzonPrintProductSnapshot() {}

    static boolean missing(OzonPostingItemDto item,Map<String,OzonProductDto> products){
        try{OzonProductLabelBarcode.registeredForProduct(item,products.get(item.sku()));return false;}
        catch(IOException unavailable){return true;}
    }

    static void refreshMissing(Shop shop,Collection<OzonPostingDto> postings,Map<String,OzonProductDto> products,OzonApiClient api)throws IOException{
        var needed=new LinkedHashMap<String,List<OzonPostingItemDto>>();
        for(var posting:postings)for(var item:posting.items())if(missing(item,products)&&!item.sku().isBlank())
            needed.computeIfAbsent(item.sku(),ignored->new ArrayList<>()).add(item);
        if(needed.isEmpty())return;
        ListingIdentity.require(shop);api.requireSyncOwner(shop.getId());
        var skus=needed.keySet().stream().filter(OzonApiClient::validSku).toList();
        for(int offset=0;offset<skus.size();offset+=1000){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("OZON_PRINT_INTERRUPTED");
            var window=skus.subList(offset,Math.min(offset+1000,skus.size()));
            var response=api.productInfoBySkus(window);
            ListingIdentity.require(shop);api.requireSyncOwner(shop.getId());
            List<OzonProductDto> fetched;
            try{fetched=OzonJson.parseProductInfo(response);}
            catch(RuntimeException invalid){throw new IOException("OZON_LABEL_PRODUCT_RESPONSE_INVALID",invalid);}
            var accepted=new LinkedHashMap<String,OzonProductDto>();
            for(var product:fetched){
                if(!window.contains(product.sku())||accepted.containsKey(product.sku()))throw new IOException("OZON_LABEL_PRODUCT_RESPONSE_MISMATCH");
                for(var item:needed.get(product.sku()))OzonProductLabelBarcode.registeredForProduct(item,product);
                var previous=products.get(product.sku());
                if(previous!=null&&previous.productId().equals(product.productId())&&previous.offerId().equals(product.offerId()))
                    product=new OzonProductDto(product.productId(),product.offerId(),product.sku(),product.name().isBlank()?previous.name():product.name(),product.primaryImageUrl().isBlank()?previous.primaryImageUrl():product.primaryImageUrl(),
                            previous.article(),previous.color(),previous.size(),previous.category(),previous.gender(),product.archived(),product.updatedAt(),
                            product.barcodes(),previous.descriptionCategoryId(),previous.typeId(),previous.manufacturerSize(),previous.russianSize());
                accepted.put(product.sku(),product);
            }
            // Commit only this validated in-memory page. An error on a later page must not
            // discard already-read products or overwrite a newer durable catalog snapshot.
            products.putAll(accepted);
        }
        if(postings.stream().flatMap(posting->posting.items().stream()).anyMatch(item->needed.containsKey(item.sku())&&missing(item,products)))
            throw new IOException("OZON_LABEL_PRODUCT_RESPONSE_INCOMPLETE");
    }
}
