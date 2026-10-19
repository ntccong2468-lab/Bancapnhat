package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** Resumable source → exact variant → existing durable Catalog/GTIN write → readback pipeline. */
public final class ZnackAutoSyncService {
    private final SyncRunRepository runs=new SyncRunRepository();
    private final GtinSyncRepository gtins=new GtinSyncRepository();
    private final ProductRepository products=new ProductRepository();
    @FunctionalInterface interface Source {List<RegisteredGtin> read(Shop shop,java.util.function.Consumer<NationalCatalogGtinSource.CardIssue> issues)throws Exception;}
    private final CatalogSyncService catalog;
    private final Source source;
    private final ProductSnapshotReader reader;
    private boolean bulkProduction;
    public ZnackAutoSyncService(){this(false);}
    ZnackAutoSyncService(boolean force){this((shop,issues)->{var result=new ZnackCatalogSyncService().refresh(shop,force,p->{});result.issues().forEach(issues);return result.cards();},
        (shop,key)->shop.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().readForSynchronization(shop,key):new WbProductSnapshotReader().readForSynchronization(shop,key),new CatalogSyncService());bulkProduction=true;}
    ZnackAutoSyncService(Source source,ProductSnapshotReader reader,CatalogSyncService catalog){this.source=Objects.requireNonNull(source);this.reader=Objects.requireNonNull(reader);this.catalog=Objects.requireNonNull(catalog);}
    /** Prepare a fresh, read-only report for an explicit marketplace GTIN review. */
    public String review(Shop shop)throws Exception {return review(shop,"");}
    public String review(Shop shop,String receipt)throws Exception {
        if(!receipt.isBlank()&&!receipt.equals(new RegistrationSources().require(shop).run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
        var page=prepare(shop,null,receipt);
        if(page.checkpoint().isBlank())throw new IllegalStateException("PRODUCT_CATALOG_EMPTY");
        runs.stage(shop,page.checkpoint(),"AWAITING_CONFIRMATION","REVIEW","GTIN_CONFIRMATION_REQUIRED");
        return page.checkpoint();
    }
    public SyncCoordinator.Page step(Shop shop,String checkpoint)throws Exception {
        var active=runs.active(shop,"znack_marketplace");
        if(active.isEmpty())return prepare(shop,null);
        if(runs.find(active.get()).orElseThrow().state().equals("PAUSED"))return SyncCoordinator.Page.complete(0);
        if(runs.step(active.get()).equals("ZNACK_SOURCE"))return prepare(shop,active.get());
        String id=active.get();int processed=0;boolean waiting=false,blocked=false,review=false;SyncFailure retry=null;
        var registeredCandidates=RegistrationMarketplaceConsent.publishedCandidates(shop);
        var seenChildren=new HashSet<String>();
        try(var bulk=bulkProduction?new GtinBulkSession(shop):null){boolean prepared=false;
        for(int offset=0;;offset+=1000){var items=runs.items(id,offset,1000);if(items.isEmpty())break;
            for(var item:items){
                if(Set.of("SUCCEEDED","UNCHANGED","CANCELLED").contains(item.state()))continue;
                if(Set.of("NEEDS_ACTION","FAILED").contains(item.state())){blocked=true;continue;}
                if(item.childJob().isBlank()){
                    if(!registeredCandidates.contains(new RegistrationMarketplaceConsent.Candidate(item.productId(),item.variantId(),item.gtin()))){
                        if(!item.state().equals("AWAITING_CONFIRMATION"))runs.update(shop,item,"AWAITING_CONFIRMATION","REVIEW","GTIN_CONFIRMATION_REQUIRED",null);
                        review=true;continue;
                    }
                    if(processed>=3){waiting=true;continue;}
                    var key=new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());
                    if(!RegistrationMarketplaceConsent.allowed(shop,key,item.gtin())||!RegisteredGtinProof.sameCode(registrationGtin(shop,key),item.gtin())){
                        if(!item.state().equals("AWAITING_CONFIRMATION"))runs.update(shop,item,"AWAITING_CONFIRMATION","REVIEW","GTIN_CONFIRMATION_REQUIRED",null);
                        review=true;continue;
                    }
                    processed++;
                    try{
                        // The original Register action approved only this native card/GTIN.
                        // Fresh source membership, owner, publication and exact variant checks still apply.
                        var selections=new ZnackGtinReviewService(s->{},this,reader).select(shop,id,List.of(item));
                        var plan=catalog.preview(shop,selections,new CatalogSyncModels.Options(Set.of(CatalogSyncModels.Field.GTIN),Map.of()));
                        if(plan.readyCount()==0){
                            String reason=plan.rows().stream().map(CatalogSyncModels.Row::issue).filter(x->!x.isBlank()).findFirst().orElse("GTIN_VALIDATION_BLOCKED");
                            runs.update(shop,item,"NEEDS_ACTION","VALIDATING",reason,null);blocked=true;
                        }else{
                            // Link the durable confirmed child BEFORE the first marketplace write.
                            runs.linkReviewed(shop,id,catalog.confirm(shop,plan.id()));waiting=true;
                        }
                    }catch(Exception error){runs.update(shop,item,"NEEDS_ACTION","VALIDATING",SyncFailure.of(error,0).code(),null);blocked=true;}
                    continue;
                }
                if(!seenChildren.add(item.childJob()))continue;
                if(CatalogSyncService.running(shop,item.childJob())){waiting=true;continue;}
                var members=runs.childItems(shop,id,item.childJob());
                var work=bulk==null?catalog:bulk.catalog;
                if(!work.read(shop,item.childJob()).confirmed()){
                    runs.update(shop,item,"AWAITING_CONFIRMATION","REVIEW","GTIN_CONFIRMATION_REQUIRED",null);review=true;continue;
                }
                if(processed>=3){waiting=true;continue;}
                processed++;
                try{
                    if(bulk!=null&&!prepared){var keys=new ArrayList<GtinSyncModels.ProductKey>();for(int n=0;;n+=1000){var rows=runs.items(id,n,1000);for(var row:rows)if(!row.childJob().isBlank()&&!Set.of("SUCCEEDED","UNCHANGED","CANCELLED","NEEDS_ACTION","FAILED").contains(row.state()))keys.add(new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId()));if(rows.size()<1000)break;}bulk.prepare(keys);prepared=true;}
                    if(members.size()==1)advance(shop,item);else advanceGroup(shop,members,work,bulk==null?reader:bulk::read);
                }catch(Exception e){
                    var failure=SyncFailure.of(e,0);long failures=runs.events(id,item.id(),1000).stream().filter(event->event.error().equals(failure.code())).count();
                    boolean exhausted=failure.actionRequired()||failures>=2;
                    for(var member:members){var current=runs.item(id,member.id()).orElse(member);if(!Set.of("SUCCEEDED","UNCHANGED","CANCELLED").contains(current.state()))runs.update(shop,current,exhausted?"NEEDS_ACTION":"RECONCILING","READBACK",failure.code(),null);}
                    if(!exhausted)retry=failure;
                }
                waiting=true;
            }
            if(items.size()<1000)break;
        }
        }
        if(waiting){runs.stage(shop,id,"WAITING_REMOTE","READBACK",retry==null?"":retry.code());return new SyncCoordinator.Page(id,false,processed,retry==null?new SyncFailure("READBACK_PENDING",Duration.ofSeconds(5),false):retry);}
        runs.stage(shop,id,review?"AWAITING_CONFIRMATION":blocked?"PARTIAL":"SUCCEEDED","REPORT",review?"GTIN_CONFIRMATION_REQUIRED":blocked?"ITEMS_NEED_ACTION":"");
        return SyncCoordinator.Page.complete(processed);
    }
    private SyncCoordinator.Page prepare(Shop shop,String existing)throws Exception {return prepare(shop,existing,"");}
    private SyncCoordinator.Page prepare(Shop shop,String existing,String receipt)throws Exception {
        String id=existing==null?runs.begin(shop,"znack_marketplace","ZNACK_SOURCE"):existing;
        try{
            var issues=new ArrayList<NationalCatalogGtinSource.CardIssue>();
            var source=this.source.read(shop,issues::add);
            ListingIdentity.require(shop);gtins.mergeCatalog(shop.getId(),shop.getMarketplace(),source);
            project(shop,source);
            var buffered=new ArrayList<SyncRunRepository.Item>();
            for(var issue:issues){buffered.add(reportItem(id,"catalog:"+issue.gtin(),"catalog","","","","",issue.gtin(),"NEEDS_ACTION",issue.code(),"{}"));if(buffered.size()==1000){runs.addBatch(shop,id,buffered);buffered.clear();}}
            var index=new GtinCatalogIndex(source);
            String cursor=null;int productCount=0;
            do{
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");
                var page=products.loadPage(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,300),receipt);
                for(var row:page.rows()){
                    productCount++;
                    List<RegisteredGtin> candidates;
                    try{candidates=match(shop,row,index);}catch(IllegalArgumentException changed){buffered.add(reportItem(id,row.key().cardId(),row.key().variantId(),"",row.vendorArticle(),row.color(),row.size(),"","NEEDS_ACTION",SyncFailure.of(changed,0).code(),"{}"));continue;}
                    String error=candidates.size()>1?"GTIN_AMBIGUOUS":candidates.isEmpty()?"ZNACK_MAPPING_UNVERIFIED":"";
                    String sku=sku(shop,row.key());String gtin=candidates.size()==1?candidates.getFirst().gtin():"";
                    var evidence=new com.google.gson.JsonObject();evidence.addProperty("source",candidates.size()==1?candidates.getFirst().source():"");evidence.addProperty("published",candidates.size()==1&&candidates.getFirst().published());evidence.addProperty("title",row.title());
                    if(shop.getMarketplace()==Marketplace.OZON&&candidates.size()==1&&index.attributes(row.vendorArticle(),row.color(),GtinVariantIdentity.primarySize(row),row.russianSize()).isEmpty()&&index.ozon(row).contains(candidates.getFirst()))evidence.addProperty("matchRule","OZON_SELLER_SKU_MODEL_SIZE");
                    buffered.add(reportItem(id,row.key().cardId(),row.key().variantId(),sku,row.vendorArticle(),row.color(),row.size(),gtin,error.isBlank()?"AWAITING_CONFIRMATION":"NEEDS_ACTION",error.isBlank()?"GTIN_CONFIRMATION_REQUIRED":error,evidence.toString()));
                }
                if(!buffered.isEmpty()){runs.addBatch(shop,id,buffered);buffered.clear();}
                cursor=page.nextCursor();
            }while(cursor!=null);
            if(productCount==0){runs.stage(shop,id,"PARTIAL","REPORT","PRODUCT_CATALOG_EMPTY");return new SyncCoordinator.Page("",true,source.size(),new SyncFailure("PRODUCT_CATALOG_EMPTY",Duration.ofMinutes(1),true));}
            runs.stage(shop,id,"RUNNING","MATCHING",issues.isEmpty()?"":"CATALOG_PARTIAL");
            return new SyncCoordinator.Page(id,false,source.size());
        }catch(Exception failure){
            try{runs.stage(shop,id,"FAILED","ZNACK_SOURCE",SyncFailure.of(failure,0).code());}
            catch(RuntimeException reporting){failure.addSuppressed(reporting);}
            throw failure;
        }
    }
    private static SyncRunRepository.Item reportItem(String run,String product,String variant,String sku,String article,String color,String size,String gtin,String state,String error,String evidence){return new SyncRunRepository.Item(UUID.randomUUID().toString(),run,product,variant,sku,article,color,size,gtin,state,"MATCHING",error,"",evidence);}
    private List<RegisteredGtin> match(Shop shop,ProductRow product,GtinCatalogIndex index)throws Exception {
        var matched=candidates(shop,product,index);
        var compatible=matched.stream().filter(card->GtinTnvedIdentity.compatible(product.tnved(),card.details().values("tnved"))).toList();
        if(!matched.isEmpty()&&compatible.isEmpty())throw new IllegalArgumentException("GTIN_TNVED_GROUP_CHANGED");
        return compatible;
    }
    private List<RegisteredGtin> candidates(Shop shop,ProductRow product,GtinCatalogIndex index)throws Exception {
        String nativeGtin=registrationGtin(shop,product.key());
        if(!nativeGtin.isBlank()){
            var exact=index.code(nativeGtin);
            if(exact.size()==1&&compatible(product.color(),exact.getFirst().color())&&GtinVariantIdentity.sizeMatches(exact.getFirst(),GtinVariantIdentity.primarySize(product),product.russianSize()))return exact;
            return List.of();
        }
        var present=index.codes(product.barcodes());if(!present.isEmpty())return present.stream().filter(row->compatible(product.color(),row.color())&&GtinVariantIdentity.sizeMatches(row,GtinVariantIdentity.primarySize(product),product.russianSize())).toList();
        return shop.getMarketplace()==Marketplace.OZON?index.ozon(product):index.attributes(product.vendorArticle(),product.color(),GtinVariantIdentity.primarySize(product),product.russianSize());
    }
    private void advance(Shop shop,SyncRunRepository.Item item)throws Exception {
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());
        RegisteredGtinProof.requireCurrent(shop,item.gtin());
        if(!item.childJob().isBlank()){
            if(!catalog.read(shop,item.childJob()).confirmed())throw new IllegalArgumentException("GTIN_CONFIRMATION_REQUIRED");
            var fresh=reader.read(shop,key);
            requireSkuMatch(shop,item,fresh);
            if(shop.getMarketplace()==Marketplace.OZON&&(!item.sku().equals(fresh.metadata().get("sku"))||item.sku().isBlank()))throw new IllegalArgumentException("OZON_SKU_CHANGED");
            var variant=fresh.variants().stream().filter(v->v.id().equals(item.variantId())).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
            if(!compatible(variant.color(),item.color())||!compatible(GtinVariantIdentity.size(shop.getMarketplace(),variant),item.size()))throw new IllegalArgumentException("PRODUCT_CHANGED");
            var result=catalog.run(shop,item.childJob(),false);
            if(result.rows().stream().allMatch(r->r.state()==CatalogSyncModels.State.SUCCEEDED)){
                completeRegistration(shop,key,item.gtin());runs.update(shop,item,"SUCCEEDED","VERIFIED","",null);
            }else if(result.rows().stream().anyMatch(r->r.state()==CatalogSyncModels.State.BLOCKED)){
                String issue=result.rows().stream().filter(r->r.state()==CatalogSyncModels.State.BLOCKED).map(CatalogSyncModels.Row::issue).filter(x->!x.isBlank()).findFirst().orElse("GTIN_REJECTED");runs.update(shop,item,"NEEDS_ACTION","READBACK",issue,null);
            }else {
                String pendingIssue=result.rows().stream().map(CatalogSyncModels.Row::issue).filter(issue->!issue.isBlank()).findFirst().orElse("");
                if(!pendingIssue.isBlank())throw new java.io.IOException(pendingIssue);
                runs.update(shop,item,"WAITING_REMOTE","READBACK","READBACK_PENDING",null);
            }
            return;
        }
        throw new IllegalArgumentException("GTIN_CONFIRMATION_REQUIRED");
    }
    /** A shared parent is advanced once; one rejected member cannot change a verified sibling's state. */
    private void advanceGroup(Shop shop,List<SyncRunRepository.Item> items,CatalogSyncService work,ProductSnapshotReader productReader)throws Exception {
        if(items.isEmpty())return;var original=work.read(shop,items.getFirst().childJob());if(!original.confirmed())throw new IllegalArgumentException("GTIN_CONFIRMATION_REQUIRED");
        var expected=new HashMap<GtinSyncModels.ProductKey,CatalogSyncModels.Row>();for(var row:original.rows())expected.put(row.key(),row);
        for(var item:items){var key=new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());var row=expected.get(key);if(row==null||row.catalog()==null||!RegisteredGtinProof.sameCode(item.gtin(),row.catalog().gtin()))throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
            // Older multirow plans have no frozen product identity. Preserve the original
            // worker's exact SKU/color/size checks before they can dispatch a queued write.
            if(row.state()!=CatalogSyncModels.State.SUCCEEDED&&row.differences().stream().noneMatch(d->d.field().equals("identity"))){
                var fresh=productReader.read(shop,new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId()));requireSkuMatch(shop,item,fresh);
                if(shop.getMarketplace()==Marketplace.OZON&&(item.sku().isBlank()||!item.sku().equals(fresh.metadata().get("sku"))))throw new IllegalArgumentException("OZON_SKU_CHANGED");
                var variant=fresh.variants().stream().filter(v->v.id().equals(item.variantId())).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
                if(!compatible(variant.color(),item.color())||!compatible(GtinVariantIdentity.size(shop.getMarketplace(),variant),item.size()))throw new IllegalArgumentException("PRODUCT_CHANGED");
            }
        }
        var result=work.run(shop,original.id(),false);var rows=new HashMap<GtinSyncModels.ProductKey,CatalogSyncModels.Row>();for(var row:result.rows())rows.put(row.key(),row);
        var candidates=completionCandidates(shop);var outcomes=new ArrayList<SyncRunRepository.Item>();
        for(var item:items){if(Set.of("SUCCEEDED","UNCHANGED","CANCELLED").contains(item.state()))continue;
            var key=new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());var row=rows.get(new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId()));
            if(row.state()==CatalogSyncModels.State.SUCCEEDED){if(candidates.contains(key))completeRegistration(shop,key,item.gtin());outcomes.add(outcome(item,"SUCCEEDED","VERIFIED",""));}
            else if(row.state()==CatalogSyncModels.State.BLOCKED)outcomes.add(outcome(item,"NEEDS_ACTION","READBACK",row.issue().isBlank()?"GTIN_REJECTED":row.issue()));
            else if(!row.issue().isBlank()){var failure=SyncFailure.of(new java.io.IOException(row.issue()),0);long failures=runs.events(item.runId(),item.id(),1000).stream().filter(e->e.error().equals(failure.code())).count();outcomes.add(outcome(item,failure.actionRequired()||failures>=2?"NEEDS_ACTION":"RECONCILING","READBACK",failure.code()));}
            else outcomes.add(outcome(item,"WAITING_REMOTE","READBACK","READBACK_PENDING"));
        }
        runs.checkpointOutcomes(shop,items.getFirst().runId(),outcomes);
    }
    private static SyncRunRepository.Item outcome(SyncRunRepository.Item item,String state,String step,String issue){return new SyncRunRepository.Item(item.id(),item.runId(),item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),item.gtin(),state,step,issue,item.childJob(),item.evidence());}
    private static Set<ProductKey> completionCandidates(Shop shop)throws java.sql.SQLException {
        var result=new HashSet<ProductKey>();String sql=shop.getMarketplace()==Marketplace.OZON?"SELECT product_id,offer_id FROM ozon_card_registrations WHERE shop_id=? AND status='PUBLISHED' AND active_gtin<>gtin":"SELECT r.nm_id,r.chrt_id FROM znack_card_registrations r JOIN znack_card_registration_replacements x ON x.shop_id=r.shop_id AND x.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.status='PUBLISHED' AND x.state='CANDIDATE'";
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)){p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())result.add(new ProductKey(shop.getId(),shop.getMarketplace(),r.getString(1),r.getString(2)));}}return result;
    }
    private static boolean compatible(String a,String b){return a==null||b==null||a.isBlank()||b.isBlank()||GtinMatchingService.normalize(a).equals(GtinMatchingService.normalize(b));}
    static void requireSkuMatch(Shop shop,SyncRunRepository.Item item,ProductSnapshot fresh){
        if(shop.getMarketplace()!=Marketplace.OZON)return;
        var evidence=com.google.gson.JsonParser.parseString(item.evidence()).getAsJsonObject();
        if(!evidence.has("matchRule")||!"OZON_SELLER_SKU_MODEL_SIZE".equals(evidence.get("matchRule").getAsString()))return;
        var source=new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,item.gtin());
        var variant=fresh.variants().stream().filter(v->v.id().equals(item.variantId())).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
        if(new OzonSkuGtinMatcher(List.of(source)).candidates(item.variantId(),fresh.vendorArticle(),variant.color(),variant.size(),variant.russianSize(),fresh.title(),fresh.categoryId(),fresh.brand()).size()!=1)throw new IllegalArgumentException("OZON_SKU_GTIN_MISMATCH");
    }
    static String sku(Shop shop,ProductKey key)throws Exception{
        ListingIdentity.require(shop,key);
        if(shop.getMarketplace()!=Marketplace.OZON)return "";
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT sku FROM ozon_products WHERE shop_id=? AND product_id=? AND offer_id=? AND archived=0")){
            p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());
            try(var r=p.executeQuery()){return r.next()?Objects.toString(r.getString(1),""):"";}
        }
    }
    private static String registrationGtin(Shop shop,ProductKey key)throws Exception {
        if(shop.getMarketplace()==Marketplace.OZON){
            var row=new OzonCardRegistrationRepository().find(key);
            if(row==null||row.status()!=ZnackCardRegistrationModels.Status.PUBLISHED)return "";
            if(!ListingIdentity.fingerprint(shop).equals(row.owner())||!row.scopeJson().equals(com.vncode.app.features.products.ProductJson.GSON.toJson(ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()))))throw new IllegalArgumentException("ZNACK_REGISTRATION_SCOPE_CHANGED");
            if(!row.sku().equals(sku(shop,key)))throw new IllegalArgumentException("OZON_SKU_CHANGED");
            return row.gtin();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin FROM znack_card_registrations WHERE shop_id=? AND nm_id=? AND chrt_id=? AND status='PUBLISHED'")){
            p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());try(var r=p.executeQuery()){if(!r.next())return "";var repository=new ZnackCardRegistrationRepository();long variant=Long.parseLong(key.variantId());var current=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());if(!ListingIdentity.fingerprint(shop).equals(repository.ownerHash(shop.getId(),variant))||!current.equals(repository.scope(shop.getId(),variant)))throw new IllegalArgumentException("ZNACK_REGISTRATION_SCOPE_CHANGED");return Objects.toString(r.getString(1),"");}
        }
    }
    private static void completeRegistration(Shop shop,ProductKey key,String gtin)throws Exception {
        if(shop.getMarketplace()==Marketplace.OZON){var repo=new OzonCardRegistrationRepository();var row=repo.find(key);if(row!=null&&RegisteredGtinProof.sameCode(row.gtin(),gtin))repo.complete(shop,key,ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()),gtin);}
        else new ZnackCardRegistrationRepository().completeReplacement(shop.getId(),Long.parseLong(key.variantId()),gtin);
    }
    private static void project(Shop shop,List<RegisteredGtin> source){ZnackCatalogSyncService.project(shop,source);}
}
