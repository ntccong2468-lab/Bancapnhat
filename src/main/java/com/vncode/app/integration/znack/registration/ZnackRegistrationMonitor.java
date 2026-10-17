package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.*;

/** Bounded saved-feed polling in the existing worker. Signing requires exact-card seller consent. */
public final class ZnackRegistrationMonitor {
    public record Result(int checked,int published,boolean pending){}
    private final ZnackCardRegistrationRepository wb=new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow wbWorkflow;
    private final OzonCardRegistrationWorkflow ozonWorkflow;
    public ZnackRegistrationMonitor(){this(new ZnackCardRegistrationWorkflow(new ZnackCardRegistrationRepository()),new OzonCardRegistrationWorkflow());}
    ZnackRegistrationMonitor(ZnackCardRegistrationWorkflow wb,OzonCardRegistrationWorkflow ozon){wbWorkflow=wb;ozonWorkflow=ozon;}
    public Result poll(Shop shop,int limit)throws Exception {
        ListingIdentity.require(shop);if(limit<1||limit>100)throw new IllegalArgumentException("REGISTRY_POLL_LIMIT");
        int checked=0,published=0;
        if(shop.getMarketplace()==Marketplace.WILDBERRIES){
            var rows=new ArrayList<>(wb.pendingStatus(shop.getId(),limit));
            if(rows.size()<limit)rows.addAll(wb.unconfirmedPublications(shop,limit-rows.size()));
            for(var row:rows){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");if(wbWorkflow.refreshStatus(shop,row))published++;checked++;}
        }else{
            var keys=new ArrayList<ProductKey>();
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id FROM ozon_card_registrations WHERE shop_id=? AND (status IN ('FEED_SUBMITTED','PROCESSING','READY_TO_SIGN','SIGNING') OR status='ERROR' AND error='CATALOG_CARD_NOT_CREATED_AFTER_WAIT') AND NULLIF(feed_id,'') IS NOT NULL ORDER BY CASE status WHEN 'READY_TO_SIGN' THEN 1 ELSE 0 END,updated_at LIMIT ?")){
                p.setInt(1,shop.getId());p.setInt(2,limit);try(var r=p.executeQuery()){while(r.next())keys.add(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)));}
            }
            for(var key:keys){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");if(ozonWorkflow.refreshStatus(shop,key))published++;checked++;}
        }
        String table=shop.getMarketplace()==Marketplace.OZON?"ozon_card_registrations":"znack_card_registrations";
        boolean pending;
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM "+table+" WHERE shop_id=? AND status IN ('FEED_SUBMITTED','PROCESSING','SIGNING') AND NULLIF(feed_id,'') IS NOT NULL LIMIT 1")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){pending=r.next();}
        }
        return new Result(checked,published,pending);
    }
}
