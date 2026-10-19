package com.vncode.app.features.sync;

import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.util.*;
import java.io.IOException;

/** One click/worker pass, one owned catalog index. No account data survives the scope. */
final class GtinBulkSession implements AutoCloseable {
    private final Shop shop;
    private final GtinBatchTransport transport;
    private final GtinBatchAdapter adapter;
    private final WbProductSnapshotReader wb;
    private final OzonProductSnapshotReader ozon;
    private final com.vncode.app.config.Database.BatchSession databaseSession;
    private final ListingIdentity.OwnerCheckBatch ownerChecks;
    private final LinkedHashMap<String,ProductSnapshot> snapshots=new LinkedHashMap<>(64,.75f,true);
    private long snapshotBytes;
    private long generation=-1;
    final CatalogSyncService catalog;
    GtinBulkSession(Shop shop){this(shop,new ListingHttpClient(shop));}
    GtinBulkSession(Shop shop,ListingTransport client){this(shop,client,null);}
    GtinBulkSession(Shop shop,ListingTransport client,OzonGtinAdapter.BeforeRequest beforeRequest){
        this.shop=shop;transport=new GtinBatchTransport(shop,client);
        adapter=shop.getMarketplace()==Marketplace.WILDBERRIES?new WbGtinAdapter(shop,transport):beforeRequest==null?new OzonGtinAdapter(shop,transport):new OzonGtinAdapter(shop,transport,beforeRequest);
        wb=new WbProductSnapshotReader(s->transport);ozon=new OzonProductSnapshotReader(s->transport);
        databaseSession=com.vncode.app.config.Database.openBatchSession();
        ownerChecks=openOwnerChecks(shop,databaseSession);
        try{catalog=new CatalogSyncService(new CatalogSyncRepository(),new GtinSyncRepository(),new ProductBulkEditService(),this::read,
            s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter(),key->adapter,RegisteredGtinProof::requireCurrent);}
        catch(RuntimeException failure){transport.close();ownerChecks.close();databaseSession.close();throw failure;}
    }
    private static ListingIdentity.OwnerCheckBatch openOwnerChecks(Shop shop,com.vncode.app.config.Database.BatchSession database){
        try{return ListingIdentity.batchChecks(shop);}catch(RuntimeException failure){try{database.close();}catch(RuntimeException close){failure.addSuppressed(close);}throw failure;}
    }
    void prepare(Collection<GtinSyncModels.ProductKey> keys)throws Exception{adapter.prepareBatch(keys);}
    ProductSnapshot read(Shop owner,ProductKey key)throws IOException {
        ListingIdentity.require(owner,key);if(!ListingIdentity.fingerprint(owner).equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("shop_mismatch");
        if(generation!=transport.generation()){snapshots.clear();snapshotBytes=0;generation=transport.generation();}
        var cached=snapshots.get(key.cardId());
        if(cached==null){cached=shop.getMarketplace()==Marketplace.OZON?ozon.readForGtinSync(shop,key):wb.readForGtinSync(shop,key);long size=weight(cached);
            while(snapshotBytes+size>16L*1024*1024&&!snapshots.isEmpty()){var id=snapshots.keySet().iterator().next();snapshotBytes-=weight(snapshots.remove(id));}
            if(size<=16L*1024*1024){snapshots.put(key.cardId(),cached);snapshotBytes+=size;}
        }
        if(cached.key().equals(key))return cached;
        return new ProductSnapshot(key,cached.revision(),cached.title(),cached.description(),cached.brand(),cached.vendorArticle(),cached.categoryId(),cached.media(),cached.attributes(),cached.variants(),cached.dimensions(),cached.documents(),cached.checkedAt(),cached.metadata());
    }
    private static long weight(ProductSnapshot snapshot){return 2048L+snapshot.metadata().getOrDefault("source.payload","").length()*2L+snapshot.variants().size()*512L;}
    @Override public void close(){snapshots.clear();snapshotBytes=0;try{transport.close();}finally{try{ownerChecks.close();}finally{databaseSession.close();}}}
}
