package com.vncode.app.features.tnved;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.util.*;

/** Local advice only. Actual updates still need fresh marketplace preview and seller confirmation. */
public final class ProductTnvedReviewService {
    public record Review(List<ProductTnvedAdvisor.Proposal> proposals,Map<ProductKey,List<String>> values){
        public Review {proposals=List.copyOf(proposals);values=Map.copyOf(values);}
    }
    public Review review(Shop shop,List<ProductKey> selected){
        ListingIdentity.require(shop);var cards=new LinkedHashMap<String,List<ProductKey>>();
        for(var key:selected){ListingIdentity.require(shop,key);cards.computeIfAbsent(key.cardId(),k->new ArrayList<>()).add(key);}
        var advisor=new ProductTnvedAdvisor();var repo=new ProductRepository();var proposals=new ArrayList<ProductTnvedAdvisor.Proposal>();var values=new LinkedHashMap<ProductKey,List<String>>();String owner=ListingIdentity.fingerprint(shop);
        for(var group:cards.values()){
            var key=group.getFirst();ProductSnapshot snapshot=null;
            for(var variant:group){var cached=repo.localSnapshot(variant).orElse(null);if(cached!=null&&(cached.metadata().get("owner")==null||owner.equals(cached.metadata().get("owner")))){snapshot=cached;break;}}
            var proposal=snapshot==null?advisor.unavailable(key):advisor.propose(snapshot);proposals.add(proposal);
            if(proposal.unique())for(var variant:group)values.put(variant,List.of(proposal.candidates().getFirst().code()));
        }
        return new Review(proposals,values.size()==selected.size()?values:Map.of());
    }
}
