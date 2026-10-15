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
    public ZnackAutoSyncService(){this((shop,issues)->NationalCatalogGtinSource.forShopPartial(shop,issues).read(shop.getId()),
        (shop,key)->shop.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(shop,key):new WbProductSnapshotReader().read(shop,key),new CatalogSyncService());}
    ZnackAutoSyncService(Source source,ProductSnapshotReader reader,CatalogSyncService catalog){this.source=Objects.requireNonNull(source);this.reader=Objects.requireNonNull(reader);this.catalog=Objects.requireNonNull(catalog);}
    public SyncCoordinator.Page step(Shop shop,String checkpoint)throws Exception {
        var active=runs.active(shop,"znack_marketplace");
        if(active.isEmpty())return prepare(shop,null);
        if(runs.find(active.get()).orElseThrow().state().equals("PAUSED"))return SyncCoordinator.Page.complete(0);
        if(runs.step(active.get()).equals("ZNACK_SOURCE"))return prepare(shop,active.get());
        String id=active.get();int processed=0;boolean waiting=false,blocked=false;
        for(int offset=0;;offset+=1000){var items=runs.items(id,offset,1000);if(items.isEmpty())break;
            for(var item:items){
                if(Set.of("SUCCEEDED","UNCHANGED","CANCELLED").contains(item.state()))continue;
                if(Set.of("NEEDS_ACTION","FAILED").contains(item.state())){blocked=true;continue;}
                if(processed>=3){waiting=true;continue;}
                processed++;
                try{advance(shop,item);}catch(Exception e){var failure=SyncFailure.of(e,0);runs.update(shop,item,failure.actionRequired()?"NEEDS_ACTION":"RECONCILING",item.childJob().isBlank()?"VALIDATING":"READBACK",failure.code(),null);}
                waiting=true;
            }
            if(items.size()<1000)break;
        }
        if(waiting){runs.stage(shop,id,"WAITING_REMOTE","READBACK","");return new SyncCoordinator.Page(id,false,processed);}
        runs.stage(shop,id,blocked?"PARTIAL":"SUCCEEDED","REPORT",blocked?"ITEMS_NEED_ACTION":"");
        return SyncCoordinator.Page.complete(processed);
    }
    private SyncCoordinator.Page prepare(Shop shop,String existing)throws Exception {
        String id=existing==null?runs.begin(shop,"znack_marketplace","ZNACK_SOURCE"):existing;
        try{
            var issues=new ArrayList<NationalCatalogGtinSource.CardIssue>();
            var source=this.source.read(shop,issues::add);
            ListingIdentity.require(shop);gtins.mergeCatalog(shop.getId(),shop.getMarketplace(),source);
            project(shop,source);
            for(var issue:issues)runs.add(shop,id,"catalog:"+issue.gtin(),"catalog","","","","",issue.gtin(),"NEEDS_ACTION",issue.code(),"{}");
            String cursor=null;
            do{
                var page=products.loadPage(new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),null,false,cursor,300));
                for(var row:page.rows()){
                    List<RegisteredGtin> candidates;
                    try{candidates=match(shop,row,source);}catch(IllegalArgumentException changed){runs.add(shop,id,row.key().cardId(),row.key().variantId(),"",row.vendorArticle(),row.color(),row.size(),"","NEEDS_ACTION",SyncFailure.of(changed,0).code(),"{}");continue;}
                    String error=candidates.size()>1?"GTIN_AMBIGUOUS":candidates.isEmpty()?"ZNACK_MAPPING_UNVERIFIED":"";
                    String sku=sku(shop,row.key());String gtin=candidates.size()==1?candidates.getFirst().gtin():"";
                    var evidence=new com.google.gson.JsonObject();evidence.addProperty("source",candidates.size()==1?candidates.getFirst().source():"");evidence.addProperty("published",candidates.size()==1&&candidates.getFirst().published());
                    runs.add(shop,id,row.key().cardId(),row.key().variantId(),sku,row.vendorArticle(),row.color(),row.size(),gtin,error.isBlank()?"QUEUED":"NEEDS_ACTION",error,evidence.toString());
                }
                cursor=page.nextCursor();
            }while(cursor!=null);
            runs.stage(shop,id,"RUNNING","MATCHING",issues.isEmpty()?"":"CATALOG_PARTIAL");
            return new SyncCoordinator.Page(id,false,source.size());
        }catch(Exception failure){runs.stage(shop,id,"FAILED","ZNACK_SOURCE",SyncFailure.of(failure,0).code());throw failure;}
    }
    private List<RegisteredGtin> match(Shop shop,ProductRow product,List<RegisteredGtin> source)throws Exception {
        String nativeGtin=registrationGtin(shop,product.key());
        var eligible=source.stream().filter(g->g.published()&&g.tradeUnit()&&RegisteredGtinValidator.isValid(g.gtin())).toList();
        if(!nativeGtin.isBlank()){
            var exact=eligible.stream().filter(g->RegisteredGtinProof.sameCode(g.gtin(),nativeGtin)).toList();
            if(exact.size()==1&&compatible(product.color(),exact.getFirst().color())&&compatible(product.size(),exact.getFirst().size()))return exact;
            return List.of();
        }
        var present=eligible.stream().filter(g->RegisteredGtinProof.contains(product.barcodes(),g.gtin())).toList();
        if(!present.isEmpty())return present;
        var key=new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),product.key().cardId(),product.key().variantId());
        return new GtinMatchingService().match(ProductFingerprints.snapshot(key,product.vendorArticle(),product.color(),product.size(),product.barcodes()),eligible).candidates();
    }
    private void advance(Shop shop,SyncRunRepository.Item item)throws Exception {
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());
        RegisteredGtinProof.requireCurrent(shop,item.gtin());
        if(!item.childJob().isBlank()){
            var result=catalog.run(shop,item.childJob(),true);
            if(result.rows().stream().allMatch(r->r.state()==CatalogSyncModels.State.SUCCEEDED)){
                completeRegistration(shop,key,item.gtin());runs.update(shop,item,"SUCCEEDED","VERIFIED","",null);
            }else if(result.rows().stream().anyMatch(r->r.state()==CatalogSyncModels.State.BLOCKED)){
                String issue=result.rows().stream().filter(r->r.state()==CatalogSyncModels.State.BLOCKED).map(CatalogSyncModels.Row::issue).filter(x->!x.isBlank()).findFirst().orElse("GTIN_REJECTED");runs.update(shop,item,"NEEDS_ACTION","READBACK",issue,null);
            }else runs.update(shop,item,"WAITING_REMOTE","READBACK","",null);
            return;
        }
        // Fresh detail, not an article-level rule, proves Ozon SKU/size/color before planning.
        var fresh=reader.read(shop,key);
        if(shop.getMarketplace()==Marketplace.OZON&&(!item.sku().equals(fresh.metadata().get("sku"))||item.sku().isBlank()))throw new IllegalArgumentException("OZON_SKU_CHANGED");
        var variant=fresh.variants().stream().filter(v->v.id().equals(item.variantId())).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
        var source=gtins.catalogItem(shop.getId(),shop.getMarketplace(),item.gtin());
        if(!compatible(variant.color(),source.color())||!compatible(variant.size(),source.size()))throw new IllegalArgumentException("GTIN_VARIANT_CONFLICT");
        if(!compatible(variant.color(),item.color())||!compatible(variant.size(),item.size()))throw new IllegalArgumentException("PRODUCT_CHANGED");
        var targetKey=new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),item.productId(),item.variantId());
        var options=new CatalogSyncModels.Options(Set.of(CatalogSyncModels.Field.GTIN),Map.of());
        var plan=catalog.preview(shop,List.of(new CatalogSyncModels.Selection(targetKey,item.gtin())),options);
        if(plan.readyCount()==0){String issue=plan.rows().stream().flatMap(r->r.differences().stream()).filter(CatalogSyncModels.Diff::blocking).map(CatalogSyncModels.Diff::code).findFirst().orElse("GTIN_PLAN_BLOCKED");runs.update(shop,item,"NEEDS_ACTION","VALIDATING",issue,plan.id());return;}
        runs.update(shop,item,"RUNNING","PLANNED","",plan.id()); // Persist child identity before any write.
        catalog.run(shop,plan.id(),true);
    }
    private static boolean compatible(String a,String b){return a==null||b==null||a.isBlank()||b.isBlank()||a.strip().equalsIgnoreCase(b.strip());}
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
    private static void project(Shop shop,List<RegisteredGtin> source)throws Exception {
        var repo=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        var items=new ArrayList<ZnackModels.Product>();
        for(var row:source){if(!row.published()||!row.tradeUnit())continue;
            var docs=row.details().documents().stream().filter(d->!d.number().isBlank()&&!d.from().isBlank()).map(d->new ZnackModels.GoodsDocument(d.type(),d.number(),d.from())).toList();
            items.add(new ZnackModels.Product(row.gtin(),row.details().single("title"),row.details().single("tnved"),null,null,null,null,null,null,"published","","",null,null,docs));
        }
        ListingIdentity.require(shop);repo.upsertProducts(items,repo.getSettings());
    }
}
