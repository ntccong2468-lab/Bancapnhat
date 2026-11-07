package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.features.kiz.status.OzonKn3Protocol;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.features.kiz.KizSourceSelection;
import java.io.IOException;
import java.util.*;

/** Assembly is allowed only after Ozon confirms the exact reserved marks, not after a local print. */
final class OzonAcceptanceKizService {
    private final OzonExemplarJobRepository jobs;
    OzonAcceptanceKizService(OzonExemplarJobRepository jobs) { this.jobs=jobs; }

    /** A refused optional mark must not block a shipment Ozon explicitly permits without it. */
    OzonPostingDto prepareForReceipt(Shop shop,OzonPostingDto posting,OzonApiClient api,KizSourceSelection sources)throws IOException {
        try{ensure(shop,posting,api,sources);}
        catch(IOException|OzonRequirementGuard.MissingMappingException failure){
            if(!posting.requirements().mandatoryMarkProductIds().isEmpty()||!optionalFailure(failure))throw failure;
            ListingIdentity.require(shop);
            var raw=api.getPosting(posting.postingNumber(),true,true);
            var fresh=OzonJson.requirePostingDetail(raw,posting.postingNumber()).withTrackingOwner(api.syncOwnerFingerprint(shop.getId()));
            var detail=OzonJson.object(raw,"result");if(detail.isEmpty())detail=raw;
            var mandatory=OzonJson.object(detail,"requirements").get("products_requiring_mandatory_mark");
            if(mandatory==null||!mandatory.isJsonArray()||!mandatory.getAsJsonArray().isEmpty()
                    ||!fresh.canShip()||!"awaiting_packaging".equals(fresh.status())
                    ||!fresh.orderId().equals(posting.orderId())||!fresh.warehouseId().equals(posting.warehouseId())
                    ||!sameProducts(posting,fresh))throw failure;
            jobs.logAction(shop.getId(),"accept_kiz_optional",posting.postingNumber(),"skipped",
                    failure instanceof OzonRequirementGuard.MissingMappingException?"KIZ_GTIN_MAPPING_MISSING":failure.getMessage(),null);
            new OzonPostingRepository().upsertDetail(shop.getId(),fresh);
            return fresh;
        }
        return OzonJson.requirePostingDetail(api.getPosting(posting.postingNumber(),true,true),posting.postingNumber())
                .withTrackingOwner(api.syncOwnerFingerprint(shop.getId()));
    }
    private static boolean optionalFailure(Exception failure){
        if(failure instanceof OzonRequirementGuard.MissingMappingException)return true;
        return Set.of("OZON_ACCEPT_KIZ_REJECTED","OZON_ACCEPT_KIZ_STOCK_REQUIRED","OZON_ACCEPT_KIZ_SOURCE_REQUIRED")
                .contains(Objects.requireNonNullElse(failure.getMessage(),""));
    }
    private static boolean sameProducts(OzonPostingDto left,OzonPostingDto right){
        var a=new TreeMap<String,Integer>();var b=new TreeMap<String,Integer>();
        left.items().forEach(item->a.merge(item.productId()+"/"+item.sku(),item.quantity(),Math::addExact));
        right.items().forEach(item->b.merge(item.productId()+"/"+item.sku(),item.quantity(),Math::addExact));
        return a.equals(b);
    }

    static boolean requiredBeforeAccept(Shop shop,OzonPostingDto posting) {
        return requiredBeforeAccept(shop,posting,null);
    }
    static boolean requiredBeforeAccept(Shop shop,OzonPostingDto posting,KizSourceSelection sources) {
        if(!posting.requirements().mandatoryMarkProductIds().isEmpty())return true;
        // Ozon describes this list as *possible* marking, not a requirement.
        // An explicit no-source choice must not turn every optional SKU into a
        // blocking mandatory mark. The server still decides whether to accept it.
        if(sources!=null&&!sources.anySelected())return false;
        var optional=new HashSet<>(posting.requirements().optionalMarkProductIds());
        if(optional.isEmpty())return false;
        var exempt=new OzonProductKizPolicyRepository().findExemptSkus(shop.getId());
        return posting.items().stream().anyMatch(item->optional.contains(item.productId())&&!exempt.contains(item.sku()));
    }
    void ensure(Shop shop,OzonPostingDto posting,OzonApiClient api)throws IOException {
        ensure(shop,posting,api,null);
    }
    void ensure(Shop shop,OzonPostingDto posting,OzonApiClient api,KizSourceSelection sources)throws IOException {
        if(!requiredBeforeAccept(shop,posting,sources))return;
        var replacements=new OzonAcceptanceKizReplacement();
        for(int attempt=0;attempt<1000;attempt++) {
            if(Thread.currentThread().isInterrupted())throw new IOException("OZON_ACCEPT_KIZ_PENDING");
            try {
                var job=jobs.find(shop.getId(),posting.postingNumber());
                var ticket=replacements.active(shop,job);
                if(ticket==null&&job!=null){
                    var observed=new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,posting.postingNumber());
                    var bound=jobs.bindings(job.id());
                    if(observed.stream().anyMatch(m->m.state()==Kn3Mark.State.REJECTED&&bound.stream().anyMatch(b->OzonAcceptanceKizReplacement.matches(b,m)))){
                        if(job.mutationAttemptedAt()!=null&&!job.mutationAttemptedAt().isBlank()){
                            verify(shop,posting,api,job);return;
                        }
                        throw detailedRejection(shop,posting,job,observed,OzonExemplarService.exemplarPayload(posting.postingNumber(),bound,true),"");
                    }
                }
                if(ticket!=null&&ticket.state().equals("PREPARED"))sendPreparedReplacement(shop,posting,api,job,ticket,replacements);
                ensureOnce(shop,posting,api,sources);
                return;
            }catch(RejectedMarks failure){
                if(!replacements.prepare(shop,posting,failure.job,failure.marks,failure.payload,failure.remoteAnchor,sources,jobs))throw failure;
            }
        }
        throw new IOException("OZON_ACCEPT_KIZ_RETRY_LIMIT");
    }
    private void ensureOnce(Shop shop,OzonPostingDto posting,OzonApiClient api,KizSourceSelection sources)throws IOException {
        if(!requiredBeforeAccept(shop,posting,sources))return;
        if(sources!=null&&!sources.anySelected())throw new IOException("OZON_ACCEPT_KIZ_SOURCE_REQUIRED");
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);
        new OzonPostingRepository().upsertDetail(shop.getId(),posting);
        var prior=jobs.find(shop.getId(),posting.postingNumber());
        // Older/local print jobs can exist before the first accept_kiz action is logged.
        // Keep the mutation-attempt check below so an uncertain submission is reconciled.
        String action=Objects.requireNonNullElse(jobs.latestActionStatus(shop.getId(),"accept_kiz",posting.postingNumber()),"");
        if(prior!=null&&(!action.isBlank()&&!action.equals("rejected")
                || action.isBlank()&&prior.mutationAttemptedAt()!=null&&!prior.mutationAttemptedAt().isBlank())) {
            verify(shop,posting,api,prior);return;
        }
        var categorySkus=new HashSet<String>();
        if(sources==null||sources.sellerImported())categorySkus.addAll(OzonCategoryStock.categorySkus(shop.getId(),posting.items().stream().map(OzonPostingItemDto::sku).toList()));
        // Already reserved category bindings must remain valid even if the seller
        // selects another source on a later retry; they are never reallocated.
        categorySkus.addAll(OzonCategoryStock.reservedCategorySkus(shop.getId(),posting,prior));
        // Seller-imported category stock is independent of Catalog/SKU GTIN mappings.
        // Read those mappings only when bought stock may actually be used.
        var skuMappings=sources!=null&&!sources.purchased()?Map.<String,String>of():
                new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId());
        var plan=OzonRequirementGuard.plan(posting,skuMappings,
                new OzonProductKizPolicyRepository().findExemptSkus(shop.getId()),categorySkus);
        var local=jobs.stageLocalForPrint(shop.getId(),plan,sources,()->{ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);},
                ()->OzonRequirementGuard.requireCurrentPurchasedMappings(shop.getId(),plan,sources));
        var job=local.job();
        if(!local.ready())throw new IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");
        var remote=api.createOrGetExemplars(OzonExemplarService.createRequest(posting));
        // Match IDs by product before replacing local print slots; KIZ reservations stay unchanged.
        var ids=OzonExemplarService.remoteExemplarIds(remote,plan);
        if(hasMarks(remote))throw new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS");
        jobs.persistRemoteExemplars(job,plan,ids);
        var bindings=jobs.bindings(job.id());
        var rawValidation=api.validateExemplars(OzonExemplarService.exemplarPayload(posting.postingNumber(),bindings,false));
        var validation=OzonExemplarJson.validation(rawValidation,bindings.size());
        if(validation.rejected())throw detailedRejection(shop,posting,job,validationMarks(posting,rawValidation,bindings),
                OzonExemplarService.exemplarPayload(posting.postingNumber(),bindings,true),"");
        if(!validation.allMarksPassed())throw new IOException("OZON_ACCEPT_KIZ_PENDING");
        var payload=OzonExemplarService.exemplarPayload(posting.postingNumber(),bindings,true);
        String fingerprint=ListingIdentity.hash(payload.toString());
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);
        jobs.requireCurrentBindingsBeforeSend(shop,posting.postingNumber(),job.id());
        if(!jobs.tryBeginAction(shop.getId(),"accept_kiz",posting.postingNumber(),fingerprint)){
            verify(shop,posting,api,job);return;
        }
        if(job.stage()==OzonExemplarJobStage.VALIDATED)
            job=jobs.transition(job,OzonExemplarJobStage.SET_PENDING,fingerprint,null,true);
        try {
            api.setExemplars(payload);
            jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),"submitted",null,fingerprint);
            if(job.stage()==OzonExemplarJobStage.SET_PENDING)
                job=jobs.transition(job,OzonExemplarJobStage.VERIFYING,fingerprint,null,false);
        } catch(OzonApiException failure) {
            jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),failure.ambiguousMutation()?"ambiguous":"rejected",failure.safeErrorCode(),fingerprint);
            if(!failure.ambiguousMutation()&&job.stage()==OzonExemplarJobStage.SET_PENDING)
                jobs.transition(job,OzonExemplarJobStage.VALIDATED,fingerprint,failure.safeErrorCode(),false);
            // Keep every bound mark until authoritative readback; a timeout never releases stock.
            throw failure;
        }
        verify(shop,posting,api,job);
    }

    private void verify(Shop shop,OzonPostingDto posting,OzonApiClient api,OzonExemplarJob job)throws IOException {
        var bindings=jobs.bindings(job.id());
        if(bindings.isEmpty())throw new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS");
        var request=new JsonObject();request.addProperty("posting_number",posting.postingNumber());
        for(int attempt=0;attempt<8;attempt++) {
            ListingIdentity.require(shop);
            var raw=api.exemplarStatus(request);
            var marks=OzonKn3Protocol.parse(posting.postingNumber(),raw);
            boolean identitiesMatch=marks.size()==bindings.size()&&bindings.stream().allMatch(binding->
                    marks.stream().anyMatch(mark->OzonAcceptanceKizReplacement.matches(binding,mark)));
            if(identitiesMatch&&marks.stream().anyMatch(m->m.state()==Kn3Mark.State.REJECTED)){
                var rejected=marks.stream().filter(m->m.state()==Kn3Mark.State.REJECTED).findFirst().orElseThrow();
                throw detailedRejection(shop,posting,job,marks,OzonKn3Protocol.replacement(posting.postingNumber(),raw,rejected,rejected.code()),rejected.fingerprint());
            }
            boolean same=marks.size()==bindings.size()&&bindings.stream().allMatch(binding->marks.stream().anyMatch(mark->
                    mark.productId().equals(binding.productId())&&mark.exemplarId().equals(binding.exemplarId())
                            &&mark.fingerprint().equals(Kn3Mark.fingerprint(binding.rawCode()))&&mark.state()==Kn3Mark.State.ACCEPTED));
            if(same&&OzonExemplarJson.status(raw,bindings.size()).accepted()) {
                if(job.stage()==OzonExemplarJobStage.SET_PENDING)job=jobs.transition(job,OzonExemplarJobStage.VERIFYING,job.requestFingerprint(),null,false);
                if(job.stage()==OzonExemplarJobStage.VERIFYING||job.stage()==OzonExemplarJobStage.RECONCILE_REQUIRED)jobs.consumeAccepted(job);
                new OzonAcceptanceKizReplacement().settle(shop,job,marks);
                jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),"reconciled",null,job.requestFingerprint());
                return;
            }
            if(attempt<7)try{Thread.sleep(250);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("OZON_ACCEPT_KIZ_PENDING",stopped);}
        }
        throw new IOException("OZON_ACCEPT_KIZ_PENDING");
    }
    private void sendPreparedReplacement(Shop shop,OzonPostingDto posting,OzonApiClient api,OzonExemplarJob job,
            OzonAcceptanceKizReplacement.Ticket ticket,OzonAcceptanceKizReplacement replacements)throws IOException {
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);
        var payload=ticket.payload();
        if(ticket.remoteRejected()){
            var request=new JsonObject();request.addProperty("posting_number",posting.postingNumber());var fresh=api.exemplarStatus(request);
            var current=OzonKn3Protocol.parse(posting.postingNumber(),fresh);
            var target=current.stream().filter(m->m.state()==Kn3Mark.State.REJECTED&&m.fingerprint().equals(ticket.oldHash())).findFirst().orElseThrow(()->new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS"));
            var replacement=jobs.bindings(job.id()).stream().filter(b->b.productId().equals(target.productId())&&b.exemplarId().equals(target.exemplarId())
                    &&Kn3Mark.fingerprint(b.rawCode()).equals(ticket.newHash())).findFirst().orElseThrow(()->new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS"));
            payload=OzonKn3Protocol.replacement(posting.postingNumber(),fresh,target,replacement.rawCode());
            // Sibling mandatory marks must still be the exact owned bindings.
            var bound=jobs.bindings(job.id());
            if(current.size()!=bound.size()||current.stream().filter(m->m!=target).anyMatch(m->bound.stream().noneMatch(b->OzonAcceptanceKizReplacement.matches(b,m))))
                throw new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS");
        }else{
            var remote=api.createOrGetExemplars(OzonExemplarService.createRequest(posting));
            var ids=OzonExemplarJson.exemplarIdsByProduct(remote);var bound=jobs.bindings(job.id());
            if(hasMarks(remote)||ids.values().stream().mapToInt(List::size).sum()!=bound.size()
                    ||bound.stream().anyMatch(b->!ids.getOrDefault(b.productId(),List.of()).contains(b.exemplarId())))
                throw new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS");
        }
        var sendingTicket=new OzonAcceptanceKizReplacement.Ticket(ticket.id(),ticket.state(),payload,ticket.oldHash(),ticket.newHash(),ticket.remoteRejected());
        var validationPayload=payload.deepCopy();
        for(var product:validationPayload.getAsJsonArray("products"))
            for(var exemplar:product.getAsJsonObject().getAsJsonArray("exemplars"))exemplar.getAsJsonObject().remove("exemplar_id");
        var raw=api.validateExemplars(validationPayload);var status=OzonExemplarJson.validation(raw,jobs.bindings(job.id()).size());
        if(status.rejected())throw detailedRejection(shop,posting,job,validationMarks(posting,raw,jobs.bindings(job.id())),payload,ticket.remoteRejected()?ticket.oldHash():"");
        if(!status.allMarksPassed())throw new IOException("OZON_ACCEPT_KIZ_PENDING");
        jobs.requireCurrentBindingsBeforeSend(shop,posting.postingNumber(),job.id());
        String fingerprint=ListingIdentity.hash(payload.toString());
        if(!replacements.claim(shop,job,sendingTicket,fingerprint))throw new IOException("OZON_ACCEPT_KIZ_PENDING");
        try {
            ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);api.setExemplars(payload);
            replacements.state(shop,sendingTicket,"AWAITING");
            jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),"submitted",null,fingerprint);
            var current=jobs.find(shop.getId(),posting.postingNumber());
            if(current.stage()==OzonExemplarJobStage.SET_PENDING)jobs.transition(current,OzonExemplarJobStage.VERIFYING,fingerprint,null,false);
        }catch(OzonApiException failure){
            if(!failure.ambiguousMutation()){
                replacements.state(shop,sendingTicket,"FAILED");
                var current=jobs.find(shop.getId(),posting.postingNumber());
                if(current.stage()==OzonExemplarJobStage.SET_PENDING)jobs.transition(current,OzonExemplarJobStage.VALIDATED,fingerprint,failure.safeErrorCode(),false);
            }
            jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),failure.ambiguousMutation()?"ambiguous":"rejected",failure.safeErrorCode(),fingerprint);
            throw failure;
        }
    }
    private IOException detailedRejection(Shop shop,OzonPostingDto posting,OzonExemplarJob job,List<Kn3Mark> marks,JsonObject payload,String remoteAnchor){
        var generic=recordRejection(shop,posting,job);
        return marks.stream().anyMatch(m->m.state()==Kn3Mark.State.REJECTED)?new RejectedMarks(job,marks,payload,remoteAnchor):generic;
    }
    /** Validation has no exemplar IDs: resolve only a unique exact product/code pair, never array order. */
    private static List<Kn3Mark> validationMarks(OzonPostingDto posting,JsonObject raw,List<OzonExemplarJobRepository.KizBinding> bindings){
        var rejected=new HashSet<String>();var root=OzonKn3Protocol.result(raw);
        for(var product:OzonJson.array(root,"products")){
            if(!product.isJsonObject())continue;var p=product.getAsJsonObject();String id=OzonJson.text(p,"product_id");
            for(var exemplar:OzonJson.array(p,"exemplars")){
                if(!exemplar.isJsonObject())continue;
                for(var value:OzonJson.array(exemplar.getAsJsonObject(),"marks")){
                    if(!value.isJsonObject())continue;var mark=value.getAsJsonObject();
                    boolean invalid=mark.has("valid")&&mark.get("valid").isJsonPrimitive()
                            &&mark.getAsJsonPrimitive("valid").isBoolean()&&!mark.get("valid").getAsBoolean();
                    invalid|=!OzonJson.array(mark,"errors").isEmpty();
                    if(!invalid||!"mandatory_mark".equals(OzonJson.text(mark,"mark_type")))continue;
                    String hash=Kn3Mark.fingerprint(OzonJson.text(mark,"mark"));
                    var exact=bindings.stream().filter(b->b.productId().equals(id)&&Kn3Mark.fingerprint(b.rawCode()).equals(hash)).toList();
                    if(exact.size()==1)rejected.add(id+":"+exact.getFirst().exemplarId());
                }
            }
        }
        return bindings.stream().map(b->new Kn3Mark(posting.postingNumber(),b.productId(),b.exemplarId(),b.rawCode(),
                rejected.contains(b.productId()+":"+b.exemplarId())?Kn3Mark.State.REJECTED:Kn3Mark.State.PENDING,"VALIDATION_REJECTED")).toList();
    }
    private static final class RejectedMarks extends IOException {
        final OzonExemplarJob job;final List<Kn3Mark> marks;final JsonObject payload;final String remoteAnchor;
        RejectedMarks(OzonExemplarJob job,List<Kn3Mark> marks,JsonObject payload,String remoteAnchor){super("OZON_ACCEPT_KIZ_REJECTED");this.job=job;this.marks=List.copyOf(marks);this.payload=payload.deepCopy();this.remoteAnchor=remoteAnchor;}
    }
    private IOException recordRejection(Shop shop,OzonPostingDto posting,OzonExemplarJob job){
        jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),"rejected","OZON_ACCEPT_KIZ_REJECTED",job.requestFingerprint());
        // Keep the exact reservation for this order's combined label. Local printing does
        // not change the rejected accept_kiz action into a remote acceptance.
        jobs.transition(job,job.stage(),job.requestFingerprint(),"OZON_ACCEPT_KIZ_REJECTED",false);
        return new IOException("OZON_ACCEPT_KIZ_REJECTED");
    }
    private static boolean hasMarks(com.google.gson.JsonElement value) {
        if(value==null||value.isJsonNull())return false;
        if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()) {
            if((entry.getKey().equals("mark")||entry.getKey().equals("mandatory_mark"))&&entry.getValue().isJsonPrimitive()&&!entry.getValue().getAsString().isBlank())return true;
            if(hasMarks(entry.getValue()))return true;
        } else if(value.isJsonArray())for(var item:value.getAsJsonArray())if(hasMarks(item))return true;
        return false;
    }
}
