package com.tuandev.fbsbarcode.ui.gtinsync;

import com.tuandev.fbsbarcode.features.gtinsync.*;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.util.*;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

/** Plain view state. Tokens prevent old async reads from overwriting a newly selected shop. */
public final class GtinSyncViewModel {
    public record LoadToken(int shopId,Marketplace marketplace,long generation) {}
    public record Row(ProductSnapshot product,String gtin,Operation operation,String oldGtin,String reason) {}
    private long generation;
    private LoadToken current=new LoadToken(0,Marketplace.WILDBERRIES,0);
    private List<RegisteredGtin> catalog=List.of();
    private final Map<ProductKey,Row> rows=new LinkedHashMap<>();
    public synchronized void load(Shop shop) {
        current=new LoadToken(shop==null?0:shop.getId(),shop==null?Marketplace.WILDBERRIES:shop.getMarketplace(),++generation);
        catalog=List.of();rows.clear();
    }
    public synchronized LoadToken token(){return current;}
    public synchronized List<RegisteredGtin> catalog(){return catalog;}
    public synchronized List<Row> rows(){return List.copyOf(rows.values());}
    public synchronized boolean accept(LoadToken token,List<RegisteredGtin> catalog,List<ProductSnapshot> products) {
        if(!current.equals(token))return false;
        if(products.stream().anyMatch(p->p.key().shopId()!=token.shopId()||p.key().marketplace()!=token.marketplace()))throw new IllegalArgumentException("shop_mismatch");
        var prepared=new LinkedHashMap<ProductKey,Row>();var matcher=new GtinMatchingService();
        for(var p:products) {
            var match=matcher.match(p,catalog);String gtin=match.exact()?match.candidates().getFirst().gtin():"";
            String reason=match.reason();if(!gtin.isBlank()&&p.barcodes().contains(gtin))reason="already_present";
            if(prepared.put(p.key(),new Row(p,gtin,Operation.ADD,"",reason))!=null)throw new IllegalArgumentException("duplicate_product");
        }
        this.catalog=List.copyOf(catalog);rows.clear();rows.putAll(prepared);return true;
    }
    public synchronized void choose(ProductKey key,String gtin,Operation operation,String oldGtin) {
        rows.put(key,selection(key,gtin,operation,oldGtin));
    }
    public synchronized Row selection(ProductKey key,String gtin,Operation operation,String oldGtin) {
        var row=rows.get(key);if(row==null)throw new IllegalArgumentException("product_not_found");
        if(operation==null)throw new IllegalArgumentException("invalid_operation");
        if(catalog.stream().noneMatch(g->g.gtin().equals(gtin)&&g.tradeUnit()&&g.published()&&RegisteredGtinValidator.isValid(g.gtin())))
            throw new IllegalArgumentException("unverified_catalog_gtin");
        if(operation==Operation.REPLACE && (oldGtin==null||oldGtin.isBlank()||!row.product().barcodes().contains(oldGtin)||oldGtin.equals(gtin)))
            throw new IllegalArgumentException("old_gtin_not_found");
        if(operation==Operation.ADD && oldGtin!=null&&!oldGtin.isBlank())throw new IllegalArgumentException("unexpected_old_gtin");
        if(row.product().barcodes().contains(gtin))throw new IllegalArgumentException("already_present");
        return new Row(row.product(),gtin,operation,oldGtin==null?"":oldGtin,"manual_mapping");
    }
    public synchronized List<ProductKey> exactKeys(){return rows.values().stream().filter(r->r.reason().isBlank()&&!r.gtin().isBlank()&&r.operation()==Operation.ADD).map(r->r.product().key()).toList();}
}
