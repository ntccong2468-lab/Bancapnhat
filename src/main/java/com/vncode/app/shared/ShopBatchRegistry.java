package com.vncode.app.shared;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop-local ownership; clients on different machines have separate data and queues. */
public final class ShopBatchRegistry {
    private record Key(String data,Marketplace marketplace,int shop,String action){}
    private static final Map<Key,Lease> JOBS=new ConcurrentHashMap<>();
    private static final Map<java.nio.file.Path,Lease> OUTPUTS=new HashMap<>();
    private ShopBatchRegistry(){}
    public static synchronized Optional<Lease> claim(Shop shop,String action){
        return claim(shop,action,List.of());
    }
    public static synchronized Optional<Lease> claim(Shop shop,String action,Collection<java.nio.file.Path> outputs){
        Objects.requireNonNull(shop);if(action==null||!action.matches("[a-z-]{1,40}"))throw new IllegalArgumentException("Invalid batch action");
        var key=new Key(AppPaths.appDataDir().toAbsolutePath().normalize().toString(),shop.getMarketplace(),shop.getId(),action);
        if(outputs==null||outputs.size()>8)throw new IllegalArgumentException("Invalid output selection");
        var targets=outputs.stream().map(ShopBatchRegistry::outputPath).distinct().toList();
        if(targets.stream().anyMatch(OUTPUTS::containsKey))return Optional.empty();
        if(JOBS.size()>=32||JOBS.containsKey(key))return Optional.empty();
        var lease=new Lease(key,ListingIdentity.fingerprint(shop),targets);JOBS.put(key,lease);targets.forEach(path->OUTPUTS.put(path,lease));return Optional.of(lease);
    }
    private static java.nio.file.Path outputPath(java.nio.file.Path value){
        Objects.requireNonNull(value);var path=value.toAbsolutePath().normalize();var parent=path.getParent();
        try{return parent!=null&&java.nio.file.Files.exists(parent)?parent.toRealPath().resolve(path.getFileName()):path;}
        catch(java.io.IOException unavailable){throw new IllegalArgumentException("Export directory unavailable");}
    }
    public static boolean hasWork(){return !JOBS.isEmpty();}
    public static int activeCount(){return JOBS.size();}
    public static boolean hasShop(int shopId){return JOBS.keySet().stream().anyMatch(k->k.shop()==shopId&&k.data().equals(AppPaths.appDataDir().toAbsolutePath().normalize().toString()));}
    public static final class Lease implements AutoCloseable {
        private final Key key;private final String owner;private final List<java.nio.file.Path> outputs;private final AtomicBoolean stop=new AtomicBoolean(),closed=new AtomicBoolean();
        private Lease(Key key,String owner,List<java.nio.file.Path> outputs){this.key=key;this.owner=owner;this.outputs=outputs;}
        public void requestStop(){stop.set(true);}
        public boolean stopping(){return stop.get();}
        public void requireOwner(Shop shop){if(closed.get()||shop.getId()!=key.shop()||shop.getMarketplace()!=key.marketplace()||!owner.equals(ListingIdentity.fingerprint(shop)))throw new IllegalStateException("Batch shop changed");}
        @Override public void close(){if(closed.compareAndSet(false,true))synchronized(ShopBatchRegistry.class){JOBS.remove(key,this);outputs.forEach(path->OUTPUTS.remove(path,this));}}
        @Override public String toString(){return "ShopBatchLease[redacted]";}
    }
}
