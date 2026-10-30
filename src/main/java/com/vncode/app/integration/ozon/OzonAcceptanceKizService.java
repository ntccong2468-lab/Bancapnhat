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

    static boolean requiredBeforeAccept(Shop shop,OzonPostingDto posting) {
        if(!posting.requirements().mandatoryMarkProductIds().isEmpty())return true;
        var optional=new HashSet<>(posting.requirements().optionalMarkProductIds());
        if(optional.isEmpty())return false;
        var exempt=new OzonProductKizPolicyRepository().findExemptSkus(shop.getId());
        return posting.items().stream().anyMatch(item->optional.contains(item.productId())&&!exempt.contains(item.sku()));
    }
    void ensure(Shop shop,OzonPostingDto posting,OzonApiClient api)throws IOException {
        ensure(shop,posting,api,null);
    }
    void ensure(Shop shop,OzonPostingDto posting,OzonApiClient api,KizSourceSelection sources)throws IOException {
        if(!requiredBeforeAccept(shop,posting))return;
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
        var plan=OzonRequirementGuard.plan(posting,new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId()),
                new OzonProductKizPolicyRepository().findExemptSkus(shop.getId()),categorySkus);
        var local=jobs.stageLocalForPrint(shop.getId(),plan,sources,()->{ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);});
        var job=local.job();
        if(!local.ready())throw new IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");
        var remote=api.createOrGetExemplars(OzonExemplarService.createRequest(posting));
        // Match IDs by product before replacing local print slots; KIZ reservations stay unchanged.
        var ids=OzonExemplarService.remoteExemplarIds(remote,plan);
        if(hasMarks(remote))throw new IOException("OZON_ACCEPT_KIZ_EXISTING_MARKS");
        jobs.persistRemoteExemplars(job,plan,ids);
        var bindings=jobs.bindings(job.id());
        var validation=OzonExemplarJson.validation(api.validateExemplars(
                OzonExemplarService.exemplarPayload(posting.postingNumber(),bindings,false)),bindings.size());
        if(validation.rejected())throw new IOException("OZON_ACCEPT_KIZ_REJECTED");
        if(!validation.allMarksPassed())throw new IOException("OZON_ACCEPT_KIZ_PENDING");
        var payload=OzonExemplarService.exemplarPayload(posting.postingNumber(),bindings,true);
        String fingerprint=ListingIdentity.hash(payload.toString());
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);
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
            if(marks.stream().anyMatch(m->m.state()==Kn3Mark.State.REJECTED))throw new IOException("OZON_ACCEPT_KIZ_REJECTED");
            boolean same=marks.size()==bindings.size()&&bindings.stream().allMatch(binding->marks.stream().anyMatch(mark->
                    mark.productId().equals(binding.productId())&&mark.exemplarId().equals(binding.exemplarId())
                            &&mark.fingerprint().equals(Kn3Mark.fingerprint(binding.rawCode()))&&mark.state()==Kn3Mark.State.ACCEPTED));
            if(same&&OzonExemplarJson.status(raw,bindings.size()).accepted()) {
                if(job.stage()==OzonExemplarJobStage.SET_PENDING)job=jobs.transition(job,OzonExemplarJobStage.VERIFYING,job.requestFingerprint(),null,false);
                if(job.stage()==OzonExemplarJobStage.VERIFYING||job.stage()==OzonExemplarJobStage.RECONCILE_REQUIRED)jobs.consumeAccepted(job);
                jobs.logAction(shop.getId(),"accept_kiz",posting.postingNumber(),"reconciled",null,job.requestFingerprint());
                return;
            }
            if(attempt<7)try{Thread.sleep(250);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("OZON_ACCEPT_KIZ_PENDING",stopped);}
        }
        throw new IOException("OZON_ACCEPT_KIZ_PENDING");
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
