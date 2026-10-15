package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Complete, read-only catalog refresh. Unknown publication/packaging metadata stays ineligible. */
public final class NationalCatalogGtinSource implements RegisteredGtinSource {
    @FunctionalInterface public interface PageReader {JsonElement read(int page) throws Exception;}
    @FunctionalInterface public interface CardReader {JsonElement read(String gtin) throws Exception;default void prepare(Collection<String> gtins){}}
    @FunctionalInterface public interface AdditionalGtinReader {List<String> read() throws Exception;}
    private final int shopId;
    private final PageReader pages;
    private final CardReader cards;
    private final String source;
    private final AdditionalGtinReader additional;
    private final CardReader documents;
    /** Same 25-GTIN, semicolon-delimited contract as ZnackProductService. Cache only one batch. */
    static final class BatchedCards implements CardReader {
        private final CardReader remote;private final Map<String,String> groups=new LinkedHashMap<>();private String cachedGroup;private JsonElement cached;
        BatchedCards(CardReader remote){this.remote=remote;}
        public void prepare(Collection<String> gtins){groups.clear();cachedGroup=null;cached=null;var ordered=List.copyOf(gtins);for(int i=0;i<ordered.size();i+=25){var batch=ordered.subList(i,Math.min(i+25,ordered.size()));String group=String.join(";",batch);batch.forEach(gtin->groups.put(gtin,group));}}
        public JsonElement read(String gtin)throws Exception{String group=groups.get(gtin);if(group==null)throw new IllegalArgumentException("unknown_catalog_batch_gtin");if(!group.equals(cachedGroup)){var response=remote.read(group);cached=response;cachedGroup=group;}return cached;}
    }
    public NationalCatalogGtinSource(int shopId,PageReader pages,CardReader cards){this(shopId,pages,cards,"National Catalog");}
    public NationalCatalogGtinSource(int shopId,PageReader pages,CardReader cards,String source){this(shopId,pages,cards,source,List::of);}
    public NationalCatalogGtinSource(int shopId,PageReader pages,CardReader cards,String source,AdditionalGtinReader additional){this(shopId,pages,cards,source,additional,null);}
    public NationalCatalogGtinSource(int shopId,PageReader pages,CardReader cards,String source,AdditionalGtinReader additional,CardReader documents){this.shopId=shopId;this.pages=pages;this.cards=cards;this.source=Objects.requireNonNull(source);this.additional=Objects.requireNonNull(additional);this.documents=documents;}
    public static RegisteredGtinSource forShop(Shop selected) {return forScope(selected,null,false);}
    /** Explicit selected-card refresh avoids registry calls across an entire large shop. */
    public static RegisteredGtinSource forSelection(Shop selected,Set<String> selectedGtins,boolean includeDocuments){return forScope(selected,Set.copyOf(selectedGtins),includeDocuments);}
    private static RegisteredGtinSource forScope(Shop selected,Set<String> selectedGtins,boolean includeDocuments) {
        Shop shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        int id=shop.getId();String name=shop.getName();
        return requested -> {
            if(requested!=id) throw new IllegalArgumentException("shop_mismatch");
            LicenseRuntime.require(Permission.SELLER_API);
            var settings=new ZnackRepository(new ZnackModels.ShopContext(id,name)).getSettings();
            var owner=RegisteredGtinProof.scope(shop,settings,Instant.now());
            var api=com.vncode.app.integration.znack.CatalogConnectionService.apiForShop(shop,settings);
            var provider=new com.vncode.app.integration.znack.signature.CryptoProSignatureProvider(settings.cryptcpPath(),owner.fingerprint(),java.time.Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
            com.vncode.app.integration.znack.signature.ZnackSignatureProvider signer=(payload,context)->{
                requireOwner(shop,owner.source());
                var signed=provider.sign(payload,context);
                var certificate=com.vncode.app.integration.znack.signature.CryptoProSignedCertificateReader.read(signed,owner.fingerprint());
                if(!owner.inn().equals(certificate.inn())||certificate.validFrom().isAfter(Instant.now())||!certificate.validTo().isAfter(Instant.now()))
                    throw new IllegalStateException("unverified_catalog_owner");
                return signed;
            };
            var auth=new ZnackAuthService(api,signer);
            var token=auth.trueApiToken(settings);
            if(auth.authenticatedParticipantInn()!=null&&!owner.inn().equals(auth.authenticatedParticipantInn()))throw new IllegalStateException("unverified_catalog_owner");
            var registeredScope=ZnackRegistrationScope.fromSettings(settings);
            var result=new NationalCatalogGtinSource(id,p->{requireOwner(shop,owner.source());return selectedGtins==null?api.products(settings.resolvedTrueApiBaseUrl(),token,p,10_000):com.google.gson.JsonParser.parseString("{\"results\":[],\"total\":0}");},new BatchedCards(g->{requireOwner(shop,owner.source());var response=api.productCards(settings.resolvedTrueApiBaseUrl(),token,g);requireOwner(shop,owner.source());return response;}),owner.source(),()->{
                requireOwner(shop,owner.source());return selectedGtins==null?ownedPublishedGtins(shop,registeredScope):List.copyOf(selectedGtins);
            },includeDocuments?g->{requireOwner(shop,owner.source());return api.permitDocuments(settings.resolvedTrueApiBaseUrl(),token,g,owner.inn());}:null).read(id);
            requireOwner(shop,owner.source());
            return result;
        };
    }
    @Override public List<RegisteredGtin> read(int requestedShop) throws Exception {
        if(requestedShop!=shopId) throw new IllegalArgumentException("shop_mismatch");
        var gtins=new LinkedHashSet<String>();int received=0;Integer total=null;
        boolean complete=false;
        for(int page=0;page<100;page++) {
            LicenseRuntime.require(Permission.SELLER_API);
            JsonElement response=pages.read(page);JsonArray rows;
            if(response!=null && response.isJsonArray()) rows=response.getAsJsonArray();
            else if(response!=null && response.isJsonObject() && response.getAsJsonObject().has("results") && response.getAsJsonObject().get("results").isJsonArray()) {
                var object=response.getAsJsonObject();rows=object.getAsJsonArray("results");
                if(object.has("total")) total=object.get("total").getAsInt();
            } else throw new IOException("invalid_catalog_page");
            for(var e:rows) {
                if(!e.isJsonObject())throw new IOException("invalid_catalog_product");
                String gtin=text(e.getAsJsonObject(),"gtin","productGtin");
                if(!RegisteredGtinValidator.isValid(gtin))throw new IOException("invalid_catalog_gtin");
                if(!gtins.add(gtin))throw new IOException("repeated_catalog_product");
            }
            received+=rows.size();
            if(total!=null && received>=total){complete=true;break;}
            if(rows.isEmpty() && total!=null && received<total)throw new IOException("incomplete_catalog");
            if(total==null && rows.size()<10_000){complete=true;break;}
        }
        if(!complete)throw new IOException("catalog_page_limit");
        // The existing group-limited listing can omit an app-registered card from another
        // category. Its local checkpoint is only an identity to query, never publication proof.
        for(String gtin:additional.read()) {
            if(!RegisteredGtinValidator.isValid(gtin))throw new IOException("invalid_registered_catalog_gtin");
            gtins.add(gtin);
        }
        var result=new ArrayList<RegisteredGtin>();var now=Instant.now();
        cards.prepare(gtins);
        for(String gtin:gtins) {
            LicenseRuntime.require(Permission.SELLER_API);
            var response=cards.read(gtin);
            if(response==null || !response.isJsonObject() || !response.getAsJsonObject().has("result") || !response.getAsJsonObject().get("result").isJsonArray())throw new IOException("invalid_catalog_card");
            JsonObject selected=null;boolean unit=false;
            for(var e:response.getAsJsonObject().getAsJsonArray("result")) {
                if(!e.isJsonObject())continue;var card=e.getAsJsonObject();boolean belongs=RegisteredGtinProof.sameCode(gtin,text(card,"gtin","productGtin"));boolean thisUnit=false;
                if(card.has("identified_by") && card.get("identified_by").isJsonArray())for(var id:card.getAsJsonArray("identified_by")) {
                    if(!id.isJsonObject())continue;var identifier=id.getAsJsonObject();
                    if("gtin".equalsIgnoreCase(text(identifier,"type")) && RegisteredGtinProof.sameCode(gtin,text(identifier,"value","gtin"))) {
                        belongs=true;thisUnit="trade-unit".equalsIgnoreCase(text(identifier,"level"));
                    }
                }
                if(belongs){if(selected!=null)throw new IOException("ambiguous_catalog_card");selected=card;unit=thisUnit;}
            }
            if(selected==null)throw new IOException("missing_catalog_card");
            var details=CatalogProductDataParser.parse(selected);
            if(documents!=null){
                try{details=CatalogProductDataParser.withRegistry(details,documents.read(gtin));}
                catch(Exception unavailable){if(unavailable instanceof InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}details=CatalogProductDataParser.registryUnavailable(details);}
            }
            String article=details.single("article"),color=details.single("color"),size=details.single("size");
            boolean published="published".equalsIgnoreCase(text(selected,"good_status","goodStatus","cardStatus"));
            result.add(new RegisteredGtin(gtin,article,color,size,unit,published,source,now,details));
        }
        return List.copyOf(result);
    }
    private static void requireOwner(Shop shop,String source){if(!source.equals(RegisteredGtinProof.source(shop)))throw new IllegalStateException("catalog_owner_changed");}
    static List<String> ownedPublishedGtins(Shop shop,ZnackRegistrationScope scope)throws Exception {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);scope.assertCurrent(shop);
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        var result=new LinkedHashSet<String>();
        try(var c=com.vncode.app.config.Database.getConnection()){
            var scopedJson=new Gson().toJson(scope);
            if(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON)try(var p=c.prepareStatement("""
              SELECT r.gtin FROM ozon_card_registrations r WHERE r.shop_id=? AND r.status='PUBLISHED'
                AND r.owner=? AND r.scope_json=? AND r.gtin<>''
              UNION SELECT r.active_gtin FROM ozon_card_registrations r
                JOIN ozon_card_registration_history h ON h.shop_id=r.shop_id AND h.product_id=r.product_id
                  AND h.offer_id=r.offer_id AND h.gtin=r.active_gtin
                WHERE r.shop_id=? AND r.owner=? AND r.scope_json=? AND r.active_gtin<>''
              """)){
                p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,scopedJson);p.setInt(4,shop.getId());p.setString(5,owner);p.setString(6,scopedJson);
                try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}
            }
            boolean ownerColumn=false;
            try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_card_registration_scopes)")){while(r.next())if("owner_hash".equals(r.getString("name")))ownerColumn=true;}
            // Legacy WB rows have no marketplace credential stamp and are not silently rebound.
            if(ownerColumn&&shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES)try(var p=c.prepareStatement("""
              SELECT r.gtin FROM znack_card_registrations r JOIN znack_card_registration_scopes s
              ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.status='PUBLISHED'
              AND s.participant_inn=? AND s.environment=? AND s.fingerprint=? AND s.owner_hash=? AND r.gtin<>''
              UNION SELECT x.active_gtin FROM znack_card_registration_replacements x
                JOIN znack_card_registration_scopes s ON s.shop_id=x.shop_id AND s.chrt_id=x.chrt_id
                WHERE x.shop_id=? AND s.participant_inn=? AND s.environment=? AND s.fingerprint=?
                  AND s.owner_hash=? AND x.active_gtin<>'' AND x.state='CANDIDATE'
              """)){
                p.setInt(1,shop.getId());p.setString(2,scope.inn());p.setString(3,scope.environment());p.setString(4,scope.fingerprint());p.setString(5,owner);
                p.setInt(6,shop.getId());p.setString(7,scope.inn());p.setString(8,scope.environment());p.setString(9,scope.fingerprint());p.setString(10,owner);
                try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}
            }
        }
        com.vncode.app.integration.listing.ListingIdentity.require(shop);scope.assertCurrent(shop);
        return List.copyOf(result);
    }
    private static String attribute(JsonObject card,String...names) {
        String direct=text(card,names);if(!direct.isBlank())return direct;
        if(card.has("good_attrs") && card.get("good_attrs").isJsonArray()) for(var e:card.getAsJsonArray("good_attrs")) {
            if(!e.isJsonObject())continue;var a=e.getAsJsonObject();String name=text(a,"attr_name","name");
            if(Arrays.stream(names).anyMatch(n->n.equalsIgnoreCase(name)))return text(a,"attr_value","value");
        }
        return "";
    }
    private static String text(JsonObject o,String...keys) {
        for(String key:keys)if(o.has(key) && o.get(key).isJsonPrimitive())return o.get(key).getAsString();return "";
    }
}
