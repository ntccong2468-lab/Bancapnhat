package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Observed source state; it cannot manufacture a local feed, signature or success. */
public enum CatalogCardState {
    PUBLISHED("status.published","SUCCEEDED","SOURCE_PUBLISHED_CONFIRMED"),
    ERRORS("source.errors","NEEDS_ACTION","CATALOG_CARD_ERRORS"),
    MODERATION("source.moderation","WAITING_REMOTE","CATALOG_CARD_MODERATION"),
    NOT_SIGNED("source.not_signed","NEEDS_ACTION","CATALOG_CARD_NOT_SIGNED"),
    DRAFT("source.draft","NEEDS_ACTION","CATALOG_CARD_DRAFT"),
    UNPUBLISHED("source.unpublished","UNCHANGED","SOURCE_NOT_PUBLISHED");
    public final String viewStatus,reportState,reason;
    CatalogCardState(String viewStatus,String reportState,String reason){this.viewStatus=viewStatus;this.reportState=reportState;this.reason=reason;}
    public static CatalogCardState of(RegisteredGtin card){
        if(card.published())return PUBLISHED;
        var details=card.details().fields().getOrDefault("@catalog_detailed_status",java.util.List.of()).stream()
                .map(s->s.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if(details.stream().anyMatch(Set.of("errors","rejected","error")::contains))return ERRORS;
        if(details.contains("moderation"))return MODERATION;
        // Only the explicit remote status is sufficient. A draft or good_signed=false
        // alone does not mean that moderation has approved it for signature.
        if(details.contains("notsigned")||card.details().values("@catalog_status").stream().anyMatch(s->"notsigned".equalsIgnoreCase(s.strip())))return NOT_SIGNED;
        if(details.contains("draft")||card.details().values("@catalog_status").stream().anyMatch(s->"draft".equalsIgnoreCase(s.strip())))return DRAFT;
        return UNPUBLISHED;
    }
    /** Review eligibility only; an actual submission still requires a fresh exact-card read. */
    public static boolean reviewableDraft(RegisteredGtin card){
        return card!=null&&!card.published()&&card.tradeUnit()
                &&com.vncode.app.features.gtinsync.RegisteredGtinProof.identifiedObservation(card)
                &&"draft".equalsIgnoreCase(card.details().single("@catalog_status"))
                &&card.details().values("@catalog_signed").stream().allMatch("false"::equalsIgnoreCase)
                &&card.details().values("@catalog_detailed_status").stream().map(value->value.strip().toLowerCase(Locale.ROOT))
                .allMatch("draft"::equals);
    }
}
