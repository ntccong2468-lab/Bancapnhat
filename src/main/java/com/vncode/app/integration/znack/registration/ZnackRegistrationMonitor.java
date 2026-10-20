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
        boolean batchPending=shop.getMarketplace()==Marketplace.WILDBERRIES
                ?new ZnackRegistrationBatchService(wb).resumePending(shop)
                :resumeOzonBatch.test(shop);
        int checked=0,published=0;com.vncode.app.features.sync.SyncFailure warning=null;
        if(shop.getMarketplace()==Marketplace.WILDBERRIES){
            var rows=new ArrayList<>(wb.pendingStatus(shop.getId(),limit));
            if(rows.size()<limit)rows.addAll(wb.unconfirmedPublications(shop,limit-rows.size()));
            for(var row:rows){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");try{if(!row.hasFeed()&&row.canResume()){if(!batchPending||!new ZnackRegistrationBatchService(wb).ownsPending(shop,row))wbWorkflow.resume(shop,row,null);}else if(wbWorkflow.refreshStatus(shop,row))published++;checked++;}catch(Exception error){if(CatalogFeedback.rateLimited(error)||error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException))throw error;warning=com.vncode.app.features.sync.SyncFailure.of(error,0);try{wbWorkflow.recordPollingFailure(shop,row,error);}catch(RuntimeException originalAccountChanged){/* Never rebind a historical request. */}checked++;}}
        }else{
            var keys=new ArrayList<ProductKey>();
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id FROM ozon_card_registrations WHERE shop_id=? AND ((status IN ('FEED_SUBMITTED','PROCESSING','READY_TO_SIGN','SIGNING') OR status='ERROR' AND error='CATALOG_CARD_NOT_CREATED_AFTER_WAIT') AND NULLIF(feed_id,'') IS NOT NULL OR status='READY_TO_SUBMIT' AND NULLIF(gtin,'') IS NOT NULL AND NULLIF(feed_id,'') IS NULL) ORDER BY CASE status WHEN 'READY_TO_SIGN' THEN 1 ELSE 0 END,updated_at LIMIT ?")){
                p.setInt(1,shop.getId());p.setInt(2,limit);try(var r=p.executeQuery()){while(r.next())keys.add(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)));}
            }
            for(var key:keys){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");try{var row=new OzonCardRegistrationRepository().find(key);if(row!=null&&row.status()==ZnackCardRegistrationModels.Status.READY_TO_SUBMIT&&row.feedId().isBlank()){if(!batchPending||!ownsOzonBatch.test(shop,key))ozonWorkflow.resume(shop,key,null);}else if(ozonWorkflow.refreshStatus(shop,key))published++;checked++;}catch(Exception error){if(CatalogFeedback.rateLimited(error)||error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException))throw error;warning=com.vncode.app.features.sync.SyncFailure.of(error,0);try{ozonWorkflow.recordPollingFailure(shop,key,error);}catch(RuntimeException originalAccountChanged){/* Never rebind a historical request. */}checked++;}}
        }
        String table=shop.getMarketplace()==Marketplace.OZON?"ozon_card_registrations":"znack_card_registrations";
        boolean pending;
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM "+table+" WHERE shop_id=? AND (status IN ('FEED_SUBMITTED','PROCESSING','SIGNING') AND NULLIF(feed_id,'') IS NOT NULL OR status='READY_TO_SUBMIT' AND NULLIF(feed_id,'') IS NULL AND NULLIF(gtin,'') IS NOT NULL) LIMIT 1")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){pending=r.next();}
        }
        if(!pending){
            String product=shop.getMarketplace()==Marketplace.OZON?"product_id":"nm_id",variant=shop.getMarketplace()==Marketplace.OZON?"offer_id":"chrt_id";
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM "+table+" r JOIN znack_registration_signing_consents consent ON consent.shop_id=r.shop_id AND consent.product_id=CAST(r."+product+" AS TEXT) AND consent.variant_id=CAST(r."+variant+" AS TEXT) AND consent.gtin=r.gtin WHERE r.shop_id=? AND r.status='READY_TO_SIGN' AND consent.marketplace=? AND consent.owner=? AND consent.enabled=1 LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){pending=r.next();}
            }
        }
        pending|=batchPending;
        pending|=shop.getMarketplace()==Marketplace.WILDBERRIES?wbWorkflow.activeCount(shop)>0:ozonWorkflow.isRunning(shop);
        return new Result(checked,published,pending,warning);
    }
}
