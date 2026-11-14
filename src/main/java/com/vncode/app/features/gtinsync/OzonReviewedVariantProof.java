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

/** Native Ozon/Catalog proofs shared by GTIN writes and purchased KIZ:
 * seller-reviewed ov2 pairs and uniquely matched model/size oe1 pairs.
 * Observations alone grant neither kind. No HTTP, allocation or paid request here. */
public final class OzonReviewedVariantProof {
    private OzonReviewedVariantProof(){}
    private static final String PREFIX="ov2:";
    private static final String EXACT_PREFIX="oe1:";
    private record Observation(ProductSnapshot product,GtinSyncModels.RegisteredGtin card,String fullRevision,String catalogReview){}
    @FunctionalInterface interface ExactCatalogLoader {ExactCatalogRead read(Connection c,Shop shop)throws SQLException;}
    /** Immutable, one-call read snapshot. It grants no link and is never shared between calls. */
    static final class ExactCatalogRead {
        private final String owner,source,readRevision;
        private final OzonExactVariantIdentity index;
        private ExactCatalogRead(String owner,String source,String readRevision,List<GtinSyncModels.RegisteredGtin> cards){
            this.owner=owner;this.source=source;this.readRevision=readRevision;this.index=new OzonExactVariantIdentity(List.copyOf(cards));
        }
        private void requireCurrent(Shop shop){
            ListingIdentity.require(shop);
            if(!owner.equals(ListingIdentity.fingerprint(shop))||!source.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
            com.vncode.app.integration.znack.CatalogReadRevision.requireUnchanged(shop.getId(),readRevision);
            com.vncode.app.integration.znack.CatalogReadRevision.requireComplete(shop.getId());
        }
        private void requireUnique(Shop shop,ProductSnapshot product,GtinSyncModels.RegisteredGtin card){
            requireCurrent(shop);var matches=new HashSet<String>();
            for(var candidate:index.candidates(product))matches.add(RegisteredGtinProof.canonicalCode(candidate.gtin())+"\n"+candidate.details().single("@catalog_id"));
            if(matches.size()!=1||!matches.contains(RegisteredGtinProof.canonicalCode(card.gtin())+"\n"+card.details().single("@catalog_id")))throw new IllegalArgumentException("GTIN_AMBIGUOUS");
        }
    }
    public record Claim(String productId,String offerId,String sku,String gtin,ZnackModels.Product metadata,String issue){}
    public static boolean version2(String revision){return revision!=null&&revision.matches("ov2:[0-9a-f]{64}");}
    /** Machine-verified full model/size identity, distinct from a seller-reviewed pair. */
    public static boolean exactProof(String revision){return revision!=null&&revision.matches("oe1:[0-9a-f]{64}");}
    public static boolean nativeProof(String revision){return version2(revision)||exactProof(revision);}
    private static String exactProductIdentity(ProductSnapshot product){
        var identity=OzonExactVariantIdentity.identity(product).orElseThrow(()->new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED"));
        return ListingIdentity.hash(new Gson().toJson(List.of(product.key().toString(),product.metadata().getOrDefault("sku",""),identity)));
    }
    public static String exactRevision(ProductSnapshot product,GtinSyncModels.RegisteredGtin card){
        var identity=OzonExactVariantIdentity.identity(card).orElseThrow(()->new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED"));
        return EXACT_PREFIX+ListingIdentity.hash(exactProductIdentity(product)+"\n"+new Gson().toJson(List.of(RegisteredGtinProof.canonicalCode(card.gtin()),card.details().single("@catalog_id"),identity)));
    }
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
    /** Record current observations only; this does not create an exact link or assert barcode writeback. */
    public static void observeExact(Shop shop,ProductSnapshot product,GtinSyncModels.RegisteredGtin card){
        requireExactPair(shop,product,card);
        var value=new Observation(product,card,RegistrationReviewedValues.productRevision(product),reviewRevision(card));
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);String key=key(shop,product.key(),true);
            var previous=observation(c,shop,product.key(),true);if(previous!=null&&previous.product().checkedAt().isAfter(product.checkedAt()))return null;
            try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){p.setString(1,key);p.setString(2,ProductJson.GSON.toJson(value));p.executeUpdate();}return null;
        });}catch(SQLException e){throw storage(e);}
    }
    public static void observeForLink(Shop shop,ProductSnapshot product,GtinSyncModels.RegisteredGtin card){
        var link=OzonReviewedGtinLinks.read(shop,new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,product.key().cardId(),product.key().variantId()));
        if(link!=null&&exactProof(link.revision()))observeExact(shop,product,card);else observe(shop,product,card);
    }
    public static String approvedRevision(Shop shop,GtinSyncModels.ProductKey key,String fullRevision,String catalogReview){
        return observedRevision(shop,key,fullRevision,catalogReview,false);
    }
    public static String exactObservedRevision(Shop shop,GtinSyncModels.ProductKey key,String fullRevision,String catalogReview){
        return observedRevision(shop,key,fullRevision,catalogReview,true);
    }
    private static String observedRevision(Shop shop,GtinSyncModels.ProductKey key,String fullRevision,String catalogReview,boolean exact){
        try(var c=Database.getConnection()){
            var observed=observation(c,shop,productKey(key),exact);
            if(observed==null||!observed.fullRevision().equals(fullRevision)||!observed.catalogReview().equals(catalogReview))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
            if(exact){requireExactPair(shop,observed.product(),observed.card());return exactRevision(observed.product(),observed.card());}
            requirePair(shop,observed.product(),observed.card());return revision(observed.product(),observed.card());
        }catch(SQLException e){throw storage(e);}
    }
    public static boolean matches(Shop shop,ProductSnapshot fresh,GtinSyncModels.RegisteredGtin card){
        return matches(shop,fresh,card,null);
    }
    private static boolean matches(Shop shop,ProductSnapshot fresh,GtinSyncModels.RegisteredGtin card,ExactCatalogRead exactCatalog){
        try{
            if(shop==null||fresh==null||shop.getMarketplace()!=Marketplace.OZON)return false;
            fresh=new OzonShopBrand().apply(shop,fresh);
            var key=new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,fresh.key().cardId(),fresh.key().variantId());
            var link=OzonReviewedGtinLinks.read(shop,key);
            if(link==null||!nativeProof(link.revision()))return false;boolean exact=exactProof(link.revision());
            if(exact)requireExactPair(shop,fresh,card,exactCatalog);else requirePair(shop,fresh,card);
            if(exact)try(var c=Database.getConnection()){if(!compatibleRegistration(c,shop,key,link))return false;}catch(SQLException failed){throw storage(failed);}
            return ListingIdentity.fingerprint(shop).equals(link.owner())
                    &&RegisteredGtinProof.source(shop).equals(link.source())&&link.sku().equals(fresh.metadata().get("sku"))
                    &&RegisteredGtinProof.sameCode(link.gtin(),card.gtin())&&Long.toString(link.goodId()).equals(card.details().single("@catalog_id"))
                    &&link.revision().equals(exact?exactRevision(fresh,card):revision(fresh,card));
        }catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){return false;}
    }
    /** Claims remain visible when proof expires, so legacy broad article rules cannot select a sibling. */
    public static List<Claim> purchaseClaims(Connection c,Shop shop)throws SQLException{
        return claims(c,shop,true);
    }
    private static List<Claim> claims(Connection c,Shop shop,boolean purchase)throws SQLException{
        return claims(c,shop,purchase,OzonReviewedVariantProof::readExactCatalog);
    }
    static List<Claim> claims(Connection c,Shop shop,boolean purchase,ExactCatalogLoader loader)throws SQLException{
        if(shop.getMarketplace()!=Marketplace.OZON)return List.of();requireOwner(c,shop);
        try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_reviewed_gtin_links'");var r=p.executeQuery()){if(!r.next())return List.of();}
        var claims=new ArrayList<Claim>();ExactCatalogRead exactCatalog=null;boolean exactLoaded=false;RuntimeException exactFailure=null;
        try(var p=c.prepareStatement("SELECT product_id,offer_id,sku,gtin,revision,owner,source,good_id FROM ozon_reviewed_gtin_links WHERE shop_id=?")){
            p.setInt(1,shop.getId());try(var rows=p.executeQuery()){while(rows.next()){
                if(!nativeProof(rows.getString(5)))continue;boolean exact=exactProof(rows.getString(5));
                String nativeId=rows.getString(1),offer=rows.getString(2),sku=rows.getString(3),gtin=rows.getString(4);ZnackModels.Product metadata=null;String issue="GTIN_REVIEW_CHANGED";
                try{
                    var pk=new ProductKey(shop.getId(),Marketplace.OZON,nativeId,offer);var observed=observation(c,shop,pk,exact);
                    if(observed!=null&&currentBasic(c,shop,observed.product(),exact)){
                        var card=currentCard(c,shop,gtin,observed.card());
                        if(exact&&!exactLoaded){exactLoaded=true;try{exactCatalog=loader.read(c,shop);}catch(RuntimeException failed){exactFailure=failed;}}
                        if(exact&&exactFailure!=null)throw exactFailure;
                        if(matches(shop,observed.product(),card,exact?exactCatalog:null)&&(!purchase||activePermit(card))){
                            var facts=card.details();var docs=facts.documents().stream().filter(d->activeDocument(d)).map(d->new ZnackModels.GoodsDocument(switch(d.type()){case "DECLARATION","CONFORMITY_DECLARATION"->"CONFORMITY_DECLARATION";case "CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CONFORMITY_CERTIFICATE"->"CONFORMITY_CERTIFICATE";default->"STATE_REGISTRATION_CERTIFICATE";},d.number(),d.from())).toList();
                            metadata=new ZnackModels.Product(RegisteredGtinProof.canonicalCode(gtin),facts.single("title"),facts.single("tnved"),null,null,null,null,null,null,"published","","",null,null,docs);issue="";
                        }
                    }
                }catch(IllegalArgumentException|IllegalStateException invalid){/* Keep this exact claim blocked. */}
                claims.add(new Claim(nativeId,offer,sku,gtin,metadata,issue));
            }}
        }if(exactCatalog!=null)exactCatalog.requireCurrent(shop);return List.copyOf(claims);
    }
    public static boolean needsRefresh(Shop shop,GtinSyncModels.ProductKey key){
        try(var c=Database.getConnection()){var link=OzonReviewedGtinLinks.read(shop,key);boolean exact=link!=null&&exactProof(link.revision());var observed=observation(c,shop,productKey(key),exact);return observed==null||!currentBasic(c,shop,observed.product(),exact)||!fresh(observed.product().checkedAt())||!RegisteredGtinProof.currentObservation(currentCard(c,shop,observed.card().gtin(),observed.card()),Instant.now());}catch(SQLException e){throw storage(e);}
    }
    public static Map<ProductKey,String> matchingIndex(Shop shop){
        if(shop.getMarketplace()!=Marketplace.OZON)return Map.of();
        try(var c=Database.getConnection()){
            var result=new LinkedHashMap<ProductKey,String>();
            for(var claim:claims(c,shop,false))result.put(new ProductKey(shop.getId(),Marketplace.OZON,claim.productId(),claim.offerId()),claim.metadata()==null?"":claim.gtin());
            return Map.copyOf(result);
        }catch(SQLException e){throw storage(e);}
    }
    /** Proof-kind discriminator only; presence in this set never grants a mapping. */
    public static Set<ProductKey> exactProofKeys(Shop shop){
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON)return Set.of();
        try(var c=Database.getConnection()){
            requireOwner(c,shop);var keys=new HashSet<ProductKey>();
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_reviewed_gtin_links'");var r=p.executeQuery()){if(!r.next())return Set.of();}
            try(var p=c.prepareStatement("SELECT product_id,offer_id,revision FROM ozon_reviewed_gtin_links WHERE shop_id=?")){
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())if(exactProof(r.getString(3)))keys.add(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)));}
            }return Set.copyOf(keys);
        }catch(SQLException failure){throw storage(failure);}
    }
    private static boolean currentBasic(Connection c,Shop shop,ProductSnapshot observed,boolean exact)throws SQLException{
        if(!fresh(observed.checkedAt()))return false;
        try(var p=c.prepareStatement("SELECT payload FROM product_snapshots WHERE shop_id=? AND marketplace='OZON' AND card_id=? AND variant_id=?")){
            p.setInt(1,shop.getId());p.setString(2,observed.key().cardId());p.setString(3,observed.key().variantId());try(var r=p.executeQuery()){if(r.next()){
                var newer=ProductJson.GSON.fromJson(r.getString(1),ProductSnapshot.class);
                if(newer!=null&&newer.revision().startsWith("live:")&&ListingIdentity.fingerprint(shop).equals(newer.metadata().get("owner"))
                        &&newer.checkedAt().isAfter(observed.checkedAt())&&!(exact?exactProductIdentity(newer).equals(exactProductIdentity(observed)):productIdentity(new OzonShopBrand().apply(shop,newer)).equals(productIdentity(observed))))return false;
            }}
        }
        try(var p=c.prepareStatement("SELECT product_id,offer_id,sku,color,size,manufacturer_size,russian_size,updated_at,description_category_id,type_id,article FROM ozon_products WHERE shop_id=? AND sku=? AND archived=0")){
            p.setInt(1,shop.getId());p.setString(2,observed.metadata().get("sku"));try(var r=p.executeQuery()){
                if(!r.next()||!observed.key().cardId().equals(r.getString(1))||!observed.key().variantId().equals(r.getString(2)))return false;
                var v=observed.variants().getFirst();String size=Objects.toString(r.getString(6),"").isBlank()?r.getString(5):r.getString(6);
                if(exact){
                    // Do not copy an old explicit article/size attribute over the
                    // current native summary; it could conceal a real model edit.
                    var local=new ProductRow(observed.key(),observed.title(),Objects.toString(r.getString(11),""),r.getString(3),"","",r.getString(4),size,List.of(),"","","","",DocumentState.UNVERIFIED,"",true,r.getString(7),r.getString(6));
                    var expected=OzonExactVariantIdentity.identity(observed);var current=OzonExactVariantIdentity.identity(local);
                    if(expected.isEmpty()||!expected.equals(current))return false;
                }else if(!OzonSkuGtinMatcher.sameColor(v.color(),r.getString(4))||!same(v.size().isBlank()?v.russianSize():v.size(),size)
                        ||!Objects.toString(r.getString(7),"").isBlank()&&!same(v.russianSize(),r.getString(7)))return false;
                String changed=r.getString(8);if(changed!=null&&!changed.isBlank())try{if(Instant.parse(changed).isAfter(observed.checkedAt().plusSeconds(1)))return false;}catch(RuntimeException invalid){return false;}
                if(!exact&&!Objects.toString(r.getString(9),"").isBlank()&&!observed.categoryId().equals(r.getString(9)+":"+r.getString(10)))return false;
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
        return com.vncode.app.features.catalogsync.CatalogPermitEvidence.active(d);
    }
    private static boolean compatibleRegistration(Connection c,Shop shop,GtinSyncModels.ProductKey key,OzonReviewedGtinLinks.Link link)throws SQLException{
        try(var p=c.prepareStatement("SELECT gtin,good_id,sku,active_gtin FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=?")){
            p.setInt(1,shop.getId());p.setString(2,key.productId());p.setString(3,key.variantId());try(var r=p.executeQuery()){
                if(!r.next())return true;String registered=Objects.toString(r.getString(4),"").isBlank()?Objects.toString(r.getString(1),""):r.getString(4);
                return (registered.isBlank()||RegisteredGtinProof.sameCode(registered,link.gtin()))&&(r.getLong(2)<=0||r.getLong(2)==link.goodId())&&(Objects.toString(r.getString(3),"").isBlank()||r.getString(3).equals(link.sku()));
            }
        }
    }
    private static void requirePair(Shop shop,ProductSnapshot p,GtinSyncModels.RegisteredGtin card){
        requireNativePair(shop,p,card);
        var v=p.variants().getFirst();if(!OzonSkuGtinMatcher.sameColor(v.color(),card.color())||!GtinVariantIdentity.sizeMatches(card,v.size().isBlank()?v.russianSize():v.size(),v.russianSize()))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
    }
    private static void requireExactPair(Shop shop,ProductSnapshot p,GtinSyncModels.RegisteredGtin card){
        requireExactPair(shop,p,card,null);
    }
    private static void requireExactPair(Shop shop,ProductSnapshot p,GtinSyncModels.RegisteredGtin card,ExactCatalogRead exactCatalog){
        requireNativePair(shop,p,card);
        com.vncode.app.integration.znack.CatalogReadRevision.requireComplete(shop.getId());
        if(!com.vncode.app.integration.znack.CatalogReadRevision.observedAt(card,com.vncode.app.integration.znack.CatalogReadRevision.current(shop.getId()))||!OzonExactVariantIdentity.sameIdentity(p,card))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        // A machine-selected association loses authority when another exact GTIN is
        // discovered. A previously explicit seller choice uses ov2 and is unchanged.
        if(exactCatalog==null)try(var c=Database.getConnection()){exactCatalog=readExactCatalog(c,shop);}catch(SQLException failed){throw storage(failed);}
        exactCatalog.requireUnique(shop,p,card);
    }
    static ExactCatalogRead readExactCatalog(Connection c,Shop shop)throws SQLException{
        requireOwner(c,shop);String owner=ListingIdentity.fingerprint(shop),source=RegisteredGtinProof.source(shop),readRevision=com.vncode.app.integration.znack.CatalogReadRevision.current(c,shop.getId());
        var cards=new ArrayList<GtinSyncModels.RegisteredGtin>();
        try(var query=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON'")){
            query.setInt(1,shop.getId());try(var rows=query.executeQuery()){while(rows.next()){
                var raw=JsonParser.parseString(rows.getString(1)).getAsJsonObject();var candidate=ProductJson.GSON.fromJson(raw,GtinSyncModels.RegisteredGtin.class);
                if(!source.equals(candidate.source()))continue;
                if(!raw.has("owner")||!owner.equals(raw.get("owner").getAsString()))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");cards.add(candidate);
            }}
        }
        var result=new ExactCatalogRead(owner,source,readRevision,cards);result.requireCurrent(shop);return result;
    }
    private static void requireNativePair(Shop shop,ProductSnapshot p,GtinSyncModels.RegisteredGtin card){
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||p==null||p.key().shopId()!=shop.getId()||p.key().marketplace()!=Marketplace.OZON||!p.revision().startsWith("live:")||!ListingIdentity.fingerprint(shop).equals(p.metadata().get("owner"))||!fresh(p.checkedAt()))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
        if(card==null||!scope.source().equals(card.source())||!RegisteredGtinProof.currentObservation(card,Instant.now())||p.variants().size()!=1||!p.key().variantId().equals(p.variants().getFirst().id())||!p.metadata().getOrDefault("sku","").matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("unverified_catalog_gtin");
    }
    private static Observation observation(Connection c,Shop shop,ProductKey key)throws SQLException{
        return observation(c,shop,key,false);
    }
    private static Observation observation(Connection c,Shop shop,ProductKey key,boolean exact)throws SQLException{
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){p.setString(1,key(shop,key,exact));try(var r=p.executeQuery()){if(!r.next())return null;try{return ProductJson.GSON.fromJson(r.getString(1),Observation.class);}catch(RuntimeException invalid){throw new IllegalStateException("GTIN_REVIEW_CHANGED",invalid);}}}
    }
    private static String key(Shop shop,ProductKey key){return "ozon.reviewed.variant.v2."+shop.getId()+"."+ListingIdentity.hash(ListingIdentity.fingerprint(shop)+"\n"+key.cardId()+"\n"+key.variantId());}
    private static String key(Shop shop,ProductKey key,boolean exact){return exact?"ozon.exact.variant.v1."+shop.getId()+"."+ListingIdentity.hash(ListingIdentity.fingerprint(shop)+"\n"+key.cardId()+"\n"+key.variantId()):key(shop,key);}
    private static ProductKey productKey(GtinSyncModels.ProductKey k){return new ProductKey(k.shopId(),k.marketplace(),k.productId(),k.variantId());}
    private static void requireOwner(Connection c,Shop s)throws SQLException{if(!ListingIdentity.fingerprint(s).equals(ListingIdentity.fingerprint(c,s.getId(),Marketplace.OZON)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");}
    private static boolean fresh(Instant at){return at!=null&&!at.isBefore(Instant.now().minusSeconds(86400))&&!at.isAfter(Instant.now().plusSeconds(300));}
    private static boolean same(String a,String b){return GtinMatchingService.normalize(Objects.toString(a,"")).equals(GtinMatchingService.normalize(Objects.toString(b,"")));}
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("SYNC_STORAGE",e);}
}
