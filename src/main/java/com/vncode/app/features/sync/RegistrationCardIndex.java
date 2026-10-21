package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.*;

/** Presentation only: fresh Catalog observations never invent native feed/checkpoint identities. */
public final class RegistrationCardIndex {
    public record View(String status,String gtin,String detail,boolean exists,boolean published,boolean blocked,boolean draftReview) {
        public View(String status,String gtin,String detail,boolean exists,boolean published,boolean blocked){this(status,gtin,detail,exists,published,blocked,false);}
        public boolean canReviewDraft(){return draftReview&&exists&&!published&&status.equals("source.draft")&&detail.equals("CATALOG_CARD_DRAFT");}
    }
    /** A local allocation/checkpoint is not proof that the product feed reached Catalog. */
    public static boolean notSubmitted(Sku row) {
        return notSubmitted(row.status(),row.gtin(),row.goodId(),row.feedId(),row.wbUpdated());
    }
    public static boolean notSubmitted(Status status,String gtin,Long goodId,String feedId,boolean marketplaceUpdated) {
        if(goodId!=null||!safe(feedId).isBlank()||marketplaceUpdated)return false;
        return switch(status==null?Status.NOT_CREATED:status) {
            case NOT_CREATED,PREFLIGHT_ERROR,CHECKING -> safe(gtin).isBlank();
            case READY_TO_SUBMIT,PRE_SUBMIT_ERROR -> true;
            // Old allocations and an interrupted POST need reconciliation, never another new POST.
            default -> false;
        };
    }
    public static boolean confirmedSubmission(Status status,String feedId) {
        if(safe(feedId).isBlank()||status==null)return false;
        return switch(status) {
            case FEED_SUBMITTED,PROCESSING,READY_TO_SIGN,SIGNING,PUBLISHED -> true;
            default -> false;
        };
    }
    /** A readback/variant warning blocks writes, not visibility of an accepted request. */
    public static boolean awaitingCatalog(View view,Status status,String feedId){
        if(Set.of("source.draft","source.processing","source.moderation").contains(view.status()))return true;
        return !view.published()&&confirmedSubmission(status,feedId)
                &&(!view.blocked()||view.detail().equals("GTIN_VARIANT_UNVERIFIED"));
    }
    /** Unsent preparation and uncertain requests have their own filters, not Catalog errors. */
    public static boolean rejectedCard(View view,Status status,Long goodId,String feedId){
        if(view.published()||awaitingCatalog(view,status,feedId))return false;
        return view.status().equals("source.errors")||status==Status.ERROR&&(goodId!=null||!safe(feedId).isBlank());
    }
    private final Map<String,List<RegisteredGtin>> codes=new HashMap<>(),attributes=new HashMap<>();
    private final OzonSkuGtinMatcher ozon;
    private final Set<Long> wbOwners=new HashSet<>();
    private final String owner,scopeJson,catalogSource;
    private final long catalogErrors;
    public RegistrationCardIndex(Shop shop) {
        var ready=new RegistrationSources().require(shop);
        var scope=RegisteredGtinProof.current(shop);owner=ListingIdentity.fingerprint(shop);catalogSource=scope.source();
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
    /** Called on a read worker before retaining a previous observation, never from a cell renderer. */
    public boolean currentScope(Shop shop){
        try{return owner.equals(ListingIdentity.fingerprint(shop))&&catalogSource.equals(RegisteredGtinProof.current(shop).source());}
        catch(RuntimeException unavailable){return false;}
    }
    /** Historical observation only; a feed/checkpoint cannot populate this value. */
    public Optional<RegisteredGtin> observation(View view){
        var matches=codes.getOrDefault(normal(view.gtin()),List.of());return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
    }
    /** Display proof is stricter than a local allocation/checkpoint or barcode format. */
    public Optional<RegisteredGtin> verifiedWb(Sku row){
        var observed=view(row.status(),row.gtin(),row.barcodes(),row.vendorCode(),row.color(),row.size(),row.wbSize(),ownsWb(row.chrtId()));
        return verified(observed,row.vendorCode(),row.color(),row.size(),row.wbSize(),List.of(),false);
    }
    public Optional<RegisteredGtin> verifiedOzon(com.vncode.app.features.products.ProductRow row,Status status,String gtin,boolean nativeOwned){
        var candidates=ozon.candidates(row);var observed=viewOzon(status,gtin,row,nativeOwned);
        return verified(observed,row.vendorArticle(),row.color(),GtinVariantIdentity.primarySize(row),row.russianSize(),candidates,true);
    }
    private Optional<RegisteredGtin> verified(View observed,String article,String color,String size,String russianSize,List<RegisteredGtin> skuCandidates,boolean ozonColor){
        if(observed.gtin().isBlank()||Set.of("source.unverified","source.ambiguous").contains(observed.status()))return Optional.empty();
        var matches=codes.getOrDefault(normal(observed.gtin()),List.of());if(matches.size()!=1)return Optional.empty();var card=matches.getFirst();
        var now=Instant.now();if(card.syncedAt().isBefore(now.minusSeconds(86400))||card.syncedAt().isAfter(now.plusSeconds(300)))return Optional.empty();
        // A unique raw barcode cannot prove an apparel variant with missing color/size facts.
        if(safe(color).isBlank()||safe(card.color()).isBlank()||safe(size).isBlank()&&safe(russianSize).isBlank()||safe(card.size()).isBlank())return Optional.empty();
        if(!(ozonColor?OzonSkuGtinMatcher.sameColor(color,card.color()):!conflict(color,card.color()))||!GtinVariantIdentity.sizeMatches(card,size,russianSize))return Optional.empty();
        boolean sameArticle=GtinMatchingService.normalizeArticle(article).equals(GtinMatchingService.normalizeArticle(card.article()));
        boolean exactSellerSku=skuCandidates.size()==1&&skuCandidates.contains(card);
        if(!safe(article).isBlank()&&!safe(card.article()).isBlank()&&!sameArticle&&!exactSellerSku)return Optional.empty();
        return Optional.of(card);
    }
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
            boolean exactSellerSku=skuCandidates.size()==1&&skuCandidates.contains(card);
            boolean articleConflict=!safe(article).isBlank()&&!safe(card.article()).isBlank()
                    &&!GtinMatchingService.normalizeArticle(article).equals(GtinMatchingService.normalizeArticle(card.article()))&&!exactSellerSku;
            if(articleConflict||(skuCandidates.contains(card)?!OzonSkuGtinMatcher.sameColor(color,card.color()):conflict(color,card.color()))||!safe(size).isBlank()&&!safe(card.size()).isBlank()&&!GtinVariantIdentity.sizeMatches(card,size,russianSize))return new View("source.ambiguous",card.gtin(),"GTIN_VARIANT_CONFLICT",true,false,true);
            boolean missingVariant=safe(color).isBlank()||safe(card.color()).isBlank()
                    ||safe(size).isBlank()&&safe(russianSize).isBlank()||safe(card.size()).isBlank();
            if(missingVariant){
                // A real remote card may still be in moderation/draft while its variant is unproven.
                // Preserve that independent stage, but never present its GTIN as verified/published.
                String observed=card.published()?"source.unverified":com.vncode.app.integration.znack.registration.CatalogCardState.of(card).viewStatus;
                if(Set.of(Status.FEED_SUBMITTED,Status.PROCESSING,Status.READY_TO_SIGN,Status.SIGNING).contains(status))observed="status."+status.name().toLowerCase(Locale.ROOT);
                return new View(observed,card.gtin(),"GTIN_VARIANT_UNVERIFIED",true,false,true);
            }
            if(card.published())return new View("status.published",card.gtin(),"SOURCE_PUBLISHED_CONFIRMED",true,true,false);
            if(status==Status.ERROR&&com.vncode.app.integration.znack.registration.CatalogCardState.of(card)==com.vncode.app.integration.znack.registration.CatalogCardState.MODERATION)
                return new View("source.moderation",card.gtin(),"CATALOG_CARD_MODERATION",true,false,true);
            if(com.vncode.app.integration.znack.registration.CatalogCardState.reviewableDraft(card))return new View("source.draft",card.gtin(),"CATALOG_CARD_DRAFT",true,false,true,true);
            if(!nativeExists||status==Status.PUBLISHED||safe(gtin).isBlank()&&notSubmitted(status,gtin,null,"",false)){
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
