package com.vncode.app.integration.ozon;

import com.vncode.app.features.fbo.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import java.io.IOException;
import java.util.*;

/** Ozon keeps native identity and atomic reservations, then merges marked units into one label. */
public final class OzonFboKizPrintPlanner {
    private final FboKizPrintPlanner planner=new FboKizPrintPlanner();
    public FboPrintPlan plan(int shopId,List<FboBarcodePrintItem> items){
        var selected=items==null?List.<FboBarcodePrintItem>of():items.stream().filter(i->i!=null&&i.product()!=null&&i.quantity()>0).toList();
        if(selected.size()>10000||selected.stream().mapToLong(FboBarcodePrintItem::quantity).sum()>100000)throw new IllegalArgumentException("FBO batch is too large");
        var products=new OzonCatalogRepository().findBySkusForPrinting(shopId,selected.stream().map(i->i.product().catalogSku()).distinct().toList());
        var canonical=new ArrayList<FboBarcodePrintItem>();
        // Resolve actual registered barcodes before reserving any KIZ. The UI row is not proof.
        for(var item:selected){var prior=item.product();var actual=products.get(prior.catalogSku());
            try{
                if(actual!=null&&!prior.productId().equals(actual.productId()))throw OzonProductLabelBarcode.failure("OZON_LABEL_PRODUCT_MISMATCH","ozon.label.product.mismatch","Dữ liệu sản phẩm không khớp với sản phẩm Ozon đã chọn. Đồng bộ lại Sản phẩm.",expected(prior));
                String barcode=OzonCombinedLabelRenderer.barcodeFor(expected(prior),actual);
                var product=new FboProductSku(prior.nmId(),first(actual.article(),actual.offerId()),actual.category(),prior.brand(),actual.name(),actual.color(),first(actual.size(),actual.russianSize()),first(actual.russianSize(),actual.size()),barcode,actual.primaryImageUrl(),prior.requiresKiz(),actual.sku(),actual.offerId(),prior.verifiedGtin(),actual.productId());
                canonical.add(new FboBarcodePrintItem(product,item.quantity()));
            }catch(IOException invalid){throw new IllegalStateException(invalid.getMessage(),invalid);}
        }
        var reserved=planner.plan(shopId,Marketplace.OZON,canonical);
        try{
            var units=new LinkedHashMap<Integer,List<FboPrintPage>>();for(var page:reserved.pages())units.computeIfAbsent(page.pairNumber(),k->new ArrayList<>()).add(page);
            var pages=new ArrayList<FboPrintPage>();
            for(var unit:units.values()){
                var codes=unit.stream().filter(p->p.kind()==FboPrintPage.Kind.KIZ).toList();
                if(codes.size()>1||codes.isEmpty()&&unit.getFirst().product().requiresKiz())throw new IllegalStateException("OZON_FBO_KIZ_BINDING_MISMATCH");
                if(codes.isEmpty())pages.addAll(unit); // The existing two unmarked barcode copies remain.
                else{var kiz=codes.getFirst();pages.add(new FboPrintPage(kiz.product(),kiz.kizCode(),kiz.pairNumber(),FboPrintPage.Kind.COMBINED,kiz.categoryStock()));}
            }
            return new FboPrintPlan(List.copyOf(pages),reserved.usedKizs());
        }catch(RuntimeException failure){new ZnackGtinInventoryService().release(shopId,reserved.usedKizs());throw failure;}
    }
    private static OzonPostingItemDto expected(FboProductSku p){return new OzonPostingItemDto(0,p.productId(),p.catalogSku(),p.variantId(),p.title(),1,"","");}
    private static String first(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
}
