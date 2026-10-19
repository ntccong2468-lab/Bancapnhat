package com.vncode.app.features.sync;

import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.function.Consumer;

/** A click authorizes GTIN append only. Matching, source proof and readback remain mandatory. */
public final class ZnackGtinBackgroundService {
    public record Progress(int completed,int total,int succeeded,int pending,int blocked,int submitted){
        public Progress(int completed,int total,int succeeded,int pending,int blocked){this(completed,total,succeeded,pending,blocked,0);}
    }
    public record Inventory(int total,int withGtin,int withoutGtin,int blockedBeforeUpdate) {}
    public record Result(String run,Progress progress,Inventory inventory){
        public Result(String run,Progress progress){this(run,progress,new Inventory(progress.total(),progress.total(),0,0));}
    }
    /** Preserve the complete run and confirmed results when a later request fails. */
    public static final class RunException extends java.io.IOException {
        private final Result result;
        RunException(Result result,Exception cause){super("GTIN_UPDATE_PARTIAL",cause);this.result=result;}
        public Result result(){return result;}
    }
    /** Only thrown before any marketplace write has been attempted by this run. */
    public static final class PreparationException extends Exception {
        PreparationException(Exception cause){super("GTIN_SOURCE_PREPARATION_FAILED",cause);}
    }
    interface Operations {
        String prepare(Shop shop)throws Exception;
        default String prepare(Shop shop,Consumer<String> phase)throws Exception{return prepare(shop);}
        List<SyncRunRepository.Item> page(Shop shop,String run,int offset);
        List<Selection> select(Shop shop,String run,List<SyncRunRepository.Item> rows)throws Exception;
        Job preview(Shop shop,List<Selection> rows)throws Exception;
        Job execute(Shop shop,String job)throws Exception;
        default Job execute(Shop shop,String job,Consumer<com.vncode.app.features.gtinsync.GtinSyncModels.JobItem> progress)throws Exception{return execute(shop,job);}
        default Job confirm(Shop shop,Job job){return job;}
        default int batchSize(){return 1;}
        default boolean ownerChecks(){return false;}
        default void prepareBatchRun(Shop shop,List<SyncRunRepository.Item> rows)throws Exception{}
        default void close(){}
        void link(Shop shop,String run,Job job);
        default void blocked(Shop shop,String run,List<SyncRunRepository.Item> rows,Job job){}
        default void invalid(Shop shop,String run,SyncRunRepository.Item row,Exception failure){}
        default void finish(Shop shop,String run){}
        default void noEligible(Shop shop,String run){}
    }
    private final Operations operations;
    public ZnackGtinBackgroundService(){
        var review=new ZnackGtinReviewService();var catalog=new CatalogSyncService();var reports=new SyncRunRepository();
        operations=new Operations(){
            private GtinBulkSession bulk;
            public int batchSize(){return com.vncode.app.features.gtinsync.GtinBatchAdapter.MAX_PLAN_ROWS;}
            public boolean ownerChecks(){return true;}
            public void prepareBatchRun(Shop s,List<SyncRunRepository.Item> rows)throws Exception{bulk=new GtinBulkSession(s);bulk.prepare(rows.stream().map(r->new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(s.getId(),s.getMarketplace(),r.productId(),r.variantId())).toList());}
            public void close(){if(bulk!=null)bulk.close();}
            public String prepare(Shop s)throws Exception{return review.prepare(s);}
            public String prepare(Shop s,Consumer<String> phase)throws Exception{return review.prepare(s,phase);}
            public List<SyncRunRepository.Item> page(Shop s,String r,int offset){return review.page(s,r,offset);}
            public List<Selection> select(Shop s,String r,List<SyncRunRepository.Item> rows)throws Exception{return bulk==null?review.select(s,r,rows):review.select(s,r,rows,bulk::read);}
            public Job preview(Shop s,List<Selection> rows)throws Exception{return (bulk==null?catalog:bulk.catalog).preview(s,rows,new Options(Set.of(Field.GTIN),Map.of()));}
            public Job confirm(Shop s,Job job){return (bulk==null?catalog:bulk.catalog).confirm(s,job.id());}
            public Job execute(Shop s,String id)throws Exception{return (bulk==null?catalog:bulk.catalog).run(s,id,true);}
            public Job execute(Shop s,String id,Consumer<com.vncode.app.features.gtinsync.GtinSyncModels.JobItem> progress)throws Exception{return (bulk==null?catalog:bulk.catalog).run(s,id,true,progress);}
            public void link(Shop s,String r,Job j){reports.linkReviewed(s,r,j);}
            public void blocked(Shop s,String r,List<SyncRunRepository.Item> rows,Job j){
                for(var row:j.rows())if(row.state()==State.BLOCKED){
                    var item=rows.stream().filter(i->i.productId().equals(row.key().productId())&&i.variantId().equals(row.key().variantId())).findFirst().orElseThrow();
                    String reason=row.issue().isBlank()?row.differences().stream().filter(Diff::blocking).map(Diff::code).filter(code->!code.isBlank()).findFirst().orElse("GTIN_VALIDATION_BLOCKED"):row.issue();
                    reports.update(s,item,"NEEDS_ACTION","VALIDATING",reason,null);
                }
            }
            public void invalid(Shop s,String r,SyncRunRepository.Item row,Exception failure){
                var error=SyncFailure.of(failure,0);
                reports.update(s,row,error.actionRequired()?"NEEDS_ACTION":"WAITING_REMOTE","VALIDATING",error.code(),null);
            }
            public void finish(Shop s,String r){
                var counts=reports.counts(r);
                if(counts.getOrDefault("WAITING_REMOTE",0L)+counts.getOrDefault("RECONCILING",0L)>0)return;
                if(counts.getOrDefault("AWAITING_CONFIRMATION",0L)>0)return;
                boolean blocked=counts.getOrDefault("NEEDS_ACTION",0L)+counts.getOrDefault("FAILED",0L)>0;
                reports.stage(s,r,blocked?"PARTIAL":"SUCCEEDED","REPORT",blocked?"ITEMS_NEED_ACTION":"");
            }
            public void noEligible(Shop s,String r){reports.stage(s,r,"PARTIAL","REPORT","GTIN_NO_ELIGIBLE_PUBLISHED_CARDS");}
        };
    }
    ZnackGtinBackgroundService(Operations operations){this.operations=operations;}
    public Result run(Shop shop,Set<ProductKey> selected,Consumer<Progress> progress)throws Exception {
        return run(shop,selected,progress,phase->{});
    }
    public Result run(Shop shop,Set<ProductKey> selected,Consumer<Progress> progress,Consumer<String> phase)throws Exception {
        try(var database=com.vncode.app.config.Database.openBatchSession();var checks=operations.ownerChecks()?com.vncode.app.integration.listing.ListingIdentity.batchChecks(shop):null){return runActive(shop,selected,progress,phase);}
    }
    private Result runActive(Shop shop,Set<ProductKey> selected,Consumer<Progress> progress,Consumer<String> phase)throws Exception {
        for(var key:selected)if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
        phase.accept("PREPARING");
        String run;
        try{run=operations.prepare(shop,phase);}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|SecurityException denied){throw denied;}
        catch(Exception failure){throw new PreparationException(failure);}
        var last=new Progress[]{new Progress(0,0,0,0,0)};
        Consumer<Progress> updates=value->{last[0]=value;progress.accept(value);};
        var rows=new ArrayList<SyncRunRepository.Item>();int covered=0,missing=0,excluded=0;
        for(int offset=0;;offset+=300){var page=operations.page(shop,run,offset);
            for(var row:page){
                if(row.productId().startsWith("catalog:")||(!selected.isEmpty()&&!selected.contains(new ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId()))))continue;
                covered++;if(row.gtin().isBlank())missing++;
                if(ZnackGtinReviewService.eligible(row))rows.add(row);else if(!row.gtin().isBlank())excluded++;
            }
            if(page.size()<300)break;
        }
        var inventory=new Inventory(covered,covered-missing,missing,excluded);
        if(rows.isEmpty()){
            operations.noEligible(shop,run);
            var empty=new Progress(0,0,0,0,0);updates.accept(empty);
            return new Result(run,empty,inventory);
        }
        int done=0,ok=0,pending=0,blocked=0;var submitted=new HashSet<com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey>();updates.accept(new Progress(0,rows.size(),0,0,0));
        var chunks=new ArrayDeque<List<SyncRunRepository.Item>>();int size=Math.max(1,Math.min(operations.batchSize(),com.vncode.app.features.gtinsync.GtinBatchAdapter.MAX_PLAN_ROWS));
        for(int offset=0;offset<rows.size();offset+=size)chunks.addLast(List.copyOf(rows.subList(offset,Math.min(offset+size,rows.size()))));
        try {
        phase.accept("READING_MARKETPLACE");operations.prepareBatchRun(shop,rows);
        while(!chunks.isEmpty()){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("GTIN_SYNC_CANCELLED");
            var batch=chunks.removeFirst();Job plan;
            try{plan=operations.preview(shop,operations.select(shop,run,batch));}
            catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|SecurityException|com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException denied){throw denied;}
            catch(Exception invalid){
                // Only a read/preview can split. An uncertain execution is never replayed.
                if(batch.size()>1){int middle=batch.size()/2;chunks.addFirst(List.copyOf(batch.subList(middle,batch.size())));chunks.addFirst(List.copyOf(batch.subList(0,middle)));continue;}
                var item=batch.getFirst();
                operations.invalid(shop,run,item,invalid);
                if(SyncFailure.of(invalid,0).actionRequired())blocked++;else pending++;
                updates.accept(new Progress(++done,rows.size(),ok,pending,blocked,submitted.size()));continue;
            }
            if(plan.readyCount()>0){
                plan=operations.confirm(shop,plan);if(plan.confirmed())operations.link(shop,run,plan);
                final int baseDone=done,baseOk=ok,basePending=pending,baseBlocked=blocked;
                var observed=new HashMap<com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey,com.vncode.app.features.gtinsync.GtinSyncModels.Status>();var counts=new int[4];
                plan=operations.execute(shop,plan.id(),item->{
                    var status=item.status();var key=item.preview().before().key();var previous=observed.put(key,status);
                    if(previous!=null)countStatus(previous,counts,-1);countStatus(status,counts,1);
                    if(Set.of(com.vncode.app.features.gtinsync.GtinSyncModels.Status.AWAITING_VERIFICATION,com.vncode.app.features.gtinsync.GtinSyncModels.Status.RECONCILE_REQUIRED,com.vncode.app.features.gtinsync.GtinSyncModels.Status.SUCCEEDED).contains(status))submitted.add(key);
                    updates.accept(new Progress(baseDone+counts[0],rows.size(),baseOk+counts[1],basePending+counts[2],baseBlocked+counts[3],submitted.size()));
                });operations.link(shop,run,plan);
            }
            operations.blocked(shop,run,batch,plan);
            for(var row:plan.rows())switch(row.state()){
                case SUCCEEDED -> ok++;
                case BLOCKED -> blocked++;
                default -> pending++;
            }
            done+=batch.size();updates.accept(new Progress(done,rows.size(),ok,pending,blocked,submitted.size()));
        }
        operations.finish(shop,run);
        return new Result(run,new Progress(done,rows.size(),ok,pending,blocked,submitted.size()),inventory);
        }catch(Exception failure){throw new RunException(new Result(run,last[0],inventory),failure);}
        finally{operations.close();}
    }
    private static void countStatus(com.vncode.app.features.gtinsync.GtinSyncModels.Status status,int[] counts,int direction){
        switch(status){
            case SUCCEEDED -> {counts[0]+=direction;counts[1]+=direction;}
            case FAILED,CANCELLED -> {counts[0]+=direction;counts[3]+=direction;}
            case AWAITING_VERIFICATION,RECONCILE_REQUIRED -> {counts[0]+=direction;counts[2]+=direction;}
            default -> {}
        }
    }
}
