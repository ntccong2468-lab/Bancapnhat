package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/** Durable prepare-order orchestration. Duplicate calls resume the same persisted job. */
public final class OzonExemplarService {
    private static final int MAX_STATUS_POLLS = 8;
    private static final Duration POLL_DELAY = Duration.ofMillis(250);
    private static final ConcurrentMap<String, PostingLock> POSTING_LOCKS = new ConcurrentHashMap<>();

    private final OzonPostingRepository postings;
    private final OzonProductGtinMappingRepository mappings;
    private final OzonProductKizPolicyRepository policies;
    private final OzonExemplarJobRepository jobs;
    private final BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients;

    public OzonExemplarService() {
        this(
                new OzonPostingRepository(),
                new OzonProductGtinMappingRepository(),
                new OzonProductKizPolicyRepository(),
                new OzonExemplarJobRepository(),
                OzonApiClient::new);
    }

    OzonExemplarService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonExemplarJobRepository jobs) {
        this(postings, mappings, new OzonProductKizPolicyRepository(), jobs, OzonApiClient::new);
    }

    OzonExemplarService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonExemplarJobRepository jobs,
            BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients) {
        this(postings, mappings, new OzonProductKizPolicyRepository(), jobs, apiClients);
    }

    OzonExemplarService(
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

    public OzonPreparationResult prepare(Shop shop, String postingNumber) throws IOException {
        return executeForPrint(shop, postingNumber);
    }

    /** Reserves KIZ locally for printing. No exemplar/KIZ payload is sent to Ozon. */
    public OzonPreparationResult stageForPrint(Shop shop, String postingNumber) throws IOException {
        return executeForPrint(shop, postingNumber);
    }

    private OzonPreparationResult executeForPrint(Shop shop, String postingNumber) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        String key = shop.getId() + ":" + safePosting;
        PostingLock lock = POSTING_LOCKS.compute(key, (ignored, existing) -> {
            PostingLock acquired = existing == null ? new PostingLock() : existing;
            acquired.references++;
            return acquired;
        });
        synchronized (lock.monitor) {
            try {
                return prepareForPrintLocked(shop, safePosting);
            } finally {
                POSTING_LOCKS.computeIfPresent(key, (ignored, current) -> {
                    if (current != lock) return current;
                    current.references--;
                    return current.references == 0 ? null : current;
                });
            }
        }
    }

    private OzonPreparationResult prepareForPrintLocked(Shop shop, String postingNumber) throws IOException {
        OzonApiClient api = apiClients.apply(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        postings.upsertDetail(shop.getId(), posting);
        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(
                posting, mappings.findResolvedBySku(shop.getId()), policies.findExemptSkus(shop.getId()));
        if (plan.exemplarCount() == 0) {
            return new OzonPreparationResult(postingNumber, "NOT_REQUIRED", 0,
                    true, false, "");
        }

        OzonExemplarJob job = jobs.findOrCreate(shop.getId(), postingNumber);
        if (job.stage() == OzonExemplarJobStage.ACCEPTED) {
            return result(job, plan.exemplarCount(), true);
        }
        if (job.stage() == OzonExemplarJobStage.REJECTED) {
            job = jobs.reopenRejectedForPrint(job);
        }

        List<OzonExemplarJobRepository.KizBinding> bindings = jobs.bindings(job.id());
        if (bindings.size() == plan.exemplarCount()) {
            job = jobs.markPrintReady(job);
            return result(job, plan.exemplarCount(), true);
        }
        if (!bindings.isEmpty()) {
            throw new IOException("The existing Ozon KIZ print reservation is incomplete.");
        }
        if (job.stage() != OzonExemplarJobStage.CREATED
                && job.stage() != OzonExemplarJobStage.RECONCILE_REQUIRED) {
            throw new IOException("The existing Ozon KIZ print reservation cannot be resumed safely.");
        }

        jobs.persistLocalPrintSlots(job, plan);
        try {
            jobs.reserveAndLink(job, plan);
        } catch (OzonExemplarJobRepository.InsufficientKizException exception) {
            job = jobs.transition(job, job.stage(), null, "kiz_unavailable", false);
            return result(job, plan.exemplarCount(), false);
        }
        job = requireJob(shop.getId(), postingNumber);
        job = jobs.markPrintReady(job);
        jobs.logAction(shop.getId(), "kiz_print_reserve", postingNumber, "success", null, null);
        return result(job, plan.exemplarCount(), true);
    }

    /** Legacy remote workflow retained only for migration/reference; production entry points no longer call it. */
    private OzonPreparationResult prepareLocked(
            Shop shop, String postingNumber, boolean stopAfterValidation) throws IOException {
        OzonApiClient api = apiClients.apply(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        postings.upsertDetail(shop.getId(), posting);
        Map<String, String> skuMappings = mappings.findResolvedBySku(shop.getId());
        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(
                posting, skuMappings, policies.findExemptSkus(shop.getId()));
        if (plan.exemplarCount() == 0) {
            return new OzonPreparationResult(postingNumber, "NOT_REQUIRED", 0,
                    posting.shipAvailable(), false, "");
        }

        OzonExemplarJob job = jobs.findOrCreate(shop.getId(), postingNumber);
        if (job.stage() == OzonExemplarJobStage.ACCEPTED) return result(job, plan.exemplarCount(), true);
        if (job.stage() == OzonExemplarJobStage.REJECTED) return result(job, plan.exemplarCount(), false);

        // create-or-get is the only mutation whose contract is explicitly idempotent. After an
        // ambiguous response, use it to recover remote IDs, then reserve exactly once locally.
        if (job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED
                && jobs.bindings(job.id()).size() < plan.exemplarCount()) {
            try {
                List<String> exemplarIds = remoteExemplarIds(
                        api.createOrGetExemplars(createRequest(posting)), plan);
                jobs.persistRemoteExemplars(job, plan, exemplarIds);
                jobs.reserveAndLink(job, plan);
                job = requireJob(shop.getId(), postingNumber);
            } catch (OzonApiException | RuntimeException exception) {
                if (exception instanceof OzonApiException apiException && !apiException.ambiguousMutation()) {
                    job = jobs.releaseRejected(job, true, apiException.kind());
                }
                return result(job, plan.exemplarCount(), false);
            }
        }

        if (job.stage() == OzonExemplarJobStage.CREATED) {
            try {
                List<String> exemplarIds = remoteExemplarIds(
                        api.createOrGetExemplars(createRequest(posting)), plan);
                jobs.persistRemoteExemplars(job, plan, exemplarIds);
                jobs.reserveAndLink(job, plan);
                job = requireJob(shop.getId(), postingNumber);
                jobs.logAction(shop.getId(), "exemplar_create", postingNumber, "success", null, null);
            } catch (OzonApiException exception) {
                if (exception.ambiguousMutation()) {
                    job = jobs.transition(
                            job, OzonExemplarJobStage.RECONCILE_REQUIRED, null, exception.kind(), true);
                    jobs.logAction(
                            shop.getId(), "exemplar_create", postingNumber, "ambiguous", exception.kind(), null);
                } else {
                    job = jobs.releaseRejected(job, true, exception.kind());
                    jobs.logAction(
                            shop.getId(), "exemplar_create", postingNumber, "rejected", exception.kind(), null);
                }
                return result(job, plan.exemplarCount(), false);
            } catch (OzonExemplarJobRepository.InsufficientKizException exception) {
                job = jobs.transition(job, OzonExemplarJobStage.CREATED, null, "kiz_unavailable", false);
                return result(job, plan.exemplarCount(), false);
            } catch (RuntimeException exception) {
                // A deterministic local/response failure before reservation is safe to reject.
                job = jobs.releaseRejected(job, true, "create_invalid");
                return result(job, plan.exemplarCount(), false);
            }
        }

        if (job.stage() == OzonExemplarJobStage.RESERVED) {
            List<OzonExemplarJobRepository.KizBinding> bindings = jobs.bindings(job.id());
            try {
                OzonExemplarRemoteStatus validation = OzonExemplarJson.validation(
                        api.validateExemplars(exemplarPayload(postingNumber, bindings, false)), bindings.size());
                if (validation.rejected()) {
                    job = jobs.releaseRejected(job, true, "validation_rejected");
                    jobs.logAction(shop.getId(), "exemplar_validate", postingNumber, "rejected", "validation_rejected", null);
                    return result(job, bindings.size(), false);
                }
                if (!validation.allMarksPassed()) {
                    job = jobs.transition(
                            job, OzonExemplarJobStage.RESERVED, null, "validation_pending", false);
                    return result(job, bindings.size(), false);
                }
                job = jobs.transition(job, OzonExemplarJobStage.VALIDATED, null, null, false);
                jobs.logAction(shop.getId(), "exemplar_validate", postingNumber, "success", null, null);
            } catch (OzonApiException exception) {
                if (exception.retryable()) {
                    job = jobs.transition(
                            job, OzonExemplarJobStage.RESERVED, null, exception.kind(), false);
                } else {
                    job = jobs.releaseRejected(job, true, exception.kind());
                }
                return result(job, bindings.size(), false);
            }
        }

        if (job.stage() == OzonExemplarJobStage.VALIDATED && stopAfterValidation) {
            return result(job, plan.exemplarCount(), false);
        }

        if (job.stage() == OzonExemplarJobStage.VALIDATED) {
            List<OzonExemplarJobRepository.KizBinding> bindings = jobs.bindings(job.id());
            JsonObject payload = exemplarPayload(postingNumber, bindings, true);
            String fingerprint = fingerprint(payload);
            job = jobs.transition(job, OzonExemplarJobStage.SET_PENDING, fingerprint, null, true);
            try {
                api.setExemplars(payload);
                job = jobs.transition(job, OzonExemplarJobStage.VERIFYING, fingerprint, null, false);
                jobs.logAction(shop.getId(), "exemplar_set", postingNumber, "submitted", null, fingerprint);
            } catch (OzonApiException exception) {
                job = jobs.transition(job, OzonExemplarJobStage.RECONCILE_REQUIRED,
                        fingerprint, exception.safeErrorCode(), false);
                jobs.logAction(shop.getId(), "exemplar_set", postingNumber, "ambiguous", exception.safeErrorCode(), fingerprint);
                return reconcile(api, job, bindings.size());
            }
        }

        if (job.stage() == OzonExemplarJobStage.SET_PENDING
                || job.stage() == OzonExemplarJobStage.VERIFYING
                || job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED) {
            return reconcile(api, job, plan.exemplarCount());
        }
        return result(job, plan.exemplarCount(), job.stage() == OzonExemplarJobStage.ACCEPTED);
    }

    private OzonPreparationResult reconcile(OzonApiClient api, OzonExemplarJob initial, int expected)
            throws IOException {
        OzonExemplarJob job = initial;
        for (int attempt = 0; attempt < MAX_STATUS_POLLS; attempt++) {
            OzonExemplarRemoteStatus status;
            try {
                status = OzonExemplarJson.status(api.exemplarStatus(statusRequest(job.postingNumber())), expected);
            } catch (OzonApiException exception) {
                if (job.stage() != OzonExemplarJobStage.RECONCILE_REQUIRED) {
                    job = jobs.transition(job, OzonExemplarJobStage.RECONCILE_REQUIRED,
                            job.requestFingerprint(), exception.kind(), false);
                }
                return result(job, expected, false);
            }
            if (status.accepted()) {
                if (job.stage() == OzonExemplarJobStage.SET_PENDING) {
                    job = jobs.transition(job, OzonExemplarJobStage.VERIFYING,
                            job.requestFingerprint(), null, false);
                }
                job = jobs.consumeAccepted(job);
                jobs.logAction(job.shopId(), "exemplar_status", job.postingNumber(), "accepted", null,
                        job.requestFingerprint());
                return result(job, expected, true);
            }
            if (status.rejected()) {
                if (!status.hasRemoteMarks()) {
                    job = jobs.releaseRejected(job, true, "remote_rejected_empty");
                } else {
                    job = jobs.transition(job, OzonExemplarJobStage.RECONCILE_REQUIRED,
                            job.requestFingerprint(), "remote_rejected_with_marks", false);
                }
                return result(job, expected, false);
            }
            if (!status.hasRemoteMarks()
                    && (job.stage() == OzonExemplarJobStage.SET_PENDING
                        || job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED)) {
                // An asynchronous set can still be applying while status temporarily has no
                // marks. Only a terminal rejected readback above is conclusive enough to release.
                if (attempt + 1 < MAX_STATUS_POLLS) sleep(POLL_DELAY);
                continue;
            }
            if (job.stage() == OzonExemplarJobStage.SET_PENDING
                    || job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED) {
                job = jobs.transition(job, OzonExemplarJobStage.VERIFYING,
                        job.requestFingerprint(), null, false);
            }
            if (attempt + 1 < MAX_STATUS_POLLS) sleep(POLL_DELAY);
        }
        job = jobs.transition(job, OzonExemplarJobStage.RECONCILE_REQUIRED,
                job.requestFingerprint(), "status_pending", false);
        return result(job, expected, false);
    }

    private OzonExemplarJob requireJob(int shopId, String postingNumber) {
        OzonExemplarJob job = jobs.find(shopId, postingNumber);
        if (job == null) throw new IllegalStateException("Ozon exemplar job disappeared.");
        return job;
    }

    static JsonObject createRequest(OzonPostingDto posting) {
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", posting.postingNumber());
        return request;
    }

    static List<String> remoteExemplarIds(
            JsonObject response, OzonRequirementGuard.PreparationPlan plan) {
        Map<String, List<String>> byProduct = OzonExemplarJson.exemplarIdsByProduct(response);
        if (!byProduct.isEmpty()) {
            Map<String, Integer> offsets = new java.util.HashMap<>();
            List<String> ordered = new java.util.ArrayList<>();
            for (OzonRequirementGuard.RequiredItem item : plan.items()) {
                List<String> productIds = byProduct.get(item.productId());
                int offset = offsets.getOrDefault(item.productId(), 0);
                if (productIds == null || offset + item.quantity() > productIds.size()) {
                    throw new IllegalStateException("Ozon returned incomplete exemplar IDs for a product.");
                }
                ordered.addAll(productIds.subList(offset, offset + item.quantity()));
                offsets.put(item.productId(), offset + item.quantity());
            }
            if (ordered.size() == plan.exemplarCount()) return List.copyOf(ordered);
        }
        List<String> flattened = OzonExemplarJson.exemplarIds(response);
        long distinctProducts = plan.items().stream()
                .map(OzonRequirementGuard.RequiredItem::productId).distinct().count();
        if (distinctProducts == 1 && flattened.size() == plan.exemplarCount()) return flattened;
        throw new IllegalStateException("Ozon exemplar response cannot be matched safely to posting products.");
    }

    static JsonObject exemplarPayload(
            String postingNumber, List<OzonExemplarJobRepository.KizBinding> bindings, boolean includeExemplarIds) {
        JsonArray products = new JsonArray();
        Map<String, List<OzonExemplarJobRepository.KizBinding>> grouped = bindings.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OzonExemplarJobRepository.KizBinding::productId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (var entry : grouped.entrySet()) {
            JsonArray exemplars = new JsonArray();
            for (OzonExemplarJobRepository.KizBinding binding : entry.getValue()) {
                JsonObject exemplar = new JsonObject();
                if (includeExemplarIds) {
                    exemplar.addProperty("exemplar_id", numericId(binding.exemplarId(), "exemplar id"));
                }
                JsonObject mark = new JsonObject();
                mark.addProperty("mark", KizService.scannerSafeCode(binding.rawCode()));
                mark.addProperty("mark_type", "mandatory_mark");
                JsonArray marks = new JsonArray();
                marks.add(mark);
                exemplar.add("marks", marks);
                exemplars.add(exemplar);
            }
            JsonObject product = new JsonObject();
            product.addProperty("product_id", numericId(entry.getKey(), "product id"));
            product.add("exemplars", exemplars);
            products.add(product);
        }
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", postingNumber);
        request.add("products", products);
        return request;
    }

    private static BigInteger numericId(String value, String label) {
        String safe = OzonApiClient.requireExternalId(value, label);
        if (!safe.matches("[0-9]{1,19}")) {
            throw new IllegalArgumentException("Ozon " + label + " must be numeric.");
        }
        return new BigInteger(safe);
    }

    private static JsonObject statusRequest(String postingNumber) {
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", postingNumber);
        return request;
    }

    private static String fingerprint(JsonObject payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static OzonPreparationResult result(OzonExemplarJob job, int count, boolean ready) {
        return new OzonPreparationResult(
                job.postingNumber(), job.stage().name(), count, ready,
                job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED,
                job.safeErrorCode() == null ? "" : job.safeErrorCode());
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Ozon exemplar status polling was interrupted.", exception);
        }
    }

    private static final class PostingLock {
        private final Object monitor = new Object();
        private int references;
    }
}
