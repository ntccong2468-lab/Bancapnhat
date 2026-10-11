package com.vncode.app.features.listingcopy;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

public final class ListingCopyService {
    private final ListingCopyRepository repo;private final ProductSnapshotReader reader;private final Function<Shop,ListingTargetAdapter> adapters;
    public ListingCopyService(){this(new ListingCopyRepository(),(s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(s,k):new WbProductSnapshotReader().read(s,k),s->s.getMarketplace()==Marketplace.OZON?new OzonListingAdapter():new WbListingAdapter());}
    public ListingCopyService(ListingCopyRepository r,ProductSnapshotReader reader,Function<Shop,ListingTargetAdapter> adapters){repo=r;this.reader=reader;this.adapters=adapters;repo.recoverIncomplete();}
    public List<CopyJob> draft(Shop source,List<ProductKey> keys,List<Shop> destinations,CopyOptions options)throws IOException{
        ListingIdentity.require(source);if(keys.isEmpty()||destinations.isEmpty())throw new IllegalArgumentException("Source and destination required");var groups=new LinkedHashMap<String,List<ProductKey>>();for(var key:List.copyOf(keys)){ListingIdentity.require(source,key);groups.computeIfAbsent(key.cardId(),k->new ArrayList<>()).add(key);}if(groups.size()>100||destinations.size()>5)throw new IllegalArgumentException("Copy batch limit is 100 products and 5 shops");
        var result=new ArrayList<CopyJob>();for(var group:groups.values()){
            var fresh=reader.read(source,group.getFirst());if(!fresh.revision().startsWith("live:"))throw new IllegalStateException("Fresh source required");var frozen=new LinkedHashSet<String>();for(var k:group)if(k.variantId().isBlank())fresh.variants().forEach(v->frozen.add(v.id()));else frozen.add(k.variantId());frozen.retainAll(options.variantIds());if(frozen.isEmpty())throw new IllegalStateException("Selected variants changed");
            var scoped=options(options,frozen);for(var dest:destinations){ListingIdentity.require(dest);var now=Instant.now();var job=new CopyJob(UUID.randomUUID().toString(),source.getId(),ListingIdentity.fingerprint(source),dest.getId(),dest.getMarketplace(),ListingIdentity.fingerprint(dest),fresh,scoped,null,null,CopyState.DRAFT,List.of(),now,now);repo.insert(job);result.add(job);}
        }return List.copyOf(result);
    }
    public CopyPreview preview(Shop destination,String id)throws IOException{
        var job=owned(destination,id);if(sent(job.state()))throw new IllegalStateException("Submitted jobs can only be reconciled");var source=sourceShop(job);var fresh=reader.read(source,job.source().key());var p=adapters.apply(destination).preview(destination,fresh,job.options());var next=job.with(p.publishable()?CopyState.AWAITING_CONFIRMATION:CopyState.NEEDS_DATA,fresh,job.options(),p,null,p.issues());if(!repo.stage(job,next))throw new IllegalStateException("Listing draft changed");return p;
    }
    public CopyJob configure(Shop destination,String id,CopyOptions options){var job=owned(destination,id);if(sent(job.state()))throw new IllegalStateException("Submitted job is frozen");if(!job.options().variantIds().containsAll(options.variantIds())||options.variantIds().isEmpty())throw new IllegalArgumentException("Cannot add unfrozen variants");var next=job.with(CopyState.DRAFT,job.source(),options,null,null,List.of());if(!repo.stage(job,next))throw new IllegalStateException("Listing draft changed");return next;}
    public CopyJob publish(Shop destination,String id,String approvedHash,boolean confirmed)throws IOException{
        if(!confirmed)throw new IllegalArgumentException("Explicit confirmation required");var job=owned(destination,id);if(sent(job.state()))return job;if(job.preview()==null||!job.preview().publishable()||!job.preview().requestHash().equals(approvedHash))throw new IllegalStateException("Preview changed");
        var source=sourceShop(job);var fresh=reader.read(source,job.source().key());if(!fresh.revision().equals(job.preview().sourceRevision()))throw new IllegalStateException("Source changed; preview again");var adapter=adapters.apply(destination);var current=adapter.preview(destination,fresh,job.options());if(!current.publishable()||!current.requestHash().equals(approvedHash))throw new IllegalStateException("Destination or files changed; preview again");
        if(!repo.claim(destination.getId(),id,approvedHash))return owned(destination,id);var sending=owned(destination,id);var p=job.preview();var unknown=new RemoteSubmission(destination.getId(),destination.getMarketplace(),job.destinationOwner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());
        try{var submitted=adapter.submit(destination,p);var next=sending.with(CopyState.REMOTE_PROCESSING,sending.source(),sending.options(),p,submitted,List.of());repo.checkpoint(next,Set.of(CopyState.SENDING));}
        catch(IOException|RuntimeException failure){repo.checkpoint(sending.with(CopyState.RECONCILE_REQUIRED,sending.source(),sending.options(),p,unknown,List.of(new CopyIssue("RECONCILIATION_REQUIRED","submission",true))),Set.of(CopyState.SENDING));}
        return owned(destination,id);
    }
    public CopyJob reconcile(Shop destination,String id)throws IOException{
        var job=owned(destination,id);if(job.state()==CopyState.RECONCILED||job.state()==CopyState.FAILED)return job;if(!sent(job.state()))throw new IllegalStateException("Job was not submitted");if(job.state()==CopyState.SENDING)return job;
        var r=job.submission();if(r==null){var p=job.preview();if(p==null)throw new IllegalStateException("Recovery preview unavailable");r=new RemoteSubmission(job.destinationShopId(),job.marketplace(),job.destinationOwner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        var adapter=adapters.apply(destination);var result=adapter.reconcile(destination,r);var next=job.with(result.state(),job.source(),job.options(),job.preview(),r.withPhase(r.phase(),result.identities()),result.issues());
        if(result.issues().stream().anyMatch(i->i.code().equals("ASSETS_REQUIRED"))&&r.phase().equals("CARD")){
            var assets=r.withPhase("ASSETS_SENDING",result.identities());if(!repo.claimAssets(job,assets))return owned(destination,id);try{var done=adapter.finishAssets(destination,assets);repo.checkpoint(job.with(CopyState.REMOTE_PROCESSING,job.source(),job.options(),job.preview(),done,List.of()),Set.of(CopyState.SENDING));}catch(IOException|RuntimeException failure){repo.checkpoint(job.with(CopyState.RECONCILE_REQUIRED,job.source(),job.options(),job.preview(),assets,List.of(new CopyIssue("RECONCILIATION_REQUIRED","assets",true))),Set.of(CopyState.SENDING));}
        }else repo.checkpoint(next,Set.of(CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED));return owned(destination,id);
    }
    public List<CopyJob> list(Shop destination){ListingIdentity.require(destination);return repo.list(destination.getId(),ListingIdentity.fingerprint(destination));}
    public List<CopyJob> listSource(Shop source){ListingIdentity.require(source);var owners=new HashMap<Integer,String>();new com.vncode.app.features.shop.ShopRepository().findAll().forEach(s->owners.put(s.getId(),ListingIdentity.fingerprint(s)));return repo.listSource(source.getId(),ListingIdentity.fingerprint(source)).stream().filter(j->j.destinationOwner().equals(owners.get(j.destinationShopId()))).toList();}
    public ListingTargetAdapter adapter(Shop destination){ListingIdentity.require(destination);return adapters.apply(destination);}
    public ProductSnapshot read(Shop source,ProductKey key)throws IOException{ListingIdentity.require(source,key);return reader.read(source,key);}
    private CopyJob owned(Shop s,String id){ListingIdentity.require(s);var j=repo.find(s.getId(),id).orElseThrow(()->new SecurityException("Foreign listing job"));if(j.marketplace()!=s.getMarketplace()||!j.destinationOwner().equals(ListingIdentity.fingerprint(s)))throw new SecurityException("Listing owner changed");return j;}
    private Shop sourceShop(CopyJob j){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT name,marketplace,client_id,api_key FROM shops WHERE id=?")){p.setInt(1,j.sourceShopId());try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Source shop unavailable");var s=new Shop(j.sourceShopId(),r.getString(1),Marketplace.valueOf(r.getString(2)),r.getString(3),r.getString(4));ListingIdentity.require(s,j.source().key());if(!ListingIdentity.fingerprint(s).equals(j.sourceOwner()))throw new SecurityException("Source owner changed");return s;}}catch(java.sql.SQLException e){throw new IllegalStateException("Cannot validate source owner");}}
    private static boolean sent(CopyState s){return Set.of(CopyState.SENDING,CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED,CopyState.RECONCILED,CopyState.FAILED).contains(s);}
    public static CopyOptions options(CopyOptions o,Set<String> variants){return new CopyOptions(o.fields(),variants,o.targetCategoryId(),o.attributeMapping(),o.attributeValues(),o.newArticles(),o.targetPrice(),o.currency(),o.documents(),o.kizMarked(),o.variantValues(),o.textOverrides());}
}
