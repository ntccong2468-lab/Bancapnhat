package com.vncode.app.features.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Read both sources and adopt only an exact, current Ozon barcode readback. Never writes seller cards. */
public final class OzonCatalogMappingService {
    public record Issue(String article,String size,String sku,String gtin,String reason){}
    public record Result(int mapped,int exempt,List<Issue> issues){public Result{issues=List.copyOf(issues);}}
    record Target(ProductRow row,String sku,boolean exempt,String existing){
        Target{Objects.requireNonNull(row);sku=Objects.requireNonNullElse(sku,"");existing=Objects.requireNonNullElse(existing,"");}
    }
    interface Operations {
        String scope(Shop shop);
        String synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception;
        List<Target> targets(Shop shop,String receipt)throws Exception;
        List<RegisteredGtin> catalog(Shop shop);
        List<RegisteredGtin> details(Shop shop,String code)throws Exception;
        void enrich(Shop shop,List<RegisteredGtin> cards);
        void verified(Shop shop,Target target,String gtin);
    }
    private final Operations operations;
    public OzonCatalogMappingService(){this(new LocalOperations());}
    OzonCatalogMappingService(Operations operations){this.operations=Objects.requireNonNull(operations);}

    public Result synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");
        Objects.requireNonNull(progress);
        String scope=operations.scope(shop);
        String receipt=operations.synchronize(shop,progress);requireScope(shop,scope);
        var targets=operations.targets(shop,receipt);requireScope(shop,scope);
        if(targets.size()>100000)throw new IllegalStateException("SYNC_REPORT_LIMIT");
        var catalog=new HashMap<String,RegisteredGtin>();merge(catalog,operations.catalog(shop));
        var needed=new LinkedHashSet<String>();
        for(var target:targets){
            requireTarget(shop,target);
            if(target.exempt())continue;
            for(String code:codes(target.row())){
                var card=catalog.get(code);
                if(card==null||!scope.equals(card.source())||card.details().documents().stream().noneMatch(OzonCatalogMappingService::activeDocument))needed.add(code);
            }
        }
        var failures=new HashMap<String,String>();var enriched=new ArrayList<RegisteredGtin>();int done=0;
        for(String code:needed){
            requireScope(shop,scope);
            progress.accept(new NationalCatalogGtinSource.Progress("READING_DOCUMENTS",done++,needed.size()));
            try{enriched.addAll(operations.details(shop,code));}
            catch(Exception error){
                if(CatalogFeedback.rateLimited(error)||error instanceof InterruptedException
                        ||error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException)
                        ||error instanceof SecurityException)throw error;
                failures.put(code,SyncFailure.of(error,0).code());
            }
        }
        requireScope(shop,scope);operations.enrich(shop,enriched);merge(catalog,enriched);
        var duplicate=duplicates(targets);var issues=new ArrayList<Issue>();int mapped=0,exempt=0;
        for(var target:targets){
            requireScope(shop,scope);
            if(target.exempt()){exempt++;continue;}
            var row=target.row();var codes=codes(row);
            var usable=codes.stream().filter(code->!failures.containsKey(code))
                    .filter(code->problem(target,catalog.get(code),scope,duplicate).isBlank()).toList();
            if(usable.size()==1){
                // Only an observed barcode can create a new verified variant-level choice.
                operations.verified(shop,target,catalog.get(usable.getFirst()).gtin());mapped++;continue;
            }
            if(usable.size()>1){issues.add(issue(target,String.join(", ",usable),"GTIN_AMBIGUOUS"));continue;}
            // Keep a seller's existing manual rule when no contradictory Catalog barcode is present.
            // Its validity is rechecked, but a rule is not promoted into a remote barcode readback.
            var existing=RegisteredGtinValidator.isValid(target.existing())?catalog.get(normalized(target.existing())):null;
            boolean observedCatalogCode=codes.stream().anyMatch(catalog::containsKey);
            if(!observedCatalogCode&&codes.stream().noneMatch(failures::containsKey)&&existing!=null
                    &&!failures.containsKey(normalized(existing.gtin()))){
                String existingProblem=problem(target,existing,scope,duplicate);
                if(existingProblem.isBlank()){mapped++;continue;}
                issues.add(issue(target,existing.gtin(),existingProblem));continue;
            }
            if(codes.isEmpty()){issues.add(issue(target,"","GTIN_BARCODE_MISSING"));continue;}
            String code=codes.stream().filter(catalog::containsKey).findFirst().orElse(codes.iterator().next());
            String reason=failures.containsKey(code)?readFailure(failures.get(code)):problem(target,catalog.get(code),scope,duplicate);
            issues.add(issue(target,code,reason));
        }
        requireScope(shop,scope);
        progress.accept(new NationalCatalogGtinSource.Progress("MAPPING_COMPLETE",mapped+exempt,targets.size()));
        return new Result(mapped,exempt,issues);
    }

    private void requireScope(Shop shop,String expected){
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("SOURCE_SYNC_CANCELLED");
        if(!expected.equals(operations.scope(shop)))throw new SecurityException("CATALOG_OWNER_CHANGED");
    }
    private static void requireTarget(Shop shop,Target target){
        if(target.row().key().shopId()!=shop.getId()||target.row().key().marketplace()!=Marketplace.OZON)
            throw new SecurityException("product_mismatch");
    }
    private static Issue issue(Target target,String code,String reason){return new Issue(target.row().vendorArticle(),target.row().size(),target.sku(),code,reason);}
    private static String readFailure(String code){return Set.of("ZNACK_AUTH_REQUIRED","ZNACK_HTTP_403").contains(code)?"GTIN_ACCESS_DENIED":code;}
    private static void merge(Map<String,RegisteredGtin> catalog,List<RegisteredGtin> cards){for(var card:cards)catalog.put(normalized(card.gtin()),card);}
    private static String normalized(String value){return GtinNormalizer.normalize(value);}
    private static Set<String> codes(ProductRow row){
        var out=new LinkedHashSet<String>();
        for(String code:row.barcodes())if(RegisteredGtinValidator.isValid(code)&&!GtinNormalizer.isTechnicalRange(code))out.add(normalized(code));
        return out;
    }
    private static Set<String> duplicates(List<Target> targets){
        var first=new HashMap<String,ProductRow>();var out=new HashSet<String>();
        for(var target:targets)if(!target.exempt()){
            var relevant=new LinkedHashSet<>(codes(target.row()));
            if(RegisteredGtinValidator.isValid(target.existing()))relevant.add(normalized(target.existing()));
            for(String code:relevant){var previous=first.putIfAbsent(code,target.row());if(previous!=null&&!GtinVariantIdentity.samePhysicalVariant(previous,target.row()))out.add(code);}
        }return out;
    }
    private static String problem(Target target,RegisteredGtin card,String scope,Set<String> duplicate){
        if(target.sku().isBlank())return "OZON_SKU_MISSING";
        if(card==null)return "GTIN_CARD_NOT_FOUND";
        if(!scope.equals(card.source()))return "GTIN_OWNER_NOT_VERIFIED";
        if(card.syncedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))||card.syncedAt().isAfter(Instant.now().plusSeconds(300)))return "GTIN_CATALOG_STALE";
        if(duplicate.contains(normalized(card.gtin())))return "GTIN_MULTIPLE_PRODUCTS";
        if(CatalogCardState.of(card)==CatalogCardState.ERRORS)return "GTIN_CARD_REJECTED";
        if(!card.published())return "GTIN_CARD_UNSIGNED";
        if(!card.tradeUnit())return "GTIN_NOT_TRADE_UNIT";
        var row=target.row();
        if(!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(row),row.russianSize()))return "GTIN_SIZE_MISMATCH";
        if(!OzonSkuGtinMatcher.sameColor(row.color(),card.color()))return "GTIN_COLOR_MISMATCH";
        if(!GtinTnvedIdentity.compatible(row.tnved(),card.details().values("tnved")))return "GTIN_TNVED_GROUP_CHANGED";
        if(card.details().documents().stream().noneMatch(OzonCatalogMappingService::activeDocument))return "GTIN_DOCUMENT_MISSING";
        return "";
    }
    private static boolean activeDocument(CatalogProductData.Document document){
        if(document.number().isBlank()||!Set.of("DECLARATION","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CERTIFICATE_OF_REGISTRATION","CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE").contains(document.type()))return false;
        String status=document.metadata().getOrDefault("registryStatus","");
        if(!status.isBlank()&&!Set.of("active","valid").contains(status.toLowerCase(Locale.ROOT)))return false;
        try{
            var today=LocalDate.now();if(document.from().isBlank()||LocalDate.parse(document.from()).isAfter(today))return false;
            if(!document.until().isBlank()&&LocalDate.parse(document.until()).isBefore(today))return false;
            // The registry explicitly confirms active status even when it omits an expiry date.
            return document.endless()||!document.until().isBlank()||status.equalsIgnoreCase("active")||status.equalsIgnoreCase("valid");
        }catch(java.time.DateTimeException invalid){return false;}
    }

    static final class LocalOperations implements Operations {
        public String scope(Shop shop){var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);return scope.source();}
        public String synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception{
            var receipt=new RegistrationSources(progress).refresh(shop,step->progress.accept(new NationalCatalogGtinSource.Progress(step,0,0)));
            if(!receipt.ready())throw new IllegalStateException(receipt.reason());return receipt.run();
        }
        public List<Target> targets(Shop shop,String receipt)throws Exception{
            ListingIdentity.require(shop);
            var skus=new HashMap<ProductKey,String>();
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id,sku FROM ozon_products WHERE shop_id=? AND archived=0")){
                p.setInt(1,shop.getId());try(var rows=p.executeQuery()){while(rows.next())skus.put(new ProductKey(shop.getId(),Marketplace.OZON,rows.getString(1),rows.getString(2)),Objects.requireNonNullElse(rows.getString(3),""));}
            }
            var existing=new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId());
            var exempt=new OzonProductKizPolicyRepository().findExemptSkus(shop.getId());
            var targets=new ArrayList<Target>();String cursor=null;
            do{
                var page=new ProductRepository().loadPage(new ProductQuery(shop.getId(),Marketplace.OZON,"",List.of(),null,false,cursor,1000),receipt);
                for(var row:page.rows()){
                    String sku=skus.getOrDefault(row.key(),"");targets.add(new Target(row,sku,exempt.contains(sku),existing.getOrDefault(sku,"")));
                }
                if(targets.size()>100000)throw new IllegalStateException("SYNC_REPORT_LIMIT");cursor=page.nextCursor();
            }while(cursor!=null);
            return List.copyOf(targets);
        }
        public List<RegisteredGtin> catalog(Shop shop){return new GtinSyncRepository().catalog(shop.getId(),Marketplace.OZON);}
        public List<RegisteredGtin> details(Shop shop,String code)throws Exception{return NationalCatalogGtinSource.forSelection(shop,Set.of(code),true).read(shop.getId());}
        public void enrich(Shop shop,List<RegisteredGtin> cards){new GtinSyncRepository().mergeCatalog(shop.getId(),Marketplace.OZON,cards);ZnackCatalogSyncService.project(shop,cards);}
        public void verified(Shop shop,Target target,String gtin){
            RegisteredGtinProof.requireCurrent(shop,gtin);
            new GtinSyncRepository().recordVerifiedMapping(new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,target.row().key().cardId(),target.row().key().variantId()),gtin);
        }
    }
}
