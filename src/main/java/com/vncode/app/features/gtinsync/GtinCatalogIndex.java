package com.vncode.app.features.gtinsync;

import com.vncode.app.integration.znack.GtinNormalizer;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Build once per Catalog snapshot; matching a large shop must not scan every GTIN for every variant. */
public final class GtinCatalogIndex {
    private record Attributes(String article,String color,String size){}
    private final Map<String,List<RegisteredGtin>> codes=new HashMap<>();
    private final Map<Attributes,List<RegisteredGtin>> attributes=new HashMap<>();
    public GtinCatalogIndex(List<RegisteredGtin> rows){
        for(var row:rows)if(row.published()&&row.tradeUnit()&&RegisteredGtinValidator.isValid(row.gtin())&&!GtinNormalizer.isTechnicalRange(row.gtin())){
            codes.computeIfAbsent(GtinNormalizer.normalize(row.gtin()),key->new ArrayList<>()).add(row);
            attributes.computeIfAbsent(key(row.article(),row.color(),row.size()),key->new ArrayList<>()).add(row);
        }
    }
    public List<RegisteredGtin> code(String gtin){return RegisteredGtinValidator.isValid(gtin)?List.copyOf(codes.getOrDefault(GtinNormalizer.normalize(gtin),List.of())):List.of();}
    public List<RegisteredGtin> codes(List<String> barcodes){var result=new LinkedHashSet<RegisteredGtin>();for(String barcode:barcodes)result.addAll(code(barcode));return List.copyOf(result);}
    public List<RegisteredGtin> attributes(String article,String color,String size){
        return attributes(article,color,size,"");
    }
    public List<RegisteredGtin> attributes(String article,String color,String size,String russianSize){
        var key=key(article,color,size);if(key.article().isBlank()||key.color().isBlank()||key.size().isBlank())return List.of();
        var found=new LinkedHashSet<>(attributes.getOrDefault(key,List.of()));
        if(russianSize!=null&&!russianSize.isBlank())attributes.getOrDefault(key(article,color,russianSize),List.of()).stream().filter(GtinVariantIdentity::russianSize).forEach(found::add);
        return found.stream().filter(row->GtinVariantIdentity.sizeMatches(row,size,russianSize)).toList();
    }
    private static Attributes key(String article,String color,String size){return new Attributes(GtinMatchingService.normalizeArticle(article),GtinMatchingService.normalize(color),GtinMatchingService.normalize(size));}
}
