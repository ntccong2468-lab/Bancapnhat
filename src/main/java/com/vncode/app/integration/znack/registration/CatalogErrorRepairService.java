package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/** Explicit correction of existing rejected cards, using the same default and native feed workflow.
 * Preparation is read-only. Apply rechecks both sources, then journals before any POST. */
public final class CatalogErrorRepairService {
    @FunctionalInterface public interface Snapshots {ProductSnapshot read(Shop shop,ProductKey key)throws Exception;}
    @FunctionalInterface public interface Dispatch {void resume(Shop shop,ProductKey key,CatalogSession session,Consumer<Status> progress)throws Exception;}
    public record ErrorCard(String gtin,String article,String color,String size,String reason,List<ProductRow> candidates){public ErrorCard{candidates=List.copyOf(candidates);}}
    public record Proposal(ErrorCard card,ProductSnapshot snapshot,String remoteRevision,long goodId,Draft draft,
                           List<Attribute> required,List<ZnackModels.GoodsDocument> documents,Map<Long,String> before,List<String> issues,CatalogSession session,String owner){
        public Proposal{required=List.copyOf(required);documents=List.copyOf(documents);before=Map.copyOf(before);issues=List.copyOf(issues);}
        @Override public String toString(){return "CatalogRepairProposal[gtin="+card.gtin()+"]";}
    }
    public record Progress(int completed,int total,String gtin,Status status,String detail){}
    private final ZnackCardRegistrationWorkflow.CatalogFactory catalogs;private final Snapshots snapshots;private final Dispatch dispatch;
    public CatalogErrorRepairService(){this(ZnackCardRegistrationWorkflow::productionCatalog,(shop,key)->key.marketplace()==Marketplace.WILDBERRIES?new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(shop,key):new com.vncode.app.integration.ozon.OzonProductSnapshotReader().readForSynchronization(shop,key),CatalogErrorRepairService::resume);}
    public CatalogErrorRepairService(ZnackCardRegistrationWorkflow.CatalogFactory catalogs,Snapshots snapshots,Dispatch dispatch){this.catalogs=catalogs;this.snapshots=snapshots;this.dispatch=dispatch;}
    public List<ErrorCard> report(Shop shop,Set<ProductKey> selected,boolean all)throws Exception {
        var receipt=new RegistrationSources().require(shop);String source=RegisteredGtinProof.source(shop);var products=new ArrayList<ProductRow>();String cursor=null;
        do{var page=new ProductRepository().loadPage(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,1000),receipt.run());products.addAll(page.rows());cursor=page.nextCursor();if(products.size()>100000)throw new IllegalStateException("SYNC_REPORT_LIMIT");}while(cursor!=null);
        var out=new ArrayList<ErrorCard>();
        for(var card:new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace())){
            if(!source.equals(card.source())||!card.tradeUnit()||CatalogCardState.of(card)!=CatalogCardState.ERRORS)continue;
            var matches=candidates(card,products);
            if(all||matches.stream().anyMatch(p->selected.contains(p.key())))out.add(new ErrorCard(card.gtin(),card.article(),card.color(),card.size(),"CATALOG_CARD_ERRORS",matches));
        }
        var byKey=new HashMap<ProductKey,ProductRow>();products.forEach(p->byKey.put(p.key(),p));
        if(shop.getMarketplace()==Marketplace.WILDBERRIES){
            var repo=new ZnackCardRegistrationRepository();
            for(var row:repo.search(new SearchCriteria(shop.getId(),"",List.of(),"ALL",100000,0))){
                var key=new ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()));
                if(!repairError(row.status())||!byKey.containsKey(key)||!all&&!selected.contains(key)||row.hasIdentity()&&!ListingIdentity.fingerprint(shop).equals(repo.ownerHash(shop.getId(),row.chrtId())))continue;
                if(row.hasGtin()&&out.stream().anyMatch(c->RegisteredGtinProof.sameCode(c.gtin(),row.gtin())))continue;
                out.add(new ErrorCard(safe(row.gtin()),row.vendorCode(),row.color(),row.size(),ZnackSanitizer.message(row.errorMessage()),List.of(byKey.get(key))));
            }
        }else for(var row:new OzonCardRegistrationRepository().findMany(new ArrayList<>(byKey.keySet())).values()){
            if(!repairError(row.status())||!all&&!selected.contains(row.key())||!ListingIdentity.fingerprint(shop).equals(row.owner()))continue;
            if(!row.gtin().isBlank()&&out.stream().anyMatch(c->RegisteredGtinProof.sameCode(c.gtin(),row.gtin())))continue;
            var product=byKey.get(row.key());out.add(new ErrorCard(row.gtin(),product.vendorArticle(),product.color(),product.size(),ZnackSanitizer.message(row.error()),List.of(product)));
        }
        return List.copyOf(out);
    }
    private static boolean repairError(Status status){return Set.of(Status.ERROR,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.GTIN_REVIEW_REQUIRED,Status.FEED_REVIEW_REQUIRED).contains(status);}
    /** Exact codes rank first; an incorrect old size may only offer variants of the same article/color. */
    public static List<ProductRow> candidates(GtinSyncModels.RegisteredGtin card,List<ProductRow> products){
        var exact=products.stream().filter(p->RegisteredGtinProof.contains(p.barcodes(),card.gtin())).toList();if(!exact.isEmpty())return exact;
        var compatible=products.stream().filter(p->!GtinMatchingService.normalizeArticle(card.article()).isBlank()&&GtinMatchingService.normalizeArticle(p.vendorArticle()).equals(GtinMatchingService.normalizeArticle(card.article())))
                .filter(p->!card.color().isBlank()&&!p.color().isBlank()&&OzonSkuGtinMatcher.sameColor(card.color(),p.color())).toList();
        var size=compatible.stream().filter(p->GtinVariantIdentity.sizeMatches(card,p.size(),p.russianSize())).toList();return size.isEmpty()?compatible:size;
    }
    public Proposal prepare(Shop shop,ErrorCard card,ProductKey key)throws Exception {
        if(!RegisteredGtinValidator.isValid(card.gtin()))throw new IllegalArgumentException("ZNACK_CARD_NOT_CREATED_RETRY_REGISTRATION");
        ListingIdentity.require(shop,key);new RegistrationSources().requireMember(shop,key.cardId(),key.variantId());
        if(card.candidates().stream().noneMatch(row->row.key().equals(key)))throw new IllegalArgumentException("CATALOG_REPAIR_VARIANT_REQUIRED");
        var session=catalogs.open(shop);session.scope().assertCurrent(shop);
        var raw=session.catalog().rejectedCard(session.token(),card.gtin(),null);var data=CatalogProductDataParser.parse(raw);
        var snapshot=snapshots.read(shop,key);var variant=snapshot.variants().stream().filter(v->v.id().equals(key.variantId())).findFirst().orElseThrow(()->new IllegalStateException("VARIANT_NOT_FOUND"));
        String tnved=single(snapshot,"tnved");if(!tnved.matches("[0-9]{10}"))throw new IllegalArgumentException("ZNACK_MISSING_TNVED");
        if(TnvedGroupChange.changed(data.single("tnved"),tnved))throw new IllegalArgumentException("CATALOG_REPAIR_GROUP_CHANGED_NEW_GTIN_REQUIRED");
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var choices=new LinkedHashSet<>(ZnackRegistrationDocuments.select(snapshot,settings,LocalDate.now()).candidates());
        // A shop default is an explicit alternative in this repair review, never a silent substitution.
        choices.addAll(ZnackRegistrationDocuments.select(null,settings,LocalDate.now()).candidates());
        for(var doc:data.documents())if(!doc.from().isBlank())choices.add(new ZnackModels.GoodsDocument(doc.type().equals("DECLARATION")?"CONFORMITY_DECLARATION":"CONFORMITY_CERTIFICATE",doc.number(),doc.from()));
        var checked=session.catalog().reviewDocuments(session.token(),new ZnackRegistrationDocuments.Selection(List.copyOf(choices),null,ZnackRegistrationDocuments.Source.PRODUCT,true),tnved);
        var preflight=session.catalog().preflightExisting(tnved,session.token());var category=ZnackCategoryResolver.resolve(snapshot,preflight.categories()).orElseThrow(()->new IllegalArgumentException("ZNACK_CATEGORY_UNRESOLVED"));
        var required=session.catalog().requiredAttributes(category.id(),session.token());
        var values=new LinkedHashMap<Long,String>();var types=new LinkedHashMap<Long,String>();
        // Retain category-specific existing facts; replace identity/size/material/permit from current sources.
        for(var a:ZnackProductCardAttributes.from(raw))try{long id=Long.parseLong(ZnackProductCardAttributes.id(a));String value=ZnackProductCardAttributes.text(a,"attr_value");if(!value.isBlank())values.put(id,value);types.put(id,ZnackProductCardAttributes.text(a,"attr_value_type"));}catch(NumberFormatException ignored){}
        var before=Map.copyOf(values);
        values.putAll(OzonCardRegistrationWorkflow.suggestions(snapshot,required));
        var critical=Map.of(2478L,snapshot.title(),2504L,snapshot.brand(),13914L,snapshot.vendorArticle(),13933L,tnved,35L,safe(variant.size()),36L,safe(variant.color()),2483L,single(snapshot,"material"),14013L,single(snapshot,"gender"));
        for(var entry:critical.entrySet()){
            String value=entry.getValue();var schema=required.stream().filter(a->a.id()==entry.getKey()).findFirst().orElse(null);
            if(schema!=null&&schema.presetOnly()){
                var matches=schema.presets().stream().filter(p->normal(p).equals(normal(entry.getValue()))).toList();if(matches.size()==1)value=matches.getFirst();
            }
            values.put(entry.getKey(),value);types.remove(entry.getKey());
        }
        values.remove(23557L);values.remove(23561L);values.remove(23765L);
        if(checked.candidates().size()==1){var document=checked.candidates().getFirst();values.put(RegistrationPermitValidator.attribute(document),document.number()+":::"+document.date());}
        for(var a:required){String type=ZnackWbAttributeMapper.resolveValueType(a,values.getOrDefault(a.id(),""),variant.russianSize());if(type!=null)types.put(a.id(),type);}
        var draft=new Draft(tnved,preflight.categoryTnved(),category.id(),snapshot.title(),snapshot.brand(),values,types).withProductCategory(snapshot.metadata().get("source.category.name"));
        return new Proposal(card,snapshot,revision(raw),raw.get("good_id").getAsLong(),draft,required,checked.candidates(),before,checked.issues(),session,ListingIdentity.fingerprint(shop));
    }
    public static Draft validate(Proposal p,Draft draft){
        if(draft.categoryId()!=p.draft().categoryId()||!draft.tnved().equals(p.draft().tnved())||!draft.feedTnved().equals(p.draft().feedTnved())||draft.goodName().isBlank()||draft.brand().isBlank())throw new IllegalArgumentException("CATALOG_REPAIR_REVIEW_CHANGED");
        for(long id:List.of(13914L,35L,36L,13933L))if(!safe(draft.attributes().get(id)).equals(safe(p.draft().attributes().get(id))))throw new IllegalArgumentException("CATALOG_REPAIR_VARIANT_CHANGED");
        var documents=List.of(23557L,23561L).stream().filter(id->!safe(draft.attributes().get(id)).isBlank()).toList();
        if(documents.size()!=1)throw new IllegalArgumentException("ZNACK_MISSING_PRODUCT_DOCUMENT");
        var parts=draft.attributes().get(documents.getFirst()).split(":::",2);if(parts.length!=2||parts[0].isBlank())throw new IllegalArgumentException("ZNACK_MISSING_PRODUCT_DOCUMENT");
        LocalDate.parse(parts[1]); // Manually entered permits are registry-checked again before the checkpoint.
        for(var a:p.required()){
            if(a.id()==3959||a.id()==13933&&draft.feedTnved().length()==10||Set.of(23557L,23561L).contains(a.id()))continue;
            String value=draft.attributes().getOrDefault(a.id(),"");String type=draft.attributeTypes().get(a.id());
            if(value.isBlank()||value.length()>5000||a.presetOnly()&&!a.presets().contains(value)||!a.valueTypes().isEmpty()&&(type==null||!a.valueTypes().contains(type)))throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING: "+a.name());
        }
        return draft;
    }
    public String apply(Shop shop,List<Proposal> proposals,List<Draft> drafts,Consumer<Progress> listener)throws Exception {
        if(proposals.isEmpty()||proposals.size()!=drafts.size()||proposals.size()>1000)throw new IllegalArgumentException("CATALOG_REPAIR_SELECTION_LIMIT");
        var keys=new HashSet<ProductKey>();var gtins=new HashSet<String>();for(int i=0;i<proposals.size();i++){validate(proposals.get(i),drafts.get(i));if(!keys.add(proposals.get(i).snapshot().key())||!gtins.add(proposals.get(i).card().gtin()))throw new IllegalArgumentException("CATALOG_REPAIR_DUPLICATE_VARIANT");}
        var reports=new SyncRunRepository();String run=reports.begin(shop,"catalog_repair","READBACK");int completed=0;
        var staged=new ArrayList<SyncRunRepository.Item>();for(var p:proposals){var key=p.snapshot().key();staged.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,key.cardId(),key.variantId(),p.snapshot().metadata().getOrDefault("sku",""),p.card().article(),p.card().color(),p.card().size(),p.card().gtin(),"QUEUED","READBACK","","","{}"));}reports.addBatch(shop,run,staged);
        for(int i=0;i<proposals.size();i++){
            var p=proposals.get(i);var draft=drafts.get(i);var key=p.snapshot().key();
            var item=staged.get(i);
            try{
                ListingIdentity.require(shop,key);new RegistrationSources().requireMember(shop,key.cardId(),key.variantId());p.session().scope().assertCurrent(shop);
                if(!p.owner().equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
                var current=snapshots.read(shop,key);if(!productRevision(current).equals(productRevision(p.snapshot())))throw new IllegalStateException("PRODUCT_REVIEW_CHANGED");
                var raw=p.session().catalog().rejectedCard(p.session().token(),p.card().gtin(),p.goodId());if(!revision(raw).equals(p.remoteRevision()))throw new IllegalStateException("CATALOG_REPAIR_REVIEW_CHANGED");
                p.session().catalog().validateDocuments(p.session().token(),draft);
                com.vncode.app.features.license.LicenseRuntime.require(com.vncode.app.features.license.LicenseModels.Permission.GTIN);
                var payload=ZnackNationalCatalogService.buildPayload(p.card().gtin(),draft,p.snapshot().media().isEmpty()?"":p.snapshot().media().getFirst().uri().toString());
                if(!CatalogRepairCheckpoint.prepare(shop,key,current.metadata().getOrDefault("sku",""),p.card().gtin(),p.goodId(),payload.toString(),draft,p.session().scope()))throw new IllegalStateException("CATALOG_REPAIR_CHECKPOINT_BUSY");
                final int done=completed;final Status[] state={Status.READY_TO_SUBMIT};
                dispatch.resume(shop,key,p.session(),s->{state[0]=s;listener.accept(new Progress(done,proposals.size(),p.card().gtin(),s,""));});
                String nativeFeed=key.marketplace()==Marketplace.WILDBERRIES?new ZnackCardRegistrationRepository().find(shop.getId(),Long.parseLong(key.variantId())).feedId():new OzonCardRegistrationRepository().find(key).feedId();
                var evidence=new JsonObject();evidence.addProperty("gtin",p.card().gtin());evidence.addProperty("goodId",p.goodId());evidence.addProperty("feedId",nativeFeed);evidence.addProperty("template",RegistrationTemplate.defaults().id());
                String outcome=state[0]==Status.PUBLISHED?"SUCCEEDED":Set.of(Status.ERROR,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.FEED_REVIEW_REQUIRED).contains(state[0])?"NEEDS_ACTION":"WAITING_REMOTE";
                String nativeError=key.marketplace()==Marketplace.WILDBERRIES?new ZnackCardRegistrationRepository().find(shop.getId(),Long.parseLong(key.variantId())).errorMessage():new OzonCardRegistrationRepository().find(key).error();
                evidence.addProperty("reason",ZnackSanitizer.message(nativeError));
                reports.registrationProgress(shop,item,p.card().gtin(),outcome,state[0].name(),outcome.equals("NEEDS_ACTION")?"ZNACK_REGISTRATION_NEEDS_ACTION":"",evidence.toString());
                listener.accept(new Progress(++completed,proposals.size(),p.card().gtin(),state[0],nativeFeed==null?"":nativeFeed));
            }catch(Exception error){var evidence=new JsonObject();evidence.addProperty("reason",ZnackSanitizer.message(error.getMessage()));reports.registrationProgress(shop,item,p.card().gtin(),"NEEDS_ACTION","READBACK",error.getMessage(),evidence.toString());listener.accept(new Progress(++completed,proposals.size(),p.card().gtin(),Status.ERROR,ZnackSanitizer.message(error.getMessage())));if(error instanceof InterruptedException){Thread.currentThread().interrupt();break;}}
        }
        finish(shop,reports,run);new SyncStreamStore().request(shop,"ZNACK");return run;
    }
    private static void resume(Shop shop,ProductKey key,CatalogSession session,Consumer<Status> progress)throws Exception {
        if(key.marketplace()==Marketplace.WILDBERRIES){var workflow=new ZnackCardRegistrationWorkflow(new ZnackCardRegistrationRepository());var sku=new ZnackCardRegistrationRepository().find(shop.getId(),Long.parseLong(key.variantId()));if(!workflow.resume(shop,sku,false,(s,d)->progress.accept(s)))throw new IllegalStateException("CATALOG_REPAIR_CHECKPOINT_BUSY");workflow.completion(shop,sku).toCompletableFuture().get(5,java.util.concurrent.TimeUnit.MINUTES);}
        else{var workflow=new OzonCardRegistrationWorkflow();if(!workflow.resume(shop,key,false,(s,d)->progress.accept(s)))throw new IllegalStateException("CATALOG_REPAIR_CHECKPOINT_BUSY");workflow.completion(shop).toCompletableFuture().get(5,java.util.concurrent.TimeUnit.MINUTES);}
    }
    public static void refresh(Shop shop,SyncRunRepository.Run run,List<SyncRunRepository.Item> items){
        if(!"catalog_repair".equals(run.kind()))return;var reports=new SyncRunRepository();reports.assertOwner(shop,run.id());
        for(var item:items){if(!"WAITING_REMOTE".equals(item.state()))continue;String feed,gtin,error;Status status;Long good;
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){var repo=new ZnackCardRegistrationRepository();var row=repo.find(shop.getId(),Long.parseLong(item.variantId()));if(row==null||!ListingIdentity.fingerprint(shop).equals(repo.ownerHash(shop.getId(),row.chrtId())))continue;feed=row.feedId();gtin=row.gtin();status=row.status();error=row.errorMessage();good=row.goodId();}
            else{var row=new OzonCardRegistrationRepository().find(new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId()));if(row==null||!ListingIdentity.fingerprint(shop).equals(row.owner()))continue;feed=row.feedId();gtin=row.gtin();status=row.status();error=row.error();good=row.goodId();}
            var evidence=JsonParser.parseString(item.evidence()).getAsJsonObject();if(!item.gtin().equals(gtin)||!evidence.has("feedId")||evidence.get("feedId").isJsonNull()||!evidence.get("feedId").getAsString().equals(feed))continue;
            String state=status==Status.PUBLISHED?"SUCCEEDED":Set.of(Status.ERROR,Status.PRE_SUBMIT_ERROR,Status.FEED_REVIEW_REQUIRED).contains(status)?"NEEDS_ACTION":"WAITING_REMOTE";
            evidence.addProperty("goodId",good);evidence.addProperty("reason",ZnackSanitizer.message(error));
            reports.registrationProgress(shop,item,gtin,state,status.name(),state.equals("NEEDS_ACTION")?"ZNACK_REGISTRATION_NEEDS_ACTION":"",evidence.toString());
        }finish(shop,reports,run.id());
    }
    private static void finish(Shop shop,SyncRunRepository reports,String run){var counts=reports.counts(run);reports.stage(shop,run,counts.getOrDefault("QUEUED",0L)>0?"RUNNING":counts.getOrDefault("WAITING_REMOTE",0L)>0?"WAITING_REMOTE":counts.getOrDefault("NEEDS_ACTION",0L)>0?"PARTIAL":"SUCCEEDED","READBACK","");}
    private static String single(ProductSnapshot snapshot,String field){var values=ProductFieldNames.sourceAttributes(field,snapshot.attributes()).stream().flatMap(a->a.values().stream()).filter(v->!v.isBlank()).toList();return field.equals("material")?String.join(", ",values):values.stream().distinct().count()==1?values.getFirst():"";}
    private static String productRevision(ProductSnapshot s){return ListingIdentity.hash(ProductJson.GSON.toJson(List.of(s.key(),s.title(),s.brand(),s.vendorArticle(),s.categoryId(),s.attributes(),s.variants(),s.documents())));}
    private static String revision(JsonObject raw){return ListingIdentity.hash(raw.toString());}
    private static String normal(String text){return CatalogProductDataParser.normalize(text);}
    private static String safe(String text){return text==null?"":text.strip();}
}
