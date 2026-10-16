package com.vncode.app.features.products;

import com.vncode.app.features.listingcopy.ListingTargetAdapter;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Suggestions are scoped to an own card/category, never approval to change other cards. */
public final class ProductSuggestionService {
    public record Suggestions(List<String> values,String category,boolean marketplaceValues) {
        public Suggestions {values=List.copyOf(values);}
    }
    private record CacheKey(String owner,String category,String field) {}
    private record Cached(Instant expires,List<String> values) {}
    private final Map<CacheKey,Cached> cache=new LinkedHashMap<>();
    private final ProductSnapshotReader reader;
    private final Function<Shop,ListingTargetAdapter> adapters;
    private final Function<ProductKey,Optional<ProductSnapshot>> snapshots;
    public ProductSuggestionService(){
        this((s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(s,k):new WbProductSnapshotReader().read(s,k),
                s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter(),new ProductRepository()::localSnapshot);
    }
    public ProductSuggestionService(ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> adapters,
                                    Function<ProductKey,Optional<ProductSnapshot>> snapshots){
        this.reader=reader;this.adapters=adapters;this.snapshots=snapshots;
    }
    public Suggestions suggest(Shop shop,List<ProductKey> keys,String field,String query)throws IOException {
        ListingIdentity.require(shop);if(keys.isEmpty())return new Suggestions(List.of(),"",false);
        com.vncode.app.features.license.LicenseRuntime.require(com.vncode.app.features.license.LicenseModels.Permission.SELLER_API);
        for(var key:keys)ListingIdentity.require(shop,key);
        if(!Set.of("brand","material","gender","tnved").contains(field))return new Suggestions(List.of(),"",false);
        String owner=ListingIdentity.fingerprint(shop);var key=keys.getFirst();
        var card=snapshots.apply(key).filter(p->p.revision().startsWith("live:")&&owner.equals(p.metadata().get("owner"))).orElse(null);
        if(card==null)card=reader.read(shop,key);
        ListingIdentity.require(shop,card.key());
        if(!card.key().cardId().equals(key.cardId())||!card.revision().startsWith("live:")||!owner.equals(card.metadata().get("owner")))throw new SecurityException("Own suggestion card required");
        String category=card.categoryId(),search=query.strip().toLowerCase(Locale.ROOT);
        var values=new LinkedHashSet<String>();boolean marketplace=true;
        if(shop.getMarketplace()==Marketplace.WILDBERRIES&&field.equals("material")){
            marketplace=false;
            card.attributes().stream().filter(a->ProductFieldNames.matches(field,a.name())).flatMap(a->a.values().stream()).forEach(values::add);
            values.addAll(List.of("хлопок","полиэстер","эластан","вискоза","лен","шерсть","нейлон","полиамид","акрил","шелк"));
        }else{
            boolean reusable=shop.getMarketplace()==Marketplace.WILDBERRIES&&!field.equals("tnved");
            var ck=new CacheKey(owner,category,field);Cached existing;
            synchronized(cache){existing=cache.get(ck);}
            List<String> remote;
            if(reusable&&existing!=null&&existing.expires().isAfter(Instant.now()))remote=existing.values();
            else{
                remote=List.copyOf(adapters.apply(shop).suggestValues(shop,category,field,query));
                if(reusable)synchronized(cache){cache.put(ck,new Cached(Instant.now().plusSeconds(900),remote));while(cache.size()>32)cache.remove(cache.keySet().iterator().next());}
            }
            values.addAll(remote);
        }
        return new Suggestions(values.stream().filter(v->v!=null&&!v.isBlank()&&v.toLowerCase(Locale.ROOT).contains(search)).limit(100).toList(),category,marketplace);
    }
}
