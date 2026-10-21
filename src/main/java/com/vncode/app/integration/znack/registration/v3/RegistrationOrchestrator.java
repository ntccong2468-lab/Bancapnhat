package com.vncode.app.integration.znack.registration.v3;

import com.google.gson.Gson;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService;
import java.time.*;
import java.util.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationState.*;

/** Durable single-attempt commands, exact review binding, read-only recovery. Run off the FX thread. */
public final class RegistrationOrchestrator {
    @FunctionalInterface interface Gate { void require(Action action,String environment); }
    public record Review(String jobId, Owner owner, int revision, Action action, String requestHash,
                         String content, String approvedBefore) {
        @Override public String toString(){return "RegistrationReview[redacted]";}
    }
    private static final Gson JSON=new Gson();
    private final RegistrationStore store;
    private final RegistrationGateway gateway;
    private final Gate gate;
    private final Clock clock;
    public RegistrationOrchestrator(RegistrationStore store,RegistrationGateway gateway) {
        this(store,gateway,RegistrationContractPolicy::require,Clock.systemUTC());
    }
    RegistrationOrchestrator(RegistrationStore store,RegistrationGateway gateway,Gate gate,Clock clock) {
        this.store=Objects.requireNonNull(store);this.gateway=Objects.requireNonNull(gateway);this.gate=Objects.requireNonNull(gate);this.clock=Objects.requireNonNull(clock);
    }
    private RegistrationJob current(RegistrationJob job) {
        if(!gateway.owner().equals(job.owner()))throw new SecurityException("V3_OWNER_CHANGED");
        var latest=store.get(job.owner(),job.id());
        if(!latest.equals(job))throw new IllegalStateException("V3_REVISION_CHANGED");
        return latest;
    }
    public List<RegistrationJob> importProducts() throws Exception {
        var owner=gateway.owner();var products=gateway.products();
        if(!owner.equals(gateway.owner()))throw new SecurityException("V3_OWNER_CHANGED");
        var result=new ArrayList<RegistrationJob>();
        for(var product:products)result.add(store.create(owner,product));
        return List.copyOf(result);
    }
    public RegistrationJob resolveGtin(RegistrationJob requested,String code)throws Exception {
        var job=current(requested);
        boolean recoveringAllocation=job.state()==REMOTE_RESULT_UNKNOWN&&store.operations(job.owner(),job.id()).stream()
                .anyMatch(op->op.action().equals(Action.ALLOCATE_GTIN.name())&&op.status().equals("UNKNOWN"));
        if(!recoveringAllocation&&!Set.of(PRODUCT_IMPORTED,GTIN_RESOLVED,VALIDATION_FAILED).contains(job.state()))throw new IllegalStateException("V3_RECONCILIATION_REQUIRED");
        if(!RegisteredGtinValidator.isValid(code))throw new IllegalArgumentException("V3_GTIN_INVALID");
        var candidate=new RegistrationJob(job.id(),job.owner(),job.product(),job.state(),job.revision(),job.data().withGtin(code),job.updatedAt());
        var found=gateway.observe(candidate);requireObservation(candidate,found);
        current(job);
        var next=job.next(GTIN_RESOLVED,job.data().withGtin(code).withRemote(found.goodId(),"",""));
        return store.reconcile(job,next,recoveringAllocation?Set.of(Action.ALLOCATE_GTIN):Set.of());
    }
    public RegistrationJob correct(RegistrationJob requested)throws Exception {
        var job=current(requested);if(job.state()!=MODERATION_REJECTED)throw new IllegalStateException("V3_CONFIRMED_REJECTION_REQUIRED");
        var observed=gateway.observe(job);requireObservation(job,observed);current(job);
        if(observed.state()!=RegistrationGateway.RemoteState.REJECTED)throw new IllegalStateException("V3_CONFIRMED_REJECTION_REQUIRED");
        if(store.operations(job.owner(),job.id()).stream().anyMatch(op->Set.of("SENDING","UNKNOWN").contains(op.status())))throw new IllegalStateException("V3_RECONCILIATION_REQUIRED");
        return store.save(job,job.next(NEEDS_CORRECTION,job.data().withRemote(job.data().goodId(),"",observed.detail())));
    }
    public RegistrationJob saveDraft(RegistrationJob requested,Draft draft) {
        var job=current(requested);Objects.requireNonNull(draft);
        if(!Set.of(PRODUCT_IMPORTED,GTIN_RESOLVED,ATTRIBUTES_READY,VALIDATED,READY_FOR_SUBMISSION,
                SUBMISSION_PENDING_CONFIRMATION,NEEDS_CORRECTION,VALIDATION_FAILED,AUTH_FAILED,PERMISSION_DENIED,RATE_LIMITED,TEMPORARY_FAILURE).contains(job.state()))throw new IllegalStateException("V3_DRAFT_LOCKED");
        String payload="";
        if(!blank(job.data().gtin())) {
            var body=ZnackNationalCatalogService.buildPayload(job.data().gtin(),draft.withAutoSign(false),job.product().imageUrl());
            if(job.data().goodId()!=null)body.addProperty("good_id",job.data().goodId());
            payload=body.toString();
        }
        var state=blank(job.data().gtin())?PRODUCT_IMPORTED:ATTRIBUTES_READY;
        return store.save(job,job.next(state,job.data().withDraft(draft.withAutoSign(false),payload)));
    }
    public RegistrationJob validate(RegistrationJob requested)throws Exception {
        var job=current(requested);
        if(job.state()!=ATTRIBUTES_READY)throw new IllegalStateException("V3_DRAFT_REQUIRED");
        if(!RegisteredGtinValidator.isValid(job.data().gtin())||job.data().draft()==null)throw new IllegalStateException("V3_DRAFT_REQUIRED");
        try{gateway.validate(job);}
        catch(SecurityException changed){throw changed;}
        catch(Exception failed){
            current(job);var state=knownFailure(failed);
            if(state==null)state=failed instanceof IllegalArgumentException||failed instanceof IllegalStateException?VALIDATION_FAILED:TEMPORARY_FAILURE;
            return store.save(job,job.next(state,job.data().withDetail(com.vncode.app.integration.znack.ZnackSanitizer.message(failed.getMessage()))));
        }
        current(job);
        return store.save(job,job.next(VALIDATED,job.data().withDetail("")));
    }
    public List<com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute> attributes(RegistrationJob job)throws Exception {
        current(job);var result=gateway.attributes(job);current(job);return List.copyOf(result);
    }
    public List<com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category> categories(RegistrationJob job,String tnved)throws Exception {
        current(job);var result=gateway.categories(job,tnved);current(job);return List.copyOf(result);
    }
    public record Suggestions(List<com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute> schema,Map<Long,String> values,Map<Long,String> types){}
    public Suggestions suggest(RegistrationJob job)throws Exception {
        current(job);if(job.data().draft()==null)throw new IllegalStateException("V3_DRAFT_REQUIRED");
        var schema=attributes(job);var p=job.product();
        var sku=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku(p.nmId(),p.chrtId(),p.subjectId(),p.vendorCode(),p.subjectName(),p.brand(),p.title(),p.color(),p.size(),p.barcodes(),p.imageUrl(),true,job.data().gtin(),job.data().goodId(),job.data().feedId(),com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.NOT_CREATED,"",false,p.russianSize());
        var mapped=new com.vncode.app.integration.znack.registration.ZnackWbAttributeMapper().map(sku,p.characteristics(),schema,job.data().draft().tnved(),job.data().draft().feedTnved(),null);
        var allowed=new HashSet<>(Set.of(35L,36L,2483L,2504L,2478L,13914L));
        if(blank(p.brand()))allowed.remove(2504L);if(blank(p.title()))allowed.remove(2478L);
        var values=new LinkedHashMap<Long,String>();var types=new LinkedHashMap<Long,String>();
        mapped.attributes().forEach((id,value)->{if(allowed.contains(id))values.put(id,value);});
        mapped.attributeTypes().forEach((id,value)->{if(allowed.contains(id))types.put(id,value);});current(job);
        return new Suggestions(schema,Map.copyOf(values),Map.copyOf(types));
    }
    public Review review(RegistrationJob requested,Action action)throws Exception {
        var job=current(requested);String content;
        switch(action) {
            case ALLOCATE_GTIN -> {
                if(job.state()!=PRODUCT_IMPORTED||job.data().draft()==null||!blank(job.data().gtin()))throw new IllegalStateException("V3_ALLOCATION_REVIEW_REQUIRED");
                content=JSON.toJson(job.data().draft());
            }
            case SUBMIT -> {
                if(!Set.of(VALIDATED,SUBMISSION_PENDING_CONFIRMATION).contains(job.state()))throw new IllegalStateException("V3_VALIDATION_REQUIRED");
                if(job.state()==VALIDATED)job=store.save(job,job.next(READY_FOR_SUBMISSION,job.data()));
                job=store.save(job,job.next(SUBMISSION_PENDING_CONFIRMATION,job.data()));content=job.data().payload();
            }
            case SIGN -> {
                if(!Set.of(APPROVED_FOR_SIGNING,SIGNING_PENDING).contains(job.state()))throw new IllegalStateException("V3_SIGNING_NOT_APPROVED");
                var document=gateway.document(job);current(job);
                if(job.data().goodId()==null||document.goodId()!=job.data().goodId())throw new IllegalStateException("V3_CARD_IDENTITY_CHANGED");
                job=store.save(job,job.next(SIGNING_PENDING,job.data().withDocument(document.xml(),document.hash())));content=document.xml();
            }
            case SYNC_WB -> {
                if(!Set.of(PUBLISHED_VERIFIED,WB_SYNC_PENDING).contains(job.state())||blank(job.data().verifiedAt()))throw new IllegalStateException("V3_PUBLICATION_REQUIRED");
                if(store.operations(job.owner(),job.id()).stream().anyMatch(op->op.action().equals(Action.SYNC_WB.name())))throw new IllegalStateException("V3_RECONCILIATION_REQUIRED");
                content=gateway.previewWb(job);current(job);
                // The immutable marketplace preview is retained for recovery/readback.
                job=store.save(job,job.next(WB_SYNC_PENDING,job.data().withWbPreview(content)));
            }
            default -> throw new IllegalStateException("V3_ACTION_INVALID");
        }
        return new Review(job.id(),job.owner(),job.revision(),action,hash(content),content,clock.instant().plusSeconds(600).toString());
    }
    public RegistrationJob confirm(Review review)throws Exception {
        var job=current(store.get(review.owner(),review.jobId()));
        if(job.revision()!=review.revision()||!clock.instant().isBefore(Instant.parse(review.approvedBefore()))
                ||Instant.parse(review.approvedBefore()).isAfter(clock.instant().plusSeconds(605)))throw new IllegalStateException("V3_REVIEW_EXPIRED_OR_CHANGED");
        String content=switch(review.action()) {
            case ALLOCATE_GTIN -> JSON.toJson(job.data().draft());case SUBMIT -> job.data().payload();
            case SIGN -> job.data().signingXml();case SYNC_WB -> job.data().wbPreview();
        };
        if(!hash(content).equals(review.requestHash())||!content.equals(review.content()))throw new IllegalStateException("V3_REVIEW_CHANGED");
        gate.require(review.action(),job.owner().environment());
        if(review.action()==Action.SUBMIT)gateway.validate(job);
        current(job);
        RegistrationState active=switch(review.action()) {case ALLOCATE_GTIN->GTIN_ALLOCATING;case SUBMIT->SUBMITTING;case SIGN->SIGNING;case SYNC_WB->WB_SYNC_PENDING;};
        var operation=store.begin(job,review.action(),review.requestHash(),active);
        var sending=store.get(job.owner(),job.id());
        try {
            current(sending);RegistrationJob next;
            switch(review.action()) {
                case ALLOCATE_GTIN -> {
                    String code=gateway.allocate(sending);
                    if(!RegisteredGtinValidator.isValid(code))throw new IllegalStateException("V3_GTIN_RESPONSE_INVALID");
                    next=sending.next(GTIN_RESOLVED,sending.data().withGtin(code));
                }
                case SUBMIT -> {
                    String feed=gateway.submit(sending);if(blank(feed))throw new IllegalStateException("V3_FEED_RESPONSE_INVALID");
                    next=sending.next(SUBMITTED,sending.data().withRemote(sending.data().goodId(),feed,""));
                }
                case SIGN -> {gateway.sign(sending);next=sending.next(SIGNATURE_SENT,sending.data());}
                case SYNC_WB -> {
                    boolean verified=gateway.syncWb(sending);
                    next=sending.next(verified?WB_SYNC_VERIFIED:WB_SYNC_PENDING,sending.data());
                }
                default -> throw new IllegalStateException("V3_ACTION_INVALID");
            }
            return store.finish(operation,next,"ACKNOWLEDGED");
        } catch(Exception uncertain) {
            var rejected=knownFailure(uncertain);
            if(rejected!=null)return store.finish(operation,sending.next(rejected,sending.data().withDetail(com.vncode.app.integration.znack.ZnackErrorDetails.summary(uncertain))),"REJECTED");
            // Never repeat a write because of timeout, parse failure, interruption or a lost ack.
            return store.finish(operation,sending.next(REMOTE_RESULT_UNKNOWN,sending.data().withDetail("V3_REMOTE_RESULT_UNKNOWN")),"UNKNOWN");
        }
    }
    private static RegistrationState knownFailure(Exception failure){
        if(!(failure instanceof com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException http))return null;
        return switch(http.statusCode()){case 400,413->VALIDATION_FAILED;case 401->AUTH_FAILED;case 403->PERMISSION_DENIED;case 429->RATE_LIMITED;default->null;};
    }
    public RegistrationJob reconcile(RegistrationJob requested)throws Exception {
        var job=current(requested);if(blank(job.data().gtin()))throw new IllegalStateException("V3_ALLOCATION_MANUAL_RECONCILIATION_REQUIRED");
        var operations=store.operations(job.owner(),job.id());
        if(operations.stream().anyMatch(op->op.status().equals("SENDING")))throw new IllegalStateException("V3_OPERATION_RUNNING");
        if(!blank(job.data().wbPreview())&&operations.stream().anyMatch(op->op.action().equals(Action.SYNC_WB.name()))) {
            boolean verified=gateway.verifyWb(job);current(job);
            var next=job.next(verified?WB_SYNC_VERIFIED:job.state(),job.data().withDetail(verified?"":"V3_WB_READBACK_PENDING"));
            return store.reconcile(job,next,verified?Set.of(Action.SYNC_WB):Set.of());
        }
        var observed=gateway.observe(job);requireObservation(job,observed);current(job);
        var target=switch(observed.state()){
            case PUBLISHED -> PUBLISHED_VERIFIED;case APPROVED -> APPROVED_FOR_SIGNING;
            case REJECTED -> MODERATION_REJECTED;case MODERATION -> UNDER_MODERATION;
            case DRAFT -> VERIFYING_PUBLICATION;
        };
        boolean matched=!blank(job.data().payload())&&hash(job.data().payload()).equals(observed.matchedPayloadHash());
        if(target==PUBLISHED_VERIFIED&&!blank(job.data().payload())&&!matched)throw new IllegalStateException("V3_PUBLICATION_REVISION_UNVERIFIED");
        var data=job.data().withRemote(observed.goodId(),blank(observed.feedId())?job.data().feedId():observed.feedId(),
                com.vncode.app.integration.znack.ZnackSanitizer.message(observed.detail()));
        if(target==PUBLISHED_VERIFIED)data=data.verified(observed.checkedAt());
        var proven=EnumSet.noneOf(Action.class);
        if(matched&&Set.of(PUBLISHED_VERIFIED,APPROVED_FOR_SIGNING,MODERATION_REJECTED).contains(target))proven.add(Action.SUBMIT);
        if(target==PUBLISHED_VERIFIED)proven.add(Action.SIGN);
        return store.reconcile(job,job.next(target,data),proven);
    }
    private void requireObservation(RegistrationJob job,RegistrationGateway.Observation result) {
        if(result==null||!RegisteredGtinProof.sameCode(job.data().gtin(),result.gtin())||result.goodId()==null||result.goodId()<=0
                ||job.data().goodId()!=null&&!job.data().goodId().equals(result.goodId())||!job.owner().environment().equals(result.environment())
                ||result.state()==null||blank(result.evidenceHash())||blank(result.checkedAt()))throw new IllegalStateException("V3_CARD_READBACK_INVALID");
        Instant at=Instant.parse(result.checkedAt());
        if(at.isAfter(clock.instant().plusSeconds(30))||at.isBefore(clock.instant().minusSeconds(300)))throw new IllegalStateException("V3_CARD_READBACK_EXPIRED");
    }
}
