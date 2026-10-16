package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** A user-triggered print can enqueue only the shortage, after stock and pending buys are read. */
public final class KizStockReplenisher {
    private static final Object[] LOCKS=new Object[64];static{Arrays.setAll(LOCKS,i->new Object());}
    interface Purchases {void validate(String gtin,int quantity)throws Exception;void enqueue(String gtin,int quantity,String key)throws Exception;void resume();}
    private final java.util.function.Function<Shop,Purchases> purchases;
    public KizStockReplenisher(){this(shop->{var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));var settings=repository.getSettings();var coordinator=ZnackPurchaseCoordinator.create(repository);return new Purchases(){
        public void validate(String gtin,int quantity)throws Exception{coordinator.validatePrerequisites(settings,gtin,quantity);}
        public void enqueue(String gtin,int quantity,String key)throws Exception{coordinator.enqueue(settings,gtin,quantity,key);}
        public void resume(){coordinator.resumeAsync(settings);}
    };});}
    KizStockReplenisher(java.util.function.Function<Shop,Purchases> purchases){this.purchases=purchases;}
    public record Result(Map<String,Integer> missing,int enqueued,boolean waiting){public Result{missing=Map.copyOf(missing);}}
    public Result ensure(Shop shop,Map<String,Integer> quantities)throws Exception {
        return ensure(shop,quantities,missing->{});
    }
    public Result ensure(Shop shop,Map<String,Integer> quantities,java.util.function.Consumer<Map<String,Integer>> purchaseProof)throws Exception {
        return ensure(shop,quantities,purchaseProof,false);
    }
    /** Called only after the shipment's explicit purchase checkbox has been captured. */
    public Result ensurePurchased(Shop shop,Map<String,Integer> quantities,java.util.function.Consumer<Map<String,Integer>> purchaseProof)throws Exception {
        return ensure(shop,quantities,purchaseProof,true);
    }
    private Result ensure(Shop shop,Map<String,Integer> quantities,java.util.function.Consumer<Map<String,Integer>> purchaseProof,boolean purchasedOnly)throws Exception {
        if(shop==null||shop.getId()<=0||quantities==null||quantities.size()>10000)throw new IllegalArgumentException("KIZ_QUANTITY_LIMIT");
        var normalized=new TreeMap<String,Integer>();long total=0;for(var entry:quantities.entrySet()){
            if(entry.getValue()==null||entry.getValue()<=0||entry.getValue()>100000)throw new IllegalArgumentException("KIZ_QUANTITY_LIMIT");
            total+=entry.getValue();if(total>100000)throw new IllegalArgumentException("KIZ_QUANTITY_LIMIT");normalized.merge(GtinNormalizer.normalize(entry.getKey()),entry.getValue(),Math::addExact);
        }
        synchronized(LOCKS[shop.getId()%LOCKS.length]){
            return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
                ListingIdentity.require(shop);var warehouse=new KizWarehouseRepository();
                var inventory=new ZnackGtinInventoryService();
                var counts=purchasedOnly?inventory.purchasedAvailableCounts(shop.getId(),normalized.keySet()):inventory.availableCounts(shop.getId(),normalized.keySet());
                var missing=new TreeMap<String,Integer>();normalized.forEach((g,n)->{int shortage=n-counts.getOrDefault(g,0);if(shortage>0)missing.put(g,shortage);});
                if(missing.isEmpty()||!purchasedOnly&&!warehouse.autoPurchase(shop))return new Result(missing,0,false);
                LicenseRuntime.require(Permission.KIZ);
                var waiting=new HashSet<String>();
                // Do not buy over an unresolved purchase, introduction or unverified imported stock.
                try(var c=Database.getConnection();var p=c.prepareStatement("""
                    SELECT DISTINCT gtin FROM znack_purchase_pipelines WHERE shop_id=?
                      AND stage NOT IN ('INTRODUCED','COMPLETED','FAILED','INTRODUCTION_FAILED')
                    UNION SELECT DISTINCT gtin FROM kiz_codes WHERE shop_id=? AND status='AVAILABLE'
                      AND COALESCE(legal_status,'')<>'IN_CIRCULATION'
                    """+(purchasedOnly?" AND NOT EXISTS(SELECT 1 FROM kiz_import_entries imported WHERE imported.code_id=kiz_codes.id)":""))){
                    p.setInt(1,shop.getId());p.setInt(2,shop.getId());try(var r=p.executeQuery()){while(r.next())waiting.add(r.getString(1));}
                }
                var toBuy=new TreeMap<>(missing);waiting.forEach(toBuy::remove);
                if(toBuy.isEmpty())return new Result(missing,0,true);
                purchaseProof.accept(Map.copyOf(toBuy));
                var buyer=purchases.apply(shop);
                for(var entry:toBuy.entrySet())buyer.validate(entry.getKey(),entry.getValue());
                String request=UUID.randomUUID().toString();int enqueued=0;
                for(var entry:toBuy.entrySet()){
                    ListingIdentity.require(shop);buyer.enqueue(entry.getKey(),entry.getValue(),"stock:"+request+":"+entry.getKey());enqueued++;
                }
                if(enqueued>0)buyer.resume();
                return new Result(missing,enqueued,!missing.isEmpty());
            });
        }
    }
}
