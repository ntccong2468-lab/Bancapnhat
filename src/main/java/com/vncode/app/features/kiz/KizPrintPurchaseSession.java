package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.UpdateHandoff;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** One frozen export. Consent unwinds the planning locks; waiting never holds a stock reservation. */
public final class KizPrintPurchaseSession {
    public record Row(String gtin,int required,int available,int missing,int buy) {}
    public record Proposal(List<Row> rows) {
        public Proposal { rows=List.copyOf(rows); }
        public int quantity(){return rows.stream().mapToInt(Row::buy).sum();}
    }
    public static final class ProposalRequired extends IllegalStateException {
        private final Proposal proposal;
        ProposalRequired(Proposal proposal){super("KIZ_PRINT_PURCHASE_PROPOSAL");this.proposal=proposal;}
        public Proposal proposal(){return proposal;}
    }
    /** Unwinds a stopped wait without changing the durable purchase or granting signing consent. */
    public static final class PurchasePaused extends IllegalStateException {
        private final long pipelineId;
        private final String gtin;
        private PurchasePaused(Shop shop,long pipelineId,String gtin,String stage,String reason){
            super("GTIN: "+gtin+" · #"+pipelineId+"\n"+ZnackPurchaseDiagnostics.text(reason,stage,
                    ZnackPurchaseCoordinator.nextCheckAt(shop.getId(),pipelineId)));
            this.pipelineId=pipelineId;this.gtin=gtin;
        }
        public long pipelineId(){return pipelineId;}
        public String gtin(){return gtin;}
    }
    private static final ThreadLocal<KizPrintPurchaseSession> CURRENT=new ThreadLocal<>();
    private final Shop shop;
    private final String identity;
    private final Predicate<Proposal> consent;
    private final Consumer<String> progress;
    private final BooleanSupplier cancelled;
    private final Runnable pause;
    private final Consumer<KizPurchasePendingException> poll;
    private final Runnable validate;
    private Proposal approved;
    private final Map<String,Integer> spent=new HashMap<>();
    private ZnackModels.Settings approvedSettings,proposalSettings;

    public KizPrintPurchaseSession(Shop shop,Predicate<Proposal> consent,Consumer<String> progress,BooleanSupplier cancelled){
        this(shop,consent,progress,cancelled,()->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}},null,null);
    }
    KizPrintPurchaseSession(Shop shop,Predicate<Proposal> consent,Consumer<String> progress,BooleanSupplier cancelled,
                            Runnable pause,Consumer<KizPurchasePendingException> poll,Runnable validate){
        this.shop=Objects.requireNonNull(shop);this.identity=ListingIdentity.fingerprint(shop);this.consent=consent;this.progress=progress;this.cancelled=cancelled;this.pause=pause;
        this.poll=poll==null?this::checkPipeline:poll;
        this.validate=validate==null?()->ListingIdentity.require(shop):validate;
    }
    static boolean active(){return CURRENT.get()!=null;}
    /** Read-only print preflight: a shortage is deferred to the later Apply dialog. */
    public static void preflight(Shop shop,Callable<?> work)throws Exception{
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested print purchase session");
        CURRENT.set(new KizPrintPurchaseSession(shop,p->false,s->{},()->false));
        try{work.call();}catch(ProposalRequired shortage){/* No enqueue has run. */}finally{CURRENT.remove();}
    }
    /** Called before enqueue, even for an existing pending buy. No dialog or wait inside the planning lock. */
    static void authorize(Shop shop,Map<String,Integer> required,Map<String,Integer> counts,Map<String,Integer> missing,Map<String,Integer> toBuy){
        var session=CURRENT.get();if(session==null)return;
        session.check();
        if(session.shop.getId()!=shop.getId()||!ListingIdentity.fingerprint(session.shop).equals(ListingIdentity.fingerprint(shop)))throw new IllegalStateException("KIZ_OWNER_CHANGED");
        var rows=missing.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->new Row(e.getKey(),required.get(e.getKey()),counts.getOrDefault(e.getKey(),0),e.getValue(),toBuy.getOrDefault(e.getKey(),0))).toList();
        var proposal=new Proposal(rows);
        if(session.approved==null||rows.stream().anyMatch(row->session.approved.rows().stream().noneMatch(a->a.gtin().equals(row.gtin())&&a.required()==row.required()&&a.buy()-session.spent.getOrDefault(row.gtin(),0)>=row.buy())))
            {session.proposalSettings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();throw new ProposalRequired(proposal);}
        if(session.approvedSettings!=null&&!session.approvedSettings.equals(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()))throw new IllegalStateException("KIZ_OWNER_CHANGED");
    }
    static void requesting(String gtin,int quantity){var session=CURRENT.get();if(session!=null)session.spent.merge(gtin,quantity,Math::addExact);}
    public <T> T run(Callable<T> work)throws Exception {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested print purchase session");
        CURRENT.set(this);
        try{
            while(true){
                check();
                try{return work.call();}
                catch(ProposalRequired request){
                    progress.accept(tr("proposal"));
                    if(!consent.test(request.proposal()))throw new CancellationException();
                    check();approved=request.proposal();spent.clear();
                    approvedSettings=proposalSettings;check();
                    progress.accept(tr("buying"));
                }catch(KizPurchasePendingException pending){
                    if("review".equals(pending.reason()))throw pending;
                    awaitCirculation(pending);
                    progress.accept(tr("exporting"));
                }
            }
        }finally{CURRENT.remove();}
    }
    private void awaitCirculation(KizPurchasePendingException pending){
        while(true){
            check();
            // The paid request is persisted already. Installation may pause this in-memory print;
            // the purchase coordinator resumes its original request independently after restart.
            UpdateHandoff.checkpoint();check();
            var required=new TreeMap<String,Integer>();pending.deficits().forEach(d->required.put(d.gtin(),d.required()));
            var counts=pending.availableCounts(shop,required.keySet());
            int total=required.values().stream().mapToInt(Integer::intValue).sum();
            int ready=required.entrySet().stream().mapToInt(e->Math.min(e.getValue(),counts.getOrDefault(e.getKey(),0))).sum();
            progress.accept(java.text.MessageFormat.format(tr("waiting"),ready,total));
            if(ready==total)return;
            poll.accept(pending);pause.run();
        }
    }
    private void check(){if(!identity.equals(ListingIdentity.fingerprint(shop)))throw new IllegalStateException("KIZ_OWNER_CHANGED");if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||UpdateHandoff.isStopping())throw new CancellationException();validate.run();
        if(approvedSettings!=null&&!approvedSettings.equals(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()))throw new IllegalStateException("KIZ_OWNER_CHANGED");
    }
    private void checkPipeline(KizPurchasePendingException pending){
        var counts=pending.availableCounts(shop,pending.deficits().stream().map(KizPurchasePendingException.Deficit::gtin).toList());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,stage,error_message FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? ORDER BY id DESC")){
            for(var deficit:pending.deficits()){
                if(counts.getOrDefault(deficit.gtin(),0)>=deficit.required())continue;
                p.setInt(1,shop.getId());p.setString(2,deficit.gtin());try(var r=p.executeQuery()){
                    boolean found=false,latestNeedsAction=false;
                    while(r.next()){
                        long pipelineId=r.getLong("id");String stage=r.getString("stage"),error=r.getString("error_message");
                        if(!found)latestNeedsAction=Set.of("FAILED","INTRODUCTION_FAILED","COMPLETED","INTRODUCED","INTRODUCTION_SKIPPED_MISSING_DOCUMENTS","INTRODUCTION_SKIPPED_MISSING_METADATA","WAITING_INTRODUCTION_DOCUMENTS").contains(stage);
                        found=true;
                        if(ZnackPurchaseCoordinator.isSigningConfirmationPending(shop.getId(),pipelineId,stage,error)){
                            String reason=ZnackSigningSession.isSigningDeferred(error)?error:ZnackSigningSession.signingWaitMessage(shop.getId());
                            throw new PurchasePaused(shop,pipelineId,deficit.gtin(),stage,reason);
                        }
                    }
                    if(!found||latestNeedsAction)
                        throw new KizAssignmentException("KIZ_PRINT_PURCHASE_NEEDS_ACTION","",deficit.gtin());
                }
            }
        }catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
    }
    private static String tr(String key){return I18nService.getInstance().tr("kiz.print."+key);}
}
