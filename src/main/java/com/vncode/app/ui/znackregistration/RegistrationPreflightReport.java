package com.vncode.app.ui.znackregistration;

import com.google.gson.JsonObject;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.models.Shop;
import java.util.*;

/** Failed preflight is a persisted report of every selected variant, never a creation receipt. */
final class RegistrationPreflightReport {
    static final String KIND="registration_preflight";
    static void write(Shop shop,List<ProductKey> selected,List<RegistrationBatchPreflight.Finding> findings,Throwable failure){
        if(findings.isEmpty()&&failure==null)return;
        var reports=new SyncRunRepository();var keys=List.copyOf(new LinkedHashSet<>(selected));
        keys.forEach(key->ListingIdentity.require(shop,key));
        String run=reports.begin(shop,KIND,"PREFLIGHT");var items=new ArrayList<SyncRunRepository.Item>();
        var exact=findings.stream().collect(java.util.stream.Collectors.groupingBy(RegistrationBatchPreflight.Finding::key));
        var common=findings.stream().filter(f->Set.of("brand","material","gender","tnved","documents").contains(f.field())).collect(java.util.stream.Collectors.groupingBy(f->f.key().cardId()));
        for(var key:keys){
            var problems=exact.getOrDefault(key,common.getOrDefault(key.cardId(),List.of()));var evidence=new JsonObject();
            String reason=failure==null?problems.stream().map(f->f.field()+": "+(f.reason().isBlank()?f.before():f.reason())).collect(java.util.stream.Collectors.joining("; ")):ZnackSanitizer.error(failure);
            evidence.addProperty("reason",ZnackSanitizer.message(reason));evidence.addProperty("catalogSubmitted",false);
            String error=failure!=null?SyncFailure.of(failure,0).code():problems.isEmpty()?"":"ZNACK_REQUIRED_ATTRIBUTES_MISSING";
            items.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,key.cardId(),key.variantId(),"",problems.isEmpty()?"":problems.getFirst().article(),"","","",error.isBlank()?"READY":"NEEDS_ACTION","PREFLIGHT",error,"",evidence.toString()));
        }
        for(int start=0;start<items.size();start+=10000)reports.addBatch(shop,run,items.subList(start,Math.min(start+10000,items.size())));
        reports.stage(shop,run,"NEEDS_ACTION","PREFLIGHT",failure==null?"ZNACK_REQUIRED_ATTRIBUTES_MISSING":SyncFailure.of(failure,0).code());
    }
}
