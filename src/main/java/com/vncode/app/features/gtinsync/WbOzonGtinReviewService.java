package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonShopBrand;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.integration.znack.registration.OzonExistingCardLinkService;
import com.vncode.app.integration.znack.registration.RegistrationReviewedValues;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** Read, review and persist candidate WB/Ozon links. No marketplace mutation occurs here. */
public final class WbOzonGtinReviewService {
    public record Snapshot(List<ProductRow> wbRows, List<WbOzonGtinMatcher.Result> rows,
                           List<String> warnings, List<RegisteredGtin> catalog, Map<ProductKey,WbOzonGtinMatcher.NativeAssociation> associations,
                           List<WbOzonLinkRepository.ReviewStatus> savedLinks,
                           String wbOwner, String ozonOwner, String wbSource, String ozonSource,
                           int wbShopId, int ozonShopId, Instant checkedAt) {
        public Snapshot {
            wbRows=List.copyOf(wbRows);rows=List.copyOf(rows);warnings=List.copyOf(warnings);
            catalog=List.copyOf(catalog);associations=Map.copyOf(associations);savedLinks=List.copyOf(savedLinks);Objects.requireNonNull(checkedAt);
        }
    }
    interface Gateway {
        ProductRefreshService.Report refresh(Shop shop, Consumer<ProductRefreshService.Progress> progress)throws Exception;
        ProductPage page(ProductQuery query)throws Exception;
        List<ProductKey> keys(ProductQuery query)throws Exception;
        List<RegisteredGtin> catalog(Shop ozon, Consumer<NationalCatalogGtinSource.CardIssue> issues)throws Exception;
        ProductSnapshot wb(Shop wb,ProductKey key)throws Exception;
        OzonExistingCardLinkService.Review ozon(Shop ozon,GtinSyncModels.ProductKey key,String gtin)throws Exception;
    }
    private static final class Production implements Gateway {
        private final ProductRepository products=new ProductRepository();
        public ProductRefreshService.Report refresh(Shop s,Consumer<ProductRefreshService.Progress> p)throws Exception {
            return new ProductRefreshService().refresh(s,new ProductQuery(s.getId(),s.getMarketplace(),"",List.of(),null,false,null,300),p);
        }
        public ProductPage page(ProductQuery q){return products.loadPage(q);}
        public List<ProductKey> keys(ProductQuery q){return products.freezeKeys(q);}
        public List<RegisteredGtin> catalog(Shop s,Consumer<NationalCatalogGtinSource.CardIssue> issues)throws Exception {
            return NationalCatalogGtinSource.forShopPartial(s,issues).read(s.getId());
        }
        public ProductSnapshot wb(Shop s,ProductKey k)throws Exception{return new WbProductSnapshotReader().read(s,k);}
        public OzonExistingCardLinkService.Review ozon(Shop s,GtinSyncModels.ProductKey k,String gtin)throws Exception {
            return new OzonExistingCardLinkService().read(s,k,gtin);
        }
    }
    private final Gateway gateway;
    private final WbOzonLinkRepository links;
    private final WbOzonGtinMatcher matcher=new WbOzonGtinMatcher();
    public WbOzonGtinReviewService(){this(new Production(),new WbOzonLinkRepository());}
    WbOzonGtinReviewService(Gateway gateway,WbOzonLinkRepository links){this.gateway=Objects.requireNonNull(gateway);this.links=Objects.requireNonNull(links);}

    public Snapshot scan(Shop wb,Shop ozon,Consumer<String> progress)throws Exception {
        var out=progress==null?(Consumer<String>)s->{}:progress;
        Scope scope=scope(wb,ozon);
        var warnings=new ArrayList<String>();
        refresh(wb,scope,ozon,out,warnings);
        refresh(ozon,scope,wb,out,warnings);
        requireScope(wb,ozon,scope);
        out.accept("CATALOG");
        var issues=new ArrayList<NationalCatalogGtinSource.CardIssue>();
        var catalog=List.copyOf(gateway.catalog(ozon,issues::add));
        if(!issues.isEmpty())warnings.add("CATALOG_PARTIAL:"+issues.size());
        requireScope(wb,ozon,scope);
        RegisteredGtinProof.requireKnownDocumentState(new GtinSyncRepository().catalog(ozon.getId(),Marketplace.OZON),catalog);
        new GtinSyncRepository().mergeCatalog(ozon.getId(),Marketplace.OZON,catalog);
        var wbRows=pages(wb,ozon,scope,out);
        var ozonRows=pages(ozon,wb,scope,out);
        var associations=WbOzonSourceEvidence.associations(wb);
        var matched=matcher.match(wbRows,ozonRows,catalog,scope.ozonSource(),Instant.now(),associations);
        if(!warnings.isEmpty())matched=disableAutomatic(matched);
        requireScope(wb,ozon,scope);
        return new Snapshot(wbRows,matched,warnings,catalog,associations,links.listReviewStatus(wb,ozon),scope.wbOwner(),scope.ozonOwner(),scope.wbSource(),scope.ozonSource(),wb.getId(),ozon.getId(),Instant.now());
    }
    /** Fast local preview: no claim of a complete live source; a full scan is needed before approval. */
    public Snapshot scanCached(Shop wb,Shop ozon,Consumer<String> progress)throws Exception {
        var out=progress==null?(Consumer<String>)s->{}:progress;Scope scope=scope(wb,ozon);
        var catalog=new GtinSyncRepository().catalog(ozon.getId(),Marketplace.OZON);
        var wbRows=pages(wb,ozon,scope,out);var ozonRows=pages(ozon,wb,scope,out);
        var associations=WbOzonSourceEvidence.associations(wb);
        var matched=disableAutomatic(matcher.match(wbRows,ozonRows,catalog,scope.ozonSource(),Instant.now(),associations));
        requireScope(wb,ozon,scope);
        return new Snapshot(wbRows,matched,List.of("CACHED_DATA_NOT_COMPLETE"),catalog,associations,links.listReviewStatus(wb,ozon),scope.wbOwner(),scope.ozonOwner(),scope.wbSource(),scope.ozonSource(),wb.getId(),ozon.getId(),Instant.now());
    }
    private void refresh(Shop shop,Scope scope,Shop other,Consumer<String> progress,List<String> warnings)throws Exception {
        Marketplace market=shop.getMarketplace();
        progress.accept(market.name()+":0");
        var report=gateway.refresh(shop,p->progress.accept(market.name()+":"+p.completed()+"/"+p.total()+":"+p.failed()));
        if(report==null)throw new IOException("PRODUCT_REFRESH_UNAVAILABLE");
        if(report.failed()>0)warnings.add(market.name()+"_PRODUCT_PARTIAL:"+report.failed());
        requireScope(market==Marketplace.WILDBERRIES?shop:other,market==Marketplace.OZON?shop:other,scope);
    }
    private List<ProductRow> pages(Shop shop,Shop other,Scope scope,Consumer<String> progress)throws Exception {
        String cursor=null;Long total=null;var cursors=new HashSet<String>();var keys=new HashSet<ProductKey>();var result=new ArrayList<ProductRow>();
        do {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("WB_OZON_SCAN_CANCELLED");
            var page=gateway.page(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,300));
            if(page==null||total!=null&&page.total()!=total)throw new IOException("PRODUCT_LIST_CHANGED");
            total=page.total();
            for(var row:page.rows()){
                if(row==null||row.key().shopId()!=shop.getId()||row.key().marketplace()!=shop.getMarketplace()||!keys.add(row.key()))throw new IOException("PRODUCT_LIST_INVALID");
                result.add(row);
            }
            if(result.size()>100_000||result.size()>total)throw new IOException("PRODUCT_LIST_LIMIT");
            cursor=page.nextCursor();
            if(cursor!=null&&!cursor.isBlank()&&!cursors.add(cursor))throw new IOException("PRODUCT_CURSOR_REPEATED");
            progress.accept(shop.getMarketplace().name()+"_READ:"+result.size()+"/"+total);
            requireScope(shop.getMarketplace()==Marketplace.WILDBERRIES?shop:other,shop.getMarketplace()==Marketplace.OZON?shop:other,scope);
        } while(cursor!=null&&!cursor.isBlank());
        if(total==null||result.size()!=total)throw new IOException("PRODUCT_LIST_INCOMPLETE");
        var verified=gateway.keys(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,null,300));
        if(verified.size()!=keys.size()||!keys.equals(new HashSet<>(verified)))throw new IOException("PRODUCT_LIST_CHANGED");
        return List.copyOf(result);
    }
    public List<WbOzonGtinMatcher.Candidate> candidatesFor(Snapshot snapshot,ProductRow ozonRow,String wbCardId) {
        return evaluateManual(snapshot,ozonRow,wbCardId).candidates();
    }
    public WbOzonGtinMatcher.Result evaluateManual(Snapshot snapshot,ProductRow ozonRow,String wbCardId) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(ozonRow);
        if(snapshot.rows().stream().noneMatch(r->r.ozon().equals(ozonRow)))throw new IllegalArgumentException("WB_OZON_SNAPSHOT_CHANGED");
        var result=new ArrayList<WbOzonGtinMatcher.Candidate>();var reasons=new LinkedHashSet<String>();
        for(var wb:snapshot.wbRows())if(wb.key().cardId().equals(wbCardId)){
            var evaluated=matcher.evaluatePair(wb,ozonRow,snapshot.catalog(),snapshot.ozonSource(),snapshot.checkedAt(),snapshot.associations().get(wb.key()));
            result.addAll(evaluated.candidates());if(!evaluated.reason().isBlank())reasons.add(evaluated.reason());
        }
        String reason=result.size()>1?"AMBIGUOUS":result.size()==1?"SELLER_REVIEW_REQUIRED":reasons.isEmpty()?"NO_SAFE_MATCH":String.join(", ",reasons);
        return new WbOzonGtinMatcher.Result(ozonRow,result,reason);
    }
    /** Evaluate one WB product against selected Ozon variants using a single Catalog index. */
    public List<WbOzonGtinMatcher.Result> evaluateManualBatch(Snapshot snapshot,List<ProductRow> ozonRows,String wbCardId) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(ozonRows);
        var allowed=new HashSet<ProductRow>();snapshot.rows().forEach(r->allowed.add(r.ozon()));
        if(ozonRows.size()>100_000||ozonRows.stream().anyMatch(r->!allowed.contains(r)))throw new IllegalArgumentException("WB_OZON_SNAPSHOT_CHANGED");
        var variants=snapshot.wbRows().stream().filter(w->w.key().cardId().equals(wbCardId)).toList();
        return matcher.evaluateManualBatch(variants,ozonRows,snapshot.catalog(),snapshot.ozonSource(),snapshot.checkedAt(),snapshot.associations());
    }
    /** Seller explicitly confirms the displayed pairs. Re-reads actual card identity before durable save. */
    public List<WbOzonLinkRepository.Link> approve(Shop wb,Shop ozon,Snapshot snapshot,
                                                   List<WbOzonGtinMatcher.Candidate> chosen,
                                                   Consumer<String> progress)throws Exception {
        return approveWithReport(wb,ozon,snapshot,chosen,progress).links();
    }
    public record ApprovalFailure(WbOzonGtinMatcher.Candidate candidate,String reason) {}
    public record ApprovalReport(List<WbOzonLinkRepository.Link> links,List<ApprovalFailure> failures) {
        public ApprovalReport {links=List.copyOf(links);failures=List.copyOf(failures);}
    }
    public ApprovalReport approveWithReport(Shop wb,Shop ozon,Snapshot snapshot,
                                                   List<WbOzonGtinMatcher.Candidate> chosen,
                                                   Consumer<String> progress)throws Exception {
        Objects.requireNonNull(snapshot);if(chosen==null||chosen.isEmpty()||chosen.size()>100_000)throw new IllegalArgumentException("WB_OZON_LINK_INVALID_BATCH");
        Scope scope=scope(wb,ozon);requireSnapshot(wb,ozon,snapshot,scope);
        var out=progress==null?(Consumer<String>)s->{}:progress;
        var selected=List.copyOf(chosen);var made=new ArrayList<WbOzonLinkRepository.Link>();var failures=new ArrayList<ApprovalFailure>();var destinations=new HashSet<ProductKey>();var gtins=new HashSet<String>();
        var wbSet=new HashSet<>(snapshot.wbRows());var ozonSet=new HashSet<ProductRow>();snapshot.rows().forEach(r->ozonSet.add(r.ozon()));
        var catalogSet=new HashSet<>(snapshot.catalog());
        int processed=0;
        for(var candidate:selected) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("WB_OZON_REVIEW_CANCELLED");
            requireScope(wb,ozon,scope);
            try {
            if(candidate==null||!wbSet.contains(candidate.wb())||!ozonSet.contains(candidate.ozon())||!catalogSet.contains(candidate.catalog()))throw new IllegalArgumentException("WB_OZON_SNAPSHOT_CHANGED");
            if(!snapshot.warnings().isEmpty()&&candidate.evidence()!=WbOzonGtinMatcher.Evidence.MANUAL_PAIR)throw new IllegalArgumentException("WB_OZON_MANUAL_REVIEW_REQUIRED");
            var reevaluated=matcher.evaluatePair(candidate.wb(),candidate.ozon(),List.of(candidate.catalog()),scope.ozonSource(),snapshot.checkedAt(),snapshot.associations().get(candidate.wb().key())).candidates();
            if(reevaluated.stream().noneMatch(c->samePair(c,candidate)))throw new IllegalArgumentException("WB_OZON_REVIEW_CHANGED");
            String gtin=RegisteredGtinProof.canonicalCode(candidate.catalog().gtin());
            if(!destinations.add(candidate.ozon().key())||!gtins.add(gtin))throw new IllegalArgumentException("WB_OZON_LINK_DUPLICATE");
            var wbSnapshot=gateway.wb(wb,candidate.wb().key());
            requireWbCandidate(wb,wbSnapshot,candidate);
            var target=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,candidate.ozon().key().cardId(),candidate.ozon().key().variantId());
            var observed=gateway.ozon(ozon,target,gtin);
            requireOzonCandidate(observed,candidate);
            requireScope(wb,ozon,scope);
            String revision=RegistrationReviewedValues.productRevision(wbSnapshot);
            String evidence=ListingIdentity.hash(String.join("\n",candidate.wb().key().toString(),candidate.ozon().key().toString(),gtin,Long.toString(observed.goodId()),candidate.evidence().name(),revision,observed.revision(),observed.catalogRevision()));
            var link=new WbOzonLinkRepository.Link(wb.getId(),candidate.wb().key().cardId(),candidate.wb().key().variantId(),scope.wbOwner(),ozon.getId(),target.productId(),target.variantId(),observed.sku(),scope.ozonOwner(),gtin,observed.goodId(),scope.wbSource(),scope.ozonSource(),revision,observed.revision(),candidate.evidence().name(),evidence,Instant.now());
            links.save(wb,ozon,List.of(link));made.add(link);
            } catch(SecurityException | com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){throw denied;}
            catch(Exception failure){
                if(failure instanceof InterruptedException||failure instanceof java.io.InterruptedIOException&&!(failure instanceof java.net.SocketTimeoutException)){Thread.currentThread().interrupt();throw failure;}
                var error=com.vncode.app.features.sync.SyncFailure.of(failure,0);
                failures.add(new ApprovalFailure(candidate,error.code()));
                processed++;out.accept("REVIEW:"+processed+"/"+selected.size());
                if(error.code().contains("RATE_LIMIT")||error.code().equals("API_COOLDOWN")){
                    for(int j=processed;j<selected.size();j++)failures.add(new ApprovalFailure(selected.get(j),"API_COOLDOWN"));
                    break;
                }
                continue;
            }
            processed++;out.accept("REVIEW:"+processed+"/"+selected.size());
        }
        requireScope(wb,ozon,scope);return new ApprovalReport(made,failures);
    }
    private static void requireWbCandidate(Shop wb,ProductSnapshot fresh,WbOzonGtinMatcher.Candidate c) {
        var row=c.wb();
        if(fresh==null||!fresh.key().equals(row.key())||!equal(fresh.vendorArticle(),row.vendorArticle())||!equal(fresh.brand(),row.brand())||!equal(fresh.title(),row.title()))throw new IllegalArgumentException("WB_PRODUCT_CHANGED");
        var variants=fresh.variants().stream().filter(v->v.id().equals(row.key().variantId())).toList();
        if(variants.size()!=1)throw new IllegalArgumentException("WB_PRODUCT_CHANGED");
        var v=variants.getFirst();
        if(!OzonSkuGtinMatcher.sameColor(v.color(),row.color())||!equal(v.size(),GtinVariantIdentity.primarySize(row))||!equal(v.russianSize(),row.russianSize()))throw new IllegalArgumentException("WB_PRODUCT_CHANGED");
        long goodId;try{goodId=Long.parseLong(c.catalog().details().single("@catalog_id"));}catch(RuntimeException e){throw new IllegalArgumentException("CATALOG_IDENTITY_CHANGED");}
        if(!RegisteredGtinProof.contains(v.barcodes(),c.catalog().gtin())&&!(v.gtinVerified()&&RegisteredGtinProof.sameCode(v.gtin(),c.catalog().gtin()))
                &&!WbOzonSourceEvidence.matches(wb,row.key(),c.catalog().gtin(),goodId))throw new IllegalArgumentException("WB_GTIN_CHANGED");
        for(String field:List.of("tnved","material","gender")){
            var vals=ProductFieldNames.sourceAttributes(field,fresh.attributes()).stream().flatMap(a->a.values().stream()).filter(x->!x.isBlank()).distinct().toList();
            String expected=switch(field){case "tnved"->row.tnved();case "material"->row.material();default->row.gender();};
            if(!equal(String.join(", ",vals),expected))throw new IllegalArgumentException("WB_PRODUCT_CHANGED");
        }
    }
    private static void requireOzonCandidate(OzonExistingCardLinkService.Review observed,WbOzonGtinMatcher.Candidate c) {
        var row=c.ozon();
        if(observed==null||!RegisteredGtinProof.sameCode(observed.gtin(),c.catalog().gtin())||!Long.toString(observed.goodId()).equals(c.catalog().details().single("@catalog_id"))||!equal(observed.ozonTitle(),row.title())||!equal(observed.ozonArticle(),row.vendorArticle())||!OzonSkuGtinMatcher.sameColor(observed.ozonColor(),row.color())||!equal(observed.ozonSize(),GtinVariantIdentity.primarySize(row))&&!equal(observed.ozonSize(),row.russianSize()))throw new IllegalArgumentException("OZON_PRODUCT_CHANGED");
    }
    private static boolean equal(String a,String b){return GtinMatchingService.normalize(a).equals(GtinMatchingService.normalize(b));}
    private static List<WbOzonGtinMatcher.Result> disableAutomatic(List<WbOzonGtinMatcher.Result> rows){
        return rows.stream().map(r->new WbOzonGtinMatcher.Result(r.ozon(),r.candidates().stream()
                .map(c->new WbOzonGtinMatcher.Candidate(c.wb(),c.ozon(),c.catalog(),c.evidence(),false,c.nativeAssociation())).toList(),
                r.reason().isBlank()?"SELLER_REVIEW_REQUIRED":r.reason())).toList();
    }
    private static boolean samePair(WbOzonGtinMatcher.Candidate a,WbOzonGtinMatcher.Candidate b){return a.wb().equals(b.wb())&&a.ozon().equals(b.ozon())&&a.catalog().equals(b.catalog());}
    private record Scope(String wbOwner,String ozonOwner,String wbSource,String ozonSource){}
    private static Scope scope(Shop wb,Shop ozon) {
        if(wb==null||ozon==null||wb.getMarketplace()!=Marketplace.WILDBERRIES||ozon.getMarketplace()!=Marketplace.OZON||wb.getId()==ozon.getId())throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        ListingIdentity.require(wb);ListingIdentity.require(ozon);
        var l=RegisteredGtinProof.current(wb);var r=RegisteredGtinProof.current(ozon);
        RegisteredGtinProof.requireProduction(l);RegisteredGtinProof.requireProduction(r);
        if(!l.inn().equals(r.inn())||!l.environment().equals(r.environment()))throw new SecurityException("WB_OZON_CATALOG_OWNER_MISMATCH");
        return new Scope(ListingIdentity.fingerprint(wb),ListingIdentity.fingerprint(ozon),l.source(),r.source());
    }
    private static void requireScope(Shop wb,Shop ozon,Scope expected){if(!expected.equals(scope(wb,ozon)))throw new SecurityException("WB_OZON_SOURCE_CHANGED");}
    private static void requireSnapshot(Shop wb,Shop ozon,Snapshot s,Scope scope){
        if(s.wbShopId()!=wb.getId()||s.ozonShopId()!=ozon.getId()||!Objects.equals(s.wbOwner(),scope.wbOwner())||!Objects.equals(s.ozonOwner(),scope.ozonOwner())||!Objects.equals(s.wbSource(),scope.wbSource())||!Objects.equals(s.ozonSource(),scope.ozonSource())||s.checkedAt().isBefore(Instant.now().minusSeconds(86400))||s.checkedAt().isAfter(Instant.now().plusSeconds(300)))throw new SecurityException("WB_OZON_SNAPSHOT_CHANGED");
    }
}
