package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import java.util.List;
import java.util.Objects;

/** Conservative automatic corrections for one independently observed rejected card. */
final class CatalogAutomaticRepair {
    record Change(String field, String before, String after, String source) { }
    record Proposal(JsonObject originalPayload, JsonObject payload, List<Change> changes, String rejection, long goodId) {
        Proposal { originalPayload = originalPayload.deepCopy(); payload = payload.deepCopy(); changes = List.copyOf(changes); }
        @Override public JsonObject originalPayload() { return originalPayload.deepCopy(); }
        @Override public JsonObject payload() { return payload.deepCopy(); }
    }

    /** Read the exact rejected draft again; absent owner evidence is not permission to edit. */
    static boolean verifiedRejectedOwner(ZnackCardRegistrationWorkflow.CatalogSession session,
                                         String gtin, long goodId) throws Exception {
        if (session == null || session.scope() == null || goodId <= 0) return false;
        JsonObject card;
        try { card = session.catalog().rejectedCard(session.token(), gtin, goodId); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        return ownerMatches(card, session.scope().inn());
    }

    static boolean ownerMatches(JsonObject card, String participantInn) {
        if (card == null || participantInn == null || participantInn.isBlank()) return false;
        boolean observed = false;
        for (String name : List.of("owner_inn", "participant_inn")) {
            if (!card.has(name) || card.get(name).isJsonNull()) continue;
            if (!card.get(name).isJsonPrimitive() || !participantInn.equals(card.get(name).getAsString().strip())) return false;
            observed = true;
        }
        return observed;
    }

    /** Re-read the current category model before normalizing any rejected field. */
    static Proposal propose(ZnackCardRegistrationWorkflow.CatalogSession session,
                            ZnackNationalCatalogService.FeedProgress progress,JsonObject payload,
                            String gtin,long verifiedGoodId,boolean verifiedOwner)throws Exception {
        return propose(session,progress,payload,gtin,verifiedGoodId,verifiedOwner,null,null,null);
    }

    /** Variant additions require the exact seller-reviewed operation and fresh marketplace facts. */
    static Proposal propose(ZnackCardRegistrationWorkflow.CatalogSession session,
                            ZnackNationalCatalogService.FeedProgress progress,JsonObject payload,
                            String gtin,long verifiedGoodId,boolean verifiedOwner,
                            com.vncode.app.models.Shop shop,RegistrationSourceConsentGuard.VerifiedRepairIntent proof,
                            RegistrationSnapshotReview.Reader snapshots)throws Exception {
        var packing=packing(progress,payload,gtin,verifiedGoodId,verifiedOwner);
        if(packing!=null)return packing;
        if(!verifiedOwner||progress==null||!CatalogFeedback.confirmedRejection(progress)
                ||progress.errors().stream().noneMatch(error->error!=null&&error.indexOf(':')>0))return null;
        ZnackCardRegistrationModels.Draft draft;
        try{draft=ZnackCardRegistrationWorkflow.draftFromPayload(payload);}
        catch(RuntimeException malformed){return null;}
        if(draft.categoryId()<=0)return null;
        try(var read=session.catalog().registrationCountryReadScope()){
            var schema=session.catalog().registrationAttributes(draft.categoryId(),session.token());
            CatalogApprovedVariantValues values=null;
            if(shop!=null&&proof!=null&&snapshots!=null&&session.scope()!=null&&CatalogFieldCorrections.mayNeedApprovedVariant(progress,schema)){
                session.scope().assertCurrent(shop);
                var key=proof.key();
                var snapshot=snapshots.read(shop,key);
                if(key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON)snapshot=new com.vncode.app.integration.ozon.OzonShopBrand().apply(shop,snapshot);
                session.scope().assertCurrent(shop);
                values=CatalogApprovedVariantValues.read(shop,proof,snapshot,schema);
            }
            return CatalogFieldCorrections.suggest(progress,payload,gtin,verifiedGoodId,verifiedOwner,schema,values);
        }
    }

    /**
     * A caller must first read the same draft, GTIN and good_id from Catalog and verify
     * its owner. No correction is made for an unknown feed or a missing identity.
     * Mixed/unknown errors remain for seller review; in particular an image is never
     * removed without independent proof that the category makes it optional.
     */
    static Proposal packing(ZnackNationalCatalogService.FeedProgress progress, JsonObject payload,
                            String gtin, long verifiedGoodId, boolean verifiedOwner) {
        if (!verifiedOwner || verifiedGoodId <= 0 || progress == null
                || !CatalogFeedback.confirmedRejection(progress) || progress.errors().isEmpty()
                || !Objects.equals(progress.goodId(), verifiedGoodId) || payload == null
                || !payload.has("gtin") || !payload.get("gtin").isJsonPrimitive()
                || !RegisteredGtinProof.sameCode(gtin, payload.get("gtin").getAsString())) return null;
        if (payload.has("good_id")) {
            try { if (payload.get("good_id").getAsLong() != verifiedGoodId) return null; }
            catch (RuntimeException invalid) { return null; }
        }
        boolean current;
        try { current = payload.has("is_kit") && payload.get("is_kit").getAsBoolean(); }
        catch (RuntimeException invalid) { return null; }
        Boolean corrected = CatalogPackingPolicy.correction(progress, current);
        if (corrected == null) return null;
        for (var error : progress.errors()) {
            var one = new ZnackNationalCatalogService.FeedProgress("Rejected", verifiedGoodId, List.of(error));
            if (!Objects.equals(corrected, CatalogPackingPolicy.correction(one, current))) return null;
        }
        var result = payload.deepCopy();
        result.addProperty("good_id", verifiedGoodId);
        if (corrected) result.addProperty("is_kit", true); else result.remove("is_kit");
        String before = payload.has("is_kit") ? payload.get("is_kit").toString() : "(absent)";
        String after = corrected ? "true" : "(absent)";
        String reason = CatalogFeedback.rejection(progress);
        var changes=new java.util.ArrayList<Change>();
        if(!payload.has("good_id"))changes.add(new Change("good_id","(absent)",Long.toString(verifiedGoodId),"CATALOG_VERIFIED_IDENTITY"));
        changes.add(new Change("is_kit", before, after, reason));
        return new Proposal(payload, result, changes, reason, verifiedGoodId);
    }

    private CatalogAutomaticRepair() { }
}
