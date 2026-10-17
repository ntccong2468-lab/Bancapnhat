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
    interface Operations {
        String prepare(Shop shop)throws Exception;
        List<SyncRunRepository.Item> page(Shop shop,String run,int offset);
        List<Selection> select(Shop shop,String run,List<SyncRunRepository.Item> rows)throws Exception;
        Job preview(Shop shop,List<Selection> rows)throws Exception;
        Job execute(Shop shop,String job)throws Exception;
        void link(Shop shop,String run,Job job);
        default void blocked(Shop shop,String run,List<SyncRunRepository.Item> rows,Job job){}
        default void finish(Shop shop,String run){}
        default void noEligible(Shop shop,String run){}
    }
    private final Operations operations;
    public ZnackGtinBackgroundService(){
        var review=new ZnackGtinReviewService();var catalog=new CatalogSyncService();var reports=new SyncRunRepository();
        operations=new Operations(){
            public String prepare(Shop s)throws Exception{return review.prepare(s);}
            public List<SyncRunRepository.Item> page(Shop s,String r,int offset){return review.page(s,r,offset);}
            public List<Selection> select(Shop s,String r,List<SyncRunRepository.Item> rows)throws Exception{return review.select(s,r,rows);}
            public Job preview(Shop s,List<Selection> rows)throws Exception{return catalog.preview(s,rows,new Options(Set.of(Field.GTIN),Map.of()));}
            public Job execute(Shop s,String id)throws Exception{return catalog.run(s,id,true);}
            public void link(Shop s,String r,Job j){reports.linkReviewed(s,r,j);}
            public void blocked(Shop s,String r,List<SyncRunRepository.Item> rows,Job j){
                for(var row:j.rows())if(row.state()==State.BLOCKED){
                    var item=rows.stream().filter(i->i.productId().equals(row.key().productId())&&i.variantId().equals(row.key().variantId())).findFirst().orElseThrow();
                    String reason=row.differences().stream().filter(Diff::blocking).map(Diff::code).filter(code->!code.isBlank()).findFirst().orElse("GTIN_VALIDATION_BLOCKED");
                    reports.update(s,item,"NEEDS_ACTION","VALIDATING",reason,null);
                }
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
        for(var key:selected)if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
        String run=operations.prepare(shop);var rows=new ArrayList<SyncRunRepository.Item>();
        for(int offset=0;;offset+=300){var page=operations.page(shop,run,offset);
            for(var row:page)if(ZnackGtinReviewService.eligible(row)&&(selected.isEmpty()||selected.contains(new ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId()))))rows.add(row);
            if(page.size()<300)break;
        }
        if(rows.isEmpty()){
            operations.noEligible(shop,run);
            throw new IllegalStateException("GTIN_NO_ELIGIBLE_PUBLISHED_CARDS");
        }
        int done=0,ok=0,pending=0,blocked=0;progress.accept(new Progress(0,rows.size(),0,0,0));
        // Bound each durable child; other shops and FBS retain their own reserved workers.
        for(int offset=0;offset<rows.size();offset+=100){
            var batch=List.copyOf(rows.subList(offset,Math.min(offset+100,rows.size())));
            var selections=operations.select(shop,run,batch);var plan=operations.preview(shop,selections);
            if(plan.readyCount()>0){plan=operations.execute(shop,plan.id());operations.link(shop,run,plan);}
            else operations.blocked(shop,run,batch,plan);
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
