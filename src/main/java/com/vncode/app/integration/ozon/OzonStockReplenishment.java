package com.vncode.app.integration.ozon;

import com.vncode.app.features.kiz.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** Called only from a seller's print action, never from background readiness rendering. */
public final class OzonStockReplenishment {
    public void forPrint(Shop shop,List<String> numbers)throws Exception {forPrint(shop,numbers,null);}
    public void forPrint(Shop shop,List<String> numbers,KizSourceSelection sources)throws Exception {
        if(sources!=null&&!sources.purchased())return;
        var warehouse=new KizWarehouseRepository();if(sources==null&&!warehouse.autoPurchase(shop))return;
        var readiness=new OzonPrintReadinessService().inspectAll(shop,numbers,sources);
        if(readiness.postings().stream().anyMatch(r->!r.postingAvailable()||!r.unsupportedRequirements().isEmpty()||!r.missingSkus().isEmpty()))return;
        var jobs=new OzonExemplarJobRepository();var existing=jobs.findMany(shop.getId(),numbers);
        var counts=jobs.bindingCounts(shop.getId(),existing.values().stream().map(OzonExemplarJob::id).toList());
        var required=new TreeMap<String,Integer>();
        for(var row:readiness.postings()){
            var job=existing.get(row.postingNumber());boolean secured=job!=null&&job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED&&counts.getOrDefault(job.id(),0)==row.requiredKiz();
            if(secured||row.requiredKiz()==0)continue;row.gtinAvailability().forEach(g->required.merge(g.gtin(),g.required(),Math::addExact));
        }
        if(required.isEmpty())return;
        var postingSnapshot=new OzonPostingRepository().findMany(shop.getId(),numbers);var skuMappings=new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId());
        var candidates=new ArrayList<KizPurchaseMappingProof.Product>();
        try(var c=com.vncode.app.config.Database.getConnection()){
            var products=OzonCategoryStock.verifiedProducts(c,shop.getId(),postingSnapshot.values().stream().flatMap(p->p.items().stream()).toList());
            for(var row:readiness.postings()){
                var posting=postingSnapshot.get(row.postingNumber());if(posting==null)continue;
                var needed=row.gtinAvailability().stream().map(OzonPrintReadiness.GtinAvailability::gtin).collect(java.util.stream.Collectors.toSet());
                for(var item:posting.items()){String gtin=skuMappings.get(item.sku());if(gtin!=null&&needed.contains(gtin))candidates.add(new KizPurchaseMappingProof.Product(products.get(item.sku()),item.sku(),gtin));}
            }
        }
        var replenisher=new KizStockReplenisher();
        java.util.function.Consumer<Map<String,Integer>> proof=missing->KizPurchaseMappingProof.require(shop,candidates,missing.keySet());
        var result=sources==null?replenisher.ensure(shop,required,proof):replenisher.ensurePurchased(shop,required,proof);
        if(result.waiting())throw new KizPurchasePendingException(required,result);
    }
}
