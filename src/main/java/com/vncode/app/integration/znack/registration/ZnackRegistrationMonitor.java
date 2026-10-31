package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.*;

/** Bounded saved-feed polling in the existing worker. Signing requires exact-card seller consent. */
public final class ZnackRegistrationMonitor {
    public record Result(int checked,int published,boolean pending,com.vncode.app.features.sync.SyncFailure warning){
        public Result(int checked,int published,boolean pending){this(checked,published,pending,null);}
    }
    private final ZnackCardRegistrationRepository wb=new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow wbWorkflow;
    private final OzonCardRegistrationWorkflow ozonWorkflow;
    private final java.util.function.Predicate<Shop> resumeOzonBatch;
    private final java.util.function.BiPredicate<Shop,ProductKey> ownsOzonBatch;
    public ZnackRegistrationMonitor(){this(new ZnackCardRegistrationWorkflow(new ZnackCardRegistrationRepository()),new OzonCardRegistrationWorkflow());}
    ZnackRegistrationMonitor(ZnackCardRegistrationWorkflow wb,OzonCardRegistrationWorkflow ozon){this(wb,ozon,new OzonRegistrationBatchService()::resumePending,new OzonRegistrationBatchService()::ownsPending);}
    ZnackRegistrationMonitor(ZnackCardRegistrationWorkflow wb,OzonCardRegistrationWorkflow ozon,java.util.function.Predicate<Shop> resumeOzonBatch,java.util.function.BiPredicate<Shop,ProductKey> ownsOzonBatch){wbWorkflow=wb;ozonWorkflow=ozon;this.resumeOzonBatch=resumeOzonBatch;this.ownsOzonBatch=ownsOzonBatch;}
    public Result poll(Shop shop,int limit)throws Exception {
        ListingIdentity.require(shop);if(limit<1||limit>100)throw new IllegalArgumentException("REGISTRY_POLL_LIMIT");
        var cooldown=RegistrationCatalogCooldown.warning(shop);if(cooldown!=null)return new Result(0,0,true,cooldown);
        boolean batchPending=shop.getMarketplace()==Marketplace.WILDBERRIES
                ?new ZnackRegistrationBatchService(wb).resumePending(shop)
                :resumeOzonBatch.test(shop);
        RegistrationMonitoringEngine.Result monitored;
        if(shop.getMarketplace()==Marketplace.WILDBERRIES){
            var rows=new ArrayList<>(wb.pendingStatus(shop.getId(),limit));
            if(rows.size()<limit)rows.addAll(wb.unconfirmedPublications(shop,limit-rows.size()));
            monitored=RegistrationMonitoringEngine.poll(rows,new RegistrationMonitoringEngine.Adapter<ZnackCardRegistrationModels.Sku>(){
                @Override public RegistrationMonitoringEngine.Observed<ZnackCardRegistrationModels.Sku> observe(ZnackCardRegistrationModels.Sku row)throws Exception{
                    boolean published=false;
                    if(!row.hasFeed()&&row.canResume()){if(!new ZnackRegistrationBatchService(wb).ownsPending(shop,row))wbWorkflow.resume(shop,row,null);}
                    else published=wbWorkflow.refreshStatusWithoutSigning(shop,row);
                    var current=wb.find(shop.getId(),row.chrtId());
                    var nativeKey=current==null?null:new ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(current.nmId()),Long.toString(current.chrtId()));
                    boolean eligible=current!=null&&current.status()==ZnackCardRegistrationModels.Status.READY_TO_SIGN&&!new ZnackRegistrationBatchService(wb).blocksSigning(shop,current)&&WbSourceConsentPolicy.permitsAutomatic(shop,nativeKey,current.gtin())&&RegistrationSigningConsent.allowed(shop,nativeKey,current.gtin());
                    return new RegistrationMonitoringEngine.Observed<>(published,eligible?current:null);
                }
                @Override public void recordFailure(ZnackCardRegistrationModels.Sku row,Exception error){wbWorkflow.recordPollingFailure(shop,row,error);}
                @Override public void signConsented(List<ZnackCardRegistrationModels.Sku> selected){wbWorkflow.signConsented(shop,selected,null);}
                @Override public boolean isPublished(ZnackCardRegistrationModels.Sku row){return wb.checkpointStatus(shop.getId(),row.chrtId())==ZnackCardRegistrationModels.Status.PUBLISHED;}
            });
        }else{
            var keys=pendingOzonKeys(shop,limit);
            var ozon=new OzonCardRegistrationRepository();
            monitored=RegistrationMonitoringEngine.poll(keys,new RegistrationMonitoringEngine.Adapter<ProductKey>(){
                @Override public RegistrationMonitoringEngine.Observed<ProductKey> observe(ProductKey key)throws Exception{
                    boolean published=false;var row=ozon.find(key);
                    if(row!=null&&row.status()==ZnackCardRegistrationModels.Status.READY_TO_SUBMIT&&row.feedId().isBlank()){if(!ownsOzonBatch.test(shop,key))ozonWorkflow.resume(shop,key,null);}
                    else published=ozonWorkflow.refreshStatusWithoutSigning(shop,key);
                    var current=ozon.find(key);
                    boolean eligible=current!=null&&current.status()==ZnackCardRegistrationModels.Status.READY_TO_SIGN&&!new OzonRegistrationBatchRepository().blocksSigning(shop,key)&&OzonSourceConsentPolicy.permitsAutomatic(shop,key,current.gtin())&&RegistrationSigningConsent.allowed(shop,key,current.gtin());
                    return new RegistrationMonitoringEngine.Observed<>(published,eligible?key:null);
                }
                @Override public void recordFailure(ProductKey key,Exception error){ozonWorkflow.recordPollingFailure(shop,key,error);}
                @Override public void signConsented(List<ProductKey> selected){ozonWorkflow.signConsented(shop,selected,null);}
                @Override public boolean isPublished(ProductKey key){var current=ozon.find(key);return current!=null&&current.status()==ZnackCardRegistrationModels.Status.PUBLISHED;}
            });
        }
        String table=shop.getMarketplace()==Marketplace.OZON?"ozon_card_registrations":"znack_card_registrations";
        boolean pending;
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM "+table+" WHERE shop_id=? AND (status IN ('FEED_SUBMITTED','PROCESSING','SIGNING') AND (NULLIF(feed_id,'') IS NOT NULL OR good_id>0) OR status='READY_TO_SUBMIT' AND NULLIF(feed_id,'') IS NULL AND NULLIF(gtin,'') IS NOT NULL) LIMIT 1")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){pending=r.next();}
        }
        if(!pending)pending=hasEligibleSigningConsent(shop);
        pending|=batchPending;
        pending|=shop.getMarketplace()==Marketplace.WILDBERRIES?wbWorkflow.activeCount(shop)>0:ozonWorkflow.isRunning(shop);
        return new Result(monitored.checked(),monitored.published(),pending,monitored.warning());
    }
    /** Legacy or changed-scope enabled flags are not pending signing work. */
    static String signingScopeHash(int shopId){
        try{return ListingIdentity.hash(new com.google.gson.Gson().toJson(ZnackRegistrationScope.fromSettings(new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shopId,"")).getSettings())));}
        catch(RuntimeException unverified){return "unverified-signing-scope";}
    }
    private static boolean hasEligibleSigningConsent(Shop shop)throws java.sql.SQLException{
        String product=shop.getMarketplace()==Marketplace.OZON?"product_id":"nm_id",variant=shop.getMarketplace()==Marketplace.OZON?"offer_id":"chrt_id",table=shop.getMarketplace()==Marketplace.OZON?"ozon_card_registrations":"znack_card_registrations";
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT CAST(r."+product+" AS TEXT),CAST(r."+variant+" AS TEXT),r.gtin FROM "+table+" r JOIN znack_registration_signing_consents consent ON consent.shop_id=r.shop_id AND consent.product_id=CAST(r."+product+" AS TEXT) AND consent.variant_id=CAST(r."+variant+" AS TEXT) AND consent.gtin=r.gtin WHERE r.shop_id=? AND r.status='READY_TO_SIGN' AND consent.marketplace=? AND consent.owner=? AND consent.enabled=1 AND consent.approval_kind IN ('PAYLOAD','DOCUMENT') AND consent.scope_sha256=? ORDER BY r.updated_at LIMIT 10000")){
            p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,signingScopeHash(shop.getId()));
            try(var rows=p.executeQuery()){while(rows.next()){
                var key=new ProductKey(shop.getId(),shop.getMarketplace(),rows.getString(1),rows.getString(2));
                boolean stopped=shop.getMarketplace()==Marketplace.OZON?new OzonRegistrationBatchRepository().blocksSigning(shop,key):new ZnackRegistrationBatchService().blocksSigning(shop,new ZnackCardRegistrationRepository().find(shop.getId(),Long.parseLong(key.variantId())));
                if(!stopped&&RegistrationSourcePolicy.permitsAutomatic(shop,key,rows.getString(3))&&RegistrationSigningConsent.allowed(shop,key,rows.getString(3)))return true;
            }}return false;
        }
    }
    static List<ProductKey> pendingOzonKeys(Shop shop,int limit)throws java.sql.SQLException {
        var keys=new ArrayList<ProductKey>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id FROM ozon_card_registrations r WHERE shop_id=? AND ((status IN ('FEED_SUBMITTED','PROCESSING','READY_TO_SIGN','SIGNING') OR status='ERROR' AND error='CATALOG_CARD_NOT_CREATED_AFTER_WAIT') AND (NULLIF(feed_id,'') IS NOT NULL OR good_id IS NOT NULL) OR status='READY_TO_SUBMIT' AND NULLIF(gtin,'') IS NOT NULL AND NULLIF(feed_id,'') IS NULL) ORDER BY CASE WHEN status='READY_TO_SIGN' THEN CASE WHEN EXISTS(SELECT 1 FROM znack_registration_signing_consents consent WHERE consent.shop_id=r.shop_id AND consent.marketplace='OZON' AND consent.product_id=r.product_id AND consent.variant_id=r.offer_id AND consent.gtin=r.gtin AND consent.owner=? AND consent.enabled=1 AND consent.approval_kind IN ('PAYLOAD','DOCUMENT') AND consent.scope_sha256=?) THEN 0 ELSE 2 END ELSE 0 END,updated_at LIMIT ?")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,signingScopeHash(shop.getId()));p.setInt(4,limit);try(var r=p.executeQuery()){while(r.next())keys.add(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)));}
        }
        return List.copyOf(keys);
    }
}
