package com.vncode.app.features.products;

import com.vncode.app.features.listingcopy.ListingTargetAdapter;
import com.vncode.app.features.listingcopy.ListingMediaInspector;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** Short-lived API snapshots/dictionaries, discarded between preview, send and readback. */
public final class ProductEditApiSession implements AutoCloseable {
    private final ProductSnapshotReader reader;
    private final ListingTargetAdapter adapter;
    private final GtinBatchTransport batch;
    private final ListingDictionaryCache cache;
    private ProductSnapshotReader attributeReader;
    private boolean attributesOnly;
    public ProductEditApiSession(ProductSnapshotReader reader,ListingTargetAdapter adapter,GtinBatchTransport batch){this(reader,adapter,batch,null);}
    public ProductEditApiSession(ProductSnapshotReader reader,ListingTargetAdapter adapter,GtinBatchTransport batch,ListingDictionaryCache cache){this.reader=reader;this.adapter=adapter;this.batch=batch;this.cache=cache;}
    public ProductSnapshotReader reader(){return attributesOnly&&attributeReader!=null?attributeReader:reader;}
    public void useAttributeReads(){attributesOnly=true;}
    public void refreshCache(Shop shop,ProductKey key)throws IOException{reader.read(shop,key);}
    public ListingTargetAdapter adapter(){return adapter;}
    public static ProductEditApiSession open(Shop shop,List<ProductKey> keys)throws IOException{
        return open(shop,keys,new ListingHttpClient(shop),useIndex(shop,keys));
    }
    public static ProductEditApiSession open(Shop shop,List<ProductKey> keys,ListingTransport upstream,boolean indexed)throws IOException{
        ListingIdentity.require(shop);for(var key:keys)ListingIdentity.require(shop,key);
        var cache=new ListingDictionaryCache(upstream);ListingTransport transport=cache;
        GtinBatchTransport batch=null;
        if(indexed){
            batch=new GtinBatchTransport(shop,transport);
            try{batch.prepare(convert(keys));}catch(IOException|RuntimeException failure){batch.close();throw failure;}
            transport=batch;
        }
        var api=transport;
        if(shop.getMarketplace()==Marketplace.WILDBERRIES){var reader=new WbProductSnapshotReader(s->api);return new ProductEditApiSession(reader::readForSynchronization,new WbListingAdapter(s->api,ListingMediaInspector.fullSize()),batch,cache);}
        var reader=new OzonProductSnapshotReader(s->api);var session=new ProductEditApiSession(reader::readForSynchronization,new OzonListingAdapter(s->api,ListingMediaInspector.fullSize()),batch,cache);session.attributeReader=reader::readForProductEdit;return session;
    }
    private static boolean useIndex(Shop shop,List<ProductKey> keys){
        long selected=keys.stream().map(ProductKey::cardId).distinct().count();if(selected<20)return false;
        String table=shop.getMarketplace()==Marketplace.WILDBERRIES?"wb_product_cards":"ozon_products";
        try(var c=com.vncode.app.config.Database.getConnection();var q=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE shop_id=?")){
            q.setInt(1,shop.getId());try(var r=q.executeQuery()){return r.next()&&r.getLong(1)<=selected*50;}
        }catch(java.sql.SQLException failure){return false;}
    }
    public void refresh(List<ProductKey> keys)throws IOException{if(batch!=null)batch.refresh(convert(keys));}
    public void beginReadback(){if(cache!=null)cache.beginReadback();}
    private static List<com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey> convert(List<ProductKey> keys){return keys.stream().map(k->new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(k.shopId(),k.marketplace(),k.cardId(),k.variantId())).toList();}
    @Override public void close(){if(batch!=null)batch.close();}
}
