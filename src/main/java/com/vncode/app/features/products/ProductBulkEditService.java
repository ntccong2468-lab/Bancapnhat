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
        public EditItem {changes=Map.copyOf(changes);documents=documents==null?null:List.copyOf(documents);issues=List.copyOf(issues);before=List.copyOf(before);}
        public EditItem with(CopyState s,RemoteSubmission r,List<CopyIssue> i){return new EditItem(key,changes,documents,preview,r,s,i,before);}
    }
    public record ProductEditPreview(String id,int shopId,String owner,List<ProductKey> keys,ProductBulkEdit edit,List<EditItem> items){
        public ProductEditPreview{keys=List.copyOf(keys);items=List.copyOf(items);}
        public boolean publishable(){return !items.isEmpty()&&items.stream().allMatch(i->i.preview().preview().publishable());}
    }
    public record ProductEditResult(String id,List<EditItem> items,boolean documentsVerified){public ProductEditResult{items=List.copyOf(items);}}
    private final ProductEditRepository repo;private final ProductSnapshotReader reader;private final Function<Shop,ListingTargetAdapter> adapters;
    public ProductBulkEditService(){this(new ProductEditRepository(),(s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(s,k):new WbProductSnapshotReader().read(s,k),s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter());}
    public ProductBulkEditService(ProductEditRepository repo,ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> adapters){this.repo=repo;this.reader=reader;this.adapters=adapters;repo.recoverIncomplete();}
    public ProductEditPreview preview(Shop shop,List<ProductKey> keys,ProductBulkEdit edit)throws IOException {
        ListingIdentity.require(shop);var frozen=keys.stream().distinct().sorted(Comparator.comparing(ProductKey::cardId).thenComparing(ProductKey::variantId)).toList();if(frozen.isEmpty())throw new IllegalArgumentException("Select products");
        var groups=new LinkedHashMap<String,List<ProductKey>>();for(var key:frozen){ListingIdentity.require(shop,key);groups.computeIfAbsent(key.cardId(),k->new ArrayList<>()).add(key);}if(groups.size()>100)throw new IllegalArgumentException("Product edit limit is 100 cards");
        documentBudget(shop,edit.documents());
        LicenseRuntime.require(SELLER_API);
        var items=new ArrayList<EditItem>();var adapter=adapters.apply(shop);var sharedDocuments=new HashMap<String,String>();long retained=0;
        for(var group:groups.values()){
            var values=edit.perKeyValues().getOrDefault(group.getFirst(),edit.values());if(group.stream().anyMatch(k->!values.equals(edit.perKeyValues().getOrDefault(k,edit.values()))))throw new IllegalArgumentException("Conflicting values for sizes of one card");
            LicenseRuntime.require(SELLER_API);
            var fresh=reader.read(shop,group.getFirst());requireFresh(shop,group.getFirst(),fresh);Map<String,List<String>> changes;
            if(edit.field().equals("documents"))changes=Map.of();else if(edit.field().equals("brand"))changes=Map.of("brand",values);else{
                var candidates=adapter.attributes(shop,fresh.categoryId()).stream().filter(a->matches(edit.field(),a.name())&&!a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"))).toList();if(candidates.size()!=1)throw new IllegalStateException("Field dictionary is missing or ambiguous");changes=Map.of("attribute:"+candidates.getFirst().id(),values);
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
        }
        var p=new ProductEditPreview(UUID.randomUUID().toString(),shop.getId(),ListingIdentity.fingerprint(shop),frozen,edit,items);repo.insert(p);return p;
    }
    public ProductEditResult apply(Shop shop,String id,boolean confirmed)throws IOException {
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->applyActive(shop,id,confirmed));
    }
    private ProductEditResult applyActive(Shop shop,String id,boolean confirmed)throws IOException {
        if(!confirmed)throw new IllegalArgumentException("Explicit confirmation required");var approved=owned(shop,id);if(!approved.publishable())throw new IllegalStateException("Edit needs data");var items=repo.items(id);var adapter=adapters.apply(shop);
        // Validate the complete batch before its first write. Submitted cards remain frozen.
        for(var i:items)if(i.state()==CopyState.AWAITING_CONFIRMATION){LicenseRuntime.require(LISTING_WRITE);LicenseRuntime.require(SELLER_API);var fresh=reader.read(shop,i.key());requireFresh(shop,i.key(),fresh);if(!fresh.revision().equals(i.preview().sourceRevision()))throw new IllegalStateException("Product changed; preview again");var current=adapter.previewUpdate(shop,fresh,i.changes(),i.documents());if(!current.preview().publishable()||!current.preview().requestHash().equals(i.preview().preview().requestHash()))throw new IllegalStateException("Fields or documents changed; preview again");}
        for(var i:items)if(i.state()==CopyState.AWAITING_CONFIRMATION){
            LicenseRuntime.require(LISTING_WRITE);
            LicenseRuntime.require(SELLER_API);
            if(!repo.claim(id,i))throw new IllegalStateException("Card has another pending mutation");
            // A preceding card can take minutes. Re-read under this card's durable claim before any write.
            try {
                var fresh=reader.read(shop,i.key());requireFresh(shop,i.key(),fresh);
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
            var unknown=unknown(shop,i);try{var r=adapter.submitUpdate(shop,i.preview());repo.checkpoint(id,i.with(CopyState.REMOTE_PROCESSING,r,List.of()),Set.of(CopyState.SENDING));}catch(IOException|RuntimeException e){repo.checkpoint(id,i.with(CopyState.RECONCILE_REQUIRED,unknown,List.of(new CopyIssue("RECONCILIATION_REQUIRED","submission",true))),Set.of(CopyState.SENDING));}
        }
        return result(shop,id);
    }
    public ProductEditResult reconcile(Shop shop,String id)throws IOException {
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->reconcileActive(shop,id));
    }
    private ProductEditResult reconcileActive(Shop shop,String id)throws IOException {
        owned(shop,id);var adapter=adapters.apply(shop);for(var i:repo.items(id))if(i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED){var r=i.submission()==null?unknown(shop,i):i.submission();var actual=adapter.reconcile(shop,r);var submitted=r.withPhase(r.phase(),actual.identities());if(r.phase().equals("CARD")&&actual.issues().stream().anyMatch(v->v.code().equals("ASSETS_REQUIRED"))){var assets=submitted.withPhase("ASSETS_SENDING",actual.identities());if(!repo.claimAssets(id,i,assets))continue;try{var done=adapter.finishAssets(shop,assets);repo.checkpoint(id,i.with(CopyState.REMOTE_PROCESSING,done,List.of()),Set.of(CopyState.SENDING));}catch(IOException|RuntimeException e){repo.checkpoint(id,i.with(CopyState.RECONCILE_REQUIRED,assets,List.of(new CopyIssue("RECONCILIATION_REQUIRED","documents",true))),Set.of(CopyState.SENDING));}}else if(repo.checkpoint(id,i.with(actual.state(),submitted,actual.issues()),Set.of(CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED))&&actual.state()==CopyState.RECONCILED){
            // Refresh the complete cache through the same verified reader; cache failure cannot undo a known remote outcome.
            try{reader.read(shop,i.key());}catch(IOException|RuntimeException unavailable){/* Explicit Products refresh remains available. */}
        }}return result(shop,id);
    }
    public ProductEditResult result(Shop shop,String id){owned(shop,id);return new ProductEditResult(id,repo.items(id),false);}
    public List<String> list(Shop shop){ListingIdentity.require(shop);return repo.list(shop.getId(),ListingIdentity.fingerprint(shop));}
    private ProductEditPreview owned(Shop s,String id){ListingIdentity.require(s);return repo.find(s.getId(),ListingIdentity.fingerprint(s),id);}
    private static RemoteSubmission unknown(Shop s,EditItem i){var p=i.preview().preview();return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
    private static void requireFresh(Shop s,ProductKey key,ProductSnapshot f){ListingIdentity.require(s,f.key());if(!f.key().cardId().equals(key.cardId())||!f.revision().startsWith("live:"))throw new SecurityException("Fresh own product required");}
    private static boolean matches(String field,String name){String n=Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");return field.equals("material")?Set.of("состав","материал","основнойматериал","материализделия").contains(n):Set.of("тнвэд","кодтнвэд","tnved").contains(n);}
    private static void documentBudget(Shop shop,List<ProductDocument> documents)throws IOException{
        if(documents==null)return;
        if(documents.size()>16)throw new IllegalArgumentException("At most 16 documents per product edit");
        long total=0;var files=new HashSet<Path>();
        for(var d:documents){if(d.file()==null||!"file".equalsIgnoreCase(d.file().getScheme()))continue;Path file=Path.of(d.file()).toRealPath();if(files.add(file)){total=Math.addExact(total,Files.size(file));if(total>10L*1024*1024)throw new IllegalArgumentException("Combined document files exceed 10 MiB");}}
    }
}
