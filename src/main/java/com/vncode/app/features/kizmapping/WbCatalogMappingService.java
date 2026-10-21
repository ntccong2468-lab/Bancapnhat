package com.vncode.app.features.kizmapping;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Read WB and Catalog, then record only exact, usable barcode/size readbacks. No seller API writes. */
public final class WbCatalogMappingService {
    public record Issue(String article,String size,String gtin,String reason){}
    public record Result(int mapped,List<Issue> issues){public Result{issues=List.copyOf(issues);}}
    public Result synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception {
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("shop_mismatch");
        String scope=RegisteredGtinProof.current(shop).source();
        var receipt=new RegistrationSources(progress).refresh(shop,step->progress.accept(new NationalCatalogGtinSource.Progress(step,0,0)));
        requireScope(shop,scope);
        if(!receipt.ready())throw new IllegalStateException(receipt.reason());
        var rows=new ArrayList<ProductRow>();String cursor=null;
        do{var page=new ProductRepository().loadPage(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,1000),receipt.run());rows.addAll(page.rows());cursor=page.nextCursor();}while(cursor!=null);
        var repo=new GtinSyncRepository();var catalog=new HashMap<String,RegisteredGtin>();
        for(var card:repo.catalog(shop.getId(),shop.getMarketplace()))catalog.put(GtinNormalizer.normalize(card.gtin()),card);
        var needed=new LinkedHashSet<String>();
        for(var row:rows)for(String code:codes(row)){var card=catalog.get(code);if(card==null||!scope.equals(card.source())||card.details().documents().stream().noneMatch(WbCatalogMappingService::activeDocument))needed.add(code);}
        var readIssues=new HashMap<String,String>();var enriched=new ArrayList<RegisteredGtin>();int done=0;
        // A foreign/absent GTIN must not hide results for the remaining sizes.
        for(String code:needed){
            requireScope(shop,scope);
            progress.accept(new NationalCatalogGtinSource.Progress("READING_DOCUMENTS",done++,needed.size()));
            try{enriched.addAll(NationalCatalogGtinSource.forSelection(shop,Set.of(code),true).read(shop.getId()));}
            catch(Exception error){if(CatalogFeedback.rateLimited(error)||error instanceof InterruptedException
                    ||error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException)
                    ||error instanceof SecurityException)throw error;readIssues.put(code,SyncFailure.of(error,0).code());}
        }
        requireScope(shop,scope);
        repo.mergeCatalog(shop.getId(),shop.getMarketplace(),enriched);ZnackCatalogSyncService.project(shop,enriched);
        enriched.forEach(card->catalog.put(GtinNormalizer.normalize(card.gtin()),card));
        var result=review(rows,catalog,readIssues,scope);var duplicate=duplicates(rows);
        for(var row:rows){
            var allowed=eligible(row,catalog,duplicate,readIssues,scope);
            if(allowed==null)continue;
            requireScope(shop,scope);
            RegisteredGtinProof.requireCurrent(shop,allowed.gtin());
            repo.recordVerifiedMapping(new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),row.key().cardId(),row.key().variantId()),allowed.gtin());
        }
        progress.accept(new NationalCatalogGtinSource.Progress("MAPPING_COMPLETE",result.mapped(),rows.size()));return result;
    }
    static Result review(List<ProductRow> rows,Map<String,RegisteredGtin> catalog,Map<String,String> failures){
        return review(rows,catalog,failures,"");
    }
    static Result review(List<ProductRow> rows,Map<String,RegisteredGtin> catalog,Map<String,String> failures,String scope){
        var duplicate=duplicates(rows);var issues=new ArrayList<Issue>();int mapped=0;
        for(var row:rows){
            var codes=codes(row);if(codes.isEmpty())continue;
            if(eligible(row,catalog,duplicate,failures,scope)!=null){mapped++;continue;}
            String code=codes.iterator().next(),reason;
            var card=catalog.get(code);
            if(codes.stream().anyMatch(duplicate::contains))reason="GTIN_MULTIPLE_SIZES";
            else if(codes.size()>1)reason="GTIN_AMBIGUOUS";
            else if(failures.containsKey(code))reason=Set.of("ZNACK_AUTH_REQUIRED","ZNACK_HTTP_403").contains(failures.get(code))?"GTIN_ACCESS_DENIED":failures.get(code);
            else {reason=problem(row,card,scope);if(reason.isBlank())reason="GTIN_AMBIGUOUS";}
            issues.add(new Issue(row.vendorArticle(),row.size(),code,reason));
        }
        return new Result(mapped,issues);
    }
    private static RegisteredGtin eligible(ProductRow row,Map<String,RegisteredGtin> catalog,Set<String> duplicates,Map<String,String> failures,String scope){
        var codes=codes(row);if(codes.size()!=1)return null;String code=codes.iterator().next();var card=catalog.get(code);
        return duplicates.contains(code)||failures.containsKey(code)||!problem(row,card,scope).isBlank()?null:card;
    }
    private static String problem(ProductRow row,RegisteredGtin card,String scope){
        if(card==null)return "GTIN_CARD_NOT_FOUND";
        if(!scope.isBlank()&&!scope.equals(card.source()))return "GTIN_OWNER_NOT_VERIFIED";
        if(card.syncedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))||card.syncedAt().isAfter(Instant.now().plusSeconds(300)))return "GTIN_CATALOG_STALE";
        if(!card.published())return "GTIN_CARD_UNSIGNED";
        if(!card.tradeUnit())return "GTIN_NOT_TRADE_UNIT";
        if(!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(row),row.russianSize()))return "GTIN_SIZE_MISMATCH";
        if(!OzonSkuGtinMatcher.sameColor(row.color(),card.color()))return "GTIN_COLOR_MISMATCH";
        if(!GtinTnvedIdentity.compatible(row.tnved(),card.details().values("tnved")))return "GTIN_TNVED_GROUP_CHANGED";
        return card.details().documents().stream().anyMatch(WbCatalogMappingService::activeDocument)?"":"GTIN_DOCUMENT_MISSING";
    }
    private static void requireScope(Shop shop,String scope){ListingIdentity.require(shop);if(!scope.equals(RegisteredGtinProof.current(shop).source()))throw new SecurityException("CATALOG_OWNER_CHANGED");}
    private static boolean activeDocument(CatalogProductData.Document document){
        if(document.number().isBlank()||!Set.of("DECLARATION","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CERTIFICATE_OF_REGISTRATION","CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE").contains(document.type()))return false;
        String status=document.metadata().getOrDefault("registryStatus","");
        if(!status.isBlank()&&!Set.of("active","valid").contains(status.toLowerCase(Locale.ROOT)))return false;
        try{var today=LocalDate.now();if(document.from().isBlank()||LocalDate.parse(document.from()).isAfter(today))return false;
            if(!document.until().isBlank()&&LocalDate.parse(document.until()).isBefore(today))return false;
            return document.endless()||!document.until().isBlank()||status.equalsIgnoreCase("active")||status.equalsIgnoreCase("valid");
        }catch(java.time.DateTimeException invalid){return false;}
    }
    private static Set<String> codes(ProductRow row){var out=new LinkedHashSet<String>();for(String value:row.barcodes())if(RegisteredGtinValidator.isValid(value)&&!GtinNormalizer.isTechnicalRange(value))out.add(GtinNormalizer.normalize(value));return out;}
    private static Set<String> duplicates(List<ProductRow> rows){var first=new HashMap<String,ProductRow>();var out=new HashSet<String>();for(var row:rows)for(String code:codes(row)){var previous=first.putIfAbsent(code,row);if(previous!=null&&!GtinVariantIdentity.samePhysicalVariant(previous,row))out.add(code);}return out;}
}
