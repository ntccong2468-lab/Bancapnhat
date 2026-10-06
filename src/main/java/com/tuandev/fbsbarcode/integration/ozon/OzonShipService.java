package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BiFunction;

public final class OzonShipService {
    private final OzonPostingRepository postings;
    private final OzonProductGtinMappingRepository mappings;
    private final OzonProductKizPolicyRepository policies;
    private final OzonExemplarJobRepository jobs;
    private final BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients;

    public OzonShipService() {
        this(
                new OzonPostingRepository(),
                new OzonProductGtinMappingRepository(),
                new OzonProductKizPolicyRepository(),
                new OzonExemplarJobRepository(),
                OzonApiClient::new);
    }

    OzonShipService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonExemplarJobRepository jobs,
            BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients) {
        this(postings, mappings, new OzonProductKizPolicyRepository(), jobs, apiClients);
    }

    OzonShipService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonProductKizPolicyRepository policies,
            OzonExemplarJobRepository jobs,
            BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients) {
        this.postings = Objects.requireNonNull(postings, "postings");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.apiClients = Objects.requireNonNull(apiClients, "apiClients");
    }

    public OzonShipResult ship(Shop shop, String postingNumber, boolean confirmed) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        if (!confirmed) {
            throw new IllegalArgumentException("Explicit confirmation is required before shipping an Ozon posting.");
        }
        OzonApiClient api = apiClients.apply(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        postings.upsertDetail(shop.getId(), posting);
        String previousAction = jobs.latestActionStatus(shop.getId(), "ship", posting.postingNumber());
        if (blocksShipRetry(previousAction)) {
            if (isPostShipStatus(posting.status())) {
                if (!"reconciled".equals(previousAction)) {
                    jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "reconciled", null, null);
                }
                return new OzonShipResult(posting.postingNumber(), posting.status(), true);
            }
            throw new OzonApiException("reconcile_required", 0, false, true, null);
        }
        if (!posting.canShip()) {
            throw new IllegalStateException("Ozon does not currently allow this posting to be shipped.");
        }

        JsonObject request = OzonPackageBuilder.singleCompletePackage(posting);
        String fingerprint = fingerprint(request);
        if (!jobs.tryBeginAction(shop.getId(), "ship", posting.postingNumber(), fingerprint)) {
            throw new OzonApiException("reconcile_required", 0, false, true, null);
        }
        try {
            api.ship(request);
        } catch (OzonApiException exception) {
            if (!exception.ambiguousMutation()) {
                jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "rejected",
                        exception.safeErrorCode(), fingerprint);
                throw exception;
            }
            OzonPostingDto readback = refresh(api, shop.getId(), posting.postingNumber());
            boolean stillShippable = readback.canShip();
            if (!stillShippable || !readback.status().equalsIgnoreCase(posting.status())) {
                jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "reconciled", null, fingerprint);
                return new OzonShipResult(posting.postingNumber(), readback.status(), true);
            }
            jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "ambiguous", exception.kind(), fingerprint);
            throw new OzonApiException("reconcile_required", exception.statusCode(), false, true, exception);
        }
        jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "success", null, fingerprint);
        OzonPostingDto refreshed = refresh(api, shop.getId(), posting.postingNumber());
        return new OzonShipResult(posting.postingNumber(), refreshed.status(), false);
    }

    private OzonPostingDto refresh(OzonApiClient api, int shopId, String postingNumber) throws IOException {
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        postings.upsertDetail(shopId, posting);
        return posting;
    }

    private static boolean isPostShipStatus(String status) {
        return status != null && switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "awaiting_deliver", "delivering", "delivered" -> true;
            default -> false;
        };
    }

    private static boolean blocksShipRetry(String status) {
        return "pending".equals(status)
                || "ambiguous".equals(status)
                || "success".equals(status)
                || "reconciled".equals(status);
    }

    private static String fingerprint(JsonObject value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
