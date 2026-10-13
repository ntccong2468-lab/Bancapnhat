package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.signature.CryptoProSignedCertificateReader;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.Locale;

/** Immutable ownership of a durable Catalog operation. Never displays account identifiers. */
public record ZnackRegistrationScope(String inn, String environment, String fingerprint) {
    public ZnackRegistrationScope {
        inn = inn == null ? "" : inn.strip();
        environment = environment == null ? "" : environment.strip();
        fingerprint = fingerprint == null ? "" : fingerprint.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!inn.matches("[0-9]{10}|[0-9]{12}") || !fingerprint.matches("[A-F0-9]{40}")
                || environment.isBlank()) throw new ScopeChangedException();
    }

    public static ZnackRegistrationScope fromSettings(ZnackModels.Settings settings) {
        try {
            var metadata = JsonParser.parseString(settings.certificateMetadataJson()).getAsJsonObject();
            String inn = metadata.get("inn").getAsString();
            String fingerprint = metadata.get("thumbprint").getAsString();
            Instant now=Instant.now();
            String normalizedFingerprint=fingerprint.replaceAll("\\s","").toUpperCase(Locale.ROOT);
            String selector=settings.signerCertificate()==null?"":settings.signerCertificate();
            String normalizedSelector=selector.replaceAll("\\s","").toUpperCase(Locale.ROOT);
            boolean selectorMatches=normalizedSelector.equals(normalizedFingerprint)
                    || (metadata.has("selector")&&selector.equals(metadata.get("selector").getAsString()));
            if (settings.signerTestedAt() == null || settings.signerTestedAt().isAfter(now.plusSeconds(300))
                    || !selectorMatches || !metadata.get("hasPrivateKey").getAsBoolean()
                    || Instant.parse(metadata.get("validFrom").getAsString()).isAfter(now)
                    || !Instant.parse(metadata.get("validTo").getAsString()).isAfter(now)
                    || (!blank(settings.participantInn()) && !inn.equals(settings.participantInn().strip()))
                    || (!blank(settings.ownerInn()) && !inn.equals(settings.ownerInn().strip()))) throw new ScopeChangedException();
            return new ZnackRegistrationScope(inn, settings.resolvedTrueApiBaseUrl(), fingerprint);
        } catch (ScopeChangedException error) { throw error; }
        catch (RuntimeException error) { throw new ScopeChangedException(); }
    }

    public void assertCurrent(Shop shop) {
        var settings = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
        if (!equals(fromSettings(settings))) throw new ScopeChangedException();
    }

    /** Auth challenges and every frozen signing document must be produced by the bound owner. */
    public ZnackSignatureProvider verifyingSigner(Shop shop, ZnackSignatureProvider delegate) {
        return (payload, context) -> {
            assertCurrent(shop);
            var result = delegate.sign(payload, context);
            var certificate = CryptoProSignedCertificateReader.read(result, fingerprint);
            Instant now = Instant.now();
            if (!inn.equals(certificate.inn()) || certificate.validFrom() == null || certificate.validTo() == null
                    || certificate.validFrom().isAfter(now) || !certificate.validTo().isAfter(now)) throw new ScopeChangedException();
            assertCurrent(shop);
            return result;
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    @Override public String toString() { return "ZnackRegistrationScope[redacted]"; }
    public static final class ScopeChangedException extends IllegalStateException {
        public ScopeChangedException() { super("Znack signing account changed or is not verified. Review the pending operation before continuing."); }
    }
}
