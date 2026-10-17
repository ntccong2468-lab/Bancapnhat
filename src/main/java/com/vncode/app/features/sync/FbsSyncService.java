package com.vncode.app.features.sync;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** One bounded network page/batch per turn. Catalog, images and KIZ mutations are separate. */
public final class FbsSyncService {
    private record Cursor(String after,String from,String to,int pages) {}
    private final java.util.function.Function<Shop,OzonApiClient> ozonClients;
    private final java.util.function.Supplier<WbApiClient> wbClients;
    private final java.util.function.LongSupplier nanos;
    private final Map<Integer,String> selectedSupplies=new java.util.concurrent.ConcurrentHashMap<>();
    private record RefreshedSupply(String owner,String supply,long completedNanos){}
    private final Map<Integer,RefreshedSupply> refreshedSupplies=new java.util.concurrent.ConcurrentHashMap<>();
    public FbsSyncService(){this(shop->new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())),WbApiClient::new);}
    FbsSyncService(java.util.function.Function<Shop,OzonApiClient> ozonClients,java.util.function.Supplier<WbApiClient> wbClients){this(ozonClients,wbClients,System::nanoTime);}
    FbsSyncService(java.util.function.Function<Shop,OzonApiClient> ozonClients,java.util.function.Supplier<WbApiClient> wbClients,java.util.function.LongSupplier nanos){this.ozonClients=ozonClients;this.wbClients=wbClients;this.nanos=nanos;}
    public boolean selectSupply(int shopId,String id){
        if(id==null||id.isBlank()){refreshedSupplies.remove(shopId);return selectedSupplies.remove(shopId)!=null;}
        return !id.equals(selectedSupplies.put(shopId,id));
    }
    private static final Gson JSON=new Gson();
    public SyncCoordinator.Page step(Shop shop,SyncCoordinator.Stream stream,String checkpoint)throws Exception {
        try(var deferred=com.vncode.app.shared.RateLimitDeferral.enter()){
        LicenseRuntime.require(Permission.SELLER_API);ListingIdentity.require(shop);
        var now=Instant.now();var cursor=checkpoint.isBlank()?new Cursor("",now.minus(Duration.ofDays(stream==SyncCoordinator.Stream.FBS_QUEUE?180:30)).toString(),now.plus(Duration.ofDays(stream==SyncCoordinator.Stream.FBS_QUEUE?180:0)).toString(),0):JSON.fromJson(checkpoint,Cursor.class);
        if(cursor==null||cursor.pages()>20_000)throw new IOException("FBS_CURSOR_LIMIT");
        return shop.getMarketplace()==Marketplace.OZON?ozon(shop,stream,cursor):wb(shop,stream,cursor);
        }
    }
    private SyncCoordinator.Page ozon(Shop shop,SyncCoordinator.Stream stream,Cursor cursor)throws Exception{
        var api=ozonClients.apply(shop);
        var repository=new OzonPostingRepository();
        if(stream==SyncCoordinator.Stream.FBS_STATUS){
            String after=cursor.after();var ids=new ArrayList<String>();
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT posting_number FROM ozon_postings WHERE shop_id=? AND status IN ('awaiting_packaging','awaiting_deliver','delivering') AND posting_number>? ORDER BY posting_number LIMIT 3")){
                p.setInt(1,shop.getId());p.setString(2,after);try(var r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}
            }
            for(String id:ids){var item=OzonJson.requirePostingDetail(api.getPosting(id,false),id);if(!id.equals(item.postingNumber()))throw new IOException("FBS_FOREIGN_POSTING");ListingIdentity.require(shop);repository.upsertDetail(shop.getId(),item);}
            return next(cursor,ids.isEmpty()?"":ids.getLast(),ids.size()<3,ids.size());
        }
        if(stream==SyncCoordinator.Stream.SUPPLIES||stream==SyncCoordinator.Stream.SUPPLY_CONTENT)return SyncCoordinator.Page.complete(0);
        var raw=stream==SyncCoordinator.Stream.FBS_QUEUE?api.listUnfulfilledPostings(cursor.from(),cursor.to(),cursor.after(),100):api.listPostings(cursor.from(),cursor.to(),cursor.after(),100);
        var container=raw.has("result")&&raw.get("result").isJsonObject()?raw.getAsJsonObject("result"):raw;
        if(!container.has("postings")||!container.get("postings").isJsonArray())throw new IOException("FBS_INVALID_RESPONSE");
        var page=OzonJson.requirePostingPage(raw);
        if(page.postings().size()!=container.getAsJsonArray("postings").size()||page.postings().stream().map(OzonPostingDto::postingNumber).distinct().count()!=page.postings().size())throw new IOException("FBS_INVALID_RESPONSE");
        if(page.hasNext()&&(page.postings().isEmpty()||page.cursor().isBlank()||page.cursor().equals(cursor.after())))throw new IOException("FBS_INVALID_CURSOR");
        ListingIdentity.require(shop);repository.upsertPage(shop.getId(),page.postings());
        return next(cursor,page.cursor(),!page.hasNext(),page.postings().size());
    }
    private SyncCoordinator.Page wb(Shop shop,SyncCoordinator.Stream stream,Cursor cursor)throws Exception {
        var api=wbClients.get();var orders=new WbOrderRepository();
        if(stream==SyncCoordinator.Stream.FBS_QUEUE){
            var response=api.getNewOrders(shop.getApiKey());if(response==null||response.getOrders()==null)throw new IOException("FBS_INVALID_RESPONSE");
            WbFbsResponseValidator.orders(response);
            ListingIdentity.require(shop);orders.saveCurrentNewOrdersSnapshot(shop.getId(),response.getOrders());return SyncCoordinator.Page.complete(response.getOrders().size());
        }
        if(stream==SyncCoordinator.Stream.FBS_STATUS){
            var ids=new ArrayList<Long>();long after=cursor.after().isBlank()?0:Long.parseLong(cursor.after());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT order_id FROM wb_orders WHERE shop_id=? AND order_id>? AND coalesce(supplier_status,'new') NOT IN ('cancel','cancelled') AND coalesce(wb_status,'') NOT IN ('sold','canceled','canceled_by_client','declined_by_client') ORDER BY order_id LIMIT 1000")){
                p.setInt(1,shop.getId());p.setLong(2,after);try(var r=p.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            if(ids.isEmpty())return SyncCoordinator.Page.complete(0);
            var response=api.getOrderStatuses(shop.getApiKey(),ids);if(response==null||response.getOrders()==null)throw new IOException("FBS_INVALID_RESPONSE");
            WbFbsResponseValidator.statuses(response,ids);
            ListingIdentity.require(shop);orders.updateOrderStatuses(shop.getId(),response.getOrders());
            return next(cursor,Long.toString(ids.getLast()),ids.size()<1000,response.getOrders().size());
        }
        if(stream==SyncCoordinator.Stream.SUPPLY_CONTENT){
            String preferred=selectedSupplies.getOrDefault(shop.getId(),"");String id="";
            var refreshed=refreshedSupplies.get(shop.getId());String owner=ListingIdentity.fingerprint(shop);
            boolean priority=!preferred.isBlank()&&(refreshed==null||!owner.equals(refreshed.owner())||!preferred.equals(refreshed.supply())||nanos.getAsLong()-refreshed.completedNanos()>=java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
            if(priority)id=preferred;
            else try(var c=Database.getConnection();var p=c.prepareStatement("SELECT supply_id FROM wb_supplies WHERE shop_id=? AND coalesce(done,0)=0 AND supply_id>? AND supply_id<>? ORDER BY supply_id LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,cursor.after());p.setString(3,preferred);try(var r=p.executeQuery()){if(r.next())id=r.getString(1);}
            }
            if(id.isBlank())return SyncCoordinator.Page.complete(0);
            var detail=api.getSupplyDetail(shop.getApiKey(),id);if(detail==null||!id.equals(detail.getId()))throw new IOException("FBS_FOREIGN_SUPPLY");
            var content=api.getSupplyOrderIds(shop.getApiKey(),id);if(content==null||content.getOrderIds()==null)throw new IOException("FBS_INVALID_RESPONSE");
            var ids=WbFbsResponseValidator.supplyIds(content);
            ListingIdentity.require(shop);new WbSupplyRepository().saveSupplies(shop.getId(),List.of(detail));orders.replaceSupplyOrders(shop.getId(),id,ids);
            // An active shipment refresh must not reset or consume the background scan's cursor.
            if(priority)refreshedSupplies.put(shop.getId(),new RefreshedSupply(owner,id,nanos.getAsLong()));
            return next(cursor,priority?cursor.after():id,false,ids.size());
        }
        long after=cursor.after().isBlank()?0:Long.parseLong(cursor.after());
        if(stream==SyncCoordinator.Stream.SUPPLIES){
            var response=api.getSupplies(shop.getApiKey(),after,100);if(response==null||response.getSupplies()==null)throw new IOException("FBS_INVALID_RESPONSE");
            boolean done=response.getSupplies().size()<100;long next=WbFbsResponseValidator.supplies(response,after,100);
            ListingIdentity.require(shop);new WbSupplyRepository().saveSupplies(shop.getId(),response.getSupplies());
            return next(cursor,Long.toString(next),done,response.getSupplies().size());
        }
        var response=api.getOrders(shop.getApiKey(),after,1000,Instant.parse(cursor.from()).getEpochSecond(),Instant.parse(cursor.to()).getEpochSecond());
        if(response==null||response.getOrders()==null)throw new IOException("FBS_INVALID_RESPONSE");
        boolean done=response.getOrders().size()<1000;long next=WbFbsResponseValidator.next(after,response.getNext(),response.getOrders().size(),1000);
        WbFbsResponseValidator.orders(response);ListingIdentity.require(shop);orders.saveOrders(shop.getId(),response.getOrders());
        return next(cursor,Long.toString(next),done,response.getOrders().size());
    }
    private static SyncCoordinator.Page next(Cursor before,String after,boolean done,int count){return new SyncCoordinator.Page(done?"":JSON.toJson(new Cursor(after,before.from(),before.to(),before.pages()+1)),done,count);}
}
