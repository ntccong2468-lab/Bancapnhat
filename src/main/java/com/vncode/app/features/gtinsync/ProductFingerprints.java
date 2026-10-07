package com.vncode.app.features.gtinsync;
import java.util.List;
import java.time.Instant;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
public final class ProductFingerprints {
    private ProductFingerprints() {}
    public static ProductSnapshot snapshot(ProductKey key,String article,String color,String size,List<String> barcodes) {
        var codes=barcodes==null?List.<String>of():List.copyOf(barcodes);
        String fingerprint=GtinSyncRepository.hash(GtinJson.GSON.toJson(List.of(key,safe(article),safe(color),safe(size),codes.stream().distinct().sorted().toList())));
        return new ProductSnapshot(key,article,color,size,codes,fingerprint,Instant.now());
    }
    private static String safe(String value){return value==null?"":value;}
}
