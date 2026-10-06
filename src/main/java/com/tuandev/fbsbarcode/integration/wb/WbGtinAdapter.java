package com.tuandev.fbsbarcode.integration.wb;
import com.tuandev.fbsbarcode.features.gtinsync.*;
import com.tuandev.fbsbarcode.models.Shop;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

/** Read-only until the official mutation contract can be verified. */
public final class WbGtinAdapter implements GtinMarketplaceAdapter {
    private final WbGtinProductReader reader;private final int shopId;
    public WbGtinAdapter(Shop shop){shopId=shop.getId();reader=new WbGtinProductReader(shop,new WbApiClient());}
    public Capability capability(ProductKey key){requireKey(key);return new Capability(false,false,"api_contract_unverified");}
    public ProductSnapshot read(ProductKey key)throws Exception{requireKey(key);return reader.read(key);}
    public boolean conflicts(ProductKey key,String gtin)throws Exception{requireKey(key);return reader.list().stream().anyMatch(p->!p.key().equals(key)&&p.barcodes().contains(gtin));}
    public Submission submit(Preview p){requireKey(p.before().key());throw new UnsupportedOperationException("api_contract_unverified");}
    public Verification verify(Preview p,Submission s){requireKey(p.before().key());return Verification.UNKNOWN;}
    private void requireKey(ProductKey key){if(key.shopId()!=shopId||key.marketplace()!=com.tuandev.fbsbarcode.integration.marketplace.Marketplace.WILDBERRIES)throw new IllegalArgumentException("shop_mismatch");}
}
