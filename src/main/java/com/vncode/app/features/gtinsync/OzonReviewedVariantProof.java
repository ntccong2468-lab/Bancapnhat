package com.vncode.app.features.gtinsync;

import com.google.gson.*;
import com.vncode.app.config.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One seller-reviewed native Ozon/Catalog variant proof, shared by GTIN writes and purchased KIZ.
 * Cache observations never grant approval. No HTTP, allocation or paid request in this class. */
public final class OzonReviewedVariantProof {
    private OzonReviewedVariantProof(){}
    private static final String PREFIX="ov2:";
    private record Observation(ProductSnapshot product,GtinSyncModels.RegisteredGtin card,String fullRevision,String catalogReview){}
    public record Claim(String productId,String offerId,String sku,String gtin,ZnackModels.Product metadata,String issue){}
    public static boolean version2(String revision){return revision!=null&&revision.matches("ov2:[0-9a-f]{64}");}
    public static String productIdentity(ProductSnapshot p){
        var attributes=p.attributes().stream().filter(a->physical(a)).map(a->List.of(a.id(),Objects.toString(a.complexId(),""),Integer.toString(a.groupIndex()),a.values().stream().map(GtinMatchingService::normalize).sorted().toList().toString())).sorted(Comparator.comparing(Object::toString)).toList();
        var variants=p.variants().stream().map(v->List.of(v.id(),GtinMatchingService.normalize(v.color()),GtinMatchingService.normalize(v.size()),GtinMatchingService.normalize(v.russianSize()))).sorted(Comparator.comparing(Object::toString)).toList();
        return ListingIdentity.hash(new Gson().toJson(List.of(p.key().toString(),p.metadata().getOrDefault("sku",""),p.categoryId(),GtinMatchingService.normalize(p.brand()),attributes,variants)));
    }
    private static boolean physical(ProductAttribute a){
        String name=GtinMatchingService.normalize(a.name());
        return ProductFieldNames.matches("material",a.name())||ProductFieldNames.matches("gender",a.name())
                ||Set.of("БРЕНД","БРЕНД В ОДЕЖДЕ И ОБУВИ","СТРАНА-ИЗГОТОВИТЕЛЬ","СТРАНА ПРОИЗВОДСТВА","СТРАНА ПРОИСХОЖДЕНИЯ","COUNTRY","COUNTRY OF ORIGIN","МОДЕЛЬ","MODEL").contains(name);
    }
    private static String catalogIdentity(GtinSyncModels.RegisteredGtin card){
        var fields=new TreeMap<String,List<String>>();
        for(String field:List.of("brand","material","gender","country","@size_type"))fields.put(field,card.details().values(field).stream().map(GtinMatchingService::normalize).sorted().toList());
        var attributes=card.details().attributes().stream().filter(a->physical(new ProductAttribute(a.id(),a.name(),a.values(),"","0",0)))
                .map(a->List.of(a.id(),GtinMatchingService.normalize(a.name()),a.values().stream().map(GtinMatchingService::normalize).sorted().toList().toString())).sorted(Comparator.comparing(Object::toString)).toList();
        return ListingIdentity.hash(new Gson().toJson(List.of(RegisteredGtinProof.canonicalCode(card.gtin()),card.details().single("@catalog_id"),GtinMatchingService.normalize(card.color()),GtinMatchingService.normalize(card.size()),fields,attributes)));
    }
    public static String reviewRevision(GtinSyncModels.RegisteredGtin card){return ListingIdentity.hash(new Gson().toJson(card.details())+"\n"+card.article()+"\n"+card.color()+"\n"+card.size()+"\n"+Long.parseLong(card.details().single("@catalog_id")));}
    public static String revision(ProductSnapshot product,GtinSyncModels.RegisteredGtin card){return PREFIX+ListingIdentity.hash(productIdentity(product)+"\n"+catalogIdentity(card));}
    public static void observe(Shop shop,ProductSnapshot product,GtinSyncModels.RegisteredGtin card){
        requirePair(shop,product,card);String owner=ListingIdentity.fingerprint(shop);
        var stripped=new ProductSnapshot(product.key(),product.revision(),product.title(),"",product.brand(),product.vendorArticle(),product.categoryId(),List.of(),product.attributes().stream().filter(OzonReviewedVariantProof::physical).toList(),product.variants(),null,List.of(),product.checkedAt(),Map.of("owner",owner,"sku",product.metadata().getOrDefault("sku","")));
        var value=new Observation(stripped,card,RegistrationReviewedValues.productRevision(product),reviewRevision(card));
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);String key=key(shop,product.key());
            var previous=observation(c,shop,product.key());if(previous!=null&&previous.product().checkedAt().isAfter(product.checkedAt()))return null;
            try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){p.setString(1,key);p.setString(2,ProductJson.GSON.toJson(value));p.executeUpdate();}return null;
        });}catch(SQLException e){throw storage(e);}
    }
    public static String approvedRevision(Shop shop,GtinSyncModels.ProductKey key,String fullRevision,String catalogReview){
        try(var c=Database.getConnection()){
            var observed=observation(c,shop,productKey(key));
            if(observed==null||!observed.fullRevision().equals(fullRevision)||!observed.catalogReview().equals(catalogReview))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
            requirePair(shop,observed.product(),observed.card());return revision(observed.product(),observed.card());
        }catch(SQLException e){throw storage(e);}
    }
    public static boolean matches(Shop shop,ProductSnapshot fresh,GtinSyncModels.RegisteredGtin card){
        try{
            if(shop==null||fresh==null||shop.getMarketplace()!=Marketplace.OZON)return false;
            fresh=new OzonShopBrand().apply(shop,fresh);
            requirePair(shop,fresh,card);var key=new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,fresh.key().cardId(),fresh.key().variantId());
            var link=OzonReviewedGtinLinks.read(shop,key);
            return link!=null&&version2(link.revision())&&ListingIdentity.fingerprint(shop).equals(link.owner())
                    &&RegisteredGtinProof.source(shop).equals(link.source())&&link.sku().equals(fresh.metadata().get("sku"))
                    &&RegisteredGtinProof.sameCode(link.gtin(),card.gtin())&&Long.toString(link.goodId()).equals(card.details().single("@catalog_id"))
                    &&link.revision().equals(revision(fresh,card));
        }catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){return false;}
    }
    /** Claims remain visible when proof expires, so legacy broad article rules cannot select a sibling. */
    public static List<Claim> purchaseClaims(Connection c,Shop shop)throws SQLException{
        return claims(c,shop,true);
    }
    private static List<Claim> claims(Connection c,Shop shop,boolean purchase)throws SQLException{
        if(shop.getMarketplace()!=Marketplace.OZON)return List.of();requireOwner(c,shop);
        try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_reviewed_gtin_links'");var r=p.executeQuery()){if(!r.next())return List.of();}
        var claims=new ArrayList<Claim>();
        try(var p=c.prepareStatement("SELECT product_id,offer_id,sku,gtin,revision,owner,source,good_id FROM ozon_reviewed_gtin_links WHERE shop_id=?")){
            p.setInt(1,shop.getId());try(var rows=p.executeQuery()){while(rows.next()){
                if(!version2(rows.getString(5)))continue;
                String nativeId=rows.getString(1),offer=rows.getString(2),sku=rows.getString(3),gtin=rows.getString(4);ZnackModels.Product metadata=null;String issue="GTIN_REVIEW_CHANGED";
                try{
                    var pk=new ProductKey(shop.getId(),Marketplace.OZON,nativeId,offer);var observed=observation(c,shop,pk);
                    if(observed!=null&&currentBasic(c,shop,observed.product())){
                        var card=currentCard(c,shop,gtin,observed.card());
                        if(matches(shop,observed.product(),card)&&(!purchase||activePermit(card))){
                            var facts=card.details();var docs=facts.documents().stream().filter(d->activeDocument(d)).map(d->new ZnackModels.GoodsDocument(switch(d.type()){case "DECLARATION","CONFORMITY_DECLARATION"->"CONFORMITY_DECLARATION";case "CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CONFORMITY_CERTIFICATE"->"CONFORMITY_CERTIFICATE";default->"STATE_REGISTRATION_CERTIFICATE";},d.number(),d.from())).toList();
                            metadata=new ZnackModels.Product(RegisteredGtinProof.canonicalCode(gtin),facts.single("title"),facts.single("tnved"),null,null,null,null,null,null,"published","","",null,null,docs);issue="";
                        }
                    }
                }catch(IllegalArgumentException|IllegalStateException invalid){/* Keep this exact claim blocked. */}
                claims.add(new Claim(nativeId,offer,sku,gtin,metadata,issue));
            }}
        }return List.copyOf(claims);
    }
    public static boolean needsRefresh(Shop shop,GtinSyncModels.ProductKey key){
        try(var c=Database.getConnection()){var observed=observation(c,shop,productKey(key));return observed==null||!currentBasic(c,shop,observed.product())||!fresh(observed.product().checkedAt())||!RegisteredGtinProof.currentObservation(currentCard(c,shop,observed.card().gtin(),observed.card()),Instant.now());}catch(SQLException e){throw storage(e);}
    }
    public static Map<ProductKey,String> matchingIndex(Shop shop){
        if(shop.getMarketplace()!=Marketplace.OZON)return Map.of();
        try(var c=Database.getConnection()){
            var result=new LinkedHashMap<ProductKey,String>();
            for(var claim:claims(c,shop,false))result.put(new ProductKey(shop.getId(),Marketplace.OZON,claim.productId(),claim.offerId()),claim.metadata()==null?"":claim.gtin());
            return Map.copyOf(result);
        }catch(SQLException e){throw storage(e);}
    }
    private static boolean currentBasic(Connection c,Shop shop,ProductSnapshot observed)throws SQLException{
        if(!fresh(observed.checkedAt()))return false;
        try(var p=c.prepareStatement("SELECT payload FROM product_snapshots WHERE shop_id=? AND marketplace='OZON' AND card_id=? AND variant_id=?")){
            p.setInt(1,shop.getId());p.setString(2,observed.key().cardId());p.setString(3,observed.key().variantId());try(var r=p.executeQuery()){if(r.next()){
                var newer=ProductJson.GSON.fromJson(r.getString(1),ProductSnapshot.class);
                if(newer!=null&&newer.revision().startsWith("live:")&&ListingIdentity.fingerprint(shop).equals(newer.metadata().get("owner"))
                        &&newer.checkedAt().isAfter(observed.checkedAt())&&!productIdentity(new OzonShopBrand().apply(shop,newer)).equals(productIdentity(observed)))return false;
            }}
        }
        try(var p=c.prepareStatement("SELECT product_id,offer_id,sku,color,size,manufacturer_size,russian_size,updated_at,description_category_id,type_id FROM ozon_products WHERE shop_id=? AND sku=? AND archived=0")){
            p.setInt(1,shop.getId());p.setString(2,observed.metadata().get("sku"));try(var r=p.executeQuery()){
                if(!r.next()||!observed.key().cardId().equals(r.getString(1))||!observed.key().variantId().equals(r.getString(2)))return false;
                var v=observed.variants().getFirst();String size=Objects.toString(r.getString(6),"").isBlank()?r.getString(5):r.getString(6);
                if(!OzonSkuGtinMatcher.sameColor(v.color(),r.getString(4))||!same(v.size().isBlank()?v.russianSize():v.size(),size)
                        ||!Objects.toString(r.getString(7),"").isBlank()&&!same(v.russianSize(),r.getString(7)))return false;
                String changed=r.getString(8);if(changed!=null&&!changed.isBlank())try{if(Instant.parse(changed).isAfter(observed.checkedAt().plusSeconds(1)))return false;}catch(RuntimeException invalid){return false;}
                if(!Objects.toString(r.getString(9),"").isBlank()&&!observed.categoryId().equals(r.getString(9)+":"+r.getString(10)))return false;
                if(r.next())return false;
            }
        }
        // A known conflicting Ozon product must be resolved before buying for this association.
        var link=OzonReviewedGtinLinks.read(shop,new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,observed.key().cardId(),observed.key().variantId()));
        if(link==null)return false;
        try(var p=c.prepareStatement("SELECT 1 FROM ozon_product_barcodes WHERE shop_id=? AND product_id<>? AND length(barcode) IN (8,12,13,14) AND barcode NOT GLOB '*[^0-9]*' AND substr('00000000000000'||barcode,-14)=? LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,observed.key().cardId());p.setString(3,RegisteredGtinProof.canonicalCode(link.gtin()));try(var r=p.executeQuery()){return !r.next();}
        }
    }
    private static GtinSyncModels.RegisteredGtin currentCard(Connection c,Shop shop,String gtin,GtinSyncModels.RegisteredGtin fallback)throws SQLException{
        GtinSyncModels.RegisteredGtin card=fallback;int count=0;
        try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON' AND substr('00000000000000'||gtin,-14)=?")){
            p.setInt(1,shop.getId());p.setString(2,RegisteredGtinProof.canonicalCode(gtin));try(var r=p.executeQuery()){while(r.next()){
                if(++count>1)throw new IllegalStateException("GTIN_AMBIGUOUS");var data=JsonParser.parseString(r.getString(1)).getAsJsonObject();if(!ListingIdentity.fingerprint(shop).equals(data.get("owner").getAsString()))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
                var current=ProductJson.GSON.fromJson(data,GtinSyncModels.RegisteredGtin.class);RegisteredGtinProof.requireKnownDocumentState(List.of(card),List.of(current));if(!current.syncedAt().isBefore(card.syncedAt()))card=current;
            }}
        }return card;
    }
    private static boolean activePermit(GtinSyncModels.RegisteredGtin card){return card.details().values("tnved").stream().anyMatch(v->v.matches("[0-9]{10}"))&&card.details().documents().stream().anyMatch(OzonReviewedVariantProof::activeDocument);}
    private static boolean activeDocument(com.vncode.app.features.catalogsync.CatalogProductData.Document d){
        try{var now=LocalDate.now();String status=d.metadata().getOrDefault("registryStatus","");return !d.number().isBlank()&&Set.of("DECLARATION","CONFORMITY_DECLARATION","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CONFORMITY_CERTIFICATE","CERTIFICATE_OF_REGISTRATION").contains(d.type())
                &&!d.from().isBlank()&&!LocalDate.parse(d.from()).isAfter(now)&&(d.endless()||!d.until().isBlank()&&!LocalDate.parse(d.until()).isBefore(now)||d.until().isBlank()&&Set.of("active","valid").contains(status.toLowerCase(Locale.ROOT)))
                &&(status.isBlank()||Set.of("active","valid").contains(status.toLowerCase(Locale.ROOT)));}catch(RuntimeException invalid){return false;}
    }
    private static void requirePair(Shop shop,ProductSnapshot p,GtinSyncModels.RegisteredGtin card){
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||p==null||p.key().shopId()!=shop.getId()||p.key().marketplace()!=Marketplace.OZON||!p.revision().startsWith("live:")||!ListingIdentity.fingerprint(shop).equals(p.metadata().get("owner"))||!fresh(p.checkedAt()))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
        if(card==null||!scope.source().equals(card.source())||!RegisteredGtinProof.currentObservation(card,Instant.now())||p.variants().size()!=1||!p.key().variantId().equals(p.variants().getFirst().id())||!p.metadata().getOrDefault("sku","").matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("unverified_catalog_gtin");
        var v=p.variants().getFirst();if(!OzonSkuGtinMatcher.sameColor(v.color(),card.color())||!GtinVariantIdentity.sizeMatches(card,v.size().isBlank()?v.russianSize():v.size(),v.russianSize()))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
    }
    private static Observation observation(Connection c,Shop shop,ProductKey key)throws SQLException{
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){p.setString(1,key(shop,key));try(var r=p.executeQuery()){if(!r.next())return null;try{return ProductJson.GSON.fromJson(r.getString(1),Observation.class);}catch(RuntimeException invalid){throw new IllegalStateException("GTIN_REVIEW_CHANGED",invalid);}}}
    }
    private static String key(Shop shop,ProductKey key){return "ozon.reviewed.variant.v2."+shop.getId()+"."+ListingIdentity.hash(ListingIdentity.fingerprint(shop)+"\n"+key.cardId()+"\n"+key.variantId());}
    private static ProductKey productKey(GtinSyncModels.ProductKey k){return new ProductKey(k.shopId(),k.marketplace(),k.productId(),k.variantId());}
    private static void requireOwner(Connection c,Shop s)throws SQLException{if(!ListingIdentity.fingerprint(s).equals(ListingIdentity.fingerprint(c,s.getId(),Marketplace.OZON)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");}
    private static boolean fresh(Instant at){return at!=null&&!at.isBefore(Instant.now().minusSeconds(86400))&&!at.isAfter(Instant.now().plusSeconds(300));}
    private static boolean same(String a,String b){return GtinMatchingService.normalize(Objects.toString(a,"")).equals(GtinMatchingService.normalize(Objects.toString(b,"")));}
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("SYNC_STORAGE",e);}
}
