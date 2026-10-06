package com.tuandev.fbsbarcode.integration.ozon;
import com.tuandev.fbsbarcode.features.gtinsync.*;
import com.tuandev.fbsbarcode.models.Shop;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

/** No undocumented barcode mutation is sent. */
public final class OzonGtinAdapter implements GtinMarketplaceAdapter {
    private final OzonGtinProductReader reader;private final int shopId;
    public OzonGtinAdapter(Shop shop){shopId=shop.getId();reader=new OzonGtinProductReader(shop,new OzonApiClient(shopId,new OzonCredentials(shop.getClientId(),shop.getApiKey())));}
    public Capability capability(ProductKey key){requireKey(key);return new Capability(false,false,"api_contract_unverified");}
    public ProductSnapshot read(ProductKey key)throws Exception{requireKey(key);return reader.read(key);}
    public boolean conflicts(ProductKey key,String gtin)throws Exception{requireKey(key);return reader.list().stream().anyMatch(p->!p.key().equals(key)&&p.barcodes().contains(gtin));}
    public Submission submit(Preview p){requireKey(p.before().key());throw new UnsupportedOperationException("api_contract_unverified");}
    public Verification verify(Preview p,Submission s){requireKey(p.before().key());return Verification.UNKNOWN;}
    private void requireKey(ProductKey key){if(key.shopId()!=shopId||key.marketplace()!=com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");}
}
