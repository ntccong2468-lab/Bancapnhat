package com.vncode.app.features.kizmapping;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.function.Consumer;

/** Read WB and Catalog, then record only exact, usable barcode/size readbacks. No seller API writes. */
public final class WbCatalogMappingService {
    public record Issue(String article,String size,String gtin,String reason){}
    public record Result(int mapped,List<Issue> issues){public Result{issues=List.copyOf(issues);}}
    public Result synchronize(Shop shop,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception {
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("shop_mismatch");
        var receipt=new RegistrationSources(progress).refresh(shop,step->progress.accept(new NationalCatalogGtinSource.Progress(step,0,0)));
        if(!receipt.ready())throw new IllegalStateException(receipt.reason());
        var rows=new ArrayList<ProductRow>();String cursor=null;
        do{var page=new ProductRepository().loadPage(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,1000),receipt.run());rows.addAll(page.rows());cursor=page.nextCursor();}while(cursor!=null);
        var repo=new GtinSyncRepository();var catalog=new HashMap<String,RegisteredGtin>();
        for(var card:repo.catalog(shop.getId(),shop.getMarketplace()))catalog.put(GtinNormalizer.normalize(card.gtin()),card);
        var needed=new LinkedHashSet<String>();
        for(var row:rows)for(String code:codes(row)){var card=catalog.get(code);if(card==null||card.details().documents().isEmpty())needed.add(code);}
        var readIssues=new HashMap<String,String>();var enriched=new ArrayList<RegisteredGtin>();int done=0;
        // A foreign/absent GTIN must not hide results for the remaining sizes.
        for(String code:needed){
            progress.accept(new NationalCatalogGtinSource.Progress("READING_DOCUMENTS",done++,needed.size()));
            try{enriched.addAll(NationalCatalogGtinSource.forSelection(shop,Set.of(code),true).read(shop.getId()));}
            catch(Exception error){if(CatalogFeedback.rateLimited(error)||error instanceof InterruptedException)throw error;readIssues.put(code,SyncFailure.of(error,0).code());}
        }
        repo.mergeCatalog(shop.getId(),shop.getMarketplace(),enriched);ZnackCatalogSyncService.project(shop,enriched);
        enriched.forEach(card->catalog.put(GtinNormalizer.normalize(card.gtin()),card));
        var result=review(rows,catalog,readIssues);var duplicate=duplicates(rows);var scope=RegisteredGtinProof.current(shop);
        for(var row:rows){
            var allowed=eligible(row,catalog,duplicate,readIssues);
            if(allowed==null)continue;
            ListingIdentity.require(shop);if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new SecurityException("CATALOG_OWNER_CHANGED");
            repo.recordVerifiedMapping(new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),row.key().cardId(),row.key().variantId()),allowed.gtin());
        }
        progress.accept(new NationalCatalogGtinSource.Progress("MAPPING_COMPLETE",result.mapped(),rows.size()));return result;
    }
    static Result review(List<ProductRow> rows,Map<String,RegisteredGtin> catalog,Map<String,String> failures){
        var duplicate=duplicates(rows);var issues=new ArrayList<Issue>();int mapped=0;
        for(var row:rows){
            var codes=codes(row);if(codes.isEmpty())continue;
            if(eligible(row,catalog,duplicate,failures)!=null){mapped++;continue;}
            String code=codes.iterator().next(),reason;
            var card=catalog.get(code);
            if(codes.stream().anyMatch(duplicate::contains))reason="GTIN_MULTIPLE_SIZES";
            else if(codes.size()>1)reason="GTIN_AMBIGUOUS";
            else if(failures.containsKey(code))reason=Set.of("ZNACK_AUTH_REQUIRED","ZNACK_HTTP_403").contains(failures.get(code))?"GTIN_ACCESS_DENIED":failures.get(code);
            else if(card==null)reason="GTIN_CARD_NOT_FOUND";
            else if(!card.published())reason="GTIN_CARD_UNSIGNED";
            else if(!card.tradeUnit())reason="GTIN_NOT_TRADE_UNIT";
            else if(!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(row),row.russianSize()))reason="GTIN_SIZE_MISMATCH";
            else if(card.details().documents().isEmpty())reason="GTIN_DOCUMENT_MISSING";
            else reason="GTIN_AMBIGUOUS";
            issues.add(new Issue(row.vendorArticle(),row.size(),code,reason));
        }
        return new Result(mapped,issues);
    }
    private static RegisteredGtin eligible(ProductRow row,Map<String,RegisteredGtin> catalog,Set<String> duplicates,Map<String,String> failures){
        var codes=codes(row);if(codes.size()!=1)return null;String code=codes.iterator().next();var card=catalog.get(code);
        return duplicates.contains(code)||failures.containsKey(code)||card==null||!card.published()||!card.tradeUnit()||card.details().documents().isEmpty()
                ||!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(row),row.russianSize())?null:card;
    }
    private static Set<String> codes(ProductRow row){var out=new LinkedHashSet<String>();for(String value:row.barcodes())if(RegisteredGtinValidator.isValid(value)&&!GtinNormalizer.isTechnicalRange(value))out.add(GtinNormalizer.normalize(value));return out;}
    private static Set<String> duplicates(List<ProductRow> rows){var sizes=new HashMap<String,Set<String>>();for(var row:rows)for(String code:codes(row))sizes.computeIfAbsent(code,k->new HashSet<>()).add(GtinMatchingService.normalize(GtinVariantIdentity.primarySize(row)));var out=new HashSet<String>();sizes.forEach((code,values)->{if(values.size()>1)out.add(code);});return out;}
}
