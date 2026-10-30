package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.features.kiz.KizSourceSelection;
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
        return ship(shop,postingNumber,confirmed,null);
    }
    public OzonShipResult ship(Shop shop,String postingNumber,boolean confirmed,KizSourceSelection sources)throws IOException {
        MarketplaceGuard.requireOzon(shop);
        if (!confirmed) {
            throw new IllegalArgumentException("Explicit confirmation is required before shipping an Ozon posting.");
        }
        String priorCheckpoint = jobs.latestActionStatus(shop.getId(), "ship", postingNumber);
        if (!blocksShipRetry(priorCheckpoint)) LicenseRuntime.require(Permission.PACKING);
        OzonApiClient api = apiClients.apply(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        OzonPostingDto posting = OzonJson.requirePostingDetail(api.getPosting(postingNumber, true),postingNumber);
        postings.upsertDetail(shop.getId(), posting);
        String previousAction = jobs.latestActionStatus(shop.getId(), "ship", posting.postingNumber());
        if (blocksShipRetry(previousAction)) {
            if (isPostShipStatus(posting)) {
                if (!"reconciled".equals(previousAction)) {
                    jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "reconciled", null, null);
                }
                return new OzonShipResult(posting.postingNumber(), posting.status(), true);
            }
            throw new OzonApiException("reconcile_required", 0, false, true, null);
        }
        if(OzonAcceptanceKizService.requiredBeforeAccept(shop,posting)) {
            new OzonAcceptanceKizService(jobs).ensure(shop,posting,api,sources);
            posting=refresh(api,shop.getId(),posting.postingNumber());
        }
        if (!posting.canShip()) {
            throw new IllegalStateException("Ozon does not currently allow this posting to be shipped.");
        }

        JsonObject request = OzonPackageBuilder.singleCompletePackage(posting);
        String fingerprint = fingerprint(request);
        LicenseRuntime.require(Permission.PACKING);
        if (!jobs.tryBeginAction(shop.getId(), "ship", posting.postingNumber(), fingerprint)) {
            throw new OzonApiException("reconcile_required", 0, false, true, null);
        }
        try {
            LicenseRuntime.require(Permission.PACKING);
            api.ship(request);
        } catch (LicenseDeniedException denied) {
            jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "rejected",
                    "LICENSE_REQUIRED", fingerprint);
            throw denied;
        } catch (OzonApiException exception) {
            if (!exception.ambiguousMutation()) {
                jobs.logAction(shop.getId(), "ship", posting.postingNumber(), "rejected",
                        exception.safeErrorCode(), fingerprint);
                throw exception;
            }
            OzonPostingDto readback = refresh(api, shop.getId(), posting.postingNumber());
            if (isPostShipStatus(readback)) {
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

    /** Receive/accept selection: native Ozon packages contain exactly one physical unit. */
    public OzonShipResult shipPerUnit(Shop shop,String postingNumber,boolean confirmed)throws IOException {
        return shipPerUnit(shop,postingNumber,confirmed,null);
    }
    public OzonShipResult shipPerUnit(Shop shop,String postingNumber,boolean confirmed,KizSourceSelection sources)throws IOException {
        return new OzonUnitShipService(postings,jobs,apiClients).ship(shop,postingNumber,confirmed,sources);
    }

    /** Persisted native IDs for a pending report, never guessed from the source number. */
    public java.util.List<String> knownUnitPostings(Shop shop,String postingNumber){
        MarketplaceGuard.requireOzon(shop);com.vncode.app.integration.listing.ListingIdentity.require(shop);
        var receipt=new OzonUnitShipRepository().find(shop,postingNumber);
        return receipt==null?java.util.List.of():receipt.children();
    }

    public int reconcilePendingReadOnly(Shop shop,OzonApiClient api,int maximumReads){
        return new OzonUnitShipService(postings,jobs,apiClients).reconcilePendingReadOnly(shop,api,maximumReads);
    }

    /** Local readback for the open batch report; never sends another acceptance request. */
    public java.util.Map<String,java.util.List<String>> completedUnitPostings(Shop shop,java.util.Collection<String> sources){
        MarketplaceGuard.requireOzon(shop);com.vncode.app.integration.listing.ListingIdentity.require(shop);
        return new OzonUnitShipRepository().completed(shop,sources);
    }

    private OzonPostingDto refresh(OzonApiClient api, int shopId, String postingNumber) throws IOException {
        OzonPostingDto posting = OzonJson.requirePostingDetail(api.getPosting(postingNumber, true),postingNumber);
        postings.upsertDetail(shopId, posting);
        return posting;
    }

    private static boolean isPostShipStatus(OzonPostingDto posting) {
        if ("ship_failed".equalsIgnoreCase(posting.substatus())) return false;
        String status = posting.status();
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
