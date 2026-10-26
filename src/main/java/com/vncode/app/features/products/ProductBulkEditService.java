package com.vncode.app.features.products;

import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.*;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.nio.file.*;

public final class ProductBulkEditService {
    public record EditItem(ProductKey key,Map<String,List<String>> changes,List<ProductDocument> documents,ListingTargetAdapter.ProductEditPreview preview,RemoteSubmission submission,CopyState state,List<CopyIssue> issues,List<String> before){
        public EditItem {changes=Collections.unmodifiableMap(new TreeMap<>(changes));documents=documents==null?null:List.copyOf(documents);issues=List.copyOf(issues);before=List.copyOf(before);}
        public EditItem with(CopyState s,RemoteSubmission r,List<CopyIssue> i){return new EditItem(key,changes,documents,preview,r,s,i,before);}
    }
    public record ProductEditPreview(String id,int shopId,String owner,List<ProductKey> keys,ProductBulkEdit edit,List<EditItem> items){
        public ProductEditPreview{keys=List.copyOf(keys);items=List.copyOf(items);}
        public boolean publishable(){return !items.isEmpty()&&items.stream().allMatch(i->i.preview().preview().publishable());}
    }
    public record ProductEditResult(String id,List<EditItem> items,boolean documentsVerified){public ProductEditResult{items=List.copyOf(items);}}
    public record Progress(String step,int completed,int total,int succeeded,int failed,int pending){
        public Progress(String step,int completed,int total){this(step,completed,total,0,0,0);}
        public int remaining(){return Math.max(0,total-succeeded-failed);}
    }
    private static void progress(java.util.function.Consumer<Progress> listener,String step,int done,int total){
        try{listener.accept(new Progress(step,done,total));}catch(RuntimeException ignored){/* Progress cannot change a durable mutation. */}
    }
    private static final class ProgressTracker {
        private final java.util.function.Consumer<Progress> listener;
        private final Map<ProductKey,CopyState> states=new HashMap<>();
        private int succeeded,failed,pending;
        ProgressTracker(java.util.function.Consumer<Progress> listener,List<EditItem> items){this.listener=listener;items.forEach(this::update);}
        void update(EditItem item){var previous=states.put(item.key(),item.state());if(previous!=null)count(previous,-1);count(item.state(),1);}
        private void count(CopyState state,int delta){switch(state){case RECONCILED->succeeded+=delta;case FAILED,NEEDS_DATA->failed+=delta;case REMOTE_PROCESSING,RECONCILE_REQUIRED,SENDING->pending+=delta;default->{}}}
        void emit(String step,int done){try{listener.accept(new Progress(step,done,states.size(),succeeded,failed,pending));}catch(RuntimeException ignored){}}
    }
    @FunctionalInterface public interface ApiSessions {ProductEditApiSession open(Shop shop,List<ProductKey> keys)throws IOException;}
    @FunctionalInterface private interface ApiWork<T> {T run()throws IOException;}
    private final ProductEditRepository repo;private final ApiSessions sessions;
    private final ThreadLocal<ProductEditApiSession> currentSession=new ThreadLocal<>();
    public ProductBulkEditService(){this(new ProductEditRepository(),ProductEditApiSession::open);}
    public ProductBulkEditService(ProductEditRepository repo,ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> adapters){this(repo,(shop,keys)->new ProductEditApiSession(reader,adapters.apply(shop),null));}
    public ProductBulkEditService(ProductEditRepository repo,ApiSessions sessions){this.repo=repo;this.sessions=sessions;}
    private <T>T inSession(Shop shop,List<ProductKey> keys,ApiWork<T> work)throws IOException{
        try(var checks=ListingIdentity.batchChecks(shop);var api=sessions.open(shop,keys)){
            var previous=currentSession.get();currentSession.set(api);try{return work.run();}finally{if(previous==null)currentSession.remove();else currentSession.set(previous);}
        }
    }
    private ProductSnapshot read(Shop shop,ProductKey key)throws IOException{return currentSession.get().reader().read(shop,key);}
    private ListingTargetAdapter adapter(){return currentSession.get().adapter();}
    public ProductEditPreview preview(Shop shop,List<ProductKey> keys,ProductBulkEdit edit)throws IOException {return preview(shop,keys,edit,p->{});}
    public ProductEditPreview preview(Shop shop,List<ProductKey> keys,ProductBulkEdit edit,java.util.function.Consumer<Progress> listener)throws IOException {
        LicenseRuntime.require(SELLER_API);
        return inSession(shop,keys,()->previewLoaded(shop,keys,edit,listener));
    }
    private ProductEditPreview previewLoaded(Shop shop,List<ProductKey> keys,ProductBulkEdit edit,java.util.function.Consumer<Progress> listener)throws IOException {
        if(shop.getMarketplace()==Marketplace.OZON&&!Set.of("documents","title").contains(edit.field()))currentSession.get().useAttributeReads();
        ListingIdentity.require(shop);var frozen=keys.stream().distinct().sorted(Comparator.comparing(ProductKey::cardId).thenComparing(ProductKey::variantId)).toList();if(frozen.isEmpty())throw new IllegalArgumentException("Select products");
        if(!frozen.containsAll(edit.expectedRevisions().keySet()))throw new SecurityException("Foreign product proposal scope");
        var groups=new LinkedHashMap<String,List<ProductKey>>();for(var key:frozen){ListingIdentity.require(shop,key);groups.computeIfAbsent(key.cardId(),k->new ArrayList<>()).add(key);}if(groups.size()>1000)throw new IllegalArgumentException("Product edit limit is 1000 cards");
        documentBudget(shop,edit.documents());
        LicenseRuntime.require(SELLER_API);
        var items=new ArrayList<EditItem>();var adapter=adapter();var sharedDocuments=new HashMap<String,String>();long retained=0;
        progress(listener,"VALIDATING",0,groups.size());
        for(var group:groups.values()){
            var values=edit.perKeyValues().getOrDefault(group.getFirst(),edit.values());if(group.stream().anyMatch(k->!values.equals(edit.perKeyValues().getOrDefault(k,edit.values()))))throw new IllegalArgumentException("Conflicting values for sizes of one card");
            LicenseRuntime.require(SELLER_API);
            var fresh=read(shop,group.getFirst());requireFresh(shop,group.getFirst(),fresh);Map<String,List<String>> changes;
            for(var key:group){String expected=edit.expectedRevisions().get(key);if(expected!=null&&!expected.equals(
                    expected.startsWith("live:proposal:")?ProductSuggestionService.proposalRevision(shop,fresh,edit.field()):fresh.revision()))throw new IllegalStateException("PRODUCT_PROPOSAL_CHANGED");}
            if(edit.field().equals("documents"))changes=Map.of();else if(edit.field().equals("brand"))changes=Map.of("brand",values);else{
                var candidates=ProductFieldNames.targets(edit.field(),adapter.attributes(shop,fresh.categoryId()));if(candidates.size()!=1)throw new IllegalStateException("Field dictionary is missing or ambiguous");changes=Map.of("attribute:"+candidates.getFirst().id(),values);
            }
            List<ProductDocument> documents=edit.documents();if(documents!=null){for(var d:documents)if(d.marketplace()!=shop.getMarketplace())throw new IllegalArgumentException("Foreign document");if(shop.getMarketplace()==Marketplace.WILDBERRIES){var merged=new ArrayList<>(fresh.documents());for(var d:documents){merged.removeIf(old->!d.number().isBlank()&&d.number().equals(old.number())&&d.type().equals(old.type()));merged.add(d);}documents=List.copyOf(merged);}}
            var before=edit.field().equals("brand")?List.of(Objects.toString(fresh.brand(),"")):edit.field().equals("documents")?fresh.documents().stream().map(ProductDocument::number).toList():fresh.attributes().stream().filter(a->changes.containsKey("attribute:"+a.id())).flatMap(a->a.values().stream()).toList();
            var p=adapter.previewUpdate(shop,fresh,changes,documents);var preview=p.preview();
            String hash=ListingIdentity.hash(preview.documentsJson()),shared=sharedDocuments.get(hash);
            if(shared==null){shared=preview.documentsJson();sharedDocuments.put(hash,shared);retained+=(long)shared.length()*2;}
            retained+=((long)preview.requestJson().length()+preview.mediaJson().length()+before.stream().mapToLong(String::length).sum())*2;
            if(retained>64L*1024*1024)throw new IllegalArgumentException("Product preview payload exceeds 64 MiB; select a smaller batch");
            p=new ListingTargetAdapter.ProductEditPreview(p.key(),p.sourceRevision(),ProductEditAssets.withDocuments(preview,shared));
            items.add(new EditItem(group.getFirst(),changes,documents,p,null,p.preview().publishable()?CopyState.AWAITING_CONFIRMATION:CopyState.NEEDS_DATA,p.preview().issues(),before));
            progress(listener,"VALIDATING",items.size(),groups.size());
        }
        var p=new ProductEditPreview(UUID.randomUUID().toString(),shop.getId(),ListingIdentity.fingerprint(shop),frozen,edit,items);repo.insert(p);return p;
    }
    /** A complete, already resolved multi-field card patch for the Catalog sync workflow. */
    public ProductEditPreview previewPatch(Shop shop,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> documents)throws IOException {
        LicenseRuntime.require(SELLER_API);
        return inSession(shop,List.of(fresh.key()),()->previewPatchLoaded(shop,fresh,changes,documents));
    }
    private ProductEditPreview previewPatchLoaded(Shop shop,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> documents)throws IOException {
        ListingIdentity.require(shop);LicenseRuntime.require(SELLER_API);requireFresh(shop,fresh.key(),fresh);
        if(changes.isEmpty()&&documents==null)throw new IllegalArgumentException("Empty product patch");
        changes=Collections.unmodifiableMap(new TreeMap<>(changes));
        documentBudget(shop,documents);
        if(documents!=null){
            for(var d:documents)if(d.marketplace()!=shop.getMarketplace())throw new IllegalArgumentException("Foreign document");
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){
                var merged=new ArrayList<>(fresh.documents());
                for(var d:documents){
                    var existing=merged.stream().filter(old->Objects.equals(old.number(),d.number())&&documentType(old.type()).equals(documentType(d.type()))).findFirst();
                    merged.removeIf(old->Objects.equals(old.number(),d.number())&&documentType(old.type()).equals(documentType(d.type())));
                    merged.add(existing.isPresent()?new ProductDocument(existing.get().id(),d.type(),d.number(),d.validFrom(),d.validUntil(),d.owner(),d.scope(),d.file(),d.marketplace(),d.state(),d.source(),d.checkedAt(),d.metadata()):d);
                }
                documents=List.copyOf(merged);
            }
        }
        var p=adapter().previewUpdate(shop,fresh,changes,documents);
        long bytes=((long)p.preview().requestJson().length()+p.preview().documentsJson().length())*2;
        if(bytes>64L*1024*1024)throw new IllegalArgumentException("Product preview payload exceeds 64 MiB");
        var patchChanges=changes;
        var before=new ArrayList<String>();
        if(patchChanges.containsKey("brand"))before.add(Objects.toString(fresh.brand(),""));
        fresh.attributes().stream().filter(a->patchChanges.containsKey("attribute:"+a.id())).flatMap(a->a.values().stream()).forEach(before::add);
        if(documents!=null)fresh.documents().stream().map(ProductDocument::number).forEach(before::add);
        var item=new EditItem(fresh.key(),changes,documents,p,null,p.preview().publishable()?CopyState.AWAITING_CONFIRMATION:CopyState.NEEDS_DATA,p.preview().issues(),before);
        var result=new ProductEditPreview(UUID.randomUUID().toString(),shop.getId(),ListingIdentity.fingerprint(shop),List.of(fresh.key()),null,List.of(item));repo.insert(result);return result;
    }
    private static String documentType(String type){return switch(type){case "1","certificate","CERTIFICATE_OF_CONFORMITY"->"1";case "2","declaration","DECLARATION"->"2";case "3","CERTIFICATE_OF_REGISTRATION"->"3";default->type;};}
    public ProductEditResult apply(Shop shop,String id,boolean confirmed)throws IOException {return apply(shop,id,confirmed,p->{});}
    public ProductEditResult apply(Shop shop,String id,boolean confirmed,java.util.function.Consumer<Progress> listener)throws IOException {
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->applyActive(shop,id,confirmed,listener));
    }
    private ProductEditResult applyActive(Shop shop,String id,boolean confirmed,java.util.function.Consumer<Progress> listener)throws IOException {
        if(!confirmed)throw new IllegalArgumentException("Explicit confirmation required");
        var approved=owned(shop,id);if(!approved.publishable())throw new IllegalStateException("Edit needs data");if(repo.items(id).stream().noneMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION))return result(shop,id);
        LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);
        return inSession(shop,approved.keys(),()->applyLoaded(shop,id,confirmed,listener));
    }
    private ProductEditResult applyLoaded(Shop shop,String id,boolean confirmed,java.util.function.Consumer<Progress> listener)throws IOException {
        if(!confirmed)throw new IllegalArgumentException("Explicit confirmation required");var approved=owned(shop,id);if(!approved.publishable())throw new IllegalStateException("Edit needs data");var items=repo.items(id);var adapter=adapter();
        if(items.stream().allMatch(i->i.preview().sourceRevision().startsWith("live:attributes:")))currentSession.get().useAttributeReads();
        int sent=0,validated=0;var tracking=new ProgressTracker(listener,items);progress(listener,"VALIDATING",0,items.size());
        // Validate the complete batch before its first write. Submitted cards remain frozen.
        for(var i:items){if(i.state()==CopyState.AWAITING_CONFIRMATION){LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);var fresh=read(shop,i.key());requireFresh(shop,i.key(),fresh);if(!fresh.revision().equals(i.preview().sourceRevision()))throw new IllegalStateException("Product changed; preview again");var current=adapter.previewUpdate(shop,fresh,i.changes(),i.documents());if(!current.preview().publishable()||!current.preview().requestHash().equals(i.preview().preview().requestHash()))throw new IllegalStateException("Fields or documents changed; preview again");}progress(listener,"VALIDATING",++validated,items.size());}
        progress(listener,"SENDING",0,items.size());
        if(adapter instanceof ProductUpdateBatchAdapter batch)return applyBatches(shop,id,approved,items,batch,listener);
        for(var i:items)if(i.state()==CopyState.AWAITING_CONFIRMATION){
            LicenseRuntime.require(LISTING_WRITE);
            LicenseRuntime.require(SELLER_API);
            if(!repo.claim(id,i))throw new IllegalStateException("Card has another pending mutation");
            // A preceding card can take minutes. Re-read under this card's durable claim before any write.
            try {
                var fresh=read(shop,i.key());requireFresh(shop,i.key(),fresh);
                if(!fresh.revision().equals(i.preview().sourceRevision()))throw new IllegalStateException("Product changed; preview again");
                var current=adapter.previewUpdate(shop,fresh,i.changes(),i.documents());
                if(!current.preview().publishable()||!current.preview().requestHash().equals(i.preview().preview().requestHash()))throw new IllegalStateException("Fields or documents changed; preview again");
                LicenseRuntime.require(LISTING_WRITE);
            } catch(LicenseGate.LicenseDeniedException denied) {
                if(!repo.releaseUnsent(id,approved.owner(),i))throw new IllegalStateException("Cannot release unsent product edit",denied);
                throw denied;
            } catch(IOException|RuntimeException unsent) {
                repo.checkpoint(id,i.with(CopyState.NEEDS_DATA,null,List.of(new CopyIssue("PRODUCT_CHANGED","preview",true))),Set.of(CopyState.SENDING));
                throw unsent;
            }
            var unknown=unknown(shop,i);EditItem outcome;
            try{var r=adapter.submitUpdate(shop,i.preview());outcome=i.with(CopyState.REMOTE_PROCESSING,r,List.of());}
            catch(ListingRejectedException rejected){outcome=i.with(CopyState.FAILED,unknown,List.of(new CopyIssue("PRODUCT_UPDATE_REJECTED",rejected.getMessage(),true)));}
            catch(IOException|RuntimeException e){outcome=i.with(CopyState.RECONCILE_REQUIRED,unknown,List.of(new CopyIssue("RECONCILIATION_REQUIRED","submission",true)));}
            if(!repo.checkpoint(id,outcome,Set.of(CopyState.SENDING)))throw new IllegalStateException("Product edit checkpoint changed; reconcile before retrying");tracking.update(outcome);tracking.emit("SENDING",++sent);
        }
        return result(shop,id);
    }
    private ProductEditResult applyBatches(Shop shop,String id,ProductEditPreview approved,List<EditItem> items,ProductUpdateBatchAdapter adapter,java.util.function.Consumer<Progress> listener)throws IOException{
        var awaiting=items.stream().filter(i->i.state()==CopyState.AWAITING_CONFIRMATION).toList();
        var byKey=new HashMap<ProductKey,EditItem>();for(var item:awaiting)byKey.put(item.key(),item);
        int sent=items.size()-awaiting.size();var tracking=new ProgressTracker(listener,items);
        for(var previews:adapter.updateBatches(awaiting.stream().map(EditItem::preview).toList())){
            var batch=previews.stream().map(p->byKey.get(p.key())).toList();
            LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);
            if(!repo.claimBatch(id,batch))throw new IllegalStateException("Card has another pending mutation");
            try{
                currentSession.get().refresh(batch.stream().map(EditItem::key).toList());
                for(var item:batch){
                    var fresh=read(shop,item.key());requireFresh(shop,item.key(),fresh);
                    if(!fresh.revision().equals(item.preview().sourceRevision()))throw new IllegalStateException("Product changed; preview again");
                    var current=adapter.previewUpdate(shop,fresh,item.changes(),item.documents());
                    if(!current.preview().publishable()||!current.preview().requestHash().equals(item.preview().preview().requestHash()))throw new IllegalStateException("Fields or documents changed; preview again");
                }
                LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);
            }catch(LicenseGate.LicenseDeniedException denied){
                checkpointBatch(id,batch.stream().map(i->i.with(CopyState.AWAITING_CONFIRMATION,null,i.issues())).toList());throw denied;
            }catch(IOException|RuntimeException unsent){
                checkpointBatch(id,batch.stream().map(i->i.with(CopyState.NEEDS_DATA,null,List.of(new CopyIssue("PRODUCT_CHANGED","preview",true)))).toList());throw unsent;
            }
            var unknowns=new HashMap<ProductKey,RemoteSubmission>();for(var item:batch)unknowns.put(item.key(),unknown(shop,item));
            List<EditItem> outcomes;
            try{
                var remote=adapter.submitUpdates(shop,previews,()->{ListingIdentity.require(shop);LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);});
                var completed=new ArrayList<EditItem>();
                for(var item:batch){var result=remote.get(item.key());
                    if(result==null)completed.add(item.with(CopyState.RECONCILE_REQUIRED,unknowns.get(item.key()),List.of(new CopyIssue("RECONCILIATION_REQUIRED","submission",true))));
                    else if(result.rejection()!=null)completed.add(item.with(CopyState.FAILED,unknowns.get(item.key()),List.of(new CopyIssue("PRODUCT_UPDATE_REJECTED",result.rejection(),true))));
                    else completed.add(item.with(CopyState.REMOTE_PROCESSING,result.submission(),List.of()));
                }outcomes=List.copyOf(completed);
            }catch(LicenseGate.LicenseDeniedException denied){
                checkpointBatch(id,batch.stream().map(i->i.with(CopyState.AWAITING_CONFIRMATION,null,i.issues())).toList());throw denied;
            }catch(ListingRateLimitException limited){
                // HTTP 429 is a known rejection. Keep this batch retryable instead
                // of polling forever for a request the marketplace never accepted.
                checkpointBatch(id,batch.stream().map(i->i.with(CopyState.AWAITING_CONFIRMATION,null,List.of(new CopyIssue("MARKETPLACE_RATE_LIMIT","submission",true)))).toList());throw limited;
            }catch(ListingRejectedException rejected){
                outcomes=batch.stream().map(i->i.with(CopyState.FAILED,unknowns.get(i.key()),List.of(new CopyIssue("PRODUCT_UPDATE_REJECTED",rejected.getMessage(),true)))).toList();
            }catch(IOException|RuntimeException ambiguous){
                outcomes=batch.stream().map(i->i.with(CopyState.RECONCILE_REQUIRED,unknowns.get(i.key()),List.of(new CopyIssue("RECONCILIATION_REQUIRED","submission",true)))).toList();
            }
            checkpointBatch(id,outcomes);outcomes.forEach(tracking::update);sent+=batch.size();tracking.emit("SENDING",sent);
        }
        return result(shop,id);
    }
    private void checkpointBatch(String id,List<EditItem> items){if(!repo.checkpointBatch(id,items,Set.of(CopyState.SENDING)))throw new IllegalStateException("Product edit checkpoint changed; reconcile before retrying");}
    public ProductEditResult reconcile(Shop shop,String id)throws IOException {return reconcile(shop,id,p->{});}
    public ProductEditResult reconcile(Shop shop,String id,java.util.function.Consumer<Progress> listener)throws IOException {
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->reconcileActive(shop,id,listener));
    }
    private ProductEditResult reconcileActive(Shop shop,String id,java.util.function.Consumer<Progress> listener)throws IOException {
        var approved=owned(shop,id);if(repo.items(id).stream().noneMatch(i->i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED))return result(shop,id);
        return inSession(shop,approved.keys(),()->reconcileLoaded(shop,id,listener));
    }
    private ProductEditResult reconcileLoaded(Shop shop,String id,java.util.function.Consumer<Progress> listener)throws IOException {
        currentSession.get().beginReadback();owned(shop,id);var adapter=adapter();var items=repo.items(id);
        var tracking=new ProgressTracker(listener,items);
        int checked=(int)items.stream().filter(i->i.state()!=CopyState.REMOTE_PROCESSING&&i.state()!=CopyState.RECONCILE_REQUIRED).count();tracking.emit("READBACK",checked);
        for(var i:items)if(i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED){
            var r=i.submission()==null?unknown(shop,i):i.submission();
            if(r.submittedAt()==null&&shop.getMarketplace()==Marketplace.WILDBERRIES){var started=repo.submissionStarted(id,i.key());r=new RemoteSubmission(r.shopId(),r.marketplace(),r.owner(),r.requestHash(),r.taskId(),r.articles(),r.requestJson(),r.mediaJson(),r.documentsJson(),r.phase(),r.identities(),started);}
            var actual=adapter.reconcile(shop,r);var identities=new LinkedHashMap<>(r.identities());
            for(var entry:actual.identities().entrySet()){var prior=identities.putIfAbsent(entry.getKey(),entry.getValue());if(prior!=null&&!prior.equals(entry.getValue()))throw new SecurityException("Product readback identity changed");}
            var submitted=r.withPhase(r.phase(),identities);EditItem outcome;
            if(r.phase().equals("CARD")&&actual.issues().stream().anyMatch(v->v.code().equals("ASSETS_REQUIRED"))){
                LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);var assets=submitted.withPhase("ASSETS_SENDING",submitted.identities());
                if(!repo.claimAssets(id,i,assets))continue;
                try{outcome=i.with(CopyState.REMOTE_PROCESSING,adapter.finishAssets(shop,assets),List.of());}
                catch(ListingRejectedException rejected){outcome=i.with(CopyState.FAILED,assets,List.of(new CopyIssue("MARKETPLACE_REJECTED",ListingRejectedException.safe(shop,rejected.getMessage()),true)));}
                catch(IOException|RuntimeException e){outcome=i.with(CopyState.RECONCILE_REQUIRED,assets,List.of(new CopyIssue("RECONCILIATION_REQUIRED","documents",true)));}
                if(!repo.checkpoint(id,outcome,Set.of(CopyState.SENDING)))throw new IllegalStateException("Product edit checkpoint changed; reconcile before retrying");
            }else{
                outcome=i.with(actual.state(),submitted,actual.issues());
                if(!repo.checkpoint(id,outcome,Set.of(CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED)))throw new IllegalStateException("Product edit checkpoint changed; reconcile before retrying");
                if(actual.state()==CopyState.RECONCILED){
                    try{currentSession.get().refreshCache(shop,i.key());}catch(IOException|RuntimeException unavailable){/* A cache failure cannot undo a verified remote result. */}
                }
            }
            tracking.update(outcome);tracking.emit("READBACK",++checked);
        }
        return result(shop,id);
    }
    public ProductEditResult result(Shop shop,String id){owned(shop,id);return new ProductEditResult(id,repo.items(id),false);}
    public List<String> list(Shop shop){ListingIdentity.require(shop);return repo.list(shop.getId(),ListingIdentity.fingerprint(shop));}
    private ProductEditPreview owned(Shop s,String id){ListingIdentity.require(s);return repo.find(s.getId(),ListingIdentity.fingerprint(s),id);}
    private static RemoteSubmission unknown(Shop s,EditItem i){var p=i.preview().preview();return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",s.getMarketplace()==Marketplace.OZON?Map.of(p.articles().getFirst(),i.key().cardId()):Map.of(),java.time.Instant.now());}
    private static void requireFresh(Shop s,ProductKey key,ProductSnapshot f){ListingIdentity.require(s,f.key());if(!f.key().cardId().equals(key.cardId())||!f.revision().startsWith("live:"))throw new SecurityException("Fresh own product required");}
    private static boolean matches(String field,String name){return ProductFieldNames.matches(field,name);}
    private static void documentBudget(Shop shop,List<ProductDocument> documents)throws IOException{
        if(documents==null)return;
        if(documents.size()>16)throw new IllegalArgumentException("At most 16 documents per product edit");
        long total=0;var files=new HashSet<Path>();
        for(var d:documents){if(d.file()==null||!"file".equalsIgnoreCase(d.file().getScheme()))continue;Path file=Path.of(d.file()).toRealPath();if(files.add(file)){total=Math.addExact(total,Files.size(file));if(total>10L*1024*1024)throw new IllegalArgumentException("Combined document files exceed 10 MiB");}}
    }
}
