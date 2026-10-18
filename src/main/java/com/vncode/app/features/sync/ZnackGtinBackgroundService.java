package com.vncode.app.features.sync;

import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.function.Consumer;

/** A click authorizes GTIN append only. Matching, source proof and readback remain mandatory. */
public final class ZnackGtinBackgroundService {
    public record Progress(int completed,int total,int succeeded,int pending,int blocked){}
    public record Result(String run,Progress progress){}
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
            public String prepare(Shop s)throws Exception{return review.prepare(s);}
            public String prepare(Shop s,Consumer<String> phase)throws Exception{return review.prepare(s,phase);}
            public List<SyncRunRepository.Item> page(Shop s,String r,int offset){return review.page(s,r,offset);}
            public List<Selection> select(Shop s,String r,List<SyncRunRepository.Item> rows)throws Exception{return review.select(s,r,rows);}
            public Job preview(Shop s,List<Selection> rows)throws Exception{return catalog.preview(s,rows,new Options(Set.of(Field.GTIN),Map.of()));}
            public Job execute(Shop s,String id)throws Exception{return catalog.run(s,id,true);}
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
        for(var key:selected)if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
        phase.accept("PREPARING");
        String run;
        try{run=operations.prepare(shop,phase);}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|SecurityException denied){throw denied;}
        catch(Exception failure){throw new PreparationException(failure);}
        var rows=new ArrayList<SyncRunRepository.Item>();
        for(int offset=0;;offset+=300){var page=operations.page(shop,run,offset);
            for(var row:page)if(ZnackGtinReviewService.eligible(row)&&(selected.isEmpty()||selected.contains(new ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId()))))rows.add(row);
            if(page.size()<300)break;
        }
        if(rows.isEmpty()){
            operations.noEligible(shop,run);
            throw new IllegalStateException("GTIN_NO_ELIGIBLE_PUBLISHED_CARDS");
        }
        int done=0,ok=0,pending=0,blocked=0;progress.accept(new Progress(0,rows.size(),0,0,0));
        // Validate and dispatch each variant before reading the next. A bad variant must
        // neither starve valid variants nor hide progress behind a 100-item preview.
        for(var item:rows){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("GTIN_SYNC_CANCELLED");
            var batch=List.of(item);Job plan;
            try{plan=operations.preview(shop,operations.select(shop,run,batch));}
            catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|SecurityException denied){throw denied;}
            catch(Exception invalid){
                operations.invalid(shop,run,item,invalid);
                if(SyncFailure.of(invalid,0).actionRequired())blocked++;else pending++;
                progress.accept(new Progress(++done,rows.size(),ok,pending,blocked));continue;
            }
            if(plan.readyCount()>0){plan=operations.execute(shop,plan.id());operations.link(shop,run,plan);}
            operations.blocked(shop,run,batch,plan);
            for(var row:plan.rows())switch(row.state()){
                case SUCCEEDED -> ok++;
                case BLOCKED -> blocked++;
                default -> pending++;
            }
            done+=batch.size();progress.accept(new Progress(done,rows.size(),ok,pending,blocked));
        }
        operations.finish(shop,run);
        return new Result(run,new Progress(done,rows.size(),ok,pending,blocked));
    }
}
