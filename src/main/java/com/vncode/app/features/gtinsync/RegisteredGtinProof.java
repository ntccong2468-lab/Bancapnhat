package com.vncode.app.features.gtinsync;

import com.google.gson.JsonParser;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** A registry observation belongs to one tested enterprise, certificate, environment and shop.
 * The digest contains no recoverable identity or credential and is persisted in the existing
 * source field. An older, unscoped cache remains readable but cannot authorize seller writes. */
public final class RegisteredGtinProof {
    private RegisteredGtinProof() {}

    public record Scope(String source, String inn, String fingerprint, String environment) {
        @Override public String toString() { return "RegisteredGtinProof.Scope[identity=redacted]"; }
    }

    public static String source(Shop shop) { return current(shop).source(); }
    public static String source(Shop shop, ZnackModels.Settings settings) {
        return scope(shop, settings, Instant.now()).source();
    }
    public static Scope current(Shop shop) {
        ListingIdentity.require(shop);
        var settings = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
        return scope(shop, settings, Instant.now());
    }
    public static Scope scope(Shop shop, ZnackModels.Settings settings, Instant now) {
        if (settings == null || settings.signerTestedAt() == null || settings.signerTestedAt().isAfter(now.plusSeconds(300)))
            throw new IllegalStateException("unverified_catalog_owner");
        try {
            var certificate = JsonParser.parseString(settings.certificateMetadataJson()).getAsJsonObject();
            String inn = certificate.get("inn").getAsString();
            String fingerprint = certificate.get("thumbprint").getAsString().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            String selector = settings.signerCertificate().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            if (!inn.matches("[0-9]{10}|[0-9]{12}") || !fingerprint.matches("[A-F0-9]{40}")
                    || !certificate.get("hasPrivateKey").getAsBoolean()
                    || (!selector.equals(fingerprint) && !settings.signerCertificate().equals(certificate.get("selector").getAsString()))
                    || Instant.parse(certificate.get("validFrom").getAsString()).isAfter(now)
                    || !Instant.parse(certificate.get("validTo").getAsString()).isAfter(now)
                    || (settings.participantInn() != null && !settings.participantInn().isBlank() && !inn.equals(settings.participantInn().strip()))
                    || (settings.ownerInn() != null && !settings.ownerInn().isBlank() && !inn.equals(settings.ownerInn().strip())))
                throw new IllegalArgumentException();
            String environment = settings.resolvedTrueApiBaseUrl().replaceAll("/+$", "");
            String digest = ListingIdentity.hash(String.join("\n", ListingIdentity.fingerprint(shop), inn, fingerprint, environment));
            return new Scope("National Catalog@" + digest, inn, fingerprint, environment);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("unverified_catalog_owner");
        }
    }

    public static void requireCurrent(Shop shop, String gtin) {
        var now = Instant.now();
        var scope=current(shop);requireProduction(scope);
        var source = scope.source();
        boolean found = new GtinSyncRepository().catalog(shop.getId(), shop.getMarketplace()).stream()
                .anyMatch(row -> row.gtin().equals(gtin) && row.source().equals(source) && row.published() && row.tradeUnit()
                        && !row.syncedAt().isBefore(now.minus(Duration.ofHours(24))) && !row.syncedAt().isAfter(now.plusSeconds(300)));
        if (!RegisteredGtinValidator.isValid(gtin) || !found) throw new IllegalArgumentException("unverified_catalog_gtin");
    }
    /** Production seller endpoints cannot consume a sandbox or arbitrary registry observation. */
    public static void requireProduction(Scope scope) {
        try {
            var uri=java.net.URI.create(scope.environment());
            String path=uri.getPath()==null?"":uri.getPath().replaceAll("/+$","");
            if(!"https".equalsIgnoreCase(uri.getScheme())||!"markirovka.crpt.ru".equalsIgnoreCase(uri.getHost())
                    ||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||!(path.isBlank()||path.matches("/api/v(?:3|4)/(?:true-api|lk)")))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalArgumentException("unverified_catalog_environment");}
    }

    public static boolean sameCode(String left, String right) {
        if (left == null || right == null) return false;
        if (left.matches("[0-9]{8,14}") && right.matches("[0-9]{8,14}"))
            return ("0".repeat(14-left.length())+left).equals("0".repeat(14-right.length())+right);
        return left.equals(right);
    }

    public static boolean contains(List<String> codes, String gtin) {
        return codes.stream().anyMatch(code -> sameCode(code, gtin));
    }
}
