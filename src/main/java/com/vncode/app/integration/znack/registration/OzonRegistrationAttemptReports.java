package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.models.Shop;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** Full-selection Ozon receipts displayed in the existing registration report. */
public final class OzonRegistrationAttemptReports {
    private OzonRegistrationAttemptReports(){}
    public static String queue(Shop shop,List<ProductKey> keys){
        var reports=new SyncRunRepository();String run=reports.begin(shop,RegistrationAttemptReports.KIND,"PREFLIGHT");
        reports.addBatch(shop,run,keys.stream().distinct().map(key->item(run,key,"QUEUED","PREFLIGHT","","","{}")).toList());return run;
    }
    public static void observe(Shop shop,String run,ProductKey key,String queueState,String detail){
        var reports=new SyncRunRepository();var row=new OzonCardRegistrationRepository().find(key);String gtin=row==null?"":row.gtin();
        String state=queueState.equals("CANCELLED")?"CANCELLED":queueState.equals("FAILED")?"NEEDS_ACTION":Set.of("PENDING","WAITING_REMOTE").contains(queueState)?"WAITING_REMOTE":row!=null&&(!row.feedId().isBlank()||row.goodId()!=null)?state(row.status()):"RUNNING";
        String step=row==null?"PREFLIGHT":row.status().name();String error=state.equals("NEEDS_ACTION")?"ZNACK_REGISTRATION_NEEDS_ACTION":"";
        var evidence=new JsonObject();evidence.addProperty("reason",ZnackSanitizer.message(detail));evidence.addProperty("template",RegistrationTemplate.defaults().id());
        if(row!=null){evidence.addProperty("feedId",row.feedId());evidence.addProperty("goodId",row.goodId());evidence.addProperty("publicationVerified",queueState.equals("SUBMITTED")&&row.status()==Status.PUBLISHED);}
        var item=item(run,key,state,step,error,gtin,evidence.toString());reports.registrationProgress(shop,item,gtin,state,step,error,evidence.toString());finish(shop,run);
    }
    public static void failPending(Shop shop,String run,String detail){
        var reports=new SyncRunRepository();reports.assertOwner(shop,run);
        for(int offset=0;;offset+=1000){var page=reports.items(run,offset,1000);var changes=new ArrayList<SyncRunRepository.Item>();
            for(var item:page)if(item.state().equals("QUEUED")||item.state().equals("RUNNING")||item.state().equals("WAITING_REMOTE")&&item.step().equals("PREFLIGHT")){
                var evidence=new JsonObject();evidence.addProperty("reason",ZnackSanitizer.message(detail));changes.add(new SyncRunRepository.Item(item.id(),run,item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),item.gtin(),"NEEDS_ACTION","PREFLIGHT",detail,item.childJob(),evidence.toString()));
            }reports.registrationProgressBatch(shop,run,changes);if(page.size()<1000)break;
        }finish(shop,run);
    }
    /** Queue cancellation applies only to never-started rows, never to an accepted Catalog feed. */
    public static void control(Shop shop,String run,String state){
        var reports=new SyncRunRepository();reports.assertOwner(shop,run);
        if(state.equals("CANCELLED")){
            var cancelled=new HashSet<String>();try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id FROM ozon_registration_batch_items WHERE run_id=? AND state='CANCELLED'")){p.setString(1,run);try(var r=p.executeQuery()){while(r.next())cancelled.add(r.getString(1)+"\u001f"+r.getString(2));}}catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
            for(int offset=0;;offset+=1000){var page=reports.items(run,offset,1000);var changes=new ArrayList<SyncRunRepository.Item>();for(var item:page)if(cancelled.contains(item.productId()+"\u001f"+item.variantId())){var evidence=new JsonObject();evidence.addProperty("reason","REGISTRATION_BATCH_CANCELLED");changes.add(new SyncRunRepository.Item(item.id(),run,item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),item.gtin(),"CANCELLED","PREFLIGHT","",item.childJob(),evidence.toString()));}reports.registrationProgressBatch(shop,run,changes);if(page.size()<1000)break;}
        }
        reports.stage(shop,run,state,state.equals("PAUSED")?"REGISTRATION_BATCH_PAUSED":"REGISTRATION_BATCH_CANCELLED","");
    }
    public static void refreshAll(Shop shop,SyncRunRepository.Run run){
        if(shop.getMarketplace()!=Marketplace.OZON||!RegistrationAttemptReports.KIND.equals(run.kind()))return;
        var reports=new SyncRunRepository();reports.assertOwner(shop,run.id());var repository=new OzonCardRegistrationRepository();var queueStates=new HashMap<ProductKey,String>();
        try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id,state FROM ozon_registration_batch_items WHERE run_id=?")){p.setString(1,run.id());try(var r=p.executeQuery()){while(r.next())queueStates.put(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)),r.getString(3));}}catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        for(int offset=0;offset<run.total();offset+=1000){var page=reports.items(run.id(),offset,1000);var keys=page.stream().map(i->new ProductKey(shop.getId(),Marketplace.OZON,i.productId(),i.variantId())).toList();var rows=repository.findMany(keys);var changes=new ArrayList<SyncRunRepository.Item>();
            for(var item:page){var key=new ProductKey(shop.getId(),Marketplace.OZON,item.productId(),item.variantId());String queueState=queueStates.get(key);if(queueState!=null&&!queueState.equals("SUBMITTED"))continue;if(queueState==null&&(!"WAITING_REMOTE".equals(item.state())||item.step().equals("PREFLIGHT")))continue;var row=rows.get(key);if(row==null||row.feedId().isBlank()&&row.goodId()==null||!owner.equals(row.owner())||!item.gtin().isBlank()&&!item.gtin().equals(row.gtin()))continue;
                var prior=com.google.gson.JsonParser.parseString(item.evidence()).getAsJsonObject();if(prior.has("feedId")&&!prior.get("feedId").isJsonNull()&&!prior.get("feedId").getAsString().isBlank()&&!prior.get("feedId").getAsString().equals(row.feedId()))continue;
                var evidence=prior.deepCopy();evidence.addProperty("feedId",row.feedId());evidence.addProperty("reason",ZnackSanitizer.message(row.error()));evidence.addProperty("goodId",row.goodId());evidence.addProperty("publicationVerified",row.status()==Status.PUBLISHED);String state=state(row.status()),step=row.status()==Status.PUBLISHED?"CARD_PUBLISHED":row.status().name(),error=state.equals("NEEDS_ACTION")?"ZNACK_REGISTRATION_NEEDS_ACTION":"";
                if(!state.equals(item.state())||!step.equals(item.step())||!evidence.toString().equals(item.evidence()))changes.add(new SyncRunRepository.Item(item.id(),item.runId(),item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),row.gtin(),state,step,error,item.childJob(),evidence.toString()));
            }reports.registrationProgressBatch(shop,run.id(),changes);
        }finish(shop,run.id());
    }
    private static SyncRunRepository.Item item(String run,ProductKey key,String state,String step,String error,String gtin,String evidence){String id=UUID.nameUUIDFromBytes((run+":"+key.cardId()+":"+key.variantId()).getBytes(StandardCharsets.UTF_8)).toString();return new SyncRunRepository.Item(id,run,key.cardId(),key.variantId(),"",key.variantId(),"","",gtin,state,step,error,"",evidence);}
    private static String state(Status status){return switch(status){case PUBLISHED->"SUCCEEDED";case ERROR,PREFLIGHT_ERROR,PRE_SUBMIT_ERROR,GTIN_REVIEW_REQUIRED,FEED_REVIEW_REQUIRED->"NEEDS_ACTION";case FEED_SUBMITTED,PROCESSING,READY_TO_SIGN,SIGNING->"WAITING_REMOTE";default->"RUNNING";};}
    private static void finish(Shop shop,String run){var reports=new SyncRunRepository();String control="";try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT state FROM ozon_registration_batches WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){if(r.next()&&Set.of("PAUSED","CANCELLED").contains(r.getString(1)))control=r.getString(1);}}catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}var counts=reports.counts(run);String state=!control.isBlank()?control:counts.getOrDefault("RUNNING",0L)+counts.getOrDefault("QUEUED",0L)>0?"RUNNING":counts.getOrDefault("WAITING_REMOTE",0L)>0?"WAITING_REMOTE":counts.getOrDefault("NEEDS_ACTION",0L)>0?"PARTIAL":counts.getOrDefault("CANCELLED",0L)>0?"CANCELLED":"SUCCEEDED";reports.stage(shop,run,state,"FEED_SUBMITTED",state.equals("PARTIAL")?"ZNACK_REGISTRATION_NEEDS_ACTION":"");}
}
