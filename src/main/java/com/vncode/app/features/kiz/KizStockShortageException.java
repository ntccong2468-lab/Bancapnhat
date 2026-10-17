package com.vncode.app.features.kiz;

import java.util.List;

/** Public order/product/category IDs only; no marking-code contents. */
public final class KizStockShortageException extends IllegalStateException {
    public record Missing(String order,String product,String category) {}
    private final List<Missing> missing;
    public KizStockShortageException(List<Missing> missing) {
        super("KIZ_CATEGORY_STOCK_SHORTAGE");this.missing=List.copyOf(missing);
    }
    public List<Missing> missing(){return missing;}
}
