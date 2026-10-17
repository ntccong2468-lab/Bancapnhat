package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.function.*;

/** One reviewed multi-field patch per card. A readback retry never submits a patch twice. */
final class RegistrationPreflightCorrections {
    private final ProductBulkEditService edits;
    private final ProductSnapshotReader reader;
    private final Function<Shop,ListingTargetAdapter> adapters;
    RegistrationPreflightCorrections(){this(new ProductBulkEditService(),(s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(s,k):new WbProductSnapshotReader().read(s,k),s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter());}
    RegistrationPreflightCorrections(ProductBulkEditService edits,ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> adapters){this.edits=edits;this.reader=reader;this.adapters=adapters;}
    Session prepare(Shop shop,RegistrationBatchPreflight.Review review,Map<RegistrationBatchPreflight.Finding,String> entered)throws Exception{
        var patches=new LinkedHashMap<ProductKey,Map<String,List<String>>>();var cards=new HashMap<String,ProductKey>();
        for(var finding:review.findings()){
            ListingIdentity.require(shop,finding.key());
            if(!Set.of("brand","material","gender","tnved").contains(finding.field()))throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            String value=Objects.requireNonNullElse(entered.get(finding),"").strip();
            if(value.isBlank()||value.length()>5000)throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            if(finding.field().equals("tnved")&&!value.matches("[0-9]{10}"))throw new IllegalArgumentException("invalid_tnved");
            var values=finding.field().equals("material")?Arrays.stream(value.split("\\|")).map(String::strip).filter(v->!v.isBlank()).toList():List.of(value);
            if(values.isEmpty())throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            var card=cards.computeIfAbsent(finding.key().cardId(),ignored->finding.key());
            var old=patches.computeIfAbsent(card,key->new LinkedHashMap<>()).putIfAbsent(finding.field(),values);
            if(old!=null&&!old.equals(values))throw new IllegalArgumentException("Conflicting values for sizes of one card");
        }
        if(patches.size()>1000)throw new IllegalArgumentException("REGISTRATION_SELECTION_LIMIT");
        var jobs=new ArrayList<String>();long retained=0;
        for(var patch:patches.entrySet()){
            ListingIdentity.require(shop,patch.getKey());var fresh=reader.read(shop,patch.getKey());
            if(!fresh.key().equals(patch.getKey()))throw new SecurityException("PRODUCT_MISMATCH");
            var changes=new LinkedHashMap<String,List<String>>();var dictionary=adapters.apply(shop).attributes(shop,fresh.categoryId());
            for(var field:patch.getValue().entrySet()){
                if(field.getKey().equals("brand")){changes.put("brand",field.getValue());continue;}
                var candidates=dictionary.stream().filter(a->!a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"))&&ProductFieldNames.matches(field.getKey(),a.name())).toList();
                if(candidates.size()!=1)throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
                changes.put("attribute:"+candidates.getFirst().id(),field.getValue());
            }
            var preview=edits.previewPatch(shop,fresh,changes,null);
            if(!preview.publishable())throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            retained+=preview.items().stream().mapToLong(i->(long)i.preview().preview().requestJson().length()*2).sum();
            if(retained>64L*1024*1024)throw new IllegalArgumentException("REGISTRATION_SELECTION_LIMIT");
            jobs.add(preview.id());
        }
        return new Session(shop,List.copyOf(jobs));
    }
    final class Session {
        private final Shop owner;private final List<String> jobs;private final Set<String> attempted=new HashSet<>();private int next;
        Session(Shop shop,List<String> jobs){owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.jobs=jobs;}
        boolean advance(Consumer<ProductBulkEditService.Progress> progress)throws Exception{
            for(int count=0;next<jobs.size()&&count<3;count++){
                String id=jobs.get(next);
                if(attempted.add(id))edits.apply(owner,id,true,p->{});
                var actual=edits.reconcile(owner,id,p->{});
                if(actual.items().stream().allMatch(i->i.state()==CopyState.RECONCILED)){next++;progress.accept(new ProductBulkEditService.Progress("READBACK",next,jobs.size()));}
                else if(actual.items().stream().anyMatch(i->!Set.of(CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED,CopyState.RECONCILED).contains(i.state())))throw new IllegalStateException("ZNACK_PREFLIGHT_CORRECTION_NEEDS_REVIEW");
                else return false;
            }
            return next==jobs.size();
        }
    }
}
