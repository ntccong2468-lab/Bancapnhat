package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZnackAccountInfoTest {
    private static final Instant NOW = Instant.parse("2026-10-13T05:00:00Z");
    private static final String FINGERPRINT = "A".repeat(40);

    @Test void ownerRequiresSavedCmsProofForTheSameCertificateAndEnterprise() {
        var settings = settings("7707083893", "", NOW.minusSeconds(3600), NOW.plusSeconds(86400), FINGERPRINT);
        var identity = ZnackAccountInfo.identity(settings, NOW).orElseThrow();
        assertEquals("ООО Тестовый магазин", identity.ownerName());
        assertEquals("7707083893", identity.inn());
        assertEquals("production", identity.environment());
        assertTrue(identity.networkUsable(NOW));
        assertFalse(identity.toString().contains(identity.inn()));
        assertFalse(identity.toString().contains(identity.ownerName()));
        for (var invalid : List.of(
                settings("7811089030", "", NOW.minusSeconds(3600), NOW.plusSeconds(86400), FINGERPRINT),
                settings("7707083893", "", NOW.minusSeconds(3600), NOW.plusSeconds(86400), "B".repeat(40)),
                settings("7707083893", "", NOW.plusSeconds(61), NOW.plusSeconds(86400), FINGERPRINT)))
            assertTrue(ZnackAccountInfo.identity(invalid, NOW).isEmpty());
    }

    @Test void savedSignatureTestDoesNotBecomeCurrentLoginAndExpiredProofRemainsHistorical() {
        var valid = ZnackAccountInfo.identity(settings("7707083893", "", NOW.minusSeconds(3600),
                NOW.plusSeconds(86400), FINGERPRINT), NOW).orElseThrow();
        assertFalse(ZnackAccountInfo.currentLogin(valid, null, NOW));
        assertTrue(ZnackAccountInfo.currentLogin(valid, NOW.minusSeconds(1199), NOW));
        assertFalse(ZnackAccountInfo.currentLogin(valid, NOW.minusSeconds(1200), NOW));
        assertFalse(ZnackAccountInfo.currentLogin(valid, NOW.plusSeconds(61), NOW));
        var expired = ZnackAccountInfo.identity(settings("7707083893", "", NOW.minusSeconds(3600),
                NOW, FINGERPRINT), NOW).orElseThrow();
        assertEquals("ООО Тестовый магазин", expired.ownerName());
        assertFalse(expired.networkUsable(NOW));
        assertFalse(ZnackAccountInfo.currentLogin(expired, NOW, NOW));
    }

    @Test void unsupportedEnvironmentAndIncompleteMetadataNeverAllowRemoteReads() {
        var settings = settings("7707083893", "https://foreign.invalid/api", NOW.minusSeconds(3600),
                NOW.plusSeconds(86400), FINGERPRINT);
        var identity = ZnackAccountInfo.identity(settings, NOW).orElseThrow();
        assertFalse(identity.networkUsable(NOW));
        var empty = new ZnackModels.Settings("", "", "", "", "7707083893", "", "", "", FINGERPRINT,
                "[]", "", "", "", false, "", "[]", "{}", NOW.minusSeconds(3600));
        assertTrue(ZnackAccountInfo.identity(empty, NOW).isEmpty());
        assertTrue(ZnackAccountInfo.identity(ZnackModels.Settings.empty(), NOW).isEmpty());
    }

    @Test void balancesKeepContractBoundariesAndRejectAnotherOwnerOrUnverifiedSource() {
        var identity = ZnackAccountInfo.identity(settings("7707083893", "", NOW.minusSeconds(3600),
                NOW.plusSeconds(86400), FINGERPRINT), NOW).orElseThrow();
        var a = balance("7707083893", "production", "1", "10", NOW, URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance"));
        var b = balance("7707083893", "production", "2", "20", NOW, a.source());
        var foreign = balance("7811089030", "production", "1", "10", NOW, a.source());
        var sandbox = balance("7707083893", "sandbox", "1", "10", NOW, a.source());
        var untrusted = balance("7707083893", "production", "1", "10", NOW, URI.create("https://foreign.invalid/balance"));
        assertEquals(List.of(a, b), ZnackAccountInfo.scopedBalances(identity, List.of(a, foreign, b, sandbox, untrusted)));
        assertTrue(ZnackAccountInfo.scopedBalances(null, List.of(a)).isEmpty());
        assertFalse(ZnackAccountInfo.freshBalance(null, NOW));
        assertTrue(ZnackAccountInfo.freshBalance(a, NOW));
        assertFalse(ZnackAccountInfo.freshBalance(balance("7707083893", "production", "1", "10",
                NOW.minusSeconds(86401), a.source()), NOW));
        assertFalse(ZnackAccountInfo.freshBalance(balance("7707083893", "production", "1", "10",
                NOW.plusSeconds(61), a.source()), NOW));
    }

    private ZnackModels.Settings settings(String inn, String base, Instant testedAt, Instant validTo, String fingerprint) {
        var json = new JsonObject();
        json.addProperty("inn", "7707083893");
        json.addProperty("thumbprint", FINGERPRINT);
        json.addProperty("subject", "CN=Тестовый представитель,O=ООО Тестовый магазин,INNLE=7707083893");
        json.addProperty("issuer", "CN=Fixture CA");
        json.addProperty("validFrom", NOW.minusSeconds(86400).toString());
        json.addProperty("validTo", validTo.toString());
        json.addProperty("hasPrivateKey", true);
        return new ZnackModels.Settings(base, "", "", "", inn, "", "", "", fingerprint,
                "[]", "", "", "", false, "", "[]", json.toString(), testedAt);
    }
    private BalanceSnapshot balance(String inn, String environment, String group, String contract, Instant at, URI source) {
        return new BalanceSnapshot(new BalanceScope(inn, environment, group, contract), new BigDecimal("123.45"), "RUB", at, source);
    }
}
