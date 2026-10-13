package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.znack.BalanceScope;
import com.vncode.app.integration.znack.BalanceSnapshot;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.vncode.app.features.gs1.Gs1ReadinessService.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ReadinessServiceTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final String INN = "7707083893", FINGERPRINT = "a".repeat(40);
    private static final BalanceScope BALANCE_SCOPE = new BalanceScope(INN, "production", "1", "10");

    private Gs1ReadinessService service() {
        return new Gs1ReadinessService(new Gs1DashboardService(new Gs1Repository(directory.resolve("gs1.db"))));
    }
    private CryptoProCertificateInfo certificate() {
        return new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT, "O=Fixture, INNLE=" + INN, "CN=CA", INN,
                NOW.minusSeconds(86400), NOW.plusSeconds(86400), true, "Fixture", "");
    }
    private Gs1Membership member(LocalDate expiry, List<Gs1Membership.Prefix> prefixes) {
        return new Gs1Membership(INN, "Fixture", true, false, expiry, prefixes, NOW, Gs1Membership.CATALOG_SOURCE);
    }
    private Gs1Membership member(Integer allowance) {
        return member(LocalDate.of(2027, 10, 12), List.of(new Gs1Membership.Prefix("4601234", allowance, List.of())));
    }
    private BalanceSnapshot balance(BalanceScope scope, Instant checkedAt, String amount) {
        return new BalanceSnapshot(scope, new BigDecimal(amount), "RUB", checkedAt,
                URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance"));
    }
    private Input input(Gs1Membership member, BalanceSnapshot balance, Boolean login, Boolean agreement) {
        return new Input(new Scope(INN, "production", FINGERPRINT, BALANCE_SCOPE), certificate(), NOW,
                login, agreement, balance, member);
    }

    @Test void completeCurrentEvidenceIsReady() {
        var snapshot = service().evaluate(input(member(200), balance(BALANCE_SCOPE, NOW, "10"), true, true), NOW);
        assertTrue(snapshot.allReady()); assertFalse(snapshot.hasActionRequired());
        assertEquals(200, snapshot.availability().available().orElseThrow());
        assertEquals(new BigDecimal("10.00"), snapshot.balanceAmount());
    }
    @Test void unconfiguredAccountRequiresSignatureWithoutInventingOtherFailures() {
        var snapshot = service().evaluate(new Input(new Scope("", "unsupported", "", null), null, null,
                null, null, null, null), NOW);
        assertEquals(Action.CONFIGURE_SIGNATURE, snapshot.check(Kind.SIGNATURE).action());
        for (Kind kind : List.of(Kind.LOGIN, Kind.AGREEMENT, Kind.BALANCE, Kind.MEMBERSHIP, Kind.GTIN))
            assertEquals(State.UNKNOWN, snapshot.check(kind).state());
    }
    @Test void anotherPrefixStillHasCodesAndPreventsFalseOutOfCodes() {
        var membership = member(LocalDate.of(2027, 10, 12), List.of(
                new Gs1Membership.Prefix("4601234", 0, List.of()), new Gs1Membership.Prefix("4609999", 75, List.of())));
        var snapshot = service().evaluate(input(membership, null, null, null), NOW);
        assertEquals(State.READY, snapshot.check(Kind.GTIN).state());
        assertEquals(75, snapshot.availability().available().orElseThrow());
    }
    @Test void zeroWithUnknownPrefixDoesNotOfferPrefixPurchase() {
        var membership = member(LocalDate.of(2027, 10, 12), List.of(
                new Gs1Membership.Prefix("4601234", 0, List.of()), new Gs1Membership.Prefix("4609999", null, List.of())));
        var snapshot = service().evaluate(input(membership, null, null, null), NOW);
        assertEquals(State.UNKNOWN, snapshot.check(Kind.GTIN).state());
        assertEquals(Action.REFRESH_MEMBERSHIP, snapshot.check(Kind.GTIN).action());
    }
    @Test void positivePartialPrefixIsAvailableButDoesNotClaimCompleteTotal() {
        var membership = member(LocalDate.of(2027, 10, 12), List.of(
                new Gs1Membership.Prefix("4601234", 15, List.of()), new Gs1Membership.Prefix("4609999", null, List.of())));
        var snapshot = service().evaluate(input(membership, null, null, null), NOW);
        assertEquals(State.READY, snapshot.check(Kind.GTIN).state());
        assertFalse(snapshot.availability().complete());
        assertEquals("gs1.readiness.gtin.partial_available", snapshot.check(Kind.GTIN).messageKey());
    }
    @Test void everyKnownActivePrefixEmptyOffersRequestAndDoesNotInferFeeOwed() {
        var snapshot = service().evaluate(input(member(0), null, null, null), NOW);
        assertEquals(Action.REQUEST_PREFIX, snapshot.check(Kind.GTIN).action());
        assertEquals(State.READY, snapshot.check(Kind.MEMBERSHIP).state());
    }
    @Test void renewalOnlyWhenDueIncludingExactlySixtyDays() {
        var evaluator = service(); LocalDate date = LocalDate.of(2026, 10, 12);
        assertEquals(Action.NONE, evaluator.evaluate(input(member(date.plusDays(61), List.of()), null, null, null), NOW)
                .check(Kind.MEMBERSHIP).action());
        assertEquals(Action.RENEW_MEMBERSHIP, evaluator.evaluate(input(member(date.plusDays(60), List.of()), null, null, null), NOW)
                .check(Kind.MEMBERSHIP).action());
    }
    @Test void excludedEnterpriseRequestsRestorationRatherThanJoiningOrBuyingPrefix() {
        var excluded = new Gs1Membership(INN, "Fixture", true, true, LocalDate.of(2027, 1, 1),
                List.of(new Gs1Membership.Prefix("4601234", 0, List.of())), NOW, Gs1Membership.CATALOG_SOURCE);
        var snapshot = service().evaluate(input(excluded, null, null, null), NOW);
        assertEquals(Action.RESTORE_MEMBERSHIP, snapshot.check(Kind.MEMBERSHIP).action());
        assertEquals(Action.RESTORE_MEMBERSHIP, snapshot.check(Kind.GTIN).action());
        assertEquals(State.UNKNOWN, snapshot.check(Kind.GTIN).state());
    }
    @Test void staleMembershipDoesNotSuggestRenewalOrOutOfCodes() {
        var stale = new Gs1Membership(INN, "Fixture", true, false, LocalDate.of(2026, 10, 13),
                List.of(new Gs1Membership.Prefix("4601234", 0, List.of())), NOW.minusSeconds(86401), Gs1Membership.CATALOG_SOURCE);
        var snapshot = service().evaluate(input(stale, null, null, null), NOW);
        assertEquals(State.UNKNOWN, snapshot.check(Kind.MEMBERSHIP).state());
        assertEquals(State.UNKNOWN, snapshot.check(Kind.GTIN).state());
        assertTrue(snapshot.availability().available().isEmpty());
    }
    @Test void cachesFromOtherInnEnvironmentOrAccountRemainUnknown() {
        var foreignMember = new Gs1Membership("7811089030", "Fixture", true, false, LocalDate.of(2027, 10, 12),
                List.of(new Gs1Membership.Prefix("4601234", 0, List.of())), NOW, Gs1Membership.CATALOG_SOURCE);
        var foreignBalance = balance(new BalanceScope(INN, "production", "2", "20"), NOW, "0");
        var snapshot = service().evaluate(input(foreignMember, foreignBalance, null, null), NOW);
        assertEquals(State.UNKNOWN, snapshot.check(Kind.MEMBERSHIP).state());
        assertEquals(State.UNKNOWN, snapshot.check(Kind.BALANCE).state()); assertNull(snapshot.balanceAmount());
        var sandbox = new Input(new Scope(INN, "sandbox", FINGERPRINT, BALANCE_SCOPE), certificate(), NOW,
                true, null, balance(BALANCE_SCOPE, NOW, "0"), member(0));
        assertEquals(State.UNKNOWN, service().evaluate(sandbox, NOW).check(Kind.BALANCE).state());
        assertEquals(State.UNKNOWN, service().evaluate(sandbox, NOW).check(Kind.MEMBERSHIP).state());
    }
    @Test void staleAndFutureBalanceCannotProduceTopUpWarning() {
        for (Instant checked : List.of(NOW.minusSeconds(86401), NOW.plusSeconds(61)))
            assertEquals(State.UNKNOWN, service().evaluate(input(member(20), balance(BALANCE_SCOPE, checked, "0"), null, null), NOW)
                    .check(Kind.BALANCE).state());
        assertEquals(Action.TOP_UP_BALANCE, service().evaluate(input(member(20), balance(BALANCE_SCOPE, NOW, "0"), null, null), NOW)
                .check(Kind.BALANCE).action());
    }
    @Test void noAgreementEvidenceIsUnknownAndKnown3055BlockLinksToOfficialCabinet() {
        assertEquals(State.UNKNOWN, service().evaluate(input(member(20), null, true, null), NOW).check(Kind.AGREEMENT).state());
        var check = service().evaluate(input(member(20), null, true, false), NOW).check(Kind.AGREEMENT);
        assertEquals(State.ACTION_REQUIRED, check.state()); assertEquals(Action.OPEN_CZ_PORTAL, check.action());
    }
    @Test void signatureExpiryBoundaryAndMissingDatesCannotBecomeReady() {
        var expired = new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT, "CN=Fixture", "CN=CA", INN,
                NOW.minusSeconds(86400), NOW, true, "Fixture", "");
        var noDates = new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT, "CN=Fixture", "CN=CA", INN,
                null, null, true, "Fixture", "");
        var evaluator = service();
        for (var cert : List.of(expired, noDates)) {
            var snapshot = evaluator.evaluate(new Input(new Scope(INN, "production", FINGERPRINT, null), cert, NOW,
                    true, null, null, member(20)), NOW);
            assertNotEquals(State.READY, snapshot.check(Kind.SIGNATURE).state());
            assertEquals(State.UNKNOWN, snapshot.check(Kind.LOGIN).state());
        }
    }
    @Test void wrongCertificateNeverTurnsReportedSuccessfulLoginReady() {
        var snapshot = service().evaluate(new Input(new Scope(INN, "production", "b".repeat(40), null), certificate(), NOW,
                true, null, null, member(20)), NOW);
        assertEquals(State.ACTION_REQUIRED, snapshot.check(Kind.SIGNATURE).state());
        assertEquals(State.UNKNOWN, snapshot.check(Kind.LOGIN).state());
    }
    @Test void readinessRecordsDoNotExposeAccountIdentityInDiagnostics() {
        var value = input(member(20), balance(BALANCE_SCOPE, NOW, "10"), true, null);
        assertFalse(value.toString().contains(INN)); assertFalse(value.scope().toString().contains(INN));
    }
}
