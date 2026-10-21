package com.vncode.app.ui.products;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import java.util.*;
/** Explicit selection is independent from filters and frozen before any write. FX-thread confined. */
public final class ProductSelection {
    private int shopId;private Marketplace marketplace;private final Set<ProductKey> keys=new LinkedHashSet<>();
    public void switchShop(int id,Marketplace market){if(id!=shopId||market!=marketplace){keys.clear();shopId=id;marketplace=market;}}
    public void selectPage(List<ProductKey> page){for(var key:page)requireOwner(key);keys.addAll(page);}
    public void setSelected(ProductKey key,boolean selected){requireOwner(key);if(selected)keys.add(key);else keys.remove(key);}
    public boolean contains(ProductKey key){return keys.contains(key);}
    public List<ProductKey> freeze(){return List.copyOf(keys);}
    public void clear(){keys.clear();}
    private void requireOwner(ProductKey key){if(key.shopId()!=shopId||key.marketplace()!=marketplace)throw new SecurityException("Selection owner mismatch");}
}
