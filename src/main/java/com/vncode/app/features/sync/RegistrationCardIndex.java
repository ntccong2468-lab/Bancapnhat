package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.*;

/** Presentation only: fresh Catalog observations never invent native feed/checkpoint identities. */
public final class RegistrationCardIndex {
    public record View(String status,String gtin,String detail,boolean exists,boolean published,boolean blocked) {}
    private final Map<String,List<RegisteredGtin>> codes=new HashMap<>(),attributes=new HashMap<>();
    private final OzonSkuGtinMatcher ozon;
    private final Set<Long> wbOwners=new HashSet<>();
    private final String owner,scopeJson;
    private final long catalogErrors;
    public RegistrationCardIndex(Shop shop) {
        var ready=new RegistrationSources().require(shop);
        var scope=RegisteredGtinProof.current(shop);owner=ListingIdentity.fingerprint(shop);
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var nativeScope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(settings);
        scopeJson=new com.google.gson.Gson().toJson(nativeScope);
        Instant now=Instant.now();var scoped=new ArrayList<RegisteredGtin>();
        for(var row:new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace())) {
            if(!scope.source().equals(row.source())||!row.tradeUnit()||!RegisteredGtinValidator.isValid(row.gtin())||GtinNormalizer.isTechnicalRange(row.gtin())||row.syncedAt().isBefore(now.minusSeconds(86400))||row.syncedAt().isAfter(now.plusSeconds(300)))continue;
            scoped.add(row);codes.computeIfAbsent(normal(row.gtin()),k->new ArrayList<>()).add(row);
            attributes.computeIfAbsent(key(row.article(),row.color(),row.size()),k->new ArrayList<>()).add(row);
        }
        catalogErrors=scoped.stream().filter(c->com.vncode.app.integration.znack.registration.CatalogCardState.of(c)==com.vncode.app.integration.znack.registration.CatalogCardState.ERRORS).count();
        ozon=new OzonSkuGtinMatcher(scoped,true);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT chrt_id FROM znack_card_registration_scopes WHERE shop_id=? AND owner_hash=? AND participant_inn=? AND environment=? AND fingerprint=?")) {
            p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,nativeScope.inn());p.setString(4,nativeScope.environment());p.setString(5,nativeScope.fingerprint());
            try(var rows=p.executeQuery()){while(rows.next())wbOwners.add(rows.getLong(1));}
        }catch(java.sql.SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
        if(!ready.run().equals(new RegistrationSources().require(shop).run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
    }
    public long catalogErrorCount(){return catalogErrors;}
    public boolean ownsWb(long variant){return wbOwners.contains(variant);}
    public boolean ownsOzon(String owner,String scope){return this.owner.equals(owner)&&scopeJson.equals(scope);}
    public View view(Status status,String gtin,List<String> barcodes,String article,String color,String size,boolean nativeOwned) {
        return view(status,gtin,barcodes,article,color,size,"",nativeOwned);
    }
    public View viewOzon(Status status,String gtin,com.vncode.app.features.products.ProductRow row,boolean nativeOwned) {
        return view(status,gtin,row.barcodes(),row.vendorArticle(),row.color(),GtinVariantIdentity.primarySize(row),row.russianSize(),nativeOwned,ozon.candidates(row));
    }
    public View view(Status status,String gtin,List<String> barcodes,String article,String color,String size,String russianSize,boolean nativeOwned) {
        return view(status,gtin,barcodes,article,color,size,russianSize,nativeOwned,List.of());
    }
    private View view(Status status,String gtin,List<String> barcodes,String article,String color,String size,String russianSize,boolean nativeOwned,List<RegisteredGtin> skuCandidates) {
        boolean nativeExists=status!=Status.NOT_CREATED || !safe(gtin).isBlank();
        if(nativeExists&&!nativeOwned)return new View("source.unverified",safe(gtin),"SOURCE_REGISTRATION_OWNER_CHANGED",true,false,true);
        var matches=new LinkedHashSet<RegisteredGtin>();
        if(!safe(gtin).isBlank())matches.addAll(codes.getOrDefault(normal(gtin),List.of()));
        else {
            for(var barcode:barcodes)matches.addAll(codes.getOrDefault(normal(barcode),List.of()));
            if(matches.isEmpty()&&!GtinMatchingService.normalizeArticle(article).isBlank()&&!safe(color).isBlank()&&!safe(size).isBlank()){
                matches.addAll(attributes.getOrDefault(key(article,color,size),List.of()).stream().filter(card->GtinVariantIdentity.sizeMatches(card,size,russianSize)).toList());
                if(!safe(russianSize).isBlank())matches.addAll(attributes.getOrDefault(key(article,color,russianSize),List.of()).stream().filter(GtinVariantIdentity::russianSize).toList());
            }
        }
        if(matches.isEmpty()&&!nativeExists)matches.addAll(skuCandidates);
        if(matches.size()>1)return new View("source.ambiguous","","GTIN_AMBIGUOUS_MATCH",true,false,true);
        if(matches.size()==1) {
            var card=matches.iterator().next();
            // Exact codes also need compatible variant details before presenting a usable card.
            if((skuCandidates.contains(card)?!OzonSkuGtinMatcher.sameColor(color,card.color()):conflict(color,card.color()))||!safe(size).isBlank()&&!safe(card.size()).isBlank()&&!GtinVariantIdentity.sizeMatches(card,size,russianSize))return new View("source.ambiguous",card.gtin(),"GTIN_VARIANT_CONFLICT",true,false,true);
            if(card.published())return new View("status.published",card.gtin(),"SOURCE_PUBLISHED_CONFIRMED",true,true,false);
            if(!nativeExists||status==Status.PUBLISHED){
                var observed=com.vncode.app.integration.znack.registration.CatalogCardState.of(card);
                return new View(observed.viewStatus,card.gtin(),observed.reason,true,false,true);
            }
        }
        if(status==Status.PUBLISHED)return new View("source.unverified",safe(gtin),"SOURCE_PUBLISHED_NOT_CONFIRMED",true,false,true);
        return new View("status."+status.name().toLowerCase(Locale.ROOT),safe(gtin),nativeExists?"SOURCE_NATIVE_CHECKPOINT":"SOURCE_CARD_NOT_CREATED",nativeExists,false,false);
    }
    private static boolean conflict(String a,String b){return !safe(a).isBlank()&&!safe(b).isBlank()&&!GtinMatchingService.normalize(a).equals(GtinMatchingService.normalize(b));}
    private static String key(String a,String c,String s){return GtinMatchingService.normalizeArticle(a)+"\u001f"+GtinMatchingService.normalize(c)+"\u001f"+GtinMatchingService.normalize(s);}
    private static String normal(String code){return code!=null&&code.matches("[0-9]{8,14}")?"0".repeat(14-code.length())+code:safe(code);}
    private static String safe(String value){return value==null?"":value;}
}
