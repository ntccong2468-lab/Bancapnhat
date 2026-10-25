package com.vncode.app.features.kiz;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.ProductFieldNames;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.ZnackModels.Product;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** KIZ identity during Catalog review. This never asserts Catalog publication or circulation. */
public final class KizRegistrationIdentityRepository {
    private static final Gson JSON=new Gson();
    private static final Set<String> ACCEPTED=Set.of("FEED_SUBMITTED","PROCESSING","READY_TO_SIGN","SIGNING");
    private record Key(String product,String sku){}
    public record Identity(String productId,String variantId,String sku,String gtin,Product metadata){}
    public static final class Snapshot {
        private final Map<Key,Identity> identities=new HashMap<>();
        private final Set<String> claimed=new HashSet<>(),ambiguous=new HashSet<>();
        /** A claimed but unverifiable alias must never silently fall back to a sibling's broad rule. */
        public Identity select(String productId,String sku){
            sku=safe(sku).strip();var identity=identities.get(new Key(safe(productId),sku));
            if(claimed.contains(sku)&&(identity==null||ambiguous.contains(sku)))throw new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","",safe(productId)+"-"+sku);
            return identity;
        }
        public void applyTo(Map<String,String> mappings){
            claimed.forEach(mappings::remove);
            identities.values().stream().filter(i->!ambiguous.contains(i.sku())).forEach(i->mappings.put(i.sku(),i.gtin()));
        }
    }
    public Snapshot read(Shop shop){
        return read(shop,false);
    }
    /** Buying for an exact, scoped published registration does not depend on barcode writeback.
     * This view is never used to promote a Catalog product or authorize marketplace GTIN writes. */
    public Snapshot readForPurchase(Shop shop){return read(shop,true);}
    private Snapshot read(Shop shop,boolean includePublished){
        ListingIdentity.require(shop);var result=new Snapshot();
        RegisteredGtinProof.Scope proof=null;
        try{var candidate=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(candidate);proof=candidate;}
        catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){/* Keep claims blocked. */}
        String owner=ListingIdentity.fingerprint(shop);
        var published=includePublished?publishedCatalog(shop,owner):Map.<String,List<RegisteredGtin>>of();
        String sql=shop.getMarketplace()==Marketplace.WILDBERRIES?"""
            SELECT CAST(r.nm_id AS TEXT) product_id,CAST(r.chrt_id AS TEXT) variant_id,b.sku,
              r.gtin,r.status,r.feed_id,r.good_id,r.payload_json,scope.owner_hash owner,
              scope.participant_inn inn,scope.environment environment,scope.fingerprint fingerprint,'' scope_json,
              c.vendor_code article,s.tech_size size,s.wb_size russian_size,
              (SELECT json_group_array(json_object('name',ch.name,'value',ch.value_json))
               FROM wb_product_characteristics ch WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id) color,
              (SELECT count(*) FROM wb_product_size_skus aliases WHERE aliases.shop_id=b.shop_id AND aliases.sku=b.sku) alias_count
            FROM znack_card_registrations r
            JOIN wb_product_sizes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id AND s.nm_id=r.nm_id
            JOIN wb_product_cards c ON c.shop_id=s.shop_id AND c.nm_id=s.nm_id
            JOIN wb_product_size_skus b ON b.shop_id=s.shop_id AND b.chrt_id=s.chrt_id
            LEFT JOIN znack_card_registration_scopes scope ON scope.shop_id=r.shop_id AND scope.chrt_id=r.chrt_id
            LEFT JOIN znack_card_registration_replacements previous ON previous.shop_id=r.shop_id AND previous.chrt_id=r.chrt_id
            WHERE r.shop_id=? AND (?=1 OR r.status<>'PUBLISHED')
              AND (NULLIF(TRIM(r.gtin),'') IS NOT NULL OR NULLIF(TRIM(r.feed_id),'') IS NOT NULL OR r.good_id>0)
              AND NOT(COALESCE(previous.state,'')='CANDIDATE' AND NULLIF(TRIM(previous.active_gtin),'') IS NOT NULL)
            """:"""
            SELECT r.product_id,r.offer_id variant_id,p.sku,r.gtin,r.status,r.feed_id,r.good_id,r.payload_json,
              r.owner,'' inn,'' environment,'' fingerprint,r.scope_json,
              COALESCE(NULLIF(p.article,''),p.offer_id) article,
              COALESCE(NULLIF(p.manufacturer_size,''),p.size) size,p.russian_size,p.color,
              (SELECT count(*) FROM ozon_products aliases WHERE aliases.shop_id=p.shop_id AND aliases.sku=p.sku AND aliases.archived=0) alias_count
            FROM ozon_card_registrations r JOIN ozon_products p
              ON p.shop_id=r.shop_id AND p.product_id=r.product_id AND p.offer_id=r.offer_id AND p.sku=r.sku AND p.archived=0
            WHERE r.shop_id=? AND (?=1 OR r.status<>'PUBLISHED') AND r.active_gtin=''
              AND (r.gtin<>'' OR r.feed_id<>'' OR r.good_id>0)
            """;
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)){
            p.setInt(1,shop.getId());p.setBoolean(2,includePublished);try(var r=p.executeQuery()){while(r.next()){
                String sku=safe(r.getString("sku")).strip();if(sku.isBlank())continue;
                boolean isPublished="PUBLISHED".equals(r.getString("status"));
                // Legacy published rows lacking proof must not shadow an independently verified native barcode.
                if(!isPublished)result.claimed.add(sku);if(r.getInt("alias_count")!=1)result.ambiguous.add(sku);
                if(proof==null)continue;
                try{
                    if(!owner.equals(r.getString("owner"))||!ACCEPTED.contains(r.getString("status"))&&!isPublished
                            ||safe(r.getString("feed_id")).isBlank()&&r.getLong("good_id")<=0)continue;
                    var bound=shop.getMarketplace()==Marketplace.WILDBERRIES
                            ?new ZnackRegistrationScope(r.getString("inn"),r.getString("environment"),r.getString("fingerprint"))
                            :JSON.fromJson(r.getString("scope_json"),ZnackRegistrationScope.class);
                    if(bound==null||!bound.inn().equals(proof.inn())||!bound.fingerprint().equals(proof.fingerprint())
                            ||!bound.environment().replaceAll("/+$","").equals(proof.environment()))continue;
                    String gtin=r.getString("gtin"),payload=r.getString("payload_json");
                    if(!RegisteredGtinValidator.isValid(gtin)||GtinNormalizer.isTechnicalRange(gtin)||payload==null||payload.length()>2*1024*1024)continue;
                    var body=JsonParser.parseString(payload).getAsJsonObject();
                    if(!RegisteredGtinProof.sameCode(gtin,text(body,"gtin")))continue;
                    var facts=CatalogProductDataParser.parse(body);
                    var variant=new RegisteredGtin(gtin,facts.single("article"),facts.single("color"),facts.single("size"),true,false,proof.source(),Instant.now(),facts);
                    String color=shop.getMarketplace()==Marketplace.WILDBERRIES?singleColor(r.getString("color")):safe(r.getString("color"));
                    if(!ListingIdentity.compatibleVariant(variant,r.getString("article"),color,r.getString("size"),r.getString("russian_size")))continue;
                    if(isPublished){
                        var observed=published.getOrDefault(GtinNormalizer.normalize(gtin),List.of());
                        if(observed.size()!=1)continue;
                        var card=observed.getFirst();
                        if(!card.published()||!card.tradeUnit()||!proof.source().equals(card.source())
                                ||!RegisteredGtinProof.currentObservation(card,Instant.now())
                                ||!ListingIdentity.compatibleVariant(card,r.getString("article"),color,r.getString("size"),r.getString("russian_size")))continue;
                        // Once published, current Catalog permits supersede the submitted draft.
                        facts=card.details();
                    }
                    // The reviewed feed defines UNIT by the documented absence of is_kit; malformed values fail closed.
                    if(body.has("is_kit")&&(!body.get("is_kit").isJsonPrimitive()||!body.getAsJsonPrimitive("is_kit").isBoolean()))continue;
                    String cisType=isPublished?null:body.has("is_kit")&&body.get("is_kit").getAsBoolean()?"BUNDLE":"UNIT";
                    var documents=facts.documents().stream().filter(d->!d.number().isBlank()&&!d.from().isBlank()).map(d->new ZnackModels.GoodsDocument(switch(d.type()){
                        case "DECLARATION"->"CONFORMITY_DECLARATION";case "CERTIFICATE_OF_CONFORMITY"->"CONFORMITY_CERTIFICATE";default->"STATE_REGISTRATION_CERTIFICATE";},d.number(),d.from())).toList();
                    gtin=GtinNormalizer.normalize(gtin);
                    var product=new Product(gtin,facts.single("title"),facts.single("tnved"),null,null,null,null,null,null,"pending_registration","","",null,cisType,documents);
                    var identity=new Identity(r.getString("product_id"),r.getString("variant_id"),sku,gtin,product);
                    result.claimed.add(sku);
                    var prior=result.identities.putIfAbsent(new Key(identity.productId(),sku),identity);
                    if(prior!=null&&!prior.variantId().equals(identity.variantId()))result.ambiguous.add(sku);
                }catch(RuntimeException malformed){/* Invalid claims stay blocked. */}
            }}
            ListingIdentity.require(shop);
            if(proof!=null&&!proof.source().equals(RegisteredGtinProof.current(shop).source()))throw missing();
            return result;
        }catch(SQLException failure){throw new IllegalStateException("KIZ_REGISTRATION_IDENTITY_READ_FAILED",failure);}
    }
    /** Metadata fallback for an exact accepted/published registration; never projects a Catalog product. */
    public Optional<Product> product(int shopId,String gtin){
        var shop=new ShopRepository().findById(shopId);if(shop==null)return Optional.empty();
        String normalized=GtinNormalizer.normalize(gtin);var snapshot=readForPurchase(shop);
        var matches=snapshot.identities.values().stream().filter(i->i.gtin().equals(normalized)&&!snapshot.ambiguous.contains(i.sku())).toList();
        if(matches.isEmpty())return Optional.empty();
        if(matches.stream().map(i->i.productId()+"|"+i.variantId()).distinct().count()!=1)return Optional.empty();
        return Optional.of(matches.getFirst().metadata());
    }
    private static String singleColor(String json){
        var colors=new LinkedHashSet<String>();
        for(var raw:JsonParser.parseString(json).getAsJsonArray()){
            var attribute=raw.getAsJsonObject();if(!ProductFieldNames.matches("color",text(attribute,"name")))continue;
            var value=JsonParser.parseString(text(attribute,"value"));
            if(value.isJsonArray()){for(var color:value.getAsJsonArray()){if(!color.isJsonPrimitive()||!color.getAsJsonPrimitive().isString())return "";if(!color.getAsString().isBlank())colors.add(color.getAsString().strip());}}
            else if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())colors.add(value.getAsString().strip());else return "";
        }
        return colors.size()==1?colors.iterator().next():"";
    }
    private static String text(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
    private static Map<String,List<RegisteredGtin>> publishedCatalog(Shop shop,String owner){
        var result=new HashMap<String,List<RegisteredGtin>>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=?")){
            p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());
            try(var rows=p.executeQuery()){while(rows.next())try{
                var data=JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                if(!owner.equals(text(data,"owner")))continue;
                var card=com.vncode.app.features.products.ProductJson.GSON.fromJson(data,RegisteredGtin.class);
                if(!RegisteredGtinValidator.isValid(card.gtin())||!RegisteredGtinProof.sameCode(rows.getString(1),card.gtin()))continue;
                result.computeIfAbsent(GtinNormalizer.normalize(card.gtin()),ignored->new ArrayList<>()).add(card);
            }catch(RuntimeException unusable){/* Malformed/unowned cache cannot authorize a paid request. */}}
            return result;
        }catch(SQLException failed){throw new IllegalStateException("KIZ_REGISTRATION_IDENTITY_READ_FAILED",failed);}
    }
    private static String safe(String text){return Objects.toString(text,"");}
    private static KizAssignmentException missing(){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","","");}
}
