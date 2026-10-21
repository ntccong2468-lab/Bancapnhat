package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.ProductVariant;
import com.vncode.app.integration.marketplace.Marketplace;

/** Uses the same explicit size field as the marketplace catalog reader; never converts sizes. */
public final class GtinVariantIdentity {
    private GtinVariantIdentity(){}
    public static String primarySize(com.vncode.app.features.products.ProductRow product){return product.manufacturerSize().isBlank()?product.size():product.manufacturerSize();}
    public static boolean russianSize(GtinSyncModels.RegisteredGtin source){
        var types=source.details().values("@size_type");
        return types.size()==1&&java.util.Set.of("РОССИЯ","РОССИЙСКИЙ","РОССИЙСКИЙ РАЗМЕР","RUSSIA","RUSSIAN","RU").contains(GtinMatchingService.normalize(types.getFirst()));
    }
    public static boolean sizeMatches(GtinSyncModels.RegisteredGtin source,String primarySize,String russianSize){
        String actual=russianSize(source)&&russianSize!=null&&!russianSize.isBlank()?russianSize:primarySize;
        String target=GtinMatchingService.normalize(actual),registered=GtinMatchingService.normalize(source.size());
        return !target.isBlank()&&!registered.isBlank()&&target.equals(registered);
    }
    /** Shared GTINs require the same physical variant, rather than merely the same size label. */
    public static boolean samePhysicalVariant(com.vncode.app.features.products.ProductRow left,com.vncode.app.features.products.ProductRow right){
        String article=GtinMatchingService.normalizeArticle(left.vendorArticle()),size=GtinMatchingService.normalize(primarySize(left));
        return !article.isBlank()&&article.equals(GtinMatchingService.normalizeArticle(right.vendorArticle()))
                &&OzonSkuGtinMatcher.sameColor(left.color(),right.color())
                &&!size.isBlank()&&size.equals(GtinMatchingService.normalize(primarySize(right)))
                &&(left.russianSize().isBlank()||right.russianSize().isBlank()
                    ||GtinMatchingService.normalize(left.russianSize()).equals(GtinMatchingService.normalize(right.russianSize())));
    }
    public static String size(Marketplace marketplace,ProductVariant variant){
        return marketplace==Marketplace.OZON&&variant.russianSize()!=null&&!variant.russianSize().isBlank()
                ?variant.russianSize():variant.size();
    }
}
