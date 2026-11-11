package com.vncode.app.features.products;

import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.List;
import java.util.Set;
@FunctionalInterface
public interface ProductSnapshotReader {
    ProductSnapshot read(Shop shop,ProductKey key) throws IOException;
    /** One bounded fresh observation for an explicit refresh; read-only lambdas remain supported. */
    default Batch openBatch(Shop shop,List<ProductKey> keys)throws IOException {
        Set<ProductKey> selected=Set.copyOf(keys);
        return key->{
            if(!selected.contains(key))throw new SecurityException("Unselected product refresh");
            return read(shop,key);
        };
    }
    interface Batch extends AutoCloseable {
        ProductSnapshot read(ProductKey key)throws IOException;
        @Override default void close()throws IOException {}
    }
}
