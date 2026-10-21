package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.WbOrderRepository;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.*;
import com.vncode.app.shared.SellerMutationExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One replacement at a time per order. Waiting releases the shop and FBS network lanes. */
public final class Kn3BatchReplacement {
    public record Row(Kn3Mark mark,String productId,String barcode,String gtin,String article){
        @Override public String toString(){return "Kn3ReplacementRow[redacted]";}
    }
    public record Report(List<String> orders,List<Row> rows){public Report{orders=List.copyOf(orders);rows=List.copyOf(rows);}}
    public interface Actions {
        Kiz reserve(Shop shop,Row row,KizSourceSelection sources)throws Exception;
        void replace(Shop shop,Row row,Kiz code)throws Exception;
        void refresh(Shop shop,String order)throws Exception;
        void releaseUnclaimed(Shop shop,Kiz code);
    }
    private static final ScheduledExecutorService CLOCK=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"vncode-kn3-wait");t.setDaemon(true);return t;});
    private static final ConcurrentMap<String,CompletableFuture<Void>> ACTIVE=new ConcurrentHashMap<>();
    private final Kn3Repository repository=new Kn3Repository();
    private final Actions actions;
    public Kn3BatchReplacement(){this(new StockActions());}
    public Kn3BatchReplacement(Actions actions){this.actions=Objects.requireNonNull(actions);}

    /** Freezes the open shipment/filter, including rows outside the visible page. No reservation here. */
    public Report report(Shop shop,Collection<String> orderIds){
        ListingIdentity.require(shop);var ids=List.copyOf(new LinkedHashSet<>(orderIds));if(ids.size()>10000)throw new IllegalArgumentException("FBS_KN3_BATCH_LIMIT");
        var rows=new ArrayList<Row>();
        try(var c=Database.getConnection()){
            var observations=repository.read(c,shop.getId(),shop.getMarketplace(),ids);
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){
                var orders=new WbOrderRepository().getOrders(shop.getId(),ids.stream().map(Long::valueOf).toList());
                var mappings=new com.vncode.app.features.kizmapping.KizMappingRepository().findMappings(shop.getId(),orders.stream().map(Order::getNmId).distinct().toList());
                for(var order:orders)for(var m:observations.getOrDefault(order.getId().toString(),List.of()))if(m.state()==Kn3Mark.State.REJECTED)rows.add(new Row(m,order.getNmId().toString(),order.getBarcode(),mappings.getOrDefault(order.getNmId(),""),order.getArticle()));
            }else{
                var postings=new OzonPostingRepository().findMany(c,shop.getId(),ids);var products=new OzonCatalogRepository().findBySkus(c,shop.getId(),postings.values().stream().flatMap(p->p.items().stream()).map(OzonPostingItemDto::sku).distinct().toList());
                for(var posting:postings.values())for(var m:observations.getOrDefault(posting.postingNumber(),List.of()))if(m.state()==Kn3Mark.State.REJECTED){
                    var item=posting.items().stream().filter(i->i.sku().equals(m.productId())).findFirst().orElseThrow(()->new SecurityException("FBS_KN3_FOREIGN_PRODUCT"));
                    var product=products.get(item.sku());String productId=product==null?"":product.productId();
                    String gtin="";try(var p=c.prepareStatement("SELECT gtin FROM gtin_sync_mappings WHERE shop_id=? AND marketplace='OZON' AND product_id=? AND variant_id=?")){p.setInt(1,shop.getId());p.setString(2,productId);p.setString(3,item.offerId());try(var r=p.executeQuery()){if(r.next())gtin=r.getString(1);}}
                    rows.add(new Row(m,productId,item.sku(),gtin,item.offerId()));
                }
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
        return new Report(ids,rows);
    }
    public CompletableFuture<Void> start(Shop selected,Report report,KizSourceSelection sources){
        if(sources==null||!sources.anySelected())return CompletableFuture.failedFuture(new IllegalStateException("KIZ_SOURCE_REQUIRED"));
        if(report.rows().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("FBS_KN3_NO_REJECTED"));
        var shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        String key=shop.getId()+":"+ListingIdentity.fingerprint(shop);var result=new CompletableFuture<Void>();
        if(ACTIVE.putIfAbsent(key,result)!=null)return CompletableFuture.failedFuture(new IllegalStateException("FBS_KN3_REPLACEMENT_BUSY"));
        result.whenComplete((v,e)->ACTIVE.remove(key,result));new Run(shop,report,sources,result).schedule(0);return result;
    }
    private final class Run {
        final Shop shop;final Report report;final KizSourceSelection sources;final CompletableFuture<Void> done;
        final Map<Kn3Mark,String> accepted=new HashMap<>();
        Kn3Mark retryTarget;int index;final List<String> errors=new ArrayList<>();String expected="";long started=System.nanoTime(),nextProbe;
        Run(Shop shop,Report report,KizSourceSelection sources,CompletableFuture<Void> done){this.shop=shop;this.report=report;this.sources=sources;this.done=done;}
        void schedule(long millis){CLOCK.schedule(()->SellerMutationExecutor.submit(shop,()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){step();return null;}}).whenComplete((v,e)->{if(e!=null)done.completeExceptionally(e);else if(!done.isDone())schedule(1000);}),millis,TimeUnit.MILLISECONDS);}
        void step()throws Exception{
            ListingIdentity.require(shop);
            if(index<report.rows().size()&&System.nanoTime()-started>Duration.ofMinutes(5).toNanos()){errors.add(report.rows().get(index).mark().orderId()+": FBS_KN3_CONFIRMATION_PENDING");index++;expected="";retryTarget=null;started=System.nanoTime();}
            if(index==report.rows().size()){
                if(!errors.isEmpty())throw new IOException(String.join("\n",errors));
                try(var c=Database.getConnection()){
                    var current=repository.read(c,shop.getId(),shop.getMarketplace(),report.orders());
                    for(var row:report.rows()){var target=row.mark();String fingerprint=accepted.get(target);if(fingerprint==null||current.getOrDefault(target.orderId(),List.of()).stream().noneMatch(m->m.productId().equals(target.productId())&&m.exemplarId().equals(target.exemplarId())&&m.state()==Kn3Mark.State.ACCEPTED&&m.fingerprint().equals(fingerprint)))throw new IOException("FBS_KN3_SHIPMENT_NOT_CONFIRMED");}
                    if(current.values().stream().flatMap(List::stream).anyMatch(m->m.state()==Kn3Mark.State.REJECTED||m.state()==Kn3Mark.State.PENDING||m.state()==Kn3Mark.State.UNAVAILABLE))throw new IOException("FBS_KN3_SHIPMENT_NOT_CONFIRMED");
                }done.complete(null);return;
            }
            var original=report.rows().get(index);var row=retryTarget==null?original:new Row(retryTarget,original.productId(),original.barcode(),original.gtin(),original.article());var target=row.mark();
            if(!expected.isEmpty()&&System.nanoTime()>=nextProbe){
                nextProbe=System.nanoTime()+Duration.ofSeconds(10).toNanos();
                try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){actions.refresh(shop,target.orderId());}catch(IOException|com.vncode.app.shared.RateLimitDeferral.Deferred retryRead){return;}
            }
            var current=repository.read(shop,target.orderId()).stream().filter(m->m.productId().equals(target.productId())&&m.exemplarId().equals(target.exemplarId())).findFirst().orElseThrow(()->new IOException("FBS_KN3_CHANGED"));
            if(!expected.isEmpty()){
                if(current.fingerprint().equals(expected)&&current.state()==Kn3Mark.State.ACCEPTED){accepted.put(original.mark(),current.fingerprint());index++;expected="";retryTarget=null;started=System.nanoTime();}
                else if(current.fingerprint().equals(expected)&&current.state()==Kn3Mark.State.REJECTED){retryTarget=current;expected="";started=System.nanoTime();nextProbe=0;}
                return;
            }
            if(current.state()==Kn3Mark.State.ACCEPTED){accepted.put(original.mark(),current.fingerprint());index++;retryTarget=null;started=System.nanoTime();return;}
            if(current.state()!=Kn3Mark.State.REJECTED||!current.fingerprint().equals(target.fingerprint()))throw new IOException("FBS_KN3_CHANGED");
            Kiz source=null;
            try{source=actions.reserve(shop,row,sources);actions.replace(shop,row,source);expected=Kn3Mark.fingerprint(source.getCode());nextProbe=System.nanoTime()+Duration.ofSeconds(2).toNanos();}
            catch(SecurityException|com.vncode.app.features.license.LicenseGate.LicenseDeniedException fatal){if(source!=null)actions.releaseUnclaimed(shop,source);throw fatal;}
            catch(Exception e){if(source!=null)actions.releaseUnclaimed(shop,source);if("KIZ_PURCHASE_PENDING".equals(e.getMessage()))return;errors.add(target.orderId()+": "+failureCode(e));index++;expected="";retryTarget=null;started=System.nanoTime();}
            com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_STATUS);
        }
    }
    private static String failureCode(Exception error){String message=Objects.toString(error.getMessage(),"");return message.matches("[A-Z][A-Z0-9_]{1,100}")?message:"FBS_KN3_REQUEST_FAILED";}
    static final class StockActions implements Actions {
        final ZnackGtinInventoryService inventory=new ZnackGtinInventoryService();
        public Kiz reserve(Shop shop,Row row,KizSourceSelection sources)throws Exception{
            new Kn3Repository().discardRejected(shop,row.mark());
            var code=new KizCategoryAllocator().reserveReplacement(shop,sources.sellerImported(),sources.purchased());
            if(code==null)throw new IOException("KIZ_INVENTORY_SHORTAGE");
            return code;
        }
        public void replace(Shop shop,Row row,Kiz source)throws Exception{new Kn3ReplacementService().replace(shop,row.mark(),source.getCode(),source);}
        public void refresh(Shop shop,String order)throws Exception{
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){var metadata=KizService.getSgtinMetadata(shop,List.of(Long.valueOf(order))).get(Long.valueOf(order));if(metadata==null||!metadata.available())throw new IOException("WB_METADATA_UNAVAILABLE");new Kn3Repository().observe(shop,order,List.of(new Kn3Mark(order,"","",metadata.appliedValue(),Kn3Mark.wbState(metadata.decision(),metadata.appliedValue()),metadata.decision())));}
            else{var api=new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()));new Kn3SyncService().ozon(shop,OzonJson.requirePostingDetail(api.getPosting(order,true),order),api);}
        }
        public void releaseUnclaimed(Shop shop,Kiz code){
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM kiz_codes WHERE shop_id=? AND id=? AND reservation_token=? AND status='RESERVED' AND reservation_recoverable=1")){p.setInt(1,shop.getId());p.setInt(2,code.getId());p.setString(3,code.getReservationToken());boolean unclaimed;try(var r=p.executeQuery()){unclaimed=r.next();}if(unclaimed)inventory.release(shop.getId(),List.of(code));}catch(java.sql.SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
        }
    }
}
