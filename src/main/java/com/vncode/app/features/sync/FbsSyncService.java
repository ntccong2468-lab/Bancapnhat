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
    private record Cursor(String after,String from,String to,int pages,boolean concurrentRead,boolean incomplete,boolean unclassified,List<String> seenCursors) {}
    private final java.util.function.Function<Shop,OzonApiClient> ozonClients;
    private final java.util.function.Supplier<WbApiClient> wbClients;
    private final java.util.function.LongSupplier nanos;
    private final com.vncode.app.features.kiz.status.Kn3SyncService kn3;
    private final Map<Integer,String> selectedSupplies=new java.util.concurrent.ConcurrentHashMap<>();
    private record RefreshedSupply(String owner,String supply,long completedNanos){}
    private final Map<Integer,RefreshedSupply> refreshedSupplies=new java.util.concurrent.ConcurrentHashMap<>();
    public FbsSyncService(){this(shop->new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())),WbApiClient::new,System::nanoTime,new com.vncode.app.features.kiz.status.Kn3SyncService());}
    FbsSyncService(java.util.function.Function<Shop,OzonApiClient> ozonClients,java.util.function.Supplier<WbApiClient> wbClients){this(ozonClients,wbClients,System::nanoTime);}
    FbsSyncService(java.util.function.Function<Shop,OzonApiClient> ozonClients,java.util.function.Supplier<WbApiClient> wbClients,java.util.function.LongSupplier nanos){this(ozonClients,wbClients,nanos,null);}
    FbsSyncService(java.util.function.Function<Shop,OzonApiClient> ozonClients,java.util.function.Supplier<WbApiClient> wbClients,java.util.function.LongSupplier nanos,com.vncode.app.features.kiz.status.Kn3SyncService kn3){this.ozonClients=ozonClients;this.wbClients=wbClients;this.nanos=nanos;this.kn3=kn3;}
    public boolean selectSupply(int shopId,String id){
        if(id==null||id.isBlank()){refreshedSupplies.remove(shopId);return selectedSupplies.remove(shopId)!=null;}
        return !id.equals(selectedSupplies.put(shopId,id));
    }
    private static final Gson JSON=new Gson();
    public SyncCoordinator.Page step(Shop shop,SyncCoordinator.Stream stream,String checkpoint)throws Exception {
        try(var deferred=com.vncode.app.shared.RateLimitDeferral.enter()){
        LicenseRuntime.require(Permission.SELLER_API);ListingIdentity.require(shop);
        var now=Instant.now();var cursor=checkpoint.isBlank()?new Cursor("",now.minus(Duration.ofDays(stream==SyncCoordinator.Stream.FBS_QUEUE?180:30)).toString(),now.plus(Duration.ofDays(stream==SyncCoordinator.Stream.FBS_QUEUE?180:0)).toString(),0,false,false,false,List.of()):JSON.fromJson(checkpoint,Cursor.class);
        if(cursor==null||cursor.pages()>20_000)throw new IOException("FBS_CURSOR_LIMIT");
        return shop.getMarketplace()==Marketplace.OZON?ozon(shop,stream,cursor):wb(shop,stream,cursor);
        }
    }
    private SyncCoordinator.Page ozon(Shop shop,SyncCoordinator.Stream stream,Cursor cursor)throws Exception{
        var api=ozonClients.apply(shop);
        var repository=new OzonPostingRepository();
        if(stream==SyncCoordinator.Stream.FBS_STATUS){
            int readbacks=0;
            try{readbacks=new OzonShipService().reconcilePendingReadOnly(shop,api,3);}
            catch(RuntimeException waiting){org.slf4j.LoggerFactory.getLogger(FbsSyncService.class).warn("Ozon package readback is pending; regular FBS status refresh continues: {}",waiting.getClass().getSimpleName());}
            String after=cursor.after();
            // Ozon can return 100 order statuses in a list request. Scan the recent window
            // newest first, preserving product/KIZ details, then detail-read only active
            // orders not seen in that complete pass (including older orders).
            if(after.isBlank()||after.startsWith("L:")){
                String pageCursor=after.isBlank()?"":after.substring(2);
                var started=Instant.now();
                var raw=api.listPostingsNewestFirst(cursor.from(),cursor.to(),pageCursor,100);
                var page=OzonJson.requirePostingPage(raw);
                if(page.hasNext()&&(page.postings().isEmpty()||page.cursor().isBlank()||page.cursor().equals(pageCursor)))throw new IOException("FBS_INVALID_CURSOR");
                ListingIdentity.require(shop);
                String trackingOwner=api.syncOwnerFingerprint(shop.getId());
                int changed=repository.updateStatusPageIfUnchanged(shop.getId(),page.postings().stream().map(p->p.withTrackingOwner(trackingOwner)).toList(),Instant.parse(cursor.to()),started);
                return nextOzon(cursor,page.hasNext()?"L:"+page.cursor():"D:",false,changed+readbacks,cursor.concurrentRead(),cursor.incomplete(),cursor.unclassified());
            }
            // A checkpoint from a previous app version contains the bare detail cursor.
            String detailAfter=after.startsWith("D:")?after.substring(2):after;
            var ids=repository.findStatusNumbersAfter(shop.getId(),detailAfter,3,Instant.parse(cursor.to()));
            SyncFailure warning=null;
            for(String id:ids){var started=Instant.now();var detail=api.getPosting(id,false);var item=OzonJson.requirePostingDetail(detail,id).withTrackingOwner(api.syncOwnerFingerprint(shop.getId()));if(!id.equals(item.postingNumber()))throw new IOException("FBS_FOREIGN_POSTING");var object=detail.has("result")&&detail.get("result").isJsonObject()?detail.getAsJsonObject("result"):detail;if(!OzonJson.hasCompletePostingDetails(object,item)){if(OzonJson.isTerminalPosting(item)){ListingIdentity.require(shop);repository.updateStatusPageIfUnchanged(shop.getId(),List.of(item),Instant.parse(cursor.to()),started);}else warning=new SyncFailure("FBS_INCOMPLETE_POSTING",Duration.ofSeconds(15),false);continue;}ListingIdentity.require(shop);if(repository.upsertPageIfUnchanged(shop.getId(),List.of(item),started)==0)continue;try{if(kn3!=null)kn3.ozon(shop,item,api);}catch(IOException|com.vncode.app.shared.RateLimitDeferral.Deferred unavailable){warning=SyncFailure.of(unavailable,0);}}
            return warning(nextOzon(cursor,ids.isEmpty()?"":"D:"+ids.getLast(),ids.size()<3,ids.size()+readbacks,cursor.concurrentRead(),cursor.incomplete(),cursor.unclassified()),warning);
        }
        if(stream==SyncCoordinator.Stream.SUPPLIES||stream==SyncCoordinator.Stream.SUPPLY_CONTENT)return SyncCoordinator.Page.complete(0);
        var started=Instant.now();
        var raw=stream==SyncCoordinator.Stream.FBS_QUEUE?api.listUnfulfilledPostings(cursor.from(),cursor.to(),cursor.after(),100):api.listPostings(cursor.from(),cursor.to(),cursor.after(),100);
        var container=raw.has("result")&&raw.get("result").isJsonObject()?raw.getAsJsonObject("result"):raw;
        if(!container.has("postings")||!container.get("postings").isJsonArray())throw new IOException("FBS_INVALID_RESPONSE");
        var page=stream==SyncCoordinator.Stream.FBS_QUEUE?OzonJson.requireQueuePage(raw):OzonJson.requirePostingPage(raw);
        if(page.postings().size()!=container.getAsJsonArray("postings").size()||page.postings().stream().map(OzonPostingDto::postingNumber).distinct().count()!=page.postings().size())throw new IOException("FBS_INVALID_RESPONSE");
        if(page.hasNext()&&(page.postings().isEmpty()||page.cursor().isBlank()||page.cursor().equals(cursor.after())))throw new IOException("FBS_INVALID_CURSOR");
        ListingIdentity.require(shop);
        var usable=new ArrayList<OzonPostingDto>();int partial=0;boolean unclassified=cursor.unclassified();
        var items=container.getAsJsonArray("postings");
        for(int i=0;i<page.postings().size();i++){
            var item=page.postings().get(i);
            if(stream!=SyncCoordinator.Stream.FBS_QUEUE||(!item.status().equals("unknown")&&OzonJson.hasCompletePostingDetails(items.get(i).getAsJsonObject(),item)))usable.add(item);
            else {
                // The list response is partial: independently read this exact posting so
                // other valid orders can still be committed without guessing KIZ data.
                try{
                    var detail=api.getPosting(item.postingNumber(),false);
                    var full=OzonJson.requirePostingDetail(detail,item.postingNumber());
                    var object=detail.has("result")&&detail.get("result").isJsonObject()?detail.getAsJsonObject("result"):detail;
                    if(!full.status().equals("unknown")&&OzonJson.hasCompletePostingDetails(object,full))usable.add(full);
                    else {ListingIdentity.require(shop);repository.recordIncomplete(shop.getId(),item,started);partial++;if(item.status().equals("unknown"))unclassified=true;}
                }catch(OzonApiException unavailable){
                    ListingIdentity.require(shop);repository.recordIncomplete(shop.getId(),item,started);
                    if(unavailable.statusCode()!=404)throw unavailable;
                    partial++;if(item.status().equals("unknown"))unclassified=true;
                }
            }
        }
        ListingIdentity.require(shop);String trackingOwner=api.syncOwnerFingerprint(shop.getId());
        int written=repository.upsertPageIfUnchanged(shop.getId(),usable.stream().map(p->p.withTrackingOwner(trackingOwner)).toList(),started);
        boolean concurrent=cursor.concurrentRead()||written<usable.size();boolean incomplete=cursor.incomplete()||partial>0;
        if(!page.hasNext()){
            if(stream==SyncCoordinator.Stream.FBS_QUEUE&&!concurrent&&!unclassified)repository.markQueueMembershipComplete(shop,incomplete);
            if(concurrent||incomplete||unclassified){
                String code=unclassified?"FBS_STATUS_UNCLASSIFIED":incomplete?"FBS_INCOMPLETE_POSTING":"FBS_CONCURRENT_REFRESH";
                return new SyncCoordinator.Page("",true,written,new SyncFailure(code,Duration.ofSeconds(concurrent&&!incomplete&&!unclassified?1:15),false));
            }
        }
        return nextOzon(cursor,page.cursor(),!page.hasNext(),written,concurrent,incomplete,unclassified);
    }
    private SyncCoordinator.Page wb(Shop shop,SyncCoordinator.Stream stream,Cursor cursor)throws Exception {
        var api=wbClients.get();var orders=new WbOrderRepository();
        if(stream==SyncCoordinator.Stream.FBS_QUEUE){
            var requestedAt=Instant.now();
            var response=api.getNewOrders(shop.getApiKey());if(response==null||response.getOrders()==null)throw new IOException("FBS_INVALID_RESPONSE");
            WbFbsResponseValidator.orders(response);
            ListingIdentity.require(shop);orders.saveCurrentNewOrdersSnapshot(shop.getId(),response.getOrders(),requestedAt);return SyncCoordinator.Page.complete(response.getOrders().size());
        }
        if(stream==SyncCoordinator.Stream.FBS_STATUS){
            var ids=new ArrayList<Long>();long after=cursor.after().isBlank()?0:Long.parseLong(cursor.after());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT order_id FROM wb_orders WHERE shop_id=? AND order_id>? AND coalesce(supplier_status,'new') NOT IN ('cancel','cancelled') AND coalesce(wb_status,'') NOT IN ('sold','canceled','canceled_by_client','declined_by_client') ORDER BY order_id LIMIT 1000")){
                p.setInt(1,shop.getId());p.setLong(2,after);try(var r=p.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            if(ids.isEmpty())return SyncCoordinator.Page.complete(0);
            var requestedAt=Instant.now();
            var response=api.getOrderStatuses(shop.getApiKey(),ids);if(response==null||response.getOrders()==null)throw new IOException("FBS_INVALID_RESPONSE");
            WbFbsResponseValidator.statuses(response,ids);
            ListingIdentity.require(shop);orders.updateOrderStatuses(shop.getId(),response.getOrders(),requestedAt);
            SyncFailure warning=null;try{if(kn3!=null)kn3.wb(shop);}catch(IOException|com.vncode.app.shared.RateLimitDeferral.Deferred unavailable){warning=SyncFailure.of(unavailable,0);}
            return warning(next(cursor,Long.toString(ids.getLast()),ids.size()<1000,response.getOrders().size()),warning);
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
            var requestedAt=Instant.now();
            var detail=api.getSupplyDetail(shop.getApiKey(),id);if(detail==null||!id.equals(detail.getId()))throw new IOException("FBS_FOREIGN_SUPPLY");
            var content=api.getSupplyOrderIds(shop.getApiKey(),id);if(content==null||content.getOrderIds()==null)throw new IOException("FBS_INVALID_RESPONSE");
            var ids=WbFbsResponseValidator.supplyIds(content);
            ListingIdentity.require(shop);new WbSupplyRepository().saveSupplies(shop.getId(),List.of(detail));
            if(!orders.replaceSupplyOrders(shop.getId(),id,ids,requestedAt))throw new IOException("FBS_STALE_SUPPLY_SNAPSHOT");
            // An active shipment refresh must not reset or consume the background scan's cursor.
            if(priority)refreshedSupplies.put(shop.getId(),new RefreshedSupply(owner,id,nanos.getAsLong()));
            SyncFailure warning=null;try{if(kn3!=null&&priority)kn3.wb(shop,id);}catch(IOException|com.vncode.app.shared.RateLimitDeferral.Deferred unavailable){warning=SyncFailure.of(unavailable,0);}
            return warning(next(cursor,priority?cursor.after():id,false,ids.size()),warning);
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
    private static SyncCoordinator.Page next(Cursor before,String after,boolean done,int count){return new SyncCoordinator.Page(done?"":JSON.toJson(new Cursor(after,before.from(),before.to(),before.pages()+1,before.concurrentRead(),before.incomplete(),before.unclassified(),before.seenCursors())),done,count);}
    private static SyncCoordinator.Page nextOzon(Cursor before,String after,boolean done,int count,boolean concurrent,boolean incomplete,boolean unclassified)throws IOException {
        if(done)return SyncCoordinator.Page.complete(count);
        if(after==null||after.isBlank())throw new IOException("FBS_INVALID_CURSOR");
        var seen=new LinkedHashSet<String>(before.seenCursors()==null?List.of():before.seenCursors());
        if(seen.size()>=20_000||!seen.add(ListingIdentity.hash(after)))throw new IOException("FBS_INVALID_CURSOR");
        return new SyncCoordinator.Page(JSON.toJson(new Cursor(after,before.from(),before.to(),before.pages()+1,concurrent,incomplete,unclassified,List.copyOf(seen))),false,count);
    }
    private static SyncCoordinator.Page warning(SyncCoordinator.Page page,SyncFailure warning){return new SyncCoordinator.Page(page.checkpoint(),page.complete(),page.changed(),warning);}
}
