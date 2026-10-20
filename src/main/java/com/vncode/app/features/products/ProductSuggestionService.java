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
        return suggest(shop,keys,field,query,values->{});
    }
    /** A proposal from this shop's saved reads, never an arbitrary dictionary entry. */
    public String defaultValue(Shop shop,List<ProductKey> keys,String field){
        ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);
        var counts=new LinkedHashMap<String,Integer>();var cards=new HashSet<String>();
        for(var key:keys){
            ListingIdentity.require(shop,key);if(!cards.add(key.cardId()))continue;
            var snapshot=snapshots.apply(key).filter(p->p.revision().startsWith("live:")&&owner.equals(p.metadata().get("owner"))).orElse(null);
            if(snapshot==null)continue;ListingIdentity.require(shop,snapshot.key());if(!snapshot.key().cardId().equals(key.cardId()))throw new SecurityException("Own suggestion card required");
            String candidate=field.equals("brand")?Objects.toString(snapshot.brand(),"").strip():String.join(" | ",ProductFieldNames.sourceAttributes(field,snapshot.attributes()).stream().flatMap(a->a.values().stream()).map(String::strip).filter(v->!v.isBlank()).toList());
            if(!candidate.isBlank())counts.merge(candidate,1,Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseGet(()->field.equals("brand")&&shop.getMarketplace()==Marketplace.OZON?new OzonShopBrand().load(shop):"");
    }
    public Suggestions suggest(Shop shop,List<ProductKey> keys,String field,String query,java.util.function.Consumer<Suggestions> partial)throws IOException {
        ListingIdentity.require(shop);if(keys.isEmpty())return new Suggestions(List.of(),"",false);
        com.vncode.app.features.license.LicenseRuntime.require(com.vncode.app.features.license.LicenseModels.Permission.SELLER_API);
        for(var key:keys)ListingIdentity.require(shop,key);
        if(!Set.of("brand","material","gender","tnved").contains(field))return new Suggestions(List.of(),"",false);
        String owner=ListingIdentity.fingerprint(shop);var key=keys.getFirst();
        var own=shop.getMarketplace()==Marketplace.WILDBERRIES&&field.equals("brand")?knownBrands(shop):List.<String>of();
        if(!own.isEmpty())partial.accept(new Suggestions(filter(own,query),"",false));
        var card=snapshots.apply(key).filter(p->p.revision().startsWith("live:")&&owner.equals(p.metadata().get("owner"))).orElse(null);
        if(card==null)card=reader.read(shop,key);
        ListingIdentity.require(shop,card.key());
        if(!card.key().cardId().equals(key.cardId())||!card.revision().startsWith("live:")||!owner.equals(card.metadata().get("owner")))throw new SecurityException("Own suggestion card required");
        String category=card.categoryId(),search=query.strip().toLowerCase(Locale.ROOT);
        var values=new LinkedHashSet<String>(own);boolean marketplace=true;
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
                remote=List.copyOf(adapters.apply(shop).suggestValues(shop,category,field,query,page->{
                    var combined=new LinkedHashSet<String>(own);combined.addAll(page);partial.accept(new Suggestions(filter(combined,query),category,true));
                }));
                if(reusable)synchronized(cache){cache.put(ck,new Cached(Instant.now().plusSeconds(900),remote));while(cache.size()>32)cache.remove(cache.keySet().iterator().next());}
            }
            values.addAll(remote);
        }
        return new Suggestions(filter(values,query),category,marketplace);
    }
    private static List<String> filter(Collection<String> values,String query) {
        String search=query.strip().toLowerCase(Locale.ROOT);
        return values.stream().filter(v->v!=null&&!v.isBlank()&&v.toLowerCase(Locale.ROOT).contains(search)).toList();
    }
    private static List<String> knownBrands(Shop shop)throws IOException {
        try(var c=com.vncode.app.config.Database.getConnection()) {
            if(!ListingIdentity.fingerprint(shop).equals(com.vncode.app.features.kiz.KizWarehouseRepository.currentOwner(c,shop.getId())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
            try(var p=c.prepareStatement("SELECT DISTINCT brand FROM wb_product_cards WHERE shop_id=? AND trim(COALESCE(brand,''))<>'' ORDER BY brand")) {
                p.setInt(1,shop.getId());var result=new ArrayList<String>();try(var rows=p.executeQuery()){while(rows.next())result.add(rows.getString(1));}return List.copyOf(result);
            }
        }catch(java.sql.SQLException error){throw new IOException("Own brand suggestions unavailable",error);}
    }
}
