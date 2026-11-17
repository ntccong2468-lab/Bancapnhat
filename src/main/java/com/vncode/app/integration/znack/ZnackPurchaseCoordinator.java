package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.ZnackModels.KizOrder;
import com.vncode.app.integration.znack.ZnackModels.OrderStatus;
import com.vncode.app.integration.znack.ZnackModels.Product;
import com.vncode.app.integration.znack.ZnackModels.PurchaseStage;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZnackPurchaseCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackPurchaseCoordinator.class);
    private static final Duration ORDER_RECONCILIATION_GRACE = Duration.ofMinutes(5);
    private static final Pattern LEGACY_DOCUMENT_ID = Pattern.compile(
            "(?i)Not a JSON Object:\\s*[\"']?([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})[\"']?");
    private static final Object CREATE_LOCK = new Object();
    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "znack-purchase-pipeline");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.concurrent.ConcurrentMap<String,Instant> NEXT_CHECKS=new java.util.concurrent.ConcurrentHashMap<>();
    public static Instant nextCheckAt(int shopId,long pipelineId){return NEXT_CHECKS.get(shopId+":"+pipelineId);}
    private static final Set<String> SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong POLL_GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Set<java.util.concurrent.ScheduledFuture<?>> PENDING_POLLS = ConcurrentHashMap.newKeySet();

    private final ZnackRepository repository;
    private Settings serviceSettings;
    private long serviceGeneration = -1;
    private final ThreadLocal<ZnackPurchaseMethod> admissionMethod = new ThreadLocal<>();
    private final ZnackKizOrderService orders;
    private final ZnackKizCodeService codes;
    private final ZnackIntroductionService introduction;
    private final ZnackIntroductionReadinessService readiness;

    public ZnackPurchaseCoordinator(ZnackRepository repository, ZnackKizOrderService orders,
                                    ZnackKizCodeService codes, ZnackIntroductionService introduction) {
        this(repository, orders, codes, introduction, null);
    }

    public ZnackPurchaseCoordinator(ZnackRepository repository, ZnackKizOrderService orders,
                                    ZnackKizCodeService codes, ZnackIntroductionService introduction,
                                    ZnackIntroductionReadinessService readiness) {
        this.repository = repository;
        this.orders = orders;
        this.codes = codes;
        this.introduction = introduction;
        this.readiness = readiness;
    }

    public static ZnackPurchaseCoordinator create(ZnackRepository repository) {
        ZnackSigningSession.registerRepository(repository);
        Settings settings = repository.getSettings();
        return create(repository, settings);
    }

    private static ZnackPurchaseCoordinator create(ZnackRepository repository, Settings settings) {
        ZnackSigningSession.registerRepository(repository);
        ZnackSignatureProvider cryptoProSigner = settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                ? ZnackSignatureProvider.unconfigured()
                : new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        long generation = ZnackSigningSession.signerGenerationSnapshot(repository.shop().shopId());
        ZnackSignatureProvider signer = ZnackSigningSession.guard(repository.shop().shopId(), settings.signerCertificate(), cryptoProSigner);
        ZnackApiClient api = new ZnackApiClient();
        ZnackAuthService auth = new ZnackAuthService(api, signer);
        var coordinator=new ZnackPurchaseCoordinator(repository, new ZnackKizOrderService(api, auth, signer, repository),
                new ZnackKizCodeService(api, auth, repository),
                new ZnackIntroductionService(api, auth, signer, repository),
                new ZnackIntroductionReadinessService(api, auth, repository));
        coordinator.serviceSettings=settings;
        coordinator.serviceGeneration=generation;
        return coordinator;
    }

    public static void resumeAllPersisted() {
        for (Shop shop : new ShopRepository().findAll()) {
            try {
                ZnackRepository repository = new ZnackRepository(
                        new ZnackModels.ShopContext(shop.getId(), shop.getName()));
                ZnackPurchaseCoordinator coordinator = create(repository);
                Settings settings = repository.getSettings();
                coordinator.resume(settings);
                coordinator.resumeEligibleIntroductions(settings);
            } catch (RuntimeException e) {
                LOGGER.error("Could not resume Znack purchase pipelines for shop {}", shop.getId(), e);
            }
        }
    }

    /** True only while a persisted purchase pipeline is inside a local/remote mutation step. */
    public static boolean hasRunningMutation() {
        return !RUNNING.isEmpty();
    }

    public long start(Settings settings, String gtin, int quantity) throws Exception {
        return start(settings, gtin, quantity, null);
    }

    /** Synchronous admission uses the same durable batch path as every asynchronous producer. */
    public long start(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        String key = requestKey == null || requestKey.isBlank() ? UUID.randomUUID().toString() : requestKey;
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(repository.shop().shopId(), () -> {
            long id = admitApproved(settings, List.of(new ZnackPurchaseBatchStore.Approved(gtin, quantity, key)), false).getFirst();
            advance(settings, id);
            schedule(id);
            return id;
        });
    }

    public long enqueue(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        return enqueueBatch(settings, List.of(new ZnackPurchaseBatchStore.Approved(gtin, quantity, requestKey))).getFirst();
    }

    public long enqueueForPrint(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        return enqueue(settings, gtin, quantity, requestKey);
    }

    public long enqueueForCirculation(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        return enqueue(settings, gtin, quantity, requestKey);
    }

    /** Validate every approved amount before persisting or dispatching the first purchase. */
    public List<Long> enqueueBatch(Settings settings, List<ZnackPurchaseBatchStore.Approved> requests) throws Exception {
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(repository.shop().shopId(),
                () -> admitApproved(settings, requests, true));
    }

    List<Long> admitApproved(Settings original, List<ZnackPurchaseBatchStore.Approved> requests,
                                     boolean dispatch) throws Exception {
        if (requests == null || requests.isEmpty()) return List.of();
        LicenseRuntime.require(Permission.KIZ);
        ZnackSigningSession.registerRepository(repository);
        Settings settings = original.withAutoIntroduction(true);
        List<ZnackPurchaseBatchStore.Approved> approved = List.copyOf(requests);
        ZnackPurchaseMethod approvedMethod = new ZnackPurchasePreferences(repository.shop().shopId()).method();
        ZnackPurchaseMethod previousMethod = admissionMethod.get();
        admissionMethod.set(approvedMethod);
        try {
        var requestKeys = new java.util.HashSet<String>();
        for (var request : approved) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Purchase cancelled before enqueue.");
            requireRequestKey(request.requestKey());
            if (!requestKeys.add(request.requestKey())) throw new IllegalArgumentException("Duplicate purchase request key.");
            var existing = replay(request.requestKey(), request.gtin(), request.quantity());
            if (existing == null) validatePrerequisites(settings, request.gtin(), request.quantity());
            else requireUnsentReplay(existing);
        }
        List<Long> ids;
        synchronized (CREATE_LOCK) {
            LicenseRuntime.require(Permission.KIZ);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Purchase cancelled before enqueue.");
            ids = batches().enqueueApproved(settings, approvedMethod, approved);
        }
        for (long id : ids) {
            ZnackSigningSession.authorizePipeline(repository.shop().shopId(), id, batches().settingsForPipeline(id));
        }
        var dispatched = new java.util.HashSet<Long>();
        for (long id : ids) {
            var batch = batches().prepare(id);
            long leader = batch.map(value -> value.lines().getFirst().pipelineId()).orElse(id);
            if (dispatch && dispatched.add(leader)) {
                var pipeline = repository.findPipeline(leader).orElseThrow();
                if (!ZnackPipelineQueueExecutor.submit(repository.shop().shopId(), pipeline.gtin(),
                        workStage(pipeline.stage()), () -> advanceEnqueued(leader))) {
                    schedule(leader);
                }
            }
        }
        return List.copyOf(ids);
        } finally {
            if (previousMethod == null) admissionMethod.remove(); else admissionMethod.set(previousMethod);
        }
    }

    private ZnackPurchaseBatchStore batches() { return new ZnackPurchaseBatchStore(repository); }

    static void scheduleExisting(ZnackRepository repository, long pipelineId) {
        create(repository, new ZnackPurchaseBatchStore(repository).settingsForPipeline(pipelineId)).schedule(pipelineId);
    }

    private boolean hasCurrentRuntime(Settings original) {
        return serviceSettings != null
                && serviceSettings.withAutoIntroduction(original.autoIntroduction()).equals(original)
                && serviceGeneration == ZnackSigningSession.signerGenerationSnapshot(repository.shop().shopId());
    }

    private ZnackPurchaseCoordinator currentRuntime(Settings original) {
        // Injected test/services retain their explicit graph. Production graphs retain the
        // SUZ authentication cache until the saved authority or signer generation changes.
        return serviceSettings == null || hasCurrentRuntime(original) ? this : create(repository, original);
    }

    private void advanceEnqueued(long pipelineId) {
        ZnackPurchaseCoordinator latestCoordinator = this;
        try {
            Settings latest = repository.getSettings();
            latestCoordinator = currentRuntime(latest);
            latestCoordinator.advance(latest, pipelineId);
        } catch (Exception error) {
            ZnackPurchasePipelineState current = repository.findPipeline(pipelineId).orElse(null);
            if(error instanceof LicenseDeniedException)return;
            if (current != null && current.stage() == PurchaseStage.VALIDATING) {
                repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, error.getMessage());
                repository.log("PURCHASE_PIPELINE", current.gtin(), "ERROR", error.getMessage(), httpStatus(error));
            }
        } finally {
            latestCoordinator.schedule(pipelineId);
        }
    }

    private ZnackPurchasePipelineState replay(String requestKey, String gtin, int quantity) {
        if (requestKey == null || requestKey.isBlank()) return null;
        requireRequestKey(requestKey);
        ZnackPurchasePipelineState existing = repository.findPipelineByRequestKey(requestKey).orElse(null);
        if (existing == null) return null;
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        if (!existing.gtin().equals(normalized) || existing.quantity() != quantity) {
            throw new IllegalArgumentException("Purchase request does not match its persisted pipeline.");
        }
        return existing;
    }

    private void requireRequestKey(String requestKey) {
        try {
            if (requestKey == null || !UUID.fromString(requestKey).toString().equals(requestKey)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Purchase request key is invalid.");
        }
    }

    /**
     * Re-runs the introduction of an INTRODUCTION_FAILED pipeline (e.g. after the user fixed the
     * signature or updated the goods documents). The already-purchased codes are reused; a fresh
     * introduction document is submitted because the previous one was definitively rejected.
     */
    public void retryIntroduction(Settings settings, String gtin) throws Exception {
        ZnackPurchasePipelineState pipeline = repository.findLatestIntroductionFailedPipeline(gtin)
                .orElseThrow(() -> new IllegalStateException("No failed introduction to retry for GTIN " + gtin));
        retryIntroduction(settings, pipeline.id());
    }

    public void retryIntroduction(Settings settings, long pipelineId) throws Exception {
        ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId)
                .filter(candidate -> candidate.stage() == PurchaseStage.INTRODUCTION_FAILED)
                .orElseThrow(() -> new IllegalStateException("No failed introduction to retry for pipeline " + pipelineId));
        if (pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()) {
            throw new IllegalStateException("The failed introduction has no downloaded codes to retry.");
        }
        LicenseRuntime.require(Permission.KIZ);
        ZnackSigningSession.authorizePipeline(repository.shop().shopId(), pipeline.id(), batches().settingsForPipeline(pipeline.id()));
        synchronized (CREATE_LOCK) {
            LicenseRuntime.require(Permission.KIZ);
            repository.updatePipeline(pipeline.id(), pipeline.orderId(),
                    PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        }
        repository.log("INTRODUCTION_RETRY", pipeline.gtin(), "INFO", "RETRY_REQUESTED", null);
        try {
            advance(settings, pipeline.id());
        } finally {
            schedule(pipeline.id());
        }
    }

    /**
     * Requeues the same failed purchase after the user has replenished their Znack balance. The
     * persisted request key and pipeline id are retained, so retrying cannot create a duplicate
     * local purchase request. Only the deterministic insufficient-funds failure is eligible.
     */
    public void retryInsufficientFunds(Settings settings, long pipelineId) throws Exception {
        var batch = batches().findByPipeline(pipelineId).orElse(null);
        if (batch != null) {
            var current = repository.findPipeline(pipelineId).orElseThrow();
            if (current.stage() != PurchaseStage.FAILED || !ZnackErrorMessages.isInsufficientFunds(current.errorMessage()))
                throw new IllegalStateException("The purchase is not a confirmed insufficient-funds rejection.");
            LicenseRuntime.require(Permission.KIZ);
            if (!batches().retryRejected(batch.id()))
                throw new IllegalStateException("The rejected purchase cannot safely be retried.");
            for (var line : batch.lines())
                ZnackSigningSession.authorizePipeline(repository.shop().shopId(), line.pipelineId(), batches().settingsForPipeline(line.pipelineId()));
            try { advance(batch.settings(), pipelineId); }
            finally { batch.lines().forEach(line -> schedule(line.pipelineId())); }
            return;
        }
        ZnackPurchasePipelineState pipeline;
        synchronized (CREATE_LOCK) {
            pipeline = repository.findPipeline(pipelineId)
                    .filter(candidate -> candidate.stage() == PurchaseStage.FAILED)
                    .filter(candidate -> ZnackErrorMessages.isInsufficientFunds(candidate.errorMessage()))
                    .orElseThrow(() -> new IllegalStateException(
                            "No insufficient-funds purchase to retry for pipeline " + pipelineId));
            LicenseRuntime.require(Permission.KIZ);
            repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.QUEUED, null);
        }
        ZnackSigningSession.authorizePipeline(repository.shop().shopId(), pipeline.id(), batches().settingsForPipeline(pipeline.id()));
        repository.log("PURCHASE_RETRY", pipeline.gtin(), "INFO", "INSUFFICIENT_FUNDS_RETRY_REQUESTED", null);

        ZnackPurchasePipelineState activated = repository.activateNextQueuedPipeline(pipeline.gtin()).orElse(null);
        if (activated == null) {
            schedule(pipeline.id());
            return;
        }
        try {
            advance(settings, activated.id());
        } finally {
            schedule(activated.id());
            if (activated.id() != pipeline.id()) schedule(pipeline.id());
        }
    }

    /**
     * Foreground consent to continue one saved purchase after signing was paused.
     * Keeps the original connection, order, request key and remote checkpoints; never enqueues a buy.
     */
    public void resumeSigning(long pipelineId) throws Exception {
        var pipeline = repository.findPipeline(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("PURCHASE_PIPELINE_NOT_FOUND"));
        if (!isSigningConfirmationPending(repository.shop().shopId(), pipeline.id(), pipeline.stage().name(), pipeline.errorMessage()))
            throw new IllegalStateException("This purchase is no longer waiting for signing confirmation.");
        LicenseRuntime.require(Permission.KIZ);
        var batch = batches().findByPipeline(pipelineId).orElse(null);
        List<Long> members = batch != null && batch.state() != ZnackPurchaseBatchStore.State.ACCEPTED
                ? batch.lines().stream().map(ZnackPurchaseBatchStore.Line::pipelineId).toList() : List.of(pipelineId);
        for (long member : members) {
            var current = repository.findPipeline(member).orElseThrow();
            if (!current.active()) throw new IllegalStateException("Purchase batch member changed before signing resumed.");
            Settings original = batches().settingsForPipeline(member);
            ZnackSafety.requireSigned(original, true);
            ZnackSigningSession.authorizePipelineResumption(repository.shop().shopId(), member, original);
        }
        try { advance(batches().settingsForPipeline(pipelineId), pipelineId); }
        finally { members.forEach(this::schedule); }
    }

    /** UI and recovery share the same explicit-consent eligibility, including saved legacy errors. */
    public static boolean isSigningConfirmationPending(int shopId, Long pipelineId, String stage, String error) {
        if (pipelineId == null || stage == null) return false;
        boolean active = switch (stage) {
            case "QUEUED", "VALIDATING", "CREATING_ORDER", "RECONCILING_ORDER", "POLLING_ORDER",
                    "DOWNLOADING_CODES", "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION",
                    "POLLING_INTRODUCTION" -> true;
            default -> false;
        };
        return active && (ZnackSigningSession.isSigningDeferred(error)
                || ZnackSigningSession.isWaitingForSignature(shopId, pipelineId));
    }

    public void resume(Settings settings) {
        var recoveredProfiles=repository.resumeCompletedCirculationChecks();
        for (ZnackPurchasePipelineState pipeline : repository.findActivePipelines()) {
            if(licensePaused(pipeline))continue;
            try {
                advanceScheduled(recoveredProfiles.getOrDefault(pipeline.id(),settings), pipeline.id());
            } catch (Exception e) {
                if (!(e instanceof ZnackSigningSession.SigningDeferredException) && !(e instanceof LicenseDeniedException)) {
                    repository.log("PURCHASE_PIPELINE_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
                }
            } finally {
                schedule(pipeline.id());
            }
        }
    }

    public void resumeAsync(Settings settings) {
        var recoveredProfiles=repository.resumeCompletedCirculationChecks();
        for (ZnackPurchasePipelineState pipeline : repository.findActivePipelines()) {
            if(licensePaused(pipeline))continue;
            ZnackPipelineQueueExecutor.submit(repository.shop().shopId(), pipeline.gtin(), workStage(pipeline.stage()), () -> {
                if(repository.findPipeline(pipeline.id()).map(ZnackPurchaseCoordinator::licensePaused).orElse(true))return;
                try {
                    advanceScheduled(recoveredProfiles.getOrDefault(pipeline.id(),settings), pipeline.id());
                } catch (Exception e) {
                    if (!(e instanceof ZnackSigningSession.SigningDeferredException) && !(e instanceof LicenseDeniedException)) {
                        repository.log("PURCHASE_PIPELINE_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
                    }
                } finally {
                    schedule(pipeline.id());
                }
            });
        }
    }

    public void resumeEligibleIntroductions(Settings settings) {
        List<ZnackPurchasePipelineState> candidates = new java.util.ArrayList<>(repository.findSkippedIntroductionPipelines());
        candidates.addAll(repository.findLegacyRejectedIntroductionPipelines());
        candidates.addAll(repository.findLegacyPrimitiveDocumentResponsePipelines());
        candidates.addAll(repository.findWaitingIntroductionDocumentPipelines());
        for (ZnackPurchasePipelineState pipeline : candidates) {
            Settings original=introductionSettings(pipeline);
            if(original==null)continue;
            Product product = repository.findProduct(pipeline.gtin()).orElse(null);
            boolean production = batches().contextForPipeline(pipeline.id()).method().requiresProductionMetadata();
            if (product == null || pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()
                    || (production && (product.tnVed() == null || product.tnVed().isBlank()))
                    || (production && ZnackErrorMessages.isMissingDocumentsWait(
                                pipeline.stage().name(), pipeline.errorMessage())
                        && !hasCompletePermit(product))) {
                continue;
            }
            synchronized (CREATE_LOCK) {
                try{LicenseRuntime.require(Permission.KIZ);}
                catch(LicenseDeniedException denied){continue;}
                repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
            }
            try {
                advance(original, pipeline.id());
            } catch (Exception e) {
                PurchaseStage current = repository.findPipeline(pipeline.id())
                        .map(ZnackPurchasePipelineState::stage).orElse(pipeline.stage());
                if (current != PurchaseStage.WAITING_INTRODUCTION_READINESS
                        && repository.findLatestDocument(pipeline.orderId()).isEmpty()) {
                    repository.updatePipeline(pipeline.id(), pipeline.orderId(), pipeline.stage(), e.getMessage());
                }
                if (!(e instanceof ZnackSigningSession.SigningDeferredException) && !(e instanceof LicenseDeniedException)) {
                    repository.log("INTRODUCTION_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
                }
            } finally {
                schedule(pipeline.id());
            }
        }
    }

    public void resumeEligibleIntroductionsAsync(Settings settings) {
        List<ZnackPurchasePipelineState> candidates = new java.util.ArrayList<>(repository.findSkippedIntroductionPipelines());
        candidates.addAll(repository.findLegacyRejectedIntroductionPipelines());
        candidates.addAll(repository.findLegacyPrimitiveDocumentResponsePipelines());
        candidates.addAll(repository.findWaitingIntroductionDocumentPipelines());
        for (ZnackPurchasePipelineState pipeline : candidates) {
            Settings original=introductionSettings(pipeline);
            if(original==null)continue;
            Product product = repository.findProduct(pipeline.gtin()).orElse(null);
            boolean production = batches().contextForPipeline(pipeline.id()).method().requiresProductionMetadata();
            if (product == null || pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()
                    || (production && (product.tnVed() == null || product.tnVed().isBlank()))
                    || (production && ZnackErrorMessages.isMissingDocumentsWait(
                                pipeline.stage().name(), pipeline.errorMessage())
                        && !hasCompletePermit(product))) {
                continue;
            }
            ZnackPipelineQueueExecutor.submit(repository.shop().shopId(), pipeline.gtin(), workStage(pipeline.stage()), () -> {
                synchronized (CREATE_LOCK) {
                    try{LicenseRuntime.require(Permission.KIZ);}
                    catch(LicenseDeniedException denied){return;}
                    repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
                }
                try {
                    advance(original, pipeline.id());
                } catch (Exception e) {
                    PurchaseStage current = repository.findPipeline(pipeline.id())
                            .map(ZnackPurchasePipelineState::stage).orElse(pipeline.stage());
                    if (current != PurchaseStage.WAITING_INTRODUCTION_READINESS
                            && repository.findLatestDocument(pipeline.orderId()).isEmpty()) {
                        repository.updatePipeline(pipeline.id(), pipeline.orderId(), pipeline.stage(), e.getMessage());
                    }
                    if (!(e instanceof ZnackSigningSession.SigningDeferredException) && !(e instanceof LicenseDeniedException)) {
                        repository.log("INTRODUCTION_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
                    }
                } finally {
                    schedule(pipeline.id());
                }
            });
        }
    }

    private Settings introductionSettings(ZnackPurchasePipelineState pipeline){
        Settings original=repository.boundPipelineSettings(pipeline.id()).orElse(null);
        if(original==null||!original.autoIntroduction())return null;
        try{
            ZnackSafety.requireSigned(original,true);
            CryptoProSignatureProvider.requireAvailable(original.cryptcpPath(),Duration.ofSeconds(original.resolvedCryptoProTimeoutSeconds()));
            return original;
        }catch(Exception unavailable){return null;}
    }

    public void advance(Settings settings, long pipelineId) throws Exception {
        com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(repository.shop().shopId(), () -> {
            advanceActive(settings, pipelineId, false);
            return null;
        });
    }

    private void advanceScheduled(Settings settings, long pipelineId) throws Exception {
        com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(repository.shop().shopId(), () -> {
            advanceActive(settings, pipelineId, true);
            return null;
        });
    }

    private void advanceActive(Settings settings, long pipelineId, boolean automatic) throws Exception {
        if(serviceSettings!=null){
            var original=batches().settingsForPipeline(pipelineId);
            // A per-purchase circulation intent does not change signer/OMS/token identity.
            // Reuse those services only when every other persisted setting still matches.
            if(!hasCurrentRuntime(original)){create(repository,original).advanceActive(original,pipelineId,automatic);return;}
            settings=original;
        }
        String key = pipelineKey(pipelineId);
        if (!RUNNING.add(key)) return;
        String gtin = null;
        try (ZnackSigningSession.PipelineScope ignored =
                     ZnackSigningSession.openPipeline(repository.shop().shopId(), pipelineId, automatic)) {
            ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId).orElseThrow();
            gtin = pipeline.gtin();
            try {
                if(licensePaused(pipeline)) {
                    LicenseRuntime.require(Permission.KIZ);
                    repository.updatePipeline(pipelineId,pipeline.orderId(),pipeline.stage(),null);
                }
                var batch = batches().findByPipeline(pipeline.id()).orElse(null);
                if (batch != null && batch.state() != ZnackPurchaseBatchStore.State.ACCEPTED) {
                    advanceBatch(batch);
                    return;
                }
                switch (pipeline.stage()) {
                    case QUEUED -> {
                        if(resumeExistingPurchase(settings,pipeline))break;
                        LicenseRuntime.require(Permission.KIZ);
                        // Activation is handled in finally after checking that no purchase mutation
                        // is currently active for this GTIN.
                    }
                    case VALIDATING -> {
                        if(resumeExistingPurchase(settings,pipeline))break;
                        LicenseRuntime.require(Permission.KIZ);
                        // A validated intent is immutable; use its pinned purchase method.
                        var context = batches().contextForPipeline(pipeline.id());
                        ZnackPurchasePreflight.requireLocalPermit(settings, product(pipeline.gtin()), context.method());
                        createOrder(settings, pipeline);
                    }
                    case POLLING_ORDER -> pollOrder(settings, pipeline);
                    case DOWNLOADING_CODES -> downloadCodes(settings, pipeline);
                    case WAITING_INTRODUCTION_READINESS -> checkIntroductionReadiness(settings, pipeline);
                    case SUBMITTING_INTRODUCTION -> submitIntroduction(settings, pipeline);
                    case POLLING_INTRODUCTION -> pollIntroduction(settings, pipeline);
                    case WAITING_INTRODUCTION_DOCUMENTS -> {
                        if (settings.autoIntroduction()) {
                            repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.SUBMITTING_INTRODUCTION, null);
                            submitIntroduction(settings, repository.findPipeline(pipeline.id()).orElseThrow());
                        }
                    }
                    case CREATING_ORDER -> {
                        Long localOrderId = pipeline.orderId() != null ? pipeline.orderId()
                                : repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                                        pipeline.updatedAt().minusSeconds(30)).map(KizOrder::id).orElse(null);
                        repository.updatePipeline(pipeline.id(), localOrderId,
                                PurchaseStage.RECONCILING_ORDER, pipeline.errorMessage());
                        var interrupted=repository.findPipeline(pipeline.id()).orElseThrow();
                        if(!resumeExistingPurchase(settings,interrupted))reconcileOrder(settings,interrupted);
                    }
                    case RECONCILING_ORDER -> {if(!resumeExistingPurchase(settings,pipeline))reconcileOrder(settings,pipeline);}
                    default -> {
                    }
                }
            } catch (Exception e) {
                PurchaseStage current = repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::stage)
                        .orElse(PurchaseStage.FAILED);
                var mutationBatch = batches().findByPipeline(pipelineId).orElse(null);
                if(e instanceof LicenseDeniedException) {
                    // No new order/document was sent at this boundary. Retain bought codes and the
                    // accepted remote checkpoints, and require an explicit retry after rights return.
                    PurchaseStage paused=current==PurchaseStage.CREATING_ORDER?PurchaseStage.VALIDATING
                            :current==PurchaseStage.SUBMITTING_INTRODUCTION?PurchaseStage.WAITING_INTRODUCTION_READINESS:current;
                    if (mutationBatch != null && mutationBatch.state() != ZnackPurchaseBatchStore.State.ACCEPTED) {
                        batches().project(mutationBatch.id(), paused, "LICENSE_REQUIRED");
                        for (var line : mutationBatch.lines()) NEXT_CHECKS.remove(pipelineKey(line.pipelineId()));
                    } else repository.updatePipeline(pipelineId,null,paused,"LICENSE_REQUIRED");
                    throw e;
                } else if (e instanceof ZnackSigningSession.SigningDeferredException) {
                    var pausedBatch = batches().findByPipeline(pipelineId).orElse(null);
                    if (pausedBatch != null && pausedBatch.state() != ZnackPurchaseBatchStore.State.ACCEPTED) {
                        batches().project(pausedBatch.id(), current, e.getMessage());
                        for (var line : pausedBatch.lines()) NEXT_CHECKS.remove(pipelineKey(line.pipelineId()));
                    } else repository.updatePipeline(pipelineId, null, current, e.getMessage());
                    LOGGER.info("Znack signing deferred until explicit shop selection. shopId={}, pipelineId={}, stage={}",
                            repository.shop().shopId(), pipelineId, current);
                    throw e;
                } else if (mutationBatch != null && mutationBatch.state() != ZnackPurchaseBatchStore.State.ACCEPTED) {
                    // Batch state is authoritative. A deferred authentication/read, capacity
                    // hold or credential recovery must not become a one-member legacy FAILED
                    // projection while its approved siblings can still continue.
                    if (mutationBatch.state() != ZnackPurchaseBatchStore.State.FAILED)
                        batches().project(mutationBatch.id(), current, e.getMessage());
                } else if (current == PurchaseStage.RECONCILING_ORDER
                        || current == PurchaseStage.POLLING_ORDER || current == PurchaseStage.DOWNLOADING_CODES
                        || current == PurchaseStage.WAITING_INTRODUCTION_READINESS
                        || current == PurchaseStage.POLLING_INTRODUCTION) {
                    String reason = e instanceof CryptoProException
                            && ZnackSigningSession.isWaitingForSignature(repository.shop().shopId(), pipelineId)
                            ? ZnackSigningSession.signingWaitMessage(repository.shop().shopId()) : e.getMessage();
                    repository.updatePipeline(pipelineId, null, current, reason);
                } else if (current == PurchaseStage.SUBMITTING_INTRODUCTION) {
                    if (e instanceof ZnackIntroductionService.PermitDocumentsUnavailableException unavailable) {
                        Long orderId=repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::orderId).orElse(null);
                        OrderStatus waitingOrder = unavailable.confirmedMissing()
                                ? OrderStatus.WAITING_INTRODUCTION_DOCUMENTS
                                : OrderStatus.WAITING_INTRODUCTION_READINESS;
                        PurchaseStage waitingStage = unavailable.confirmedMissing()
                                ? PurchaseStage.WAITING_INTRODUCTION_DOCUMENTS
                                : PurchaseStage.WAITING_INTRODUCTION_READINESS;
                        if(orderId!=null)repository.updateOrder(orderId,null,null,
                                waitingOrder,e.getMessage());
                        repository.updatePipeline(pipelineId, orderId,
                                waitingStage, e.getMessage());
                    } else {
                        var orderId=repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::orderId).orElse(null);
                        var document=orderId==null?null:repository.findLatestDocument(orderId).orElse(null);
                        // A lost/5xx response may have been accepted. Observe the saved codes;
                        // never authorize a duplicate legal document from this uncertainty.
                        repository.updatePipeline(pipelineId,null,requiresCirculationReadback(document)
                                ?PurchaseStage.WAITING_INTRODUCTION_READINESS:PurchaseStage.INTRODUCTION_FAILED,e.getMessage());
                    }
                } else if (current == PurchaseStage.CREATING_ORDER
                        && !(e instanceof ZnackOrderCreationAmbiguousException)) {
                    Long failedOrderId = repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                                    repository.findPipeline(pipelineId).orElseThrow().updatedAt().minusSeconds(30))
                            .map(KizOrder::id).orElse(null);
                    repository.updatePipeline(pipelineId, failedOrderId, PurchaseStage.FAILED, e.getMessage());
                } else if (current != PurchaseStage.CREATING_ORDER && current != PurchaseStage.FAILED) {
                    repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, e.getMessage());
                }
                LOGGER.error("Znack purchase pipeline failed. shopId={}, pipelineId={}, gtin={}, stage={}, details={}",
                        repository.shop().shopId(), pipelineId, pipeline.gtin(), current, ZnackSanitizer.error(e), e);
                repository.log("PURCHASE_PIPELINE", pipeline.gtin(), "ERROR", e.getMessage(), httpStatus(e));
                throw e;
            }
        } finally {
            RUNNING.remove(key);
            if (gtin != null) activateNextQueued(gtin);
        }
    }

    public void validatePrerequisites(Settings settings, String gtin, int quantity) throws Exception {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        boolean hasParkedPurchase = repository.findWaitingIntroductionDocumentPipelines().stream()
                .anyMatch(pipeline -> normalized.equals(pipeline.gtin()));
        if (hasParkedPurchase) {
            throw new IllegalStateException("This GTIN already has purchased KIZ waiting for circulation. "
                    + "Synchronize its National Catalog document to resume the existing purchase.");
        }
        Product product = repository.findActiveProduct(normalized).orElseGet(()->
                new com.vncode.app.features.kiz.KizRegistrationIdentityRepository().product(repository.shop().shopId(),normalized).orElse(null));
        if (product == null) {
            throw new IllegalArgumentException("GTIN is not registered for the selected shop.");
        }
        ZnackPurchaseMethod method = admissionMethod.get();
        if (method == null) method = new ZnackPurchasePreferences(repository.shop().shopId()).method();
        ZnackPurchasePreflight.requireLocalPermit(settings, product, method);
        ZnackSafety.requireSigned(settings, true);
        if (settings.omsId() == null || settings.omsId().isBlank()) {
            throw new IllegalStateException("omsId is required before buying KIZ.");
        }
        CryptoProSignatureProvider.requireAvailable(settings.cryptcpPath(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
    }

    /** A legacy pre-send label cannot authorize a second purchase of its saved order. */
    private boolean resumeExistingPurchase(Settings settings,ZnackPurchasePipelineState pipeline)throws Exception{
        if(pipeline.orderId()==null)return false;
        var local=repository.findOrder(pipeline.orderId()).orElseThrow(()->new IllegalStateException("SUZ_ORDER_RECONCILIATION_INVALID"));
        // The existing explicit insufficient-funds retry is a proven server rejection,
        // unlike a FAILED local draft after a timeout/response loss.
        if((local.externalOrderId()==null||local.externalOrderId().isBlank())&&local.localStatus()==OrderStatus.FAILED&&ZnackErrorMessages.isInsufficientFunds(local.errorMessage()))return false;
        boolean accepted=local.externalOrderId()!=null&&!local.externalOrderId().isBlank();
        repository.updatePipeline(pipeline.id(),local.id(),accepted?PurchaseStage.POLLING_ORDER:PurchaseStage.RECONCILING_ORDER,pipeline.errorMessage());
        var resumed=repository.findPipeline(pipeline.id()).orElseThrow();
        if(accepted)pollOrder(settings,resumed);else reconcileOrder(settings,resumed);return true;
    }

    private void createOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        LicenseRuntime.require(Permission.KIZ);
        var batch = batches().prepare(pipeline.id()).orElse(null);
        if (batch == null) {
            // An older already-sent request is observed through its original local order.
            if (!resumeExistingPurchase(settings, pipeline))
                repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.QUEUED, null);
            return;
        }
        advanceBatch(batch);
    }

    private void advanceBatch(ZnackPurchaseBatchStore.Batch batch) throws Exception {
        LicenseRuntime.require(Permission.KIZ);
        try {
            orders.advanceBatch(batch.settings(), batch.id());
        } finally {
            // Followers need truthful diagnostics/timers even if one shared signer
            // deferred, quota held creation, or a child replaced the rejected parent.
            for (var line : batch.lines()) {
                var pipeline = repository.findPipeline(line.pipelineId()).orElse(null);
                if (pipeline != null) schedule(pipeline.id());
            }
        }
    }

    private void reconcileOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder local = pipeline.orderId() == null
                ? repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                        pipeline.updatedAt().minusSeconds(30)).orElse(null)
                : repository.findOrder(pipeline.orderId()).orElse(null);
        if (local == null) {
            String message = "The interrupted purchase did not start a remote order; the next queued request can continue.";
            repository.updatePipeline(pipeline.id(), null, PurchaseStage.FAILED, message);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "WARN", "NO_LOCAL_ATTEMPT", null);
            return;
        }
        if (pipeline.orderId() == null) {
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.RECONCILING_ORDER,
                    pipeline.errorMessage());
        }
        ZnackKizOrderService.OrderReconciliation result = orders.reconcile(settings, local);
        if (result.status() == ZnackKizOrderService.ReconciliationStatus.MATCHED) {
            ZnackKizOrderService.RemoteOrder remote = result.order();
            repository.updateOrder(local.id(), remote.externalOrderId(), remote.remoteStatus(),
                    OrderStatus.SUBMITTED, null);
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.POLLING_ORDER, null);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "INFO", "REMOTE_ORDER_RECOVERED", 200);
            pollOrder(settings, repository.findPipeline(pipeline.id()).orElseThrow());
            return;
        }
        if (result.status() == ZnackKizOrderService.ReconciliationStatus.NOT_FOUND
                && !Instant.now().isBefore(local.createdAt().plus(ORDER_RECONCILIATION_GRACE))) {
            String message = "Znack confirmed no matching order after the reconciliation window; the queue was released.";
            repository.updateOrder(local.id(), null, "NOT_FOUND", OrderStatus.FAILED, message);
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.FAILED, message);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "WARN", "REMOTE_ORDER_NOT_FOUND", 200);
            return;
        }
        String pending = result.status() == ZnackKizOrderService.ReconciliationStatus.CONFLICT
                ? "Several matching Znack orders were found; VN code will keep reconciling without buying again."
                : "Waiting for the interrupted order to become visible in Znack; VN code will retry automatically.";
        repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.RECONCILING_ORDER, pending);
    }

    private void pollOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = orders.refresh(settings, consistentRecoveryOrder(pipeline).id());
        if("CLOSED".equalsIgnoreCase(order.remoteStatus())) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.DOWNLOADING_CODES,
                    "SUZ order CLOSED; reconciling previously issued code blocks.");
            recoverInactiveOrder(settings, repository.findPipeline(pipeline.id()).orElseThrow(),
                    "SUZ order CLOSED; reconciling previously issued code blocks.", "CLOSED");
        } else if("EXHAUSTED".equalsIgnoreCase(order.remoteStatus())){
            repository.updatePipeline(pipeline.id(),order.id(),PurchaseStage.DOWNLOADING_CODES,
                    ZnackErrorMessages.EXHAUSTED_KIZ_BUFFER);
            recoverInactiveOrder(settings,repository.findPipeline(pipeline.id()).orElseThrow(),
                    ZnackErrorMessages.EXHAUSTED_KIZ_BUFFER,"EXHAUSTED");
        } else if (order.localStatus() == OrderStatus.CODES_READY || order.localStatus() == OrderStatus.CODES_DOWNLOADED) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.DOWNLOADING_CODES, null);
            downloadCodes(settings, repository.findPipeline(pipeline.id()).orElseThrow());
        } else if (order.localStatus() == OrderStatus.FAILED || order.localStatus() == OrderStatus.CANCELLED) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.FAILED, order.errorMessage());
        } else {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_ORDER, null);
        }
    }

    private void downloadCodes(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder local=consistentRecoveryOrder(pipeline);
        long orderId = local.id();
        if (ZnackErrorMessages.isClosedKizOrder(pipeline.errorMessage())||"CLOSED".equalsIgnoreCase(local.remoteStatus())) {
            recoverClosedOrder(settings, pipeline, pipeline.errorMessage());
            return;
        }
        if(ZnackErrorMessages.isExhaustedKizBuffer(pipeline.errorMessage())
                ||"EXHAUSTED".equalsIgnoreCase(local.remoteStatus())){
            recoverInactiveOrder(settings,pipeline,ZnackErrorMessages.isExhaustedKizBuffer(pipeline.errorMessage())
                    ?pipeline.errorMessage():ZnackErrorMessages.EXHAUSTED_KIZ_BUFFER,"EXHAUSTED");
            return;
        }
        try {
            boolean replayOnly = ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN.equals(local.errorMessage())
                    || repository.codeDownloadTarget(orderId).isPresent();
            boolean alreadyComplete = repository.findCodes(orderId).size() >= local.quantity();
            if (replayOnly || alreadyComplete) {
                // Neither a zero fresh buffer nor a stale readiness count may prevent
                // validation/recovery of codes that the server already issued.
                codes.download(settings, orderId);
            } else {
            var available = orders.availableCodes(orderId);
            if (available.isEmpty()) {
                orders.refresh(settings, orderId);
                available = orders.availableCodes(orderId);
            }
            if (available.isPresent() && available.getAsInt() == 0) {
                repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.POLLING_ORDER, null);
                return;
            }
            if (available.isPresent()) codes.download(settings, orderId, available.getAsInt());
            else codes.download(settings, orderId);
            orders.clearAvailability(orderId);
            }
        } catch (ZnackApiClient.ZnackApiException error) {
            if (ZnackErrorMessages.isPendingKizBuffer(error.responseBody())) {
                // The order exists and remains safe to resume. Poll that same order until SUZ changes
                // its buffer to ACTIVE; never surface this transient state or create another order.
                repository.updateOrder(orderId, null, "PENDING", OrderStatus.WAITING_CODES, null);
                repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.POLLING_ORDER, null);
                return;
            }
            if (ZnackErrorMessages.isClosedKizOrder(error.responseBody())) {
                recoverClosedOrder(settings, pipeline, error.getMessage());
                return;
            }
            if(ZnackErrorMessages.isExhaustedKizBuffer(error.responseBody())){
                recoverInactiveOrder(settings,pipeline,error.getMessage(),"EXHAUSTED");
                return;
            }
            throw error;
        }
        KizOrder order = repository.findOrder(orderId).orElseThrow();
        int downloaded = repository.findCodes(orderId).size();
        if (downloaded < order.quantity()) {
            // A response may contain fewer codes than requested. Keep downloading only the
            // remainder; partial delivery is progress, not a user-facing purchase failure.
            repository.updateOrder(orderId, null, null, OrderStatus.WAITING_CODES, null);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.DOWNLOADING_CODES, null);
            return;
        }
        completeDownloadedCodes(settings, pipeline, orderId);
    }

    private void recoverClosedOrder(Settings settings, ZnackPurchasePipelineState pipeline,
                                    String closedDiagnostic) throws Exception {
        recoverInactiveOrder(settings,pipeline,closedDiagnostic,"CLOSED");
    }

    private void recoverInactiveOrder(Settings settings,ZnackPurchasePipelineState pipeline,
                                      String diagnostic,String remoteStatus)throws Exception {
        KizOrder order=consistentRecoveryOrder(pipeline);
        long orderId=order.id();
        // A failed block lookup must never erase the already-observed inactive delivery state.
        // Persist it before external reads so a restart cannot consume another ordinary block.
        repository.updateOrder(orderId,null,remoteStatus,OrderStatus.WAITING_CODES,null);
        repository.updatePipeline(pipeline.id(),orderId,PurchaseStage.DOWNLOADING_CODES,diagnostic);
        var existingCodes=repository.findCodes(orderId);
        ZnackKizCodeService.requireStoredCodeIdentity(order,existingCodes);
        if (existingCodes.size() < order.quantity()) {
            try {
                codes.recoverIssuedBlocks(settings, orderId);
            } catch (ZnackSigningSession.SigningDeferredException deferred) {
                throw deferred;
            } catch (ZnackKizCodeService.RecoveryIdentityException identity) {
                throw identity;
            } catch (java.io.IOException recoveryUnavailable) {
                if(recoveryUnavailable instanceof ZnackApiClient.ZnackApiException response
                        &&(response.statusCode()==401||response.statusCode()==403))throw recoveryUnavailable;
                // Issued blocks can be unavailable after closure or when the first issue was
                // outside the API. Retain the inactive-state retry marker, never buy again.
                repository.log("RECOVER_CODE_BLOCKS", pipeline.gtin(), "WARN",
                        remoteStatus+"_ORDER_BLOCK_RECOVERY_PENDING", httpStatus(recoveryUnavailable));
            }
        }
        int downloaded = repository.findCodes(orderId).size();
        if (downloaded < order.quantity()) {
            repository.updateOrder(orderId, null, remoteStatus, OrderStatus.WAITING_CODES, null);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.DOWNLOADING_CODES, diagnostic);
            return;
        }
        repository.updateOrder(orderId, null, remoteStatus, OrderStatus.CODES_DOWNLOADED, null);
        completeDownloadedCodes(settings, pipeline, orderId);
    }

    private KizOrder consistentRecoveryOrder(ZnackPurchasePipelineState pipeline)throws java.io.IOException{
        var order=repository.findOrder(requiredOrderId(pipeline)).orElseThrow(()->new java.io.IOException("SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID"));
        try{
            if(order.externalOrderId()==null||order.externalOrderId().isBlank()||order.quantity()!=pipeline.quantity()
                    ||!GtinNormalizer.normalize(order.gtin()).equals(GtinNormalizer.normalize(pipeline.gtin())))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new java.io.IOException("SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID");}
        return order;
    }

    private void completeDownloadedCodes(Settings settings, ZnackPurchasePipelineState pipeline,
                                         long orderId) throws Exception {
        repository.updateOrder(orderId, null, null, OrderStatus.WAITING_INTRODUCTION_READINESS, null);
        repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        checkIntroductionReadiness(settings, repository.findPipeline(pipeline.id()).orElseThrow());
    }

    private void checkIntroductionReadiness(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        long orderId = requiredOrderId(pipeline);
        KizOrder order=repository.findOrder(orderId).orElse(null);
        List<ZnackModels.KizCode> downloadedCodes = repository.findCodes(orderId);
        if(order==null||pipeline.quantity()<=0||order.quantity()!=pipeline.quantity()
                ||downloadedCodes.size()!=order.quantity()||!sameGtin(order.gtin(),pipeline.gtin())
                ||!ZnackCisReadback.validRequests(downloadedCodes,pipeline.gtin(),orderId)){
            repository.updatePipeline(pipeline.id(),orderId,PurchaseStage.WAITING_INTRODUCTION_READINESS,"KIZ_CIRCULATION_IDENTITY_UNVERIFIED");
            return;
        }
        boolean observerOnly=!settings.autoIntroduction()||requiresCirculationReadback(repository.findLatestDocument(orderId).orElse(null));
        ZnackIntroductionReadinessService.Readiness result = readiness == null
                ? !observerOnly?ZnackIntroductionReadinessService.Readiness.ready(null)
                    :ZnackIntroductionReadinessService.Readiness.waiting("KIZ_CIRCULATION_STATUS_UNVERIFIED")
                : !observerOnly?readiness.check(settings, product(pipeline.gtin()), downloadedCodes)
                    :readiness.checkCirculation(settings,product(pipeline.gtin()),downloadedCodes);
        if (result.allIntroduced()) {
            repository.markCodes(orderId, ZnackModels.KizLegalStatus.IN_CIRCULATION, null, null);
            repository.updateOrder(orderId, null, null, OrderStatus.INTRODUCED, null);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.INTRODUCED, null);
            return;
        }
        if(observerOnly){
            String message=result.message()==null||result.message().isBlank()?"KIZ_CIRCULATION_STATUS_UNVERIFIED":result.message();
            repository.updateOrder(orderId,null,null,OrderStatus.WAITING_INTRODUCTION_READINESS,message);
            repository.updatePipeline(pipeline.id(),orderId,PurchaseStage.WAITING_INTRODUCTION_READINESS,message);
            return;
        }
        if (!result.ready()) {
            repository.updateOrder(orderId, null, null, OrderStatus.WAITING_INTRODUCTION_READINESS, result.message());
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.WAITING_INTRODUCTION_READINESS, result.message());
            return;
        }
        Product product = product(pipeline.gtin());
        if (batches().contextForOrder(orderId).method().requiresProductionMetadata()
                && (product.tnVed() == null || product.tnVed().isBlank())) {
            // Metadata is required to submit a new legal document, not to observe an
            // already introduced code. Keep polling so external circulation is mirrored.
            String error = "Missing TN VED.";
            repository.updateOrder(orderId, null, null, OrderStatus.WAITING_INTRODUCTION_READINESS, error);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.WAITING_INTRODUCTION_READINESS, error);
            return;
        }
        if (result.message() != null && !result.message().isBlank()) {
            repository.log("INTRODUCTION_READINESS", pipeline.gtin(), "WARN", result.message(), null);
        }
        LicenseRuntime.require(Permission.KIZ);
        repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.SUBMITTING_INTRODUCTION, null);
        submitIntroduction(settings, repository.findPipeline(pipeline.id()).orElseThrow());
    }

    private void submitIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        Product product = product(pipeline.gtin());
        ZnackModels.Document existing = repository.findLatestDocument(order.id()).orElse(null);
        if(existing!=null&&requiresCirculationReadback(existing)){
            if("REJECTED".equals(existing.status()))repository.updateDocument(existing.id(),null,"FAILED",existing.errorMessage());
            repository.updatePipeline(pipeline.id(),order.id(),PurchaseStage.WAITING_INTRODUCTION_READINESS,existing.errorMessage());
            return;
        }
        if (existing != null && ZnackIntroductionService.isDefinitivelyRejectedSubmission(existing)) {
            // CHECKED_NOT_OK: Znack definitively processed the previous document with errors.
            // REJECTED: a definitive client rejection confirms no document was created. Network,
            // 5xx and other ambiguous failures remain blocked until reconciled, never blindly resent.
            existing = null;
        }
        if (existing != null) {
            if (existing.externalDocumentId() != null && !existing.externalDocumentId().isBlank()) {
                repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
                return;
            }
            String recoveredDocumentId = legacyDocumentId(existing.errorMessage());
            if (!recoveredDocumentId.isBlank()) {
                repository.updateDocument(existing.id(), recoveredDocumentId, "SUBMITTED", null);
                repository.markCodes(order.id(), ZnackModels.KizLegalStatus.INTRO_SENT, null, existing.id());
                repository.updateOrder(order.id(), null, null, OrderStatus.INTRO_SENT, null);
                repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
                LOGGER.info("Recovered Znack document ID {} from a legacy primitive response for order {}.",
                        recoveredDocumentId, order.id());
                return;
            }
        }
        if (existing != null) {
            String error = "Introduction submission result is ambiguous; automatic retry is blocked.";
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.FAILED, error);
            throw new IllegalStateException(error);
        }
        LicenseRuntime.require(Permission.KIZ);
        introduction.submit(settings, order, product, repository.findCodes(order.id()));
        repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
    }

    private void pollIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        ZnackIntroductionService.ConfirmResult result =
                introduction.confirm(settings, order, repository.findCodes(order.id()));
        if (result.introduced()) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.INTRODUCED, null);
        } else if (result.failed()) {
            repository.updateOrder(order.id(), null, null, OrderStatus.INTRODUCTION_FAILED, result.message());
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.INTRODUCTION_FAILED, result.message());
            repository.log("INTRODUCTION", pipeline.gtin(), "ERROR", result.message(), null);
        } else {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, result.message());
        }
    }

    private Product product(String gtin) {
        return repository.findProduct(gtin).orElseGet(()->new com.vncode.app.features.kiz.KizRegistrationIdentityRepository()
                .product(repository.shop().shopId(),gtin).orElseThrow(()->new IllegalArgumentException("GTIN is not registered for the selected shop.")));
    }

    private static boolean hasCompletePermit(Product product) {
        if (product == null) return false;
        if (!ZnackPermitDocumentParser.selectForCirculation(product.permitDocuments()).isEmpty()) return true;
        return new ZnackModels.GoodsDocument(product.certificateType(), product.certificateNumber(),
                product.certificateDate()).complete();
    }

    private long requiredOrderId(ZnackPurchasePipelineState pipeline) {
        if (pipeline.orderId() == null) throw new IllegalStateException("Purchase pipeline has no order.");
        return pipeline.orderId();
    }

    private static boolean sameGtin(String left,String right){
        try{return GtinNormalizer.normalize(left).equals(GtinNormalizer.normalize(right));}
        catch(IllegalArgumentException invalid){return false;}
    }

    static boolean requiresCirculationReadback(ZnackModels.Document document){
        return document!=null&&(document.externalDocumentId()==null||document.externalDocumentId().isBlank())
                &&("FAILED".equals(document.status())||"REJECTED".equals(document.status()))
                &&!ZnackIntroductionService.isDefinitivelyRejectedSubmission(document)
                &&legacyDocumentId(document.errorMessage()).isBlank();
    }

    static ZnackPipelineQueueExecutor.WorkStage workStage(PurchaseStage stage) {
        return switch (stage) {
            case QUEUED, VALIDATING, CREATING_ORDER, RECONCILING_ORDER -> ZnackPipelineQueueExecutor.WorkStage.CREATE;
            case POLLING_ORDER, DOWNLOADING_CODES -> ZnackPipelineQueueExecutor.WorkStage.RETRIEVE;
            default -> ZnackPipelineQueueExecutor.WorkStage.INTRODUCE;
        };
    }

    void schedule(long pipelineId) {
        ZnackPurchasePipelineState pipeline;
        try { pipeline = repository.findPipeline(pipelineId).orElse(null); }
        catch (RuntimeException storageFailure) { scheduleLookupRetry(pipelineId); return; }
        if (pipeline == null || (!pipeline.active() && pipeline.stage() != PurchaseStage.WAITING_INTRODUCTION_DOCUMENTS)) return;
        if (licensePaused(pipeline)) { NEXT_CHECKS.remove(pipelineKey(pipelineId)); return; }
        boolean keyWait = ZnackSigningSession.isTransientKeyWait(pipeline.errorMessage());
        if ((ZnackSigningSession.isWaitingForSignature(repository.shop().shopId(), pipelineId)
                || ZnackSigningSession.isSigningDeferred(pipeline.errorMessage())) && !keyWait) {
            NEXT_CHECKS.remove(pipelineKey(pipelineId));
            return;
        }
        String key = pipelineKey(pipelineId);
        if (!SCHEDULED.add(key)) return;
        long delay = pollDelaySeconds(pipeline.stage(), pipeline.errorMessage());
        if (inactiveDelivery(pipeline)) delay = 300;
        var batch = batches().findByPipeline(pipelineId).orElse(null);
        if (batch != null && batch.state() != ZnackPurchaseBatchStore.State.ACCEPTED && batch.nextAttemptAt() != null)
            delay = Math.max(delay, Math.max(1, Duration.between(Instant.now(), batch.nextAttemptAt()).toSeconds()));
        if (keyWait) delay = 30;
        PENDING_POLLS.removeIf(java.util.concurrent.Future::isDone);
        long generation = POLL_GENERATION.get();
        NEXT_CHECKS.put(key, Instant.now().plusSeconds(delay));
        PENDING_POLLS.add(POLLER.schedule(() -> {
            if (generation != POLL_GENERATION.get()) return;
            boolean accepted = ZnackPipelineQueueExecutor.submit(repository.shop().shopId(), pipeline.gtin(),
                    workStage(pipeline.stage()), () -> {
                if (generation != POLL_GENERATION.get()) return;
                NEXT_CHECKS.remove(key);
                ZnackPurchaseCoordinator runtime = this;
                try {
                    var saved = repository.findPipeline(pipelineId).orElse(null);
                    if (saved == null) return;
                    if (ZnackSigningSession.isTransientKeyWait(saved.errorMessage())
                            && !ZnackSigningSession.retryDisconnectedKey(repository.shop().shopId(), pipelineId, saved.errorMessage())) return;
                    Settings original = batches().settingsForPipeline(pipelineId);
                    runtime = currentRuntime(original);
                    runtime.advanceScheduled(original, pipelineId);
                } catch (Exception ignored) {
                    // State/error is durable and surfaced by the existing per-GTIN read model.
                } finally {
                    if (generation == POLL_GENERATION.get()) {
                        SCHEDULED.remove(key);
                        runtime.schedule(pipelineId);
                    }
                }
            });
            if (!accepted && generation == POLL_GENERATION.get()) {
                SCHEDULED.remove(key);
                scheduleLookupRetry(pipelineId);
            }
        }, delay, TimeUnit.SECONDS));
    }

    static long pollDelaySeconds(PurchaseStage stage, String error) {
        if (stage == PurchaseStage.WAITING_INTRODUCTION_READINESS
                || stage == PurchaseStage.WAITING_INTRODUCTION_DOCUMENTS
                || stage == PurchaseStage.RECONCILING_ORDER
                || stage == PurchaseStage.POLLING_INTRODUCTION
                || error != null && !error.isBlank()) return 30;
        return 2;
    }

    private void scheduleLookupRetry(long pipelineId) {
        String key = pipelineKey(pipelineId);
        if (!SCHEDULED.add(key)) return;
        long generation = POLL_GENERATION.get();
        NEXT_CHECKS.put(key, Instant.now().plusSeconds(5));
        PENDING_POLLS.add(POLLER.schedule(() -> {
            if (generation == POLL_GENERATION.get()) {
                SCHEDULED.remove(key);
                NEXT_CHECKS.remove(key);
                schedule(pipelineId);
            }
        }, 5, TimeUnit.SECONDS));
    }

    private boolean inactiveDelivery(ZnackPurchasePipelineState pipeline){
        if(ZnackErrorMessages.isClosedKizOrder(pipeline.errorMessage())||ZnackErrorMessages.isExhaustedKizBuffer(pipeline.errorMessage()))return true;
        return pipeline.orderId()!=null&&repository.findOrder(pipeline.orderId()).map(order->
                "EXHAUSTED".equalsIgnoreCase(order.remoteStatus())||"CLOSED".equalsIgnoreCase(order.remoteStatus())).orElse(false);
    }

    private void activateNextQueued(String gtin) {
        try {
            // A paused unsent request keeps its place; restoring a lease alone does not confirm it.
            if(repository.findActivePipelines().stream().anyMatch(p->p.gtin().equals(gtin) && licensePaused(p)))return;
            try{LicenseRuntime.require(Permission.KIZ);}
            catch(LicenseDeniedException denied){
                for(var pending:repository.findActivePipelines())if(pending.gtin().equals(gtin) && pending.stage()==PurchaseStage.QUEUED)
                    repository.updatePipeline(pending.id(),pending.orderId(),PurchaseStage.QUEUED,"LICENSE_REQUIRED");
                return;
            }
            repository.activateNextQueuedPipeline(gtin).ifPresent(next -> schedule(next.id()));
        } catch (RuntimeException error) {
            LOGGER.error("Could not activate the next queued KIZ purchase. shopId={}, gtin={}",
                    repository.shop().shopId(), gtin, error);
        }
    }

    private static boolean licensePaused(ZnackPurchasePipelineState pipeline) {
        return "LICENSE_REQUIRED".equals(pipeline.errorMessage());
    }

    private static void requireUnsentReplay(ZnackPurchasePipelineState pipeline) {
        if(pipeline.stage()==PurchaseStage.QUEUED || pipeline.stage()==PurchaseStage.VALIDATING
                || pipeline.stage()==PurchaseStage.WAITING_INTRODUCTION_READINESS
                || pipeline.stage()==PurchaseStage.SUBMITTING_INTRODUCTION)LicenseRuntime.require(Permission.KIZ);
    }

    /**
     * Test hook: cancels every not-yet-fired background poll. Unit tests point the app data dir at
     * a throwaway directory per test, and a poll firing later would reopen the SQLite database of a
     * different test — on Windows that file lock makes the JUnit temp-dir cleanup fail.
     */
    static void cancelPendingPolls() {
        POLL_GENERATION.incrementAndGet();
        NEXT_CHECKS.clear();
        for (java.util.concurrent.ScheduledFuture<?> poll : PENDING_POLLS) {
            poll.cancel(false);
        }
        PENDING_POLLS.clear();
        SCHEDULED.clear();
    }

    static boolean isScheduledForTests(int shopId, long pipelineId) {
        return SCHEDULED.contains(shopId + ":" + pipelineId);
    }

    private String pipelineKey(long pipelineId) {
        return repository.shop().shopId() + ":" + pipelineId;
    }

    private Integer httpStatus(Exception error) {
        return error instanceof ZnackApiClient.ZnackApiException apiError ? apiError.statusCode() : null;
    }

    private static String legacyDocumentId(String error) {
        if (error == null || error.isBlank()) return "";
        Matcher matcher = LEGACY_DOCUMENT_ID.matcher(error);
        return matcher.find() ? matcher.group(1) : "";
    }
}
