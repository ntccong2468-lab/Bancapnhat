package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Durable emission service shared by one-item and multi-GTIN purchases. */
public class ZnackKizOrderService {
    public static final String BUFFER_MULTIPLE = "SUZ_KIZ_BUFFER_MULTIPLE";
    public static final String BUFFER_MISSING = "SUZ_KIZ_BUFFER_MISSING";
    public static final String BUFFER_STATUS_MISSING = "SUZ_KIZ_BUFFER_STATUS_MISSING";
    private static final Duration RECONCILIATION_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration RECONCILIATION_WINDOW = Duration.ofMinutes(5);
    private static final Set<String> RUNNING_BATCHES = ConcurrentHashMap.newKeySet();
    private static final Map<String, StatusRead> STATUS_READS = new ConcurrentHashMap<>();
    private static final long STATUS_REUSE_NANOS = Duration.ofSeconds(1).toNanos();
    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackSignatureProvider signer;
    private final ZnackRepository repository;
    private final Map<Long, Availability> availability = new ConcurrentHashMap<>();

    public ZnackKizOrderService(ZnackApiClient api, ZnackAuthService auth,
                               ZnackSignatureProvider signer, ZnackRepository repository) {
        this.api = api; this.auth = auth; this.signer = signer; this.repository = repository;
    }

    /** Compatibility wrapper: a direct one-GTIN purchase is a durable one-member batch. */
    public KizOrder buy(Settings settings, String gtin, int quantity) throws Exception {
        ZnackSafety.requireSigned(settings, true);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        LicenseRuntime.require(Permission.KIZ);
        var store = new ZnackPurchaseBatchStore(repository);
        // Compatibility calls pass through the same whole-request admission policy as
        // manual, WB, Ozon and FBO producers; no separate paid route bypasses permits.
        var admission = createAdmissionCoordinator();
        var ids = admission.admitApproved(settings, List.of(
                new ZnackPurchaseBatchStore.Approved(normalized, quantity, UUID.randomUUID().toString())), false);
        long pipeline = ids.getFirst();
        ZnackSigningSession.authorizePipeline(repository.shop().shopId(), pipeline, store.settingsForPipeline(pipeline));
        var prepared = store.prepare(pipeline).orElseThrow(() -> new IllegalStateException("Purchase is queued behind an existing GTIN request."));
        try (var scope = ZnackSigningSession.openPipeline(repository.shop().shopId(), pipeline)) {
            advanceBatch(prepared.settings(), prepared.id());
        } finally {
            for (var line : prepared.lines())
                ZnackPurchaseCoordinator.scheduleExisting(repository, line.pipelineId());
        }
        var current = store.findByPipeline(pipeline).orElseThrow();
        if (current.externalOrderId() == null || current.externalOrderId().isBlank()) {
            if (current.state() == ZnackPurchaseBatchStore.State.RECONCILING
                    || current.state() == ZnackPurchaseBatchStore.State.SUBMITTING)
                throw new ZnackOrderCreationAmbiguousException("Purchase outcome is being reconciled; no new order was sent.", null);
            throw new IOException(current.error() == null ? "Purchase remains queued." : current.error());
        }
        long ownOrder = current.lines().stream().filter(line -> line.pipelineId() == pipeline)
                .mapToLong(ZnackPurchaseBatchStore.Line::orderId).findFirst().orElseThrow();
        return repository.findOrder(ownOrder).orElseThrow();
    }

    /** The default compatibility facade always uses the common purchase admission policy. */
    protected ZnackPurchaseCoordinator createAdmissionCoordinator() {
        return new ZnackPurchaseCoordinator(repository, this, null, null);
    }

    /** Advance the single canonical payload for all approved members; never re-POST an unknown outcome. */
    public ZnackPurchaseBatchStore.Batch advanceBatch(Settings ignoredCurrent, long batchId) throws Exception {
        var store = new ZnackPurchaseBatchStore(repository);
        String lockKey = repository.shop().shopId() + ":" + batchId;
        if (!RUNNING_BATCHES.add(lockKey)) return store.find(batchId).orElseThrow();
        try {
            var batch = store.find(batchId).orElseThrow();
            if (batch.state() == ZnackPurchaseBatchStore.State.ACCEPTED
                    || batch.state() == ZnackPurchaseBatchStore.State.FAILED) return batch;
            if (batch.state() == ZnackPurchaseBatchStore.State.REJECTED) {
                recoverKnownUnsent(store, batch);
                return store.find(batchId).orElseThrow();
            }
            if (batch.state() == ZnackPurchaseBatchStore.State.SUBMITTING
                    || batch.state() == ZnackPurchaseBatchStore.State.RECONCILING) {
                reconcileBatch(batch);
                return store.find(batchId).orElseThrow();
            }
            if (batch.nextAttemptAt() != null && Instant.now().isBefore(batch.nextAttemptAt())) return batch;
            if (store.capacityBlocked(batch.context().accountKey())) {
                store.hold(batchId, store.capacityResumeAt(batch.context().accountKey()).orElse(Instant.now().plusSeconds(30)),
                        "SUZ_CAPACITY_3350: waiting for existing orders; no repeat purchase required.");
                return store.find(batchId).orElseThrow();
            }
            Settings settings = batch.settings();
            ZnackSafety.requireSigned(settings, true);
            if (!batch.identity().equals(batch.context().identity())) throw new IllegalStateException("Purchase account identity changed.");
            List<ZnackSigningSession.MutationPermit> permits = new ArrayList<>();
            for (var line : batch.lines()) {
                try (var scope = ZnackSigningSession.openPipeline(repository.shop().shopId(), line.pipelineId())) {
                    permits.add(ZnackSigningSession.captureMutationPermit(repository.shop().shopId(), settings));
                }
            }
            if (batch.payload() == null) {
                store.savePayload(batchId, createPayload(batch).toString());
                batch = store.find(batchId).orElseThrow();
            }
            byte[] body = batch.payload().getBytes(StandardCharsets.UTF_8);
            LicenseRuntime.require(Permission.KIZ);
            String signature = signer.sign(body, ZnackSignatureContext.SUZ_POST_BODY).base64();
            String token = auth.suzToken(settings);
            try {
                JsonObject response = executePermits(permits, 0, () -> {
                    LicenseRuntime.require(Permission.KIZ);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Purchase cancelled before submission.");
                    if (!store.beginSubmission(batchId)) return null;
                    if (Thread.currentThread().isInterrupted()) {
                        store.recordKnownNotCreated(batchId, "CANCELLED_BEFORE_HTTP");
                        throw new InterruptedIOException("Purchase cancelled before the HTTP request.");
                    }
                    return api.createOrder(settings.resolvedSuzBaseUrl(), token, settings.omsId(), body, signature);
                });
                if (response != null) {
                    if (hasReconciliationErrors(response)
                            || response.has("success") && response.get("success").isJsonPrimitive()
                            && response.getAsJsonPrimitive("success").isBoolean() && !response.get("success").getAsBoolean())
                        throw new IOException("SUZ order response did not confirm successful submission.");
                    String remote = required(text(response, "orderId", "id"), "Znack order response did not contain an order ID.");
                    store.acceptRemoteOrder(batchId, remote);
                }
            } catch (SuzRequestPacing.DeferredException notSent) {
                store.deferKnownUnsent(batchId, notSent.retryAt(), "SUZ_REQUEST_PACING_DEFERRED");
            } catch (ZnackApiClient.ZnackApiException rejected) {
                String reason = "HTTP " + rejected.statusCode() + ": " + ZnackSanitizer.diagnostic(rejected.responseBody());
                if (rejected.statusCode() == 429) {
                    Duration retry = rejected.retryAfter() == null ? Duration.ofSeconds(30) : rejected.retryAfter();
                    store.deferKnownUnsent(batchId, Instant.now().plus(retry), "SUZ_HTTP_429: " + reason);
                } else if (isCapacityRejection(rejected)) {
                    Instant retry = Instant.now().plusSeconds(30);
                    store.recordQuotaRejection(batchId, retry, "SUZ_CAPACITY_3350: " + reason);
                } else if (isCredentialRejection(rejected)) {
                    store.recordKnownNotCreated(batchId, "SUZ_CREDENTIAL_REJECTED_1090: " + reason);
                    var failed = store.find(batchId).orElseThrow();
                    if (failed.state() != ZnackPurchaseBatchStore.State.FAILED) {
                        Settings replacement = repository.getSettings();
                        if (ZnackPurchaseContext.credentialIdentity(settings).equals(ZnackPurchaseContext.credentialIdentity(replacement)))
                            {
                                auth.invalidateSuzToken(settings, token);
                                auth.suzToken(settings);
                            }
                        recoverKnownUnsent(store, store.find(batchId).orElseThrow());
                    }
                } else if (rejected.statusCode() >= 400 && rejected.statusCode() < 500 && rejected.statusCode() != 408) {
                    if (ZnackErrorMessages.isInsufficientFunds(rejected.responseBody()))
                        store.failKnownNotCreated(batchId, reason);
                    else store.fail(batchId, reason);
                } else {
                    store.markReconciling(batchId, reason);
                }
            } catch (Exception uncertain) {
                var current = store.find(batchId).orElseThrow();
                // A failed admission/signature is still definitely unsent. Only a durable
                // SUBMITTING marker means the remote outcome can be unknown.
                if (current.state() == ZnackPurchaseBatchStore.State.SUBMITTING)
                    store.markReconciling(batchId, ZnackSanitizer.error(uncertain));
                throw uncertain;
            }
            return store.find(batchId).orElseThrow();
        } catch (ZnackApiClient.ZnackApiException preflightLimit) {
            var current = store.find(batchId).orElseThrow();
            if (preflightLimit.statusCode() == 429 && current.attemptedAt() == null) {
                Duration retry = preflightLimit.retryAfter() == null ? Duration.ofSeconds(30) : preflightLimit.retryAfter();
                store.hold(batchId, Instant.now().plus(retry), "SUZ_AUTH_RATE_LIMIT: " + ZnackSanitizer.error(preflightLimit));
                return store.find(batchId).orElseThrow();
            }
            throw preflightLimit;
        } finally {
            RUNNING_BATCHES.remove(lockKey);
        }
    }

    private void recoverKnownUnsent(ZnackPurchaseBatchStore store, ZnackPurchaseBatchStore.Batch rejected) throws Exception {
        if (Thread.currentThread().isInterrupted() || !rejected.knownNotCreated() || rejected.error() == null
                || !(rejected.error().startsWith("SUZ_CREDENTIAL_REJECTED_1090")
                || rejected.error().startsWith("CANCELLED_BEFORE_HTTP"))) return;
        // The store carries the original approved lineage, remaining amount and per-member
        // attempt clock. An ineligible attempt stays REJECTED, never a re-postable hold.
        store.prepareCredentialRecovery(repository.getSettings(), rejected.id(), Instant.now());
        // A child's credentials may differ from its rejected parent. The coordinator
        // reloads each member context and creates a fresh signer/auth service graph.

    }

    private JsonObject createPayload(ZnackPurchaseBatchStore.Batch batch) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("productGroup", batch.group().apiCode());
        JsonObject attributes = new JsonObject();
        attributes.addProperty("releaseMethodType", batch.method().name());
        root.add("attributes", attributes);
        JsonArray products = new JsonArray();
        var identities = new HashSet<String>();
        if (batch.lines().isEmpty() || batch.lines().size() > 10) throw new IllegalStateException("A purchase batch requires 1–10 distinct GTINs.");
        for (var line : batch.lines()) {
            if (!identities.add(line.gtin())) throw new IllegalStateException("A purchase batch repeats a GTIN.");
            if (line.quantity() <= 0) throw new IllegalStateException("Purchase quantity must be positive.");
            JsonObject product = new JsonObject();
            product.addProperty("gtin", GtinNormalizer.requireProductionOrderable(line.gtin()));
            product.addProperty("quantity", line.quantity());
            product.addProperty("serialNumberType", "OPERATOR");
            product.addProperty("templateId", batch.group().templateId());
            product.addProperty("cisType", resolveCisType(batch.settings(), line.gtin()));
            products.add(product);
        }
        root.add("products", products);
        return root;
    }

    private static <T> T executePermits(List<ZnackSigningSession.MutationPermit> permits, int index,
                                        ZnackSigningSession.MutationWork<T> work) throws Exception {
        return index == permits.size() ? work.run()
                : permits.get(index).execute(() -> executePermits(permits, index + 1, work));
    }

    public void reconcileBatch(ZnackPurchaseBatchStore.Batch batch) throws Exception {
        var store = new ZnackPurchaseBatchStore(repository);
        Settings settings = batch.settings();
        JsonObject response = auth.readWithSuzRecovery(settings,
                token -> api.orderList(settings.resolvedSuzBaseUrl(), token, settings.omsId()));
        if (response == null || hasReconciliationErrors(response)
                || !response.has("orderInfos") || !response.get("orderInfos").isJsonArray())
            throw invalidReconciliation(null);
        validateSuppliedContext(response, batch);
        var approved = new LinkedHashMap<String, Integer>();
        batch.lines().forEach(line -> approved.put(line.gtin(), line.quantity()));
        Set<String> matches = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Instant attempted = batch.attemptedAt() == null ? batch.createdAt() : batch.attemptedAt();
        for (JsonElement item : response.getAsJsonArray("orderInfos")) {
            if (!item.isJsonObject()) throw invalidReconciliation(null);
            JsonObject candidate = item.getAsJsonObject();
            if (hasReconciliationErrors(candidate)) throw invalidReconciliation(null);
            validateSuppliedContext(candidate, batch);
            String id = strictText(candidate, "orderId");
            strictText(candidate, "orderStatus");
            if (!seen.add(id)) throw invalidReconciliation(null);
            Instant created;
            try { created = Instant.ofEpochMilli(exactNonnegative(candidate, "createdTimestamp")); }
            catch (RuntimeException invalid) { throw invalidReconciliation(invalid); }
            if (!candidate.has("buffers") || !candidate.get("buffers").isJsonArray()) throw invalidReconciliation(null);
            Map<String, Integer> quantities = new HashMap<>();
            for (JsonElement value : candidate.getAsJsonArray("buffers")) {
                if (!value.isJsonObject()) throw invalidReconciliation(null);
                JsonObject buffer = value.getAsJsonObject();
                if (hasReconciliationErrors(buffer)) throw invalidReconciliation(null);
                String gtin = GtinNormalizer.normalize(strictText(buffer, "gtin"));
                int quantity;
                try { quantity = Math.toIntExact(exactNonnegative(buffer, "totalCodes")); }
                catch (RuntimeException invalid) { throw invalidReconciliation(invalid); }
                if (quantity <= 0 || quantities.put(gtin, quantity) != null) throw invalidReconciliation(null);
            }
            if (!created.isBefore(attempted.minus(RECONCILIATION_CLOCK_SKEW))
                    && !created.isAfter(attempted.plus(RECONCILIATION_WINDOW)) && approved.equals(quantities)) matches.add(id);
        }
        if (matches.size() == 1) store.acceptRemoteOrder(batch.id(), matches.iterator().next());
        else store.markReconciling(batch.id(), matches.isEmpty()
                ? "SUZ_BATCH_RECONCILING: no uniquely confirmed order; no repeat POST."
                : "SUZ_BATCH_RECONCILING: multiple matching orders; no repeat POST.");
    }

    private static void validateSuppliedContext(JsonObject response, ZnackPurchaseBatchStore.Batch batch) throws IOException {
        if (response.has("omsId") && !strictText(response, "omsId").equals(batch.settings().omsId()))
            throw invalidReconciliation(null);
        try {
            if (response.has("productGroup")
                    && ZnackProductGroup.fromCode(strictText(response, "productGroup")) != batch.group())
                throw invalidReconciliation(null);
            if (response.has("releaseMethodType")
                    && ZnackPurchaseMethod.fromCode(strictText(response, "releaseMethodType")) != batch.method())
                throw invalidReconciliation(null);
            if (response.has("attributes")) {
                if (!response.get("attributes").isJsonObject()) throw invalidReconciliation(null);
                var attributes = response.getAsJsonObject("attributes");
                if (attributes.has("releaseMethodType")
                        && ZnackPurchaseMethod.fromCode(strictText(attributes, "releaseMethodType")) != batch.method())
                    throw invalidReconciliation(null);
            }
        } catch (IllegalArgumentException invalid) { throw invalidReconciliation(invalid); }
    }

    public KizOrder refresh(Settings settings, long id) throws Exception {
        ZnackSafety.requireSigned(settings, true);
        KizOrder order = repository.findOrder(id).orElseThrow();
        var context = new ZnackPurchaseBatchStore(repository).contextForOrder(id, settings);
        String remoteId = required(order.externalOrderId(), "Znack order has no external order ID.");
        JsonArray response = sharedStatus(context, remoteId);
        JsonObject buffer = null;
        for (JsonElement element : response) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            String candidateGtin = text(candidate, "gtin");
            if (candidateGtin.isBlank()) continue;
            try { if (!order.gtin().equals(GtinNormalizer.normalize(candidateGtin))) continue; }
            catch (IllegalArgumentException invalid) { continue; }
            if (buffer != null) throw new IOException(BUFFER_MULTIPLE);
            buffer = candidate;
        }
        if (buffer == null) throw new IOException(BUFFER_MISSING);
        String remote = text(buffer, "bufferStatus", "status"), reason = text(buffer, "rejectionReason");
        if (remote.isBlank()) throw new IOException(BUFFER_STATUS_MISSING);
        int available = integer(buffer, "availableCodes");
        if (available < 0) throw new IOException("SUZ_KIZ_BUFFER_QUANTITY_INVALID");
        availability.put(id, new Availability(available, System.nanoTime() + STATUS_REUSE_NANOS));
        BufferStatus status = new BufferStatus(remote, available, "REJECTED".equalsIgnoreCase(remote), reason);
        repository.updateOrder(id, null, remote, status.localStatus(), reason);
        return repository.findOrder(id).orElseThrow();
    }

    public OptionalInt availableCodes(long orderId) {
        Availability hint = availability.get(orderId);
        return hint != null && System.nanoTime() < hint.validUntil() ? OptionalInt.of(hint.quantity()) : OptionalInt.empty();
    }
    public void clearAvailability(long orderId) { availability.remove(orderId); }
    private record Availability(int quantity, long validUntil) { }

    private JsonArray sharedStatus(ZnackPurchaseContext context, String remoteId) throws Exception {
        String key = com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()
                + ":" + context.identity() + ":" + remoteId;
        if (STATUS_READS.size() > 2048) {
            long now = System.nanoTime();
            STATUS_READS.entrySet().removeIf(entry -> entry.getValue().result.isDone() && entry.getValue().validUntil < now);
        }
        for (;;) {
            StatusRead prior = STATUS_READS.get(key);
            if (prior != null && (!prior.result.isDone() || System.nanoTime() < prior.validUntil)) return await(prior.result).deepCopy();
            StatusRead next = new StatusRead();
            boolean owns = prior == null ? STATUS_READS.putIfAbsent(key, next) == null : STATUS_READS.replace(key, prior, next);
            if (!owns) continue;
            try {
                Settings settings = context.settings();
                JsonArray result = auth.readWithSuzRecovery(settings,
                        token -> api.orderStatus(settings.resolvedSuzBaseUrl(), token, settings.omsId(), remoteId));
                if (result == null) throw new IOException("SUZ status response is missing.");
                next.validUntil = System.nanoTime() + STATUS_REUSE_NANOS;
                next.result.complete(result.deepCopy());
                return result.deepCopy();
            } catch (Throwable failed) {
                next.result.completeExceptionally(failed);
                STATUS_READS.remove(key, next);
                if (failed instanceof Exception checked) throw checked;
                if (failed instanceof Error fatal) throw fatal;
                throw new IOException("SUZ status query failed.", failed);
            }
        }
    }

    private static JsonArray await(CompletableFuture<JsonArray> result) throws Exception {
        try { return result.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw new IOException("SUZ status query failed.", wrapped.getCause());
        }
    }

    private static final class StatusRead {
        final CompletableFuture<JsonArray> result = new CompletableFuture<>();
        volatile long validUntil;
    }

    static boolean isCredentialRejection(ZnackApiClient.ZnackApiException failure) {
        return failure.statusCode() == 400 && onlyGlobalError(failure.responseBody(), 1090);
    }
    static boolean isCapacityRejection(ZnackApiClient.ZnackApiException failure) {
        return failure.statusCode() == 400 && onlyGlobalError(failure.responseBody(), 3350);
    }
    private static boolean onlyGlobalError(String body, int expected) {
        try {
            JsonObject value = JsonParser.parseString(body).getAsJsonObject();
            if (!value.has("success") || !value.get("success").isJsonPrimitive()
                    || !value.getAsJsonPrimitive("success").isBoolean() || value.get("success").getAsBoolean()
                    || value.has("orderId") || value.has("id") || value.has("results")) return false;
            JsonArray errors = value.getAsJsonArray("globalErrors");
            if (errors == null || errors.isEmpty()) return false;
            for (JsonElement element : errors) {
                JsonObject error = element.getAsJsonObject();
                if (!error.has("errorCode") || error.get("errorCode").getAsBigDecimal().intValueExact() != expected) return false;
            }
            return true;
        } catch (RuntimeException malformed) { return false; }
    }

    public OrderReconciliation reconcile(Settings s,KizOrder local)throws Exception{
        ZnackSafety.requireSigned(s,true);
        JsonObject response=auth.readWithSuzRecovery(s,token -> api.orderList(s.resolvedSuzBaseUrl(),token,s.omsId()));
        if(response==null||hasReconciliationErrors(response)||!response.has("orderInfos")||!response.get("orderInfos").isJsonArray())throw invalidReconciliation(null);
        // The published list request is scoped by omsId. Do not invent a mandatory
        // response field; if the server supplies one, it must be the original OMS.
        if(response.has("omsId")&&!strictText(response,"omsId").equals(s.omsId()))throw invalidReconciliation(null);
        JsonArray orderInfos=response.getAsJsonArray("orderInfos");
        Instant earliest=local.createdAt().minus(RECONCILIATION_CLOCK_SKEW);
        Instant latest=local.createdAt().plus(RECONCILIATION_WINDOW);
        List<RemoteOrder> matches=new ArrayList<>();var identities=new java.util.HashSet<String>();
        for(JsonElement element:orderInfos){
            if(!element.isJsonObject())throw invalidReconciliation(null);
            JsonObject candidate=element.getAsJsonObject();if(hasReconciliationErrors(candidate))throw invalidReconciliation(null);
            String externalId=strictText(candidate,"orderId"),status=strictText(candidate,"orderStatus");
            if(!identities.add(externalId))throw invalidReconciliation(null);
            Instant created;
            try{created=Instant.ofEpochMilli(exactNonnegative(candidate,"createdTimestamp"));}
            catch(RuntimeException invalid){throw invalidReconciliation(invalid);}
            if(!candidate.has("buffers")||!candidate.get("buffers").isJsonArray()||candidate.getAsJsonArray("buffers").isEmpty())throw invalidReconciliation(null);
            int matchingQuantity=0;
            for(JsonElement bufferElement:candidate.getAsJsonArray("buffers")){
                if(!bufferElement.isJsonObject())throw invalidReconciliation(null);
                JsonObject buffer=bufferElement.getAsJsonObject();if(hasReconciliationErrors(buffer))throw invalidReconciliation(null);
                String candidateGtin=strictText(buffer,"gtin");if(!candidateGtin.matches("[0-9]{8,14}"))throw invalidReconciliation(null);
                int quantity;
                try{quantity=Math.toIntExact(exactNonnegative(buffer,"totalCodes"));}
                catch(RuntimeException invalid){throw invalidReconciliation(invalid);}
                if(local.gtin().equals(GtinNormalizer.normalize(candidateGtin)))try{matchingQuantity=Math.addExact(matchingQuantity,quantity);}
                catch(ArithmeticException invalid){throw invalidReconciliation(invalid);}
            }
            if(!created.isBefore(earliest)&&!created.isAfter(latest)&&matchingQuantity==local.quantity())matches.add(new RemoteOrder(externalId,status,created));
        }
        if(matches.isEmpty())return new OrderReconciliation(ReconciliationStatus.NOT_FOUND,null,0);
        if(matches.size()>1)return new OrderReconciliation(ReconciliationStatus.CONFLICT,null,matches.size());
        return new OrderReconciliation(ReconciliationStatus.MATCHED,matches.getFirst(),1);
    }
    private static IOException invalidReconciliation(Throwable cause){return new IOException("SUZ_ORDER_RECONCILIATION_INVALID",cause);}
    private static String strictText(JsonObject value,String field)throws IOException{
        var raw=value.get(field);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString()||raw.getAsString().isBlank())throw invalidReconciliation(null);return raw.getAsString();
    }
    private static long exactNonnegative(JsonObject value,String field){
        var raw=value.get(field);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing numeric field");
        long result=raw.getAsBigDecimal().longValueExact();if(result<0)throw new IllegalArgumentException("Negative numeric field");return result;
    }
    private static boolean hasReconciliationErrors(JsonObject value){
        for(String key:List.of("error","errorCode","errorMessage","error_message","errors","globalErrors","fieldErrors")){
            var raw=value.get(key);if(raw==null||raw.isJsonNull())continue;
            if(raw.isJsonArray()&&raw.getAsJsonArray().isEmpty()||raw.isJsonObject()&&raw.getAsJsonObject().size()==0)continue;
            if(raw.isJsonPrimitive()){var primitive=raw.getAsJsonPrimitive();if(primitive.isString()&&primitive.getAsString().isBlank()||primitive.isBoolean()&&!primitive.getAsBoolean())continue;if(key.equals("errorCode")&&(primitive.isNumber()||primitive.isString())&&primitive.getAsString().equals("0"))continue;}
            return true;
        }return false;
    }
    public enum ReconciliationStatus{MATCHED,NOT_FOUND,CONFLICT}
    public record OrderReconciliation(ReconciliationStatus status,RemoteOrder order,int candidateCount){}
    public record RemoteOrder(String externalOrderId,String remoteStatus,Instant createdAt){}
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private int integer(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0;}
    private String resolveCisType(Settings settings,String gtin)throws Exception{
        Product stored=repository.findProduct(gtin).orElseGet(()->new com.vncode.app.features.kiz.KizRegistrationIdentityRepository().product(repository.shop().shopId(),gtin).orElseThrow(()->new IllegalArgumentException("GTIN is not registered for the selected shop.")));
        if(stored.cisType()!=null)return lightIndustryCisType(stored.cisType());
        JsonElement response=api.productInfo(settings.resolvedTrueApiBaseUrl(),auth.trueApiToken(settings),gtin);
        JsonArray products=response.isJsonArray()?response.getAsJsonArray():response.getAsJsonObject().getAsJsonArray("results");
        if(products!=null)for(JsonElement element:products){
            if(!element.isJsonObject())continue;
            JsonObject product=element.getAsJsonObject();
            String candidate=text(product,"gtin","productGtin");
            if(candidate.isBlank()||!gtin.equals(GtinNormalizer.normalize(candidate)))continue;
            String cisType=Boolean.TRUE.equals(bool(product,"isKit","is_kit"))?"BUNDLE":"UNIT";
            repository.updateProductCisType(gtin,cisType);
            return cisType;
        }
        throw new IllegalStateException("Znack did not return the marking code type for GTIN "+gtin+".");
    }
    private Boolean bool(JsonObject o,String...keys){for(String key:keys)if(o.has(key)&&!o.get(key).isJsonNull()){JsonElement value=o.get(key);if(value.isJsonPrimitive()){JsonPrimitive primitive=value.getAsJsonPrimitive();if(primitive.isBoolean())return primitive.getAsBoolean();String text=primitive.getAsString();if("true".equalsIgnoreCase(text)||"1".equals(text))return true;if("false".equalsIgnoreCase(text)||"0".equals(text))return false;}}return null;}
    private String lightIndustryCisType(String value){
        String cisType=value==null?"UNIT":value.trim().toUpperCase(java.util.Locale.ROOT);
        if("UNIT".equals(cisType)||"BUNDLE".equals(cisType))return cisType;
        throw new IllegalStateException("Unsupported light-industry marking code type: "+cisType+".");
    }
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value;}
}
