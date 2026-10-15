package com.vncode.app.integration.ozon;

import com.vncode.app.features.kiz.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** Called only from a seller's print action, never from background readiness rendering. */
public final class OzonStockReplenishment {
    public void forPrint(Shop shop,List<String> numbers)throws Exception {
        var warehouse=new KizWarehouseRepository();if(!warehouse.autoPurchase(shop))return;
        var readiness=new OzonPrintReadinessService().inspectAll(shop,numbers);
        if(readiness.postings().stream().anyMatch(r->!r.postingAvailable()||!r.unsupportedRequirements().isEmpty()||!r.missingSkus().isEmpty()))return;
        var jobs=new OzonExemplarJobRepository();var existing=jobs.findMany(shop.getId(),numbers);
        var counts=jobs.bindingCounts(shop.getId(),existing.values().stream().map(OzonExemplarJob::id).toList());
        var required=new TreeMap<String,Integer>();
        for(var row:readiness.postings()){
            var job=existing.get(row.postingNumber());boolean secured=job!=null&&job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED&&counts.getOrDefault(job.id(),0)==row.requiredKiz();
            if(secured||row.requiredKiz()==0)continue;row.gtinAvailability().forEach(g->required.merge(g.gtin(),g.required(),Math::addExact));
        }
        if(required.isEmpty())return;var result=new KizStockReplenisher().ensure(shop,required);
        if(result.waiting())throw new KizAssignmentException("KIZ_PURCHASE_PENDING","","");
    }
}
