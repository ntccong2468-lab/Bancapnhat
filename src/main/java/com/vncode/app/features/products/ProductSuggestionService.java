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
    public record ProposalRow(ProductKey key,String field,String before,String proposed,String source,String reason,
                              boolean automatic,String revision,Instant checkedAt){}
    public record DefaultProposal(String value,List<ProposalRow> rows,Map<ProductKey,String> expectedRevisions){
        public DefaultProposal {rows=List.copyOf(rows);expectedRevisions=Map.copyOf(expectedRevisions);}
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
        return defaultProposal(shop,keys,field).value();
    }
    /** Missing or conflicting cards never inherit a majority value from other selected products. */
    public DefaultProposal defaultProposal(Shop shop,List<ProductKey> keys,String field){
        ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);
        if(!Set.of("brand","material","gender","tnved").contains(field))return new DefaultProposal("",List.of(),Map.of());
        var frozen=List.copyOf(keys);var cards=new HashMap<String,Optional<ProductSnapshot>>();var rows=new ArrayList<ProposalRow>();
        String configuredBrand=field.equals("brand")&&shop.getMarketplace()==Marketplace.OZON?new OzonShopBrand().load(shop):"";
        for(var key:frozen){
            ListingIdentity.require(shop,key);
            var snapshot=cards.computeIfAbsent(key.cardId(),ignored->snapshots.apply(key)
                    .filter(p->p.revision().startsWith("live:")&&owner.equals(p.metadata().get("owner")))).orElse(null);
            if(snapshot==null){rows.add(new ProposalRow(key,field,"","","","unverified",false,"",null));continue;}
            ListingIdentity.require(shop,snapshot.key());if(!snapshot.key().cardId().equals(key.cardId()))throw new SecurityException("Own suggestion card required");
            var attributes=ProductFieldNames.sourceAttributes(field,snapshot.attributes());
            String before=field.equals("brand")?Objects.toString(snapshot.brand(),"").strip():String.join(" | ",attributes.stream()
                    .flatMap(a->a.values().stream()).map(String::strip).filter(v->!v.isBlank()).toList());
            String source="marketplace",reason="current",candidate=before;
            boolean ambiguous=attributes.size()>1||Set.of("gender","tnved").contains(field)&&attributes.stream().flatMap(a->a.values().stream()).filter(v->!v.isBlank()).distinct().count()>1;
            if(ambiguous){candidate="";reason="conflicting_attributes";}
            else if(candidate.isBlank()){
                if(field.equals("brand")&&!configuredBrand.isBlank()){candidate=configuredBrand;source="shop_brand";reason="shop_brand";}
                else reason="missing";
            }
            if(field.equals("tnved")&&!candidate.isBlank()){
                candidate=ProductFieldNames.tnvedCode(candidate);if(!candidate.matches("[0-9]{10}")){candidate="";reason="missing";}
            }
            rows.add(new ProposalRow(key,field,before,candidate,source,reason,!candidate.isBlank(),snapshot.revision(),snapshot.checkedAt()));
        }
        var unique=rows.stream().map(ProposalRow::proposed).filter(v->!v.isBlank()).distinct().toList();
        boolean ready=!rows.isEmpty()&&rows.stream().allMatch(ProposalRow::automatic)&&unique.size()==1;
        var revisions=new LinkedHashMap<ProductKey,String>();if(ready)rows.forEach(row->revisions.put(row.key(),proposalRevision(shop,cards.get(row.key().cardId()).orElseThrow(),field)));
        if(!ready&&unique.size()>1)rows.replaceAll(row->new ProposalRow(row.key(),row.field(),row.before(),row.proposed(),row.source(),
                row.automatic()?"conflicting_selection":row.reason(),false,row.revision(),row.checkedAt()));
        return new DefaultProposal(ready?unique.getFirst():"",rows,revisions);
    }
    /** Stable proposal facts across full/attribute API reads; final request revisions remain separate. */
    public static String proposalRevision(Shop shop,ProductSnapshot snapshot,String field){
        ListingIdentity.require(shop,snapshot.key());var facts=new TreeMap<String,Object>();
        facts.put("owner",ListingIdentity.fingerprint(shop));facts.put("marketplace",snapshot.key().marketplace().name());facts.put("card",snapshot.key().cardId());
        facts.put("category",snapshot.categoryId());facts.put("field",field);facts.put("article",snapshot.vendorArticle());
        if(shop.getMarketplace()==Marketplace.OZON)facts.put("offer",snapshot.key().variantId());
        facts.put("variants",snapshot.variants().stream().sorted(Comparator.comparing(v->Objects.toString(v.id(),""))).map(v->List.of(
                Objects.toString(v.id(),""),Objects.toString(v.sellerArticle(),""),Objects.toString(v.color(),""),Objects.toString(v.size(),""),Objects.toString(v.russianSize(),""))).toList());
        if(field.equals("brand")){
            String brand=Objects.toString(snapshot.brand(),"").strip();facts.put("before",brand);
            if(brand.isBlank()&&shop.getMarketplace()==Marketplace.OZON)facts.put("shop_brand",new OzonShopBrand().load(shop));
        }else{
            var attributes=(field.equals("tnved")?snapshot.attributes():ProductFieldNames.sourceAttributes(field,snapshot.attributes())).stream()
                    .sorted(Comparator.comparing(ProductAttribute::mappingKey).thenComparingInt(ProductAttribute::groupIndex)).toList();
            facts.put("attributes",attributes);
            if(field.equals("tnved")){facts.put("title",snapshot.title());facts.put("category_name",snapshot.metadata().getOrDefault("source.category.name",""));}
        }
        return "live:proposal:"+ListingIdentity.hash(ListingIdentity.canonical(ProductJson.GSON.toJsonTree(facts)));
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
