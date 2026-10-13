package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.znack.BalanceScope;
import com.vncode.app.integration.znack.BalanceSnapshot;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Pure account readiness view. Unknown evidence never becomes a payment or membership claim. */
public final class Gs1ReadinessService {
    public enum Kind { SIGNATURE, LOGIN, AGREEMENT, BALANCE, MEMBERSHIP, GTIN }
    public enum State { READY, ACTION_REQUIRED, UNKNOWN }
    public enum Action { NONE, CONFIGURE_SIGNATURE, TEST_SIGNATURE, LOGIN, OPEN_CZ_PORTAL,
        REFRESH_BALANCE, TOP_UP_BALANCE, REFRESH_MEMBERSHIP, JOIN_GS1, RENEW_MEMBERSHIP,
        RESTORE_MEMBERSHIP, REQUEST_PREFIX }

    /** Booleans in Input are current session evidence for this scope, cleared on account/cert change. */
    public record Scope(String inn, String environment, String fingerprint, BalanceScope selectedBalanceScope) {
        public Scope {
            inn = Objects.requireNonNullElse(inn, "");
            environment = Objects.requireNonNullElse(environment, "unsupported");
            fingerprint = normalizeFingerprint(fingerprint);
            if (!inn.isEmpty()) Gs1Membership.requireInn(inn);
            if (!fingerprint.isEmpty() && !fingerprint.matches("[A-Fa-f0-9]{40}"))
                throw new IllegalArgumentException("Invalid certificate fingerprint");
        }
        @Override public String toString() { return "Scope[environment=" + environment + "]"; }
    }
    public record Input(Scope scope, CryptoProCertificateInfo certificate, Instant signatureTestedAt,
                        Boolean authenticated, Boolean agreementSigned, BalanceSnapshot balance,
                        Gs1Membership membership) {
        public Input { Objects.requireNonNull(scope); }
        @Override public String toString() { return "Input[scoped readiness evidence]"; }
    }
    /** messageKey is a gs1.readiness.* localization key; it never contains an upstream response. */
    public record Check(Kind kind, State state, Action action, String messageKey) {}
    public record Snapshot(List<Check> checks, GtinAvailability availability, BigDecimal balanceAmount) {
        public Snapshot { checks = List.copyOf(checks); }
        public Check check(Kind kind) { return checks.stream().filter(c -> c.kind() == kind).findFirst().orElseThrow(); }
        public boolean hasActionRequired() { return checks.stream().anyMatch(c -> c.state() == State.ACTION_REQUIRED); }
        public boolean allReady() { return checks.stream().allMatch(c -> c.state() == State.READY); }
    }

    private static final ZoneId MEMBERSHIP_ZONE = ZoneId.of("Europe/Moscow");
    private static final Duration MAX_CACHE_AGE = Duration.ofHours(24);
    private final Gs1DashboardService dashboard;

    public Gs1ReadinessService(Gs1DashboardService dashboard) { this.dashboard = Objects.requireNonNull(dashboard); }

    public Snapshot evaluate(Input input, Instant now) {
        Objects.requireNonNull(input); Objects.requireNonNull(now);
        LocalDate date = now.atZone(MEMBERSHIP_ZONE).toLocalDate();
        Check signature = signature(input, now);
        Check login = login(input, signature);
        Check agreement = agreement(input);
        Check balance = balance(input, now);
        Check membership = membership(input, date, now);
        boolean trustedMembership = usableMembership(input, now);
        GtinAvailability availability = trustedMembership ? dashboard.availability(input.membership(), date)
                : new GtinAvailability(OptionalLong.empty(), false, 0, 0);
        Check gtin = gtin(membership, availability);
        BigDecimal amount = balance.state() != State.UNKNOWN ? input.balance().amount() : null;
        return new Snapshot(List.of(signature, login, agreement, balance, membership, gtin), availability, amount);
    }

    private Check signature(Input input, Instant now) {
        var certificate = input.certificate(); var scope = input.scope();
        if (certificate == null || scope.inn().isEmpty() || scope.fingerprint().isEmpty())
            return check(Kind.SIGNATURE, State.ACTION_REQUIRED, Action.CONFIGURE_SIGNATURE, "signature.configure");
        if (!scope.fingerprint().equalsIgnoreCase(normalizeFingerprint(certificate.thumbprint()))
                || !scope.inn().equals(certificate.inn()) || !certificate.hasPrivateKey())
            return check(Kind.SIGNATURE, State.ACTION_REQUIRED, Action.CONFIGURE_SIGNATURE, "signature.mismatch");
        if (certificate.validTo() != null && !certificate.validTo().isAfter(now))
            return check(Kind.SIGNATURE, State.ACTION_REQUIRED, Action.CONFIGURE_SIGNATURE, "signature.expired");
        if (certificate.validFrom() != null && certificate.validFrom().isAfter(now))
            return check(Kind.SIGNATURE, State.ACTION_REQUIRED, Action.CONFIGURE_SIGNATURE, "signature.not_yet_valid");
        if (certificate.validFrom() == null || certificate.validTo() == null || input.signatureTestedAt() == null
                || input.signatureTestedAt().isAfter(now.plusSeconds(60))
                || input.signatureTestedAt().isBefore(certificate.validFrom()))
            return check(Kind.SIGNATURE, State.UNKNOWN, Action.TEST_SIGNATURE, "signature.unverified");
        return check(Kind.SIGNATURE, State.READY, Action.NONE, "signature.ready");
    }

    private Check login(Input input, Check signature) {
        if (!"production".equals(input.scope().environment()))
            return check(Kind.LOGIN, State.UNKNOWN, Action.OPEN_CZ_PORTAL, "login.unsupported");
        if (Boolean.TRUE.equals(input.authenticated()) && signature.state() == State.READY)
            return check(Kind.LOGIN, State.READY, Action.NONE, "login.ready");
        return check(Kind.LOGIN, Boolean.FALSE.equals(input.authenticated()) ? State.ACTION_REQUIRED : State.UNKNOWN,
                Action.LOGIN, Boolean.FALSE.equals(input.authenticated()) ? "login.failed" : "login.unverified");
    }

    private Check agreement(Input input) {
        if (input.scope().inn().isEmpty() || !"production".equals(input.scope().environment()) || input.agreementSigned() == null)
            return check(Kind.AGREEMENT, State.UNKNOWN, Action.OPEN_CZ_PORTAL, "agreement.unverified");
        return Boolean.TRUE.equals(input.agreementSigned())
                ? check(Kind.AGREEMENT, State.READY, Action.NONE, "agreement.ready")
                : check(Kind.AGREEMENT, State.ACTION_REQUIRED, Action.OPEN_CZ_PORTAL, "agreement.required");
    }

    private Check balance(Input input, Instant now) {
        BalanceSnapshot value = input.balance(); BalanceScope selected = input.scope().selectedBalanceScope();
        if (value == null || selected == null || !"production".equals(input.scope().environment()) || !selected.equals(value.scope())
                || !input.scope().inn().equals(selected.inn())
                || !input.scope().environment().equals(selected.environment())
                || !"https://markirovka.crpt.ru/bff-elk/v1/organisation/balance".equals(value.source().toString())
                || !fresh(value.checkedAt(), now))
            return check(Kind.BALANCE, State.UNKNOWN, Action.REFRESH_BALANCE, "balance.unverified");
        return value.amount().signum() > 0 ? check(Kind.BALANCE, State.READY, Action.NONE, "balance.ready")
                : check(Kind.BALANCE, State.ACTION_REQUIRED, Action.TOP_UP_BALANCE, "balance.empty");
    }

    private Check membership(Input input, LocalDate date, Instant now) {
        if (!usableMembership(input, now))
            return check(Kind.MEMBERSHIP, State.UNKNOWN, Action.REFRESH_MEMBERSHIP, "membership.unverified");
        return switch (input.membership().status(date)) {
            case ACTIVE -> check(Kind.MEMBERSHIP, State.READY, Action.NONE, "membership.ready");
            case EXPIRING -> check(Kind.MEMBERSHIP, State.ACTION_REQUIRED, Action.RENEW_MEMBERSHIP, "membership.expiring");
            case EXPIRED -> check(Kind.MEMBERSHIP, State.ACTION_REQUIRED, Action.RENEW_MEMBERSHIP, "membership.expired");
            case EXCLUDED -> check(Kind.MEMBERSHIP, State.ACTION_REQUIRED, Action.RESTORE_MEMBERSHIP, "membership.excluded");
            case NON_MEMBER -> check(Kind.MEMBERSHIP, State.ACTION_REQUIRED, Action.JOIN_GS1, "membership.non_member");
            case UNKNOWN -> check(Kind.MEMBERSHIP, State.UNKNOWN, Action.REFRESH_MEMBERSHIP, "membership.unverified");
        };
    }

    private Check gtin(Check membership, GtinAvailability value) {
        if (membership.state() == State.UNKNOWN)
            return check(Kind.GTIN, State.UNKNOWN, Action.REFRESH_MEMBERSHIP, "gtin.unverified");
        if (membership.action() == Action.JOIN_GS1 || membership.action() == Action.RESTORE_MEMBERSHIP
                || membership.messageKey().endsWith("membership.expired"))
            return check(Kind.GTIN, State.UNKNOWN, membership.action(), "gtin.membership_required");
        if (value.available().isPresent() && value.available().getAsLong() > 0)
            return check(Kind.GTIN, State.READY, Action.NONE, value.complete() ? "gtin.ready" : "gtin.partial_available");
        if (value.complete() && value.available().isPresent())
            return check(Kind.GTIN, State.ACTION_REQUIRED, Action.REQUEST_PREFIX, "gtin.empty");
        return check(Kind.GTIN, State.UNKNOWN, Action.REFRESH_MEMBERSHIP, "gtin.unverified");
    }

    private boolean usableMembership(Input input, Instant now) {
        var member = input.membership();
        return member != null && "production".equals(input.scope().environment())
                && input.scope().inn().equals(member.inn()) && Gs1Membership.CATALOG_SOURCE.equals(member.source())
                && fresh(member.checkedAt(), now);
    }
    private static boolean fresh(Instant checkedAt, Instant now) {
        return checkedAt != null && !checkedAt.isAfter(now.plusSeconds(60)) && !checkedAt.plus(MAX_CACHE_AGE).isBefore(now);
    }
    private static Check check(Kind kind, State state, Action action, String message) {
        return new Check(kind, state, action, "gs1.readiness." + message);
    }
    private static String normalizeFingerprint(String value) {
        return Objects.requireNonNullElse(value, "").replaceAll("\\s+", "");
    }
}
