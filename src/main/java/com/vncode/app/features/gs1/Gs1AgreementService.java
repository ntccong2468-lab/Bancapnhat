package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Exact-document consent and durable no-replay workflow for official operator agreements.
 * Existing GS1 requests/attachments are reused; no database schema or mail transport changes. */
public final class Gs1AgreementService {
    public enum State { CONFIRMED, PENDING, RECONCILE_REQUIRED }
    public record Result(String requestId, OperatorAgreement agreement, State state) {
        @Override public String toString() { return "Gs1AgreementResult[state=" + state + "]"; }
    }
    public static final class Prepared implements AutoCloseable {
        private final String requestId, digest, scopeSource, checkpoint;
        private final OperatorAgreement agreement;
        private final boolean requiresReconciliation;
        private final byte[] pdf;
        private boolean closed;
        private Prepared(String requestId, OperatorAgreement agreement, byte[] pdf, String digest, String scopeSource, String checkpoint, boolean requiresReconciliation) {
            this.requestId = requestId; this.agreement = agreement; this.pdf = pdf.clone();
            this.digest = digest; this.scopeSource = scopeSource; this.checkpoint = checkpoint;
            this.requiresReconciliation = requiresReconciliation;
        }
        public String requestId() { return requestId; }
        public OperatorAgreement agreement() { return agreement; }
        public String digest() { return digest; }
        public String scopeSource() { return scopeSource; }
        public boolean requiresReconciliation() { return requiresReconciliation; }
        public synchronized byte[] pdf() { requireOpen(); return pdf.clone(); }
        private synchronized void requireOpen() { if (closed) throw new IllegalStateException("GS1_AGREEMENT_PREVIEW_CLOSED"); }
        @Override public synchronized void close() { Arrays.fill(pdf, (byte) 0); closed = true; }
        @Override public String toString() { return "Gs1AgreementPrepared[redacted]"; }
    }
    interface Backend {
        List<OperatorAgreement> list() throws Exception;
        byte[] pdf(OperatorAgreement agreement) throws Exception;
        String sign(byte[] pdf) throws Exception;
        String submit(String documentId, String signature) throws Exception;
        default Integer edoStatus(String edoDocumentId) throws Exception { return null; }
    }
    @FunctionalInterface interface BackendFactory {
        Backend open(Shop shop, ZnackModels.Settings settings, RegisteredGtinProof.Scope scope, Runnable guard);
    }
    private record Context(Shop shop, ZnackModels.Settings settings, RegisteredGtinProof.Scope scope,
                           String owner, String profile, Backend backend, Runnable guard) {}
    private static final Gson JSON = new Gson();
    private static final String KIND = "OPERATOR_AGREEMENT:";
    private final Gs1Repository repository;
    private final BackendFactory factory;
    public Gs1AgreementService() { this(new Gs1Repository()); }
    public Gs1AgreementService(Gs1Repository repository) { this(repository, Gs1AgreementService::live); }
    Gs1AgreementService(Gs1Repository repository, BackendFactory factory) {
        this.repository = Objects.requireNonNull(repository); this.factory = Objects.requireNonNull(factory);
    }
    public List<OperatorAgreement> list(Shop shop) throws Exception {
        Context context = context(shop); var observed = context.backend().list(); context.guard().run();
        List<OperatorAgreement> result = new ArrayList<>();
        for (var agreement : observed) {
            OperatorAgreement value = agreement;
            if (agreement.operatorPending() && agreement.edoDocumentId().isBlank()) {
                for (var request : repository.requests(context.scope().inn())) if (request.kind().equals(kind(context, agreement.id()))) {
                    try (Prepared original = restore(context, request)) {
                        if (sameDocument(original.agreement(), agreement)) value = completeEdo(context, request, agreement);
                    }
                    if (!value.operatorPending()) break;
                }
            }
            result.add(value);
        }
        context.guard().run(); return List.copyOf(result);
    }
    /** Viewing an already signed/operator-only document grants no consent and creates no request. */
    public byte[] pdf(Shop shop, OperatorAgreement selected) throws Exception {
        Context context = context(shop); Objects.requireNonNull(selected);
        var current = find(context.backend().list(), selected.id()).orElseThrow(Gs1AgreementService::unavailable);
        context.guard().run(); if (!sameDocument(current, selected)) throw unavailable();
        byte[] bytes = context.backend().pdf(current); context.guard().run();
        if (!OperatorAgreementClient.isPdf(bytes)) throw unavailable(); return bytes.clone();
    }
    public Prepared prepare(Shop shop, OperatorAgreement selected) throws Exception {
        Context context = context(shop); Objects.requireNonNull(selected);
        String kind = kind(context, selected.id());
        // A certificate refresh never permits a second POST for an uncertain previous signature.
        for (var request : repository.requests(context.scope().inn())) if (request.kind().equals(kind)
                && Set.of(Gs1Repository.State.SENDING, Gs1Repository.State.SENT, Gs1Repository.State.SUBMITTED,
                          Gs1Repository.State.RECONCILE_REQUIRED).contains(request.state()))
            return restore(context, request);
        OperatorAgreement current = find(context.backend().list(), selected.id()).orElseThrow(Gs1AgreementService::unavailable);
        context.guard().run();
        if (!current.awaitingSignature()) throw new IllegalStateException("GS1_AGREEMENT_NOT_AWAITING_SIGNATURE");
        byte[] bytes = context.backend().pdf(current); context.guard().run();
        if (!OperatorAgreementClient.isPdf(bytes)) throw unavailable();
        String digest = digest(bytes), checkpoint = checkpoint(context, current, digest);
        for (var request : repository.requests(context.scope().inn())) if (request.kind().equals(kind)
                && request.state() == Gs1Repository.State.DRAFT && request.body().equals(checkpoint)) return restore(context, request);
        var request = repository.createRequestWithAttachments(context.scope().inn(), kind, "True API",
                current.title(), checkpoint, List.of(new Gs1MailClient.Attachment("operator-agreement.pdf", "application/pdf", bytes)));
        context.guard().run(); return new Prepared(request.id(), current, bytes, digest, context.scope().source(), checkpoint, false);
    }
    public Result sign(Shop shop, Prepared reviewed, boolean consent) throws Exception {
        if (!consent) throw new IllegalArgumentException("GS1_AGREEMENT_CONSENT_REQUIRED");
        Objects.requireNonNull(reviewed).requireOpen(); Context context = context(shop);
        var request = requireRequest(context, reviewed);
        if (request.state() != Gs1Repository.State.DRAFT) return reconcile(context, request, reviewed.agreement());
        if (!context.scope().source().equals(reviewed.scopeSource())) throw new IllegalStateException("GS1_AGREEMENT_ACCOUNT_CHANGED");
        var latest = find(context.backend().list(), reviewed.agreement().id()).orElseThrow(Gs1AgreementService::unavailable);
        context.guard().run();
        if (!sameDocument(latest, reviewed.agreement())) throw unavailable();
        if (latest.sellerSigned()) {
            repository.setState(context.scope().inn(), request.id(), Gs1Repository.State.SUBMITTED);
            return new Result(request.id(), latest, State.CONFIRMED);
        }
        if (!latest.awaitingSignature()) throw unavailable();
        // An unchanged id cannot silently replace the PDF which the seller actually reviewed.
        byte[] current = context.backend().pdf(latest); context.guard().run();
        if (!MessageDigest.isEqual(digest(current).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                reviewed.digest().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IllegalStateException("GS1_AGREEMENT_DOCUMENT_CHANGED");
        if (!repository.claimSend(context.scope().inn(), request.id())) return reconcile(context, request, reviewed.agreement());
        String signature;
        try {
            context.guard().run(); signature = context.backend().sign(reviewed.pdf()); context.guard().run();
        } catch (Exception failure) {
            repository.releaseUnsent(context.scope().inn(), request.id()); throw failure;
        }
        try {
            // SENDING is durable before the first possible network mutation. No caller retries it.
            context.guard().run(); String receipt = context.backend().submit(reviewed.agreement().id(), signature); context.guard().run();
            var response = new JsonObject(); response.addProperty("edoDocumentId", receipt);
            repository.addMessage(context.scope().inn(), request.id(), "signature-receipt", true, "True API", response.toString(), false);
            repository.setState(context.scope().inn(), request.id(), Gs1Repository.State.SENT);
        } catch (Exception failure) {
            repository.setState(context.scope().inn(), request.id(), Gs1Repository.State.RECONCILE_REQUIRED); throw failure;
        }
        return reconcile(context, repository.request(context.scope().inn(), request.id()), reviewed.agreement());
    }
    public Result reconcile(Shop shop, String requestId) throws Exception {
        Context context = context(shop); var request = repository.request(context.scope().inn(), requestId);
        try (Prepared prepared = restore(context, request)) { return reconcile(context, request, prepared.agreement()); }
    }
    private Result reconcile(Context context, Gs1Repository.Request request, OperatorAgreement original) throws Exception {
        Optional<OperatorAgreement> observed = find(context.backend().list(), original.id()).filter(item -> sameDocument(item, original)); context.guard().run();
        if (observed.isPresent() && observed.get().sellerSigned()) {
            OperatorAgreement confirmed = completeEdo(context, request, observed.get());
            repository.setState(context.scope().inn(), request.id(), Gs1Repository.State.SUBMITTED);
            return new Result(request.id(), confirmed, State.CONFIRMED);
        }
        // Missing id, HTTP 200, or a different document is never evidence that this one was signed.
        if (request.state() != Gs1Repository.State.DRAFT)
            repository.setState(context.scope().inn(), request.id(), Gs1Repository.State.RECONCILE_REQUIRED);
        return new Result(request.id(), observed.orElse(original), observed.isPresent() ? State.PENDING : State.RECONCILE_REQUIRED);
    }
    private OperatorAgreement completeEdo(Context context, Gs1Repository.Request request, OperatorAgreement confirmed) throws Exception {
        // CRM commonly omits EDO status. The acknowledged EDO id is a separate, exact identity.
        if (confirmed.operatorPending() && confirmed.edoDocumentId().isBlank()) {
            String receipt = receipt(context.scope().inn(), request.id());
            if (!receipt.isBlank()) {
                Integer status = context.backend().edoStatus(receipt); context.guard().run();
                if (status != null) return confirmed.withEdoStatus(status, receipt);
            }
        }
        return confirmed;
    }
    private String receipt(String inn, String requestId) throws IOException {
        for (var message : repository.messages(inn, requestId)) if (message.outgoing() && message.id().equals("signature-receipt") && message.sender().equals("True API")) {
            try {
                JsonObject json = JsonParser.parseString(message.body()).getAsJsonObject();
                String id = json.get("edoDocumentId").getAsString();
                if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable(); return id;
            } catch (RuntimeException invalid) { throw unavailable(); }
        }
        return "";
    }
    private Gs1Repository.Request requireRequest(Context context, Prepared prepared) throws IOException {
        var request = repository.request(context.scope().inn(), prepared.requestId());
        if (!request.body().equals(prepared.checkpoint) || !request.kind().equals(kind(context, prepared.agreement().id()))) throw unavailable();
        try (Prepared stored = restore(context, request)) {
            if (!stored.digest().equals(prepared.digest()) || !Arrays.equals(stored.pdf(), prepared.pdf())) throw unavailable();
        }
        return request;
    }
    private Prepared restore(Context context, Gs1Repository.Request request) throws IOException {
        try {
            JsonObject data = JsonParser.parseString(request.body()).getAsJsonObject();
            var agreement = JSON.fromJson(data.get("agreement"), OperatorAgreement.class);
            if (!request.kind().equals(kind(context, agreement.id())) || !context.profile().equals(data.get("profile").getAsString())
                    || !context.scope().inn().equals(data.get("inn").getAsString())
                    || !"production".equals(data.get("environment").getAsString())) throw unavailable();
            var files = repository.attachments(context.scope().inn(), request.id(), "draft");
            if (files.size() != 1 || !OperatorAgreementClient.isPdf(files.getFirst().bytes())) throw unavailable();
            byte[] bytes = files.getFirst().bytes(); String digest = data.get("digest").getAsString();
            if (!digest.equals(digest(bytes))) throw unavailable(); context.guard().run();
            return new Prepared(request.id(), agreement, bytes, digest, data.get("source").getAsString(), request.body(), request.state() != Gs1Repository.State.DRAFT);
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private Context context(Shop selected) {
        LicenseRuntime.require(Permission.GS1); ListingIdentity.require(selected);
        var shop = new Shop(selected.getId(), selected.getName(), selected.getMarketplace(), selected.getClientId(), selected.getApiKey());
        var settings = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
        var scope = RegisteredGtinProof.scope(shop, settings, Instant.now()); RegisteredGtinProof.requireProduction(scope);
        String profile = profile(), owner = ListingIdentity.fingerprint(shop);
        Runnable guard = () -> {
            LicenseRuntime.require(Permission.GS1); ListingIdentity.require(shop);
            if (!profile.equals(profile()) || !owner.equals(ListingIdentity.fingerprint(shop))
                    || !scope.source().equals(RegisteredGtinProof.current(shop).source())) throw new IllegalStateException("GS1_AGREEMENT_ACCOUNT_CHANGED");
        };
        guard.run(); return new Context(shop, settings, scope, owner, profile, factory.open(shop, settings, scope, guard), guard);
    }
    private static String profile() { return ListingIdentity.hash(Path.of(System.getProperty("vncode.appdata.dir", "")).toAbsolutePath().normalize().toString()); }
    // Contracts belong to the legal enterprise, not a marketplace credential or a shop row.
    // Approval remains source-bound below, while pending attempts block duplicates across its shops.
    private static String kind(Context context, String id) { return KIND + ListingIdentity.hash(context.scope().inn() + "\nproduction\n" + id); }
    private static String checkpoint(Context context, OperatorAgreement agreement, String digest) {
        var json = new JsonObject(); json.addProperty("owner", context.owner()); json.addProperty("profile", context.profile());
        json.addProperty("inn", context.scope().inn()); json.addProperty("environment", "production");
        json.addProperty("source", context.scope().source()); json.addProperty("digest", digest); json.add("agreement", JSON.toJsonTree(agreement)); return json.toString();
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static Optional<OperatorAgreement> find(List<OperatorAgreement> agreements, String id) { return agreements.stream().filter(item -> item.id().equals(id)).findFirst(); }
    private static boolean sameDocument(OperatorAgreement left, OperatorAgreement right) {
        return left.id().equals(right.id()) && left.name().equals(right.name()) && left.fileName().equals(right.fileName())
                && left.signType() == right.signType() && left.createdAt().equals(right.createdAt());
    }
    private static IOException unavailable() { return new IOException("GS1_AGREEMENT_EVIDENCE_UNAVAILABLE"); }
    private static Backend live(Shop shop, ZnackModels.Settings settings, RegisteredGtinProof.Scope scope, Runnable guard) {
        var registration = ZnackRegistrationScope.fromSettings(settings);
        var nativeSigner = new CryptoProSignatureProvider(settings.cryptcpPath(), scope.fingerprint(), Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        var readSigner = registration.verifyingSigner(shop, ZnackSigningSession.guardReadAuthentication(shop.getId(), scope.fingerprint(), nativeSigner));
        var businessSigner = registration.verifyingSigner(shop, ZnackSigningSession.guard(shop.getId(), scope.fingerprint(), nativeSigner));
        var auth = new ZnackAuthService(new ZnackApiClient(), readSigner); var api = new OperatorAgreementClient(guard);
        return new Backend() {
            private ZnackSigningSession.MutationPermit permit;
            private String token() throws Exception {
                guard.run(); String token = auth.trueApiToken(settings); guard.run();
                String identified = auth.authenticatedParticipantInn();
                if (identified != null && !scope.inn().equals(identified)) throw new SecurityException("GS1_FOREIGN_ENTERPRISE");
                return token;
            }
            @FunctionalInterface interface Read<T> { T read(String token) throws Exception; }
            private <T> T read(Read<T> read) throws Exception {
                String token = token();
                try { var result = read.read(token); guard.run(); return result; }
                catch (OperatorAgreementClient.HttpFailure refused) {
                    if (refused.status() != 401) throw refused;
                    auth.invalidateTrueApiToken(settings, token); var result = read.read(token()); guard.run(); return result;
                }
            }
            public List<OperatorAgreement> list() throws Exception { return read(api::list); }
            public byte[] pdf(OperatorAgreement agreement) throws Exception { return read(token -> api.pdf(token, agreement)); }
            public Integer edoStatus(String id) throws Exception { return read(token -> api.outgoingStatuses(token, Set.of(id)).get(id)); }
            public String sign(byte[] pdf) throws Exception {
                guard.run(); permit = ZnackSigningSession.captureMutationPermit(shop.getId(), settings);
                var result = businessSigner.sign(pdf, ZnackSignatureContext.TRUE_API_DOCUMENT); guard.run(); permit.requireCurrent();
                if (result.cms().length == 0) throw unavailable(); return result.base64();
            }
            public String submit(String documentId, String signature) throws Exception {
                if (permit == null) throw unavailable();
                String token = token();
                return permit.execute(() -> { guard.run(); return api.submit(token, documentId, signature); });
            }
        };
    }
}
