package com.vncode.app.features.catalogsync;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;

/** Durable attributes/documents → readback → GTIN → readback pipeline, scoped to the original shop. */
public final class CatalogSyncService {
    private static final Object[] LOCKS=new Object[64];
    static{Arrays.setAll(LOCKS,i->new Object());}
    private final CatalogSyncRepository plans;
    private final GtinSyncRepository gtins;
    private final ProductBulkEditService edits;
    private final ProductSnapshotReader reader;
    private final Function<Shop,ListingTargetAdapter> targets;
    private final Function<GtinSyncModels.ProductKey,GtinMarketplaceAdapter> adapters;
    private final BiConsumer<Shop,String> authority;
    private final GtinSyncCoordinator queue;
    @FunctionalInterface public interface SourceRefresh {void refresh(Shop shop,Set<String> gtins)throws Exception;}
    private final SourceRefresh refresh;
    public CatalogSyncService(){
        this(new CatalogSyncRepository(),new GtinSyncRepository(),new ProductBulkEditService(),
            (s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().readForSynchronization(s,k):new WbProductSnapshotReader().readForSynchronization(s,k),
            s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter(),
            k->{var s=new com.vncode.app.features.shop.ShopRepository().findById(k.shopId());if(s==null||s.getMarketplace()!=k.marketplace())throw new SecurityException("shop_mismatch");return k.marketplace()==Marketplace.OZON?new OzonGtinAdapter(s):new WbGtinAdapter(s);},
            RegisteredGtinProof::requireCurrent,(shop,selected)->{
                var observations=com.vncode.app.integration.znack.registration.NationalCatalogGtinSource.forSelection(shop,selected,true).read(shop.getId());
                ListingIdentity.require(shop);new GtinSyncRepository().mergeCatalog(shop.getId(),shop.getMarketplace(),observations);
            });
    }
    public CatalogSyncService(CatalogSyncRepository plans,GtinSyncRepository gtins,ProductBulkEditService edits,
                              ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> targets,
                              Function<GtinSyncModels.ProductKey,GtinMarketplaceAdapter> adapters,BiConsumer<Shop,String> authority){
        this(plans,gtins,edits,reader,targets,adapters,authority,(shop,selected)->{});
    }
    public CatalogSyncService(CatalogSyncRepository plans,GtinSyncRepository gtins,ProductBulkEditService edits,
                              ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> targets,
                              Function<GtinSyncModels.ProductKey,GtinMarketplaceAdapter> adapters,BiConsumer<Shop,String> authority,SourceRefresh refresh){
        this.refresh=refresh;
        this.plans=plans;this.gtins=gtins;this.edits=edits;this.reader=reader;this.targets=targets;this.adapters=adapters;this.authority=authority;
        queue=new GtinSyncCoordinator(gtins,adapters,Clock.systemUTC(),3,Duration.ofSeconds(1));
    }
    public Job preview(Shop shop,List<Selection> selection,Options options)throws Exception {
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->previewActive(shop,selection,options));
    }
    private Job previewActive(Shop shop,List<Selection> selection,Options options)throws Exception {
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.SELLER_API);
        if(selection.isEmpty()||selection.size()>1000||selection.stream().map(Selection::key).distinct().count()!=selection.size())throw new IllegalArgumentException("CATALOG_SELECTION_LIMIT");
        var groups=new LinkedHashMap<String,List<Selection>>();
        for(var item:selection){if(item.key().shopId()!=shop.getId()||item.key().marketplace()!=shop.getMarketplace())throw new SecurityException("shop_mismatch");groups.computeIfAbsent(item.key().productId(),k->new ArrayList<>()).add(item);}
        if(options.fields().contains(Field.DOCUMENTS))refresh.refresh(shop,selection.stream().map(Selection::gtin).collect(java.util.stream.Collectors.toSet()));
        var dictionaries=new HashMap<String,List<TargetAttribute>>();var rows=new ArrayList<Row>();long bytes=0;
        for(var group:groups.values()) {
            var first=group.getFirst();var productKey=productKey(first.key());var fresh=reader.read(shop,productKey);
            var dictionary=dictionaries.get(fresh.categoryId());if(dictionary==null){dictionary=options.fields().equals(Set.of(Field.GTIN))?List.of():List.copyOf(targets.apply(shop).attributes(shop,fresh.categoryId()));dictionaries.put(fresh.categoryId(),dictionary);}
            var patches=new LinkedHashMap<Selection,CatalogSyncMapper.Patch>();var sources=new LinkedHashMap<Selection,GtinSyncModels.RegisteredGtin>();
            for(var item:group){
                var source=current(shop,item.gtin());sources.put(item,source);
                if(options.fields().contains(Field.GTIN)&&!options.fields().contains(Field.TNVED))GtinTnvedIdentity.requireCompatible(fresh,source);
                patches.put(item,new CatalogSyncMapper().map(fresh,withValues(source,options.values().getOrDefault(item.key(),Map.of())),options.fields(),dictionary,options.documents().get(item.key())));
            }
            var base=patches.get(first);
            boolean conflict=patches.values().stream().anyMatch(p->!p.changes().equals(base.changes())||!documentIdentity(p.documents()).equals(documentIdentity(base.documents())));
            boolean groupBlocked=patches.values().stream().anyMatch(CatalogSyncMapper.Patch::blocked);
            ProductBulkEditService.ProductEditPreview patch=null;
            if(!conflict&&!groupBlocked&&base.changed()){
                patch=edits.previewPatch(shop,fresh,base.changes(),base.documents());
                var payload=patch.items().getFirst().preview().preview();bytes+=((long)payload.requestJson().length()+payload.documentsJson().length()+payload.mediaJson().length())*2;
            }
            for(var item:group) {
                var source=sources.get(item);var diffs=new ArrayList<>(patches.get(item).differences());
                var actual=adapters.apply(item.key()).read(item.key());
                if(!actual.key().equals(item.key()))throw new SecurityException("product_mismatch");
                if(options.fields().contains(Field.GTIN)) {
                    String code="";
                    if(!RegisteredGtinProof.contains(actual.barcodes(),source.gtin()))new GtinPreviewService(gtins,adapters).create(item.key(),GtinSyncModels.Operation.ADD,"",source.gtin());else code="CURRENT";
                    diffs.addFirst(new Diff("gtin",actual.barcodes(),List.of(source.gtin()),code,false));
                }
                if(conflict)diffs.add(new Diff("card",List.of(),List.of(),"CARD_VALUES_CONFLICT",true));
                if(groupBlocked)diffs.add(new Diff("card",List.of(),List.of(),"CARD_NOT_READY",true));
                if(patch!=null)for(var issue:patch.items().getFirst().issues())diffs.add(new Diff(issue.field(),List.of(),List.of(),issue.code(),issue.blocking()));
                boolean blocked=diffs.stream().anyMatch(Diff::blocking);
                List<ProductDocument> selectedDocuments=options.fields().contains(Field.DOCUMENTS)?options.documents().getOrDefault(item.key(),source.details().documents().stream().map(d->CatalogSyncMapper.document(d,shop.getMarketplace())).toList()):null;
                var row=new Row(UUID.randomUUID().toString(),item.key(),source,actual.barcodes(),diffs,selectedDocuments,options.values().getOrDefault(item.key(),Map.of()),patch==null?"":patch.id(),"",blocked?State.BLOCKED:State.READY,blocked?diffs.stream().filter(Diff::blocking).map(Diff::code).filter(code->!code.isBlank()).findFirst().orElse("GTIN_VALIDATION_BLOCKED"):"");
                bytes+=(long)ProductJson.GSON.toJson(row).length()*2;if(bytes>64L*1024*1024)throw new IllegalArgumentException("CATALOG_PREVIEW_LIMIT");rows.add(row);
            }
        }
        var job=new Job(UUID.randomUUID().toString(),shop.getId(),shop.getMarketplace().name(),ListingIdentity.fingerprint(shop),options.fields(),Instant.now(),false,false,rows);plans.insert(job);return job;
    }
    /** Persist exact, validated authorization before a background worker can dispatch a write. */
    public Job confirm(Shop shop,String id){
        synchronized(LOCKS[(id.hashCode()&Integer.MAX_VALUE)%LOCKS.length]){
            ListingIdentity.require(shop);LicenseRuntime.require(Permission.SELLER_API);
            var job=plans.read(shop,id);
            if(!job.confirmed()){
                if(job.readyCount()==0)throw new IllegalArgumentException("GTIN_VALIDATION_BLOCKED");
                plans.confirm(shop,id);
            }
            return plans.read(shop,id);
        }
    }
    public Job run(Shop shop,String id,boolean confirmed)throws Exception {
        synchronized(LOCKS[(id.hashCode()&Integer.MAX_VALUE)%LOCKS.length]){
            return ShopOperationCoordinator.withActiveShop(shop.getId(),()->runActive(shop,id,confirmed));
        }
    }
    private Job runActive(Shop shop,String id,boolean confirmed)throws Exception {
        var job=plans.read(shop,id);
        if(!job.confirmed()){if(!confirmed||job.readyCount()==0)throw new IllegalArgumentException("Explicit confirmation required");plans.confirm(shop,id);}
        for(var saved:job.rows()) {
            if(Thread.currentThread().isInterrupted()||plans.paused(shop,id))break;
            if(saved.state()==State.BLOCKED||saved.state()==State.SUCCEEDED)continue;
            Row row=saved;
            try {
                assertSource(shop,row.catalog());LicenseRuntime.require(Permission.SELLER_API);
                if(!row.editJob().isBlank()) {
                    var result=edits.result(shop,row.editJob());
                    row=row.next(State.ATTRIBUTES_PENDING,row.gtinJob(),"");plans.update(shop,id,row);
                    if(result.items().stream().anyMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION))result=edits.apply(shop,row.editJob(),true);
                    if(result.items().stream().anyMatch(i->i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED))result=edits.reconcile(shop,row.editJob());
                    if(result.items().stream().anyMatch(i->i.state()==CopyState.FAILED||i.state()==CopyState.NEEDS_DATA)){
                        plans.update(shop,id,row.next(State.BLOCKED,row.gtinJob(),"ATTRIBUTES_REJECTED"));continue;
                    }
                    if(result.items().stream().anyMatch(i->i.state()!=CopyState.RECONCILED)){
                        plans.update(shop,id,row.next(State.ATTRIBUTES_PENDING,row.gtinJob(),""));continue;
                    }
                }
                if(row.editJob().isBlank()){
                    var fresh=reader.read(shop,productKey(row.key()));if(job.fields().contains(Field.GTIN))GtinTnvedIdentity.requireCompatible(fresh,row.catalog());var patch=new CatalogSyncMapper().map(fresh,withValues(row.catalog(),row.values()),job.fields(),job.fields().equals(Set.of(Field.GTIN))?List.of():targets.apply(shop).attributes(shop,fresh.categoryId()),row.selectedDocuments());
                    if(patch.blocked()||patch.changed())throw new IllegalStateException("TARGET_CHANGED");
                }else if(job.fields().contains(Field.GTIN))GtinTnvedIdentity.requireCompatible(reader.read(shop,productKey(row.key())),row.catalog());
                if(plans.paused(shop,id))continue;
                if(!job.fields().contains(Field.GTIN)){plans.update(shop,id,row.next(State.SUCCEEDED,"",""));continue;}
                assertSource(shop,row.catalog());LicenseRuntime.require(Permission.GTIN);
                var adapter=adapters.apply(row.key());
                if(row.gtinJob().isBlank()) {
                    var actual=adapter.read(row.key());
                    if(row.originalBarcodes().stream().anyMatch(code->!RegisteredGtinProof.contains(actual.barcodes(),code)))throw new IllegalStateException("BARCODES_CHANGED");
                    if(RegisteredGtinProof.contains(actual.barcodes(),row.catalog().gtin())){gtins.recordVerifiedMapping(row.key(),row.catalog().gtin());plans.update(shop,id,row.next(State.SUCCEEDED,"",""));continue;}
                    if(!new HashSet<>(actual.barcodes()).equals(new HashSet<>(row.originalBarcodes())))throw new IllegalStateException("BARCODES_CHANGED");
                    var preview=new GtinPreviewService(gtins,adapters).create(row.key(),GtinSyncModels.Operation.ADD,"",row.catalog().gtin());
                    UUID child=queue.confirm(List.of(preview));row=row.next(State.GTIN_PENDING,child.toString(),"");plans.update(shop,id,row);
                }
                var childId=UUID.fromString(row.gtinJob());
                var childIssues=gtins.jobItems(childId).stream().map(i->gtins.issue(i.id())).filter(x->!x.isBlank()).toList();
                if(gtins.paused(childId)&&childIssues.stream().allMatch(x->x.startsWith("NETWORK_")||x.equals("GTIN_KNOWN_UNSENT")||x.endsWith("RATE_LIMIT")))queue.resume(childId);
                queue.runJob(childId);
                var statuses=gtins.jobItems(UUID.fromString(row.gtinJob()));
                State status=statuses.stream().allMatch(i->i.status()==GtinSyncModels.Status.SUCCEEDED)?State.SUCCEEDED:statuses.stream().anyMatch(i->i.status()==GtinSyncModels.Status.FAILED||i.status()==GtinSyncModels.Status.CANCELLED)?State.BLOCKED:State.GTIN_PENDING;
                String childIssue=statuses.stream().map(i->gtins.issue(i.id())).filter(x->!x.isBlank()).findFirst().orElse("");
                if(gtins.paused(childId)&&!childIssue.isBlank()&&!childIssue.startsWith("NETWORK_")&&!childIssue.endsWith("RATE_LIMIT"))status=State.BLOCKED;
                plans.update(shop,id,row.next(status,row.gtinJob(),childIssue.isBlank()&&status==State.BLOCKED?"GTIN_REJECTED":childIssue));
            } catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){plans.pause(shop,id,true);throw denied;}
            catch(Exception failed){
                String code=failed.getMessage();if(code==null||!code.matches("[A-Z][A-Z0-9_]{1,79}"))code="READBACK_REQUIRED";
                plans.update(shop,id,row.next(row.state()==State.READY?State.BLOCKED:State.RECONCILE_REQUIRED,row.gtinJob(),code));
            }
        }
        return plans.read(shop,id);
    }
    public Job read(Shop shop,String id){return plans.read(shop,id);}
    public List<String> list(Shop shop){return plans.list(shop);}
    public void pause(Shop shop,String id,boolean pause){plans.pause(shop,id,pause);}
    private GtinSyncModels.RegisteredGtin current(Shop shop,String gtin){
        authority.accept(shop,gtin);var row=gtins.catalogItem(shop.getId(),shop.getMarketplace(),gtin);var now=Instant.now();
        if(!RegisteredGtinValidator.isValid(gtin)||!row.tradeUnit()||!row.published()||row.syncedAt().isBefore(now.minus(Duration.ofHours(24)))||row.syncedAt().isAfter(now.plusSeconds(300)))throw new IllegalArgumentException("CATALOG_STALE");return row;
    }
    private void assertSource(Shop shop,GtinSyncModels.RegisteredGtin expected){var actual=current(shop,expected.gtin());if(!actual.source().equals(expected.source())||!actual.details().equals(expected.details())||!actual.article().equals(expected.article())||!actual.color().equals(expected.color())||!actual.size().equals(expected.size()))throw new IllegalArgumentException("CATALOG_CHANGED");}
    private static GtinSyncModels.RegisteredGtin withValues(GtinSyncModels.RegisteredGtin source,Map<String,List<String>> values){
        if(values.isEmpty())return source;var fields=new LinkedHashMap<>(source.details().fields());fields.putAll(values);
        return new GtinSyncModels.RegisteredGtin(source.gtin(),source.article(),source.color(),source.size(),source.tradeUnit(),source.published(),source.source(),source.syncedAt(),new CatalogProductData(fields,source.details().attributes(),source.details().documents()));
    }
    private static String documentIdentity(List<ProductDocument> documents){
        if(documents==null)return "null";
        return ListingIdentity.canonical(ProductJson.GSON.toJsonTree(documents.stream().map(d->List.of(d.type(),d.number(),Objects.toString(d.validFrom(),""),Objects.toString(d.validUntil(),""),Objects.toString(d.file(),""),d.metadata())).toList()));
    }
    private static ProductKey productKey(GtinSyncModels.ProductKey key){return new ProductKey(key.shopId(),key.marketplace(),key.productId(),key.variantId());}
}
