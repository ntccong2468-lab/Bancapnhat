package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.ProductVariant;
import com.vncode.app.integration.marketplace.Marketplace;

/** Uses the same explicit size field as the marketplace catalog reader; never converts sizes. */
public final class GtinVariantIdentity {
    private GtinVariantIdentity(){}
    public static boolean russianSize(GtinSyncModels.RegisteredGtin source){
        var types=source.details().values("@size_type");
        return types.size()==1&&java.util.Set.of("РОССИЯ","РОССИЙСКИЙ","РОССИЙСКИЙ РАЗМЕР","RUSSIA","RUSSIAN","RU").contains(GtinMatchingService.normalize(types.getFirst()));
    }
    public static boolean sizeMatches(GtinSyncModels.RegisteredGtin source,String primarySize,String russianSize){
        String actual=russianSize(source)&&russianSize!=null&&!russianSize.isBlank()?russianSize:primarySize;
        return GtinMatchingService.normalize(actual).equals(GtinMatchingService.normalize(source.size()));
    }
    public static String size(Marketplace marketplace,ProductVariant variant){
        return marketplace==Marketplace.OZON&&variant.russianSize()!=null&&!variant.russianSize().isBlank()
                ?variant.russianSize():variant.size();
    }
}
