package com.vncode.app.features.gs1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.integration.gs1.Gs1Membership;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Actions from sourced membership/application assertions. Unknown fee status is never guessed. */
public final class Gs1ActionPolicy {
    public enum JoinAction {
        CHECK_STATUS, JOIN, APPLICATION_SUBMITTED, ALREADY_MEMBER,
        RESTORE_MEMBERSHIP, RESTORATION_REQUESTED, RECONCILE_DELIVERY
    }
    public record Decision(JoinAction joinAction, boolean joiningInvoice, boolean renewal,
                           boolean moreCodes, boolean restoration) {}
    private static final Set<Gs1Repository.State> UNCERTAIN =
            Set.of(Gs1Repository.State.SENDING, Gs1Repository.State.RECONCILE_REQUIRED);

    private Gs1ActionPolicy() {}

    /** Opening the request explains remaining codes; sending has a stricter live-quota boundary. */
    public static boolean canOpenPrefixRequest(Gs1Membership membership,Instant now,LocalDate date){
        return !prefixesForDisplay(membership,now,date).isEmpty();
    }
    /** Identifiers remain readable for a sourced member with an unknown membership expiry; this grants no send right. */
    public static List<Gs1Membership.Prefix> prefixesForDisplay(Gs1Membership membership,Instant now,LocalDate date){
        Objects.requireNonNull(now);Objects.requireNonNull(date);
        if(membership==null||membership.stale(now)||membership.checkedAt().isAfter(now.plusSeconds(60))||!Gs1Membership.CATALOG_SOURCE.equals(membership.source())
                ||!Boolean.TRUE.equals(membership.member())||membership.excluded())return List.of();
        var status=membership.status(date);
        if(status==Gs1Membership.Status.ACTIVE||status==Gs1Membership.Status.EXPIRING)return membership.visiblePrefixes(date);
        if(membership.expiry()==null)return membership.prefixes().stream().filter(p->!p.excluded()&&(p.expiry()==null||!p.expiry().isBefore(date))).toList();
        return List.of();
    }

    public static Decision evaluate(Gs1Membership membership, JsonObject form,
                                    List<Gs1Repository.Request> requests, GtinAvailability allowance,
                                    LocalDate date, Instant now) {
        return evaluate(membership, form, requests, allowance, date, now, false);
    }

    /** newApplication is the adapter's bounded empty-form assertion from an authenticated account snapshot. */
    public static Decision evaluate(Gs1Membership membership, JsonObject form,
                                    List<Gs1Repository.Request> requests, GtinAvailability allowance,
                                    LocalDate date, Instant now, boolean newApplication) {
        Objects.requireNonNull(requests);
        Objects.requireNonNull(date);
        Objects.requireNonNull(now);
        if (membership == null || membership.stale(now) || membership.checkedAt().isAfter(now.plusSeconds(60))
                || !Gs1Membership.CATALOG_SOURCE.equals(membership.source())) return none(JoinAction.CHECK_STATUS);
        var owned = requests.stream().filter(r -> r.inn().equals(membership.inn())).toList();
        var status = membership.status(date);

        // An old submitted joining application belongs to a different operation from restoration.
        if (status == Gs1Membership.Status.EXCLUDED) {
            var restoration = owned.stream().filter(r -> r.kind().equals(Gs1LettersService.Kind.RESTORE_MEMBERSHIP.name())).toList();
            if (restoration.stream().anyMatch(r -> UNCERTAIN.contains(r.state()))) return none(JoinAction.RECONCILE_DELIVERY);
            if (restoration.stream().anyMatch(r -> Set.of(Gs1Repository.State.SENT, Gs1Repository.State.SUBMITTED).contains(r.state())))
                return none(JoinAction.RESTORATION_REQUESTED);
            return new Decision(JoinAction.RESTORE_MEMBERSHIP, false, false, false, true);
        }
        if (status == Gs1Membership.Status.ACTIVE || status == Gs1Membership.Status.EXPIRING || status == Gs1Membership.Status.EXPIRED) {
            boolean renewal = status != Gs1Membership.Status.ACTIVE && !blocked(owned, Gs1LettersService.Kind.RENEWAL.name(), false);
            boolean zero = allowance != null && allowance.complete() && allowance.available().isPresent()
                    && allowance.available().getAsLong() == 0;
            boolean more = status != Gs1Membership.Status.EXPIRED && zero
                    && !blocked(owned, Gs1LettersService.Kind.MORE_CODES.name(), false);
            return new Decision(JoinAction.ALREADY_MEMBER, false, renewal, more, false);
        }

        if (!ownedForm(membership.inn(), form)) return none(JoinAction.CHECK_STATUS);
        if (form.has("isSent") && !form.get("isSent").isJsonNull() && booleanValue(form, "isSent") == null)
            return none(JoinAction.CHECK_STATUS);
        if (form.has("errorText") && !form.get("errorText").isJsonNull()
                && !(form.get("errorText").isJsonPrimitive() && form.get("errorText").getAsJsonPrimitive().isString()))
            return none(JoinAction.CHECK_STATUS);
        var joins = owned.stream().filter(r -> r.kind().equals("JOIN")).toList();
        if (joins.stream().anyMatch(r -> UNCERTAIN.contains(r.state()))) return none(JoinAction.RECONCILE_DELIVERY);
        // The official frontend treats nonempty errorText as rejection, even if isSent is true.
        boolean rejected = primitiveText(form, "errorText").strip().length() > 0;
        Boolean sent = booleanValue(form, "isSent");
        if (!rejected && Boolean.TRUE.equals(sent)) {
            // This is an invoice inquiry, not an assertion that a fee is unpaid or approval granted.
            boolean invoice = (status == Gs1Membership.Status.NON_MEMBER || membership.prefixes().isEmpty())
                    && !blocked(owned, Gs1LettersService.Kind.JOINING_INVOICE.name(), true);
            return new Decision(JoinAction.APPLICATION_SUBMITTED, invoice, false, false, false);
        }
        if (joins.stream().anyMatch(r -> r.state() == Gs1Repository.State.SUBMITTED)) return none(JoinAction.RECONCILE_DELIVERY);
        if (status == Gs1Membership.Status.UNKNOWN) return none(JoinAction.CHECK_STATUS);
        if (sent == null && !rejected && !(newApplication && membership.prefixes().isEmpty())) return none(JoinAction.CHECK_STATUS);
        return none(JoinAction.JOIN);
    }

    private static Decision none(JoinAction action) { return new Decision(action, false, false, false, false); }
    private static boolean blocked(List<Gs1Repository.Request> requests, String kind, boolean includeSent) {
        return requests.stream().anyMatch(r -> r.kind().equals(kind)
                && (UNCERTAIN.contains(r.state()) || r.state() == Gs1Repository.State.SUBMITTED
                    || includeSent && r.state() == Gs1Repository.State.SENT));
    }
    private static boolean ownedForm(String inn, JsonObject form) {
        return form != null && form.has("applicant") && form.get("applicant").isJsonObject()
                && inn.equals(primitiveText(form.getAsJsonObject("applicant"), "inn"));
    }
    private static String primitiveText(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }
    private static Boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }
}
