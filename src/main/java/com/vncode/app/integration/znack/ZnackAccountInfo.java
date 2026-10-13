package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.features.gs1.Gs1DashboardService;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Evidence used by the Znack information tab; a saved signature test is not a live login. */
public final class ZnackAccountInfo {
    private static final ZoneId CERTIFICATE_ZONE = ZoneId.of("Europe/Moscow");
    private static final Duration CACHE_AGE = Duration.ofHours(24);
    private static final URI BALANCE_SOURCE = URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance");

    public record Identity(String inn, String fingerprint, String environment,
                           String ownerName, CryptoProCertificateInfo certificate, Instant testedAt) {
        public boolean networkUsable(Instant now) {
            return "production".equals(environment) && !certificate.validFrom().isAfter(now)
                    && certificate.validTo().isAfter(now);
        }
        public LocalDate validToDate() { return certificate.validTo().atZone(CERTIFICATE_ZONE).toLocalDate(); }
        public long remainingDays(Instant now) {
            return ChronoUnit.DAYS.between(now.atZone(CERTIFICATE_ZONE).toLocalDate(), validToDate());
        }
        @Override public String toString() { return "Identity[verified certificate evidence]"; }
    }

    private ZnackAccountInfo() {}

    /** Retain proven historical owner information after expiry, without permitting new authentication. */
    public static Optional<Identity> identity(ZnackModels.Settings settings, Instant now) {
        if (settings == null || settings.signerTestedAt() == null || settings.certificateMetadataJson() == null
                || settings.certificateMetadataJson().length() > 65536) return Optional.empty();
        try {
            JsonObject metadata = JsonParser.parseString(settings.certificateMetadataJson()).getAsJsonObject();
            String fingerprint = normalized(settings.signerCertificate());
            String inn = value(settings.participantInn()).trim();
            if (!fingerprint.matches("[A-Fa-f0-9]{40}") || !fingerprint.equalsIgnoreCase(normalized(string(metadata, "thumbprint")))
                    || !inn.matches("[0-9]{10}|[0-9]{12}") || !inn.equals(string(metadata, "inn"))
                    || !metadata.has("hasPrivateKey") || !metadata.get("hasPrivateKey").getAsBoolean()) return Optional.empty();
            Instant validFrom = Instant.parse(string(metadata, "validFrom"));
            Instant validTo = Instant.parse(string(metadata, "validTo"));
            Instant testedAt = settings.signerTestedAt();
            if (!validTo.isAfter(validFrom) || testedAt.isBefore(validFrom) || !testedAt.isBefore(validTo)
                    || testedAt.isAfter(now.plusSeconds(60))) return Optional.empty();
            var certificate = new CryptoProCertificateInfo(settings.signerCertificate(), fingerprint,
                    string(metadata, "subject"), string(metadata, "issuer"), inn, validFrom, validTo, true,
                    string(metadata, "provider"), "");
            return Optional.of(new Identity(inn, fingerprint, Gs1DashboardService.environment(settings.resolvedTrueApiBaseUrl()),
                    certificate.ownerName(), certificate, testedAt));
        } catch (RuntimeException malformed) { return Optional.empty(); }
    }

    public static boolean currentLogin(Identity identity, Instant authenticatedAt, Instant now) {
        return identity != null && identity.networkUsable(now) && authenticatedAt != null
                && !authenticatedAt.isAfter(now.plusSeconds(60)) && authenticatedAt.plus(Duration.ofMinutes(20)).isAfter(now);
    }

    /** Balance evidence belongs to one enterprise, environment, product group and contract, never an aggregate. */
    public static List<BalanceSnapshot> scopedBalances(Identity identity, List<BalanceSnapshot> values) {
        if (identity == null || values == null) return List.of();
        return values.stream().filter(Objects::nonNull)
                .filter(value -> value.scope().inn().equals(identity.inn())
                        && value.scope().environment().equals(identity.environment())
                        && "production".equals(identity.environment()) && BALANCE_SOURCE.equals(value.source()))
                .toList();
    }

    public static boolean freshBalance(BalanceSnapshot value, Instant now) {
        return value != null && !value.checkedAt().isAfter(now.plusSeconds(60))
                && !value.checkedAt().plus(CACHE_AGE).isBefore(now);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
    private static String value(String value) { return Objects.requireNonNullElse(value, ""); }
    private static String normalized(String value) { return value(value).replaceAll("\\s+", ""); }
}
