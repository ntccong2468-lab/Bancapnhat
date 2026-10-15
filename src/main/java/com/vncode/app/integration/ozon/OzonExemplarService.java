package com.vncode.app.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Durable prepare-order orchestration. Duplicate calls resume the same persisted job. */
public final class OzonExemplarService {
    private static final int MAX_STATUS_POLLS = 8;
    private static final Duration POLL_DELAY = Duration.ofMillis(250);
    private static final ConcurrentMap<String, PostingLock> POSTING_LOCKS = new ConcurrentHashMap<>();
    private static final int READ_AHEAD = 2;
    private static final ThreadPoolExecutor DETAIL_READERS = new ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "vncode-ozon-detail-read");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

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

    /** A short-lived shop-owned operation snapshot, never a process-wide catalog cache. */
    public PrintBatch openPrintBatch(Shop shop) {
        MarketplaceGuard.requireOzon(shop);
        Shop frozenShop = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(),
                shop.getClientId(), shop.getApiKey());
        return new PrintBatch(frozenShop,
                mappings.findResolvedBySku(shop.getId()), policies.findExemptSkus(shop.getId()),
                apiClients.apply(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey())));
    }

    /**
     * At most two fresh read-only details are outstanding for one selection, four for the process.
     * Reservations commit in selection order. A failed posting is isolated; no remote KIZ POST occurs.
     */
    public BatchPreparationResult stageForPrintBatch(
            Shop shop, List<String> postingNumbers, IntConsumer completed) throws IOException {
        return stageForPrintBatch(openPrintBatch(shop), postingNumbers, completed);
    }

    private BatchPreparationResult stageForPrintBatch(
            PrintBatch batch, List<String> postingNumbers, IntConsumer completed) throws IOException {
        if (postingNumbers == null || postingNumbers.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon print selection.");
        }
        List<String> selected = postingNumbers.stream()
                .map(value -> OzonApiClient.requireExternalId(value, "posting number")).distinct().toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("At least one Ozon posting is required.");
        List<OzonPreparationResult> prepared = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        int done = 0;
        try (var databaseSession = Database.openBatchSession()) {
            for (int start = 0; start < selected.size(); start += READ_AHEAD) {
                requireNotInterrupted();
                List<String> window = selected.subList(start, Math.min(start + READ_AHEAD, selected.size()));
                List<Future<OzonPostingDto>> reads = new ArrayList<>();
                try {
                    for (String number : window) {
                        reads.add(DETAIL_READERS.submit(() -> batch.readDetail(number)));
                    }
                    for (int index = 0; index < window.size(); index++) {
                        requireNotInterrupted();
                        try {
                            OzonPostingDto detail = await(reads.get(index));
                            prepared.add(executeForPrint(batch.shop, window.get(index), batch, detail));
                        } catch (LicenseDeniedException denied) {
                            throw denied;
                        } catch (ShopOperationCoordinator.ShopUnavailableException changed) {
                            throw changed;
                        } catch (IOException | IllegalStateException failure) {
                            requireNotInterrupted();
                            failures.put(window.get(index), safePreparationFailure(failure));
                        }
                        if (completed != null) completed.accept(++done);
                    }
                } catch (RejectedExecutionException full) {
                    throw new IOException("Ozon detail workers are busy; retry the durable selection.", full);
                } finally {
                    for (Future<OzonPostingDto> read : reads) if (!read.isDone()) read.cancel(true);
                }
            }
        }
        return new BatchPreparationResult(List.copyOf(prepared), java.util.Collections.unmodifiableMap(failures));
    }

    private static OzonPostingDto await(Future<OzonPostingDto> read) throws IOException {
        try {
            return read.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Ozon print preparation was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Ozon posting detail could not be read.");
        }
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Ozon print preparation was interrupted.");
    }

    private static String safePreparationFailure(Exception failure) {
        if (failure instanceof OzonRequirementGuard.MissingMappingException) return "mapping_missing";
        if (failure instanceof OzonRequirementGuard.UnsupportedRequirementException) return "requirement_unsupported";
        if (failure instanceof OzonApiException api) return api.kind();
        return failure instanceof IOException ? "detail_unavailable" : "preparation_failed";
    }

    public final class PrintBatch {
        private final Shop shop;
        private final Map<String, String> skuMappings;
        private final Set<String> exemptions;
        private final OzonApiClient api;

        private PrintBatch(Shop shop, Map<String, String> skuMappings, Set<String> exemptions, OzonApiClient api) {
            this.shop = shop;
            this.skuMappings = Map.copyOf(skuMappings);
            this.exemptions = Set.copyOf(exemptions);
            this.api = api;
        }

        public OzonPreparationResult stageForPrint(String postingNumber) throws IOException {
            return executeForPrint(shop, postingNumber, this, null);
        }

        public BatchPreparationResult stageForPrintBatch(List<String> postingNumbers) throws IOException {
            return OzonExemplarService.this.stageForPrintBatch(this, postingNumbers, null);
        }

        private OzonPostingDto readDetail(String number) throws IOException {
            ListingIdentity.require(shop);
            OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(number, true));
            if (!number.equals(posting.postingNumber())) {
                throw new IOException("Ozon returned a different posting from the confirmed selection.");
            }
            return posting;
        }
    }

    public record BatchPreparationResult(List<OzonPreparationResult> results, Map<String, String> failed) {
        public int completedCount() { return results.size() + failed.size(); }
    }

    private OzonPreparationResult executeForPrint(Shop shop, String postingNumber) throws IOException {
        return executeForPrint(shop, postingNumber, null, null);
    }

    private OzonPreparationResult executeForPrint(
            Shop shop, String postingNumber, PrintBatch batch, OzonPostingDto prefetched) throws IOException {
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
                requireNotInterrupted();
                OzonExemplarJob priorJob = jobs.find(shop.getId(), safePosting);
                if (priorJob == null || priorJob.stage() != OzonExemplarJobStage.ACCEPTED) {
                    LicenseRuntime.require(Permission.PRINT_NEW);
                }
                PrintBatch operation = batch == null ? openPrintBatch(shop) : batch;
                OzonPostingDto posting = prefetched == null ? operation.readDetail(safePosting) : prefetched;
                return ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                    ListingIdentity.require(shop);
                    postings.upsertDetail(shop.getId(), posting);
                    OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(
                            posting, operation.skuMappings, operation.exemptions, OzonCategoryStock.categorySkus(shop.getId(),posting.items().stream().map(OzonPostingItemDto::sku).toList()));
                    if (plan.exemplarCount() == 0) {
                        if (priorJob != null && priorJob.stage() != OzonExemplarJobStage.ACCEPTED
                                && jobs.bindingCounts(shop.getId(), List.of(priorJob.id())).getOrDefault(priorJob.id(), 0) > 0) {
                            throw new IOException("Refreshed Ozon requirements differ from an existing KIZ reservation; reconciliation is required.");
                        }
                        return new OzonPreparationResult(safePosting, "NOT_REQUIRED", 0, true, false, "");
                    }
                    OzonExemplarJobRepository.LocalPrintReservation reservation = jobs.stageLocalForPrint(
                            shop.getId(), plan, () -> {
                                LicenseRuntime.require(Permission.PRINT_NEW);
                                ListingIdentity.require(shop);
                            });
                    return result(reservation.job(), plan.exemplarCount(), reservation.ready());
                });
            } finally {
                POSTING_LOCKS.computeIfPresent(key, (ignored, current) -> {
                    if (current != lock) return current;
                    current.references--;
                    return current.references == 0 ? null : current;
                });
            }
        }
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

        OzonExemplarJob job = jobs.find(shop.getId(), postingNumber);
        if (job == null) {
            LicenseRuntime.require(Permission.PACKING);
            job = jobs.findOrCreate(shop.getId(), postingNumber);
        }
        if (job.stage() == OzonExemplarJobStage.ACCEPTED) return result(job, plan.exemplarCount(), true);
        if (job.stage() == OzonExemplarJobStage.REJECTED) return result(job, plan.exemplarCount(), false);

        // create-or-get is the only mutation whose contract is explicitly idempotent. After an
        // ambiguous response, use it to recover remote IDs, then reserve exactly once locally.
        if (job.stage() == OzonExemplarJobStage.RECONCILE_REQUIRED
                && jobs.bindings(job.id()).size() < plan.exemplarCount()) {
            LicenseRuntime.require(Permission.PACKING);
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
            LicenseRuntime.require(Permission.PACKING);
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
            LicenseRuntime.require(Permission.PACKING);
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
            LicenseRuntime.require(Permission.PACKING);
            job = jobs.transition(job, OzonExemplarJobStage.SET_PENDING, fingerprint, null, true);
            try {
                LicenseRuntime.require(Permission.PACKING);
                api.setExemplars(payload);
                job = jobs.transition(job, OzonExemplarJobStage.VERIFYING, fingerprint, null, false);
                jobs.logAction(shop.getId(), "exemplar_set", postingNumber, "submitted", null, fingerprint);
            } catch (LicenseDeniedException denied) {
                // This check precedes setExemplars: no remote mark was submitted by this claim.
                jobs.transition(job, OzonExemplarJobStage.VALIDATED, fingerprint, "LICENSE_REQUIRED", false);
                jobs.logAction(shop.getId(), "exemplar_set", postingNumber, "rejected", "LICENSE_REQUIRED", fingerprint);
                throw denied;
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
