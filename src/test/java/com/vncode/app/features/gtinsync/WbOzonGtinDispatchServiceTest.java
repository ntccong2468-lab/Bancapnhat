package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogSyncModels;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbOzonGtinDispatchServiceTest {
    private static final Shop WB=new Shop(1,"WB",Marketplace.WILDBERRIES,"","key");
    private static final Shop OZON=new Shop(2,"Ozon",Marketplace.OZON,"12","key");
    private static final String HASH="a".repeat(64);
    private static WbOzonLinkRepository.Link link(int i){return new WbOzonLinkRepository.Link(1,"10",""+(100+i),HASH,2,""+(1000+i),"SKU-"+i,""+(2000+i),HASH,""+(4600000000000L+i),i+1,"wb","ozon",HASH,HASH,"EXACT_ARTICLE",HASH,Instant.EPOCH);}
    private static GtinSyncModels.ProductKey key(WbOzonLinkRepository.Link l){return new GtinSyncModels.ProductKey(2,Marketplace.OZON,l.ozonProductId(),l.ozonOfferId());}

    @Test void writesBoundedPlansAndReportsReadbackPerSku() throws Exception {
        var fixture=new Fixture();var links=new ArrayList<WbOzonLinkRepository.Link>();for(int i=0;i<101;i++){var l=link(i);links.add(l);fixture.saved.put(key(l),l);}
        var events=new ArrayList<WbOzonGtinDispatchService.Report>();var result=fixture.service().apply(WB,OZON,links,events::add);
        assertEquals(101,result.size());assertTrue(result.stream().allMatch(x->x.state()==WbOzonGtinDispatchService.State.VERIFIED));
        assertEquals(List.of(100,1),fixture.planSizes);assertEquals(2,fixture.runCount);assertEquals(101,fixture.pointers.size());
        assertEquals(101,events.stream().filter(x->x.state()==WbOzonGtinDispatchService.State.CHECKING).count());
    }
    @Test void staleWbVariantIsReportedWithoutPreventingOtherRows() throws Exception {
        var fixture=new Fixture();var first=link(1);var second=link(2);fixture.saved.put(key(first),first);fixture.saved.put(key(second),second);fixture.invalid=first;
        var result=fixture.service().apply(WB,OZON,List.of(first,second),r->{});
        assertEquals(WbOzonGtinDispatchService.State.FAILED,result.getFirst().state());
        assertEquals("WB_OZON_WB_PRODUCT_CHANGED",result.getFirst().reason());
        assertEquals(WbOzonGtinDispatchService.State.VERIFIED,result.getLast().state());
        assertEquals(List.of(1),fixture.planSizes);assertFalse(fixture.pointers.containsKey(key(first)));
    }
    @Test void lostResponseResumesSavedJobInsteadOfCreatingAnother() throws Exception {
        var fixture=new Fixture();var row=link(1);fixture.saved.put(key(row),row);fixture.failAfterCheckpoint=true;
        var service=fixture.service();var first=service.apply(WB,OZON,List.of(row),r->{});
        assertEquals(WbOzonGtinDispatchService.State.WAITING,first.getFirst().state());assertFalse(first.getFirst().jobId().isBlank());
        var second=service.apply(WB,OZON,List.of(row),r->{});
        assertEquals(WbOzonGtinDispatchService.State.VERIFIED,second.getFirst().state());assertEquals(1,fixture.planSizes.size());assertEquals(2,fixture.runCount);
    }
    @Test void failedAtomicCheckpointNeverRunsRemotePlan() throws Exception {
        var fixture=new Fixture();var row=link(1);fixture.saved.put(key(row),row);fixture.failCheckpoint=true;
        var result=fixture.service().apply(WB,OZON,List.of(row),r->{});
        assertEquals(WbOzonGtinDispatchService.State.FAILED,result.getFirst().state());assertEquals("",result.getFirst().jobId());
        assertEquals(0,fixture.runCount);assertTrue(fixture.pointers.isEmpty());
    }
    @Test void duplicateGtinsAreRejectedBeforeAnyApprovalOrPlan() {
        var fixture=new Fixture();var row=link(1);var other=link(2);
        other=new WbOzonLinkRepository.Link(other.wbShopId(),other.wbNmId(),other.wbChrtId(),other.wbOwner(),other.ozonShopId(),other.ozonProductId(),other.ozonOfferId(),other.ozonSku(),other.ozonOwner(),row.gtin(),other.goodId(),other.wbCatalogSource(),other.catalogSource(),other.wbRevision(),other.ozonRevision(),other.evidenceKind(),other.evidenceDigest(),other.approvedAt());
        var second=other;assertThrows(IllegalArgumentException.class,()->fixture.service().apply(WB,OZON,List.of(row,second),r->{}));
        assertEquals(0,fixture.approvals);assertTrue(fixture.planSizes.isEmpty());
    }
    @Test void preflightNetworkFailureDoesNotCreatePlansOrHammerOtherItems() throws Exception {
        var f=new Fixture();var a=link(1);var b=link(2);var c=link(3);
        f.saved.put(key(a),a);f.saved.put(key(b),b);f.saved.put(key(c),c);f.unavailable=b;
        var reports=f.service().apply(WB,OZON,List.of(a,b,c),r->{});
        assertTrue(reports.stream().allMatch(r->r.state()==WbOzonGtinDispatchService.State.WAITING));
        assertEquals(0,f.runCount);assertTrue(f.planSizes.isEmpty());assertEquals(1,f.approvals);
    }

    private static final class Fixture implements WbOzonGtinDispatchService.LinkStore,WbOzonGtinDispatchService.CatalogPlan {
        final Map<GtinSyncModels.ProductKey,WbOzonLinkRepository.Link> saved=new HashMap<>();final Map<GtinSyncModels.ProductKey,String> pointers=new HashMap<>();
        final Map<String,CatalogSyncModels.Job> jobs=new HashMap<>();final List<Integer> planSizes=new ArrayList<>();
        WbOzonLinkRepository.Link invalid,unavailable;boolean failAfterCheckpoint,failCheckpoint;int runCount,approvals;
        WbOzonGtinDispatchService service(){return new WbOzonGtinDispatchService(this,this,(w,l)->{if(l.equals(invalid))throw new IllegalStateException("WB_OZON_WB_PRODUCT_CHANGED");if(l.equals(unavailable))throw new IOException("unavailable");},(o,k,l)->approvals++);}
        public Optional<WbOzonLinkRepository.Link> find(Shop w,Shop o,String p,String v){return Optional.ofNullable(saved.get(new GtinSyncModels.ProductKey(2,Marketplace.OZON,p,v)));}
        public Optional<String> findDispatch(Shop w,Shop o,WbOzonLinkRepository.Link l){return Optional.ofNullable(pointers.get(key(l)));}
        public List<WbOzonLinkRepository.Link> linksForDispatch(Shop w,Shop o,String job){return saved.values().stream().filter(l->job.equals(pointers.get(key(l)))).toList();}
        public void confirmAndSaveDispatch(Shop w,Shop o,List<WbOzonLinkRepository.Link> links,String job){if(failCheckpoint)throw new IllegalStateException("WB_OZON_LINK_STORAGE");for(var l:links)pointers.put(key(l),job);}
        public CatalogSyncModels.Job preview(Shop shop,List<CatalogSyncModels.Selection> selected,CatalogSyncModels.Options options){
            planSizes.add(selected.size());String id=UUID.randomUUID().toString();var rows=new ArrayList<CatalogSyncModels.Row>();
            for(var s:selected){var card=new GtinSyncModels.RegisteredGtin(s.gtin(),"model","black","L",true,true,"scope",Instant.now());rows.add(new CatalogSyncModels.Row(UUID.randomUUID().toString(),s.key(),card,List.of(),List.of(),null,Map.of(),"","",CatalogSyncModels.State.READY,""));}
            var job=new CatalogSyncModels.Job(id,2,"OZON",HASH,Set.of(CatalogSyncModels.Field.GTIN),Instant.now(),false,false,rows);jobs.put(id,job);return job;
        }
        public CatalogSyncModels.Job read(Shop s,String id){return jobs.get(id);}
        public CatalogSyncModels.Job runCross(Shop w,Shop o,String id)throws Exception{
            runCount++;var old=jobs.get(id);assertNotNull(old);for(var row:old.rows())assertEquals(id,pointers.get(row.key()));
            if(failAfterCheckpoint){failAfterCheckpoint=false;throw new IOException("socket timeout");}
            var rows=old.rows().stream().map(r->r.next(CatalogSyncModels.State.SUCCEEDED,"","")).toList();
            var done=new CatalogSyncModels.Job(old.id(),old.shopId(),old.marketplace(),old.owner(),old.fields(),old.createdAt(),true,false,rows);jobs.put(id,done);return done;
        }
    }
}
