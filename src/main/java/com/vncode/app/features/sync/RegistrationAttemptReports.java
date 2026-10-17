package com.vncode.app.features.sync;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.models.Shop;
import java.util.*;

/** Actual registration attempts, separate from the read-only registration_sources receipt.
 * Called on workers. An accepted request is never promoted to a published card here. */
public final class RegistrationAttemptReports {
    public static final String KIND="znack_registration";
    private final Shop owner;
    private final SyncRunRepository reports=new SyncRunRepository();
    private final List<Sku> selection;
    private String run;
    /** Resume reporting after a view/app restart without re-sending any request. */
    public static void refresh(Shop shop,SyncRunRepository.Run run,List<SyncRunRepository.Item> items){
        if(!KIND.equals(run.kind()))return;
        var reports=new SyncRunRepository();reports.assertOwner(shop,run.id());
        var changes=new ArrayList<SyncRunRepository.Item>();
        try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT r.* FROM znack_card_registrations r JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.nm_id=? AND r.chrt_id=? AND s.owner_hash=?")){
            for(var item:items){
                if(!Set.of("RUNNING","WAITING_REMOTE").contains(item.state())||Set.of("PREFLIGHT","MATCHING").contains(item.step()))continue;
                p.setInt(1,shop.getId());p.setString(2,item.productId());p.setString(3,item.variantId());p.setString(4,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));
                try(var r=p.executeQuery()){
                    if(!r.next()||java.time.Instant.parse(r.getString("updated_at")).isBefore(java.time.Instant.parse(run.created())))continue;
                    String gtin=Objects.requireNonNullElse(r.getString("gtin"),""),feed=Objects.requireNonNullElse(r.getString("feed_id"),"");
                    var evidence=com.google.gson.JsonParser.parseString(item.evidence()).getAsJsonObject();
                    if(!item.gtin().isBlank()&&!item.gtin().equals(gtin))continue;
                    if(evidence.has("feedId")&&!evidence.get("feedId").isJsonNull()&&!evidence.get("feedId").getAsString().equals(feed))continue;
                    Status status=Status.valueOf(r.getString("status"));String state=state(status),step=status==Status.PUBLISHED?"CARD_PUBLISHED":status.name();
                    String error=state.equals("NEEDS_ACTION")?"ZNACK_REGISTRATION_NEEDS_ACTION":"";
                    evidence.addProperty("feedId",feed);evidence.addProperty("goodId",r.getLong("good_id"));evidence.addProperty("publicationVerified",status==Status.PUBLISHED);
                    if(!state.equals(item.state())||!step.equals(item.step())||!gtin.equals(item.gtin()))changes.add(new SyncRunRepository.Item(item.id(),item.runId(),item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),gtin,state,step,error,item.childJob(),evidence.toString()));
                }
            }
        }catch(java.sql.SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
        for(var item:changes)reports.registrationProgress(shop,item,item.gtin(),item.state(),item.step(),item.error(),item.evidence());
        if(!changes.isEmpty())finish(shop,reports,run.id(),changes.getLast().step());
    }
    public RegistrationAttemptReports(Shop shop){this(shop,List.of());}
    public RegistrationAttemptReports(Shop shop,List<Sku> selection){
        owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.selection=List.copyOf(selection);
    }
    public synchronized Attempt begin(Sku sku){
        if(run==null){
            run=reports.begin(owner,KIND,"PREFLIGHT");var queued=new ArrayList<SyncRunRepository.Item>();
            for(var row:selection)queued.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,Long.toString(row.nmId()),Long.toString(row.chrtId()),"",text(row.vendorCode()),text(row.color()),text(row.size()),"","QUEUED","PREFLIGHT","","","{}"));
            reports.addBatch(owner,run,queued);
        }
        var item=reports.add(owner,run,Long.toString(sku.nmId()),Long.toString(sku.chrtId()),"",text(sku.vendorCode()),text(sku.color()),text(sku.size()),"","RUNNING","","{}");
        reports.update(owner,item,"RUNNING","PREFLIGHT","",null);
        reports.stage(owner,run,"RUNNING","PREFLIGHT","");
        return new Attempt(sku,item);
    }
    public final class Attempt {
        private final Sku sku;
        private final SyncRunRepository.Item item;
        private Attempt(Sku sku,SyncRunRepository.Item item){this.sku=sku;this.item=item;}
        public String run(){return item.runId();}
        public void blocked(String code){write("NEEDS_ACTION","PREFLIGHT",code,null);}
        public void failed(Throwable error){blocked(SyncFailure.of(error,0).code());}
        public void observe(Status status,String detail){
            var current=new ZnackCardRegistrationRepository().find(owner.getId(),sku.chrtId());
            if(current==null||current.nmId()!=sku.nmId())throw new SecurityException("SYNC_OWNER_CHANGED");
            String state=state(status);
            String error=state.equals("NEEDS_ACTION")?SyncFailure.of(new IllegalStateException(detail),0).code():"CATALOG_READBACK_RETRY_PENDING".equals(detail)?detail:"";
            write(state,status==Status.PUBLISHED?"CARD_PUBLISHED":status.name(),error,current);
        }
        private void write(String state,String step,String error,Sku current){
            var evidence=new JsonObject();evidence.addProperty("template",com.vncode.app.integration.znack.registration.RegistrationTemplate.defaults().id());
            if(current!=null){
                evidence.addProperty("feedId",current.feedId());evidence.addProperty("goodId",current.goodId());
                evidence.addProperty("publicationVerified",state.equals("SUCCEEDED"));
            }
            reports.registrationProgress(owner,item,current==null?"":Objects.requireNonNullElse(current.gtin(),""),state,step,error,evidence.toString());
            finish(owner,reports,item.runId(),step);
        }
    }
    private static String text(String value){return Objects.requireNonNullElse(value,"");}
    private static String state(Status status){return switch(status){
        case PUBLISHED -> "SUCCEEDED";
        case ERROR,PREFLIGHT_ERROR,PRE_SUBMIT_ERROR,GTIN_REVIEW_REQUIRED,FEED_REVIEW_REQUIRED -> "NEEDS_ACTION";
        case FEED_SUBMITTED,PROCESSING,READY_TO_SIGN,SIGNING -> "WAITING_REMOTE";
        default -> "RUNNING";
    };}
    private static void finish(Shop owner,SyncRunRepository reports,String run,String step){
        var counts=reports.counts(run);
        String state=counts.getOrDefault("RUNNING",0L)+counts.getOrDefault("QUEUED",0L)>0?"RUNNING":counts.getOrDefault("WAITING_REMOTE",0L)>0?"WAITING_REMOTE"
                :counts.getOrDefault("NEEDS_ACTION",0L)>0?"PARTIAL":"SUCCEEDED";
        reports.stage(owner,run,state,step,state.equals("PARTIAL")?"ZNACK_REGISTRATION_NEEDS_ACTION":"");
    }
}
