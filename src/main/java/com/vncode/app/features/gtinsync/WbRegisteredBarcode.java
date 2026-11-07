package com.vncode.app.features.gtinsync;

import java.util.List;
import java.util.Optional;

/** WB completion display only. A barcode prefix never grants Catalog ownership or KIZ purchase proof. */
public final class WbRegisteredBarcode {
    private WbRegisteredBarcode() {}

    public static Optional<String> first(List<String> barcodes) {
        if(barcodes==null)return Optional.empty();
        return barcodes.stream().filter(code->code!=null&&code.strip().matches("[0-9]{13,14}"))
                .map(code->RegisteredGtinProof.canonicalCode(code.strip()))
                .filter(code->code.startsWith("046")).findFirst();
    }

    /** Matches the Java display rule against actual WB size barcodes, never a Catalog candidate. */
    public static String existsSql(String variantAlias) {
        if(!variantAlias.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid variant alias");
        String digits="[0-9]".repeat(11);
        return "EXISTS(SELECT 1 FROM wb_product_size_skus present WHERE present.shop_id="+variantAlias+".shop_id"
                +" AND present.chrt_id="+variantAlias+".chrt_id AND (TRIM(present.sku) GLOB '046"+digits
                +"' OR TRIM(present.sku) GLOB '46"+digits+"'))";
    }
}
