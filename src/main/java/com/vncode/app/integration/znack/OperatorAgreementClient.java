package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import okhttp3.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Independent implementation of docs.crpt.ru/gismt/True_API/, sections 3.7.1–3.7.3.
 * No cookies, redirects, authenticators or transport retries may replay a signature POST. */
public final class OperatorAgreementClient {
    public static final int MAX_PDF = 10 * 1024 * 1024;
    private static final int MAX_JSON = 1024 * 1024;
    private final OkHttpClient http;
    private final HttpUrl root;
    private final Runnable scopeGuard;
    private static final class WriteAttempt { final java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean(); }

    public OperatorAgreementClient() {
        this(() -> {});
    }
    public OperatorAgreementClient(Runnable scopeGuard) {
        this(new OkHttpClient(), Objects.requireNonNull(HttpUrl.parse("https://markirovka.crpt.ru/api/v3/true-api/")), scopeGuard);
    }
    /** Loopback is accepted for deterministic transport tests; production has one pinned TLS origin. */
    public OperatorAgreementClient(OkHttpClient transport, HttpUrl root) {
        this(transport, root, () -> {});
    }
    public OperatorAgreementClient(OkHttpClient transport, HttpUrl root, Runnable scopeGuard) {
        this.root = Objects.requireNonNull(root);
        this.scopeGuard = Objects.requireNonNull(scopeGuard);
        boolean production = root.isHttps() && root.host().equals("markirovka.crpt.ru") && root.port() == 443
                && root.encodedPath().equals("/api/v3/true-api/");
        boolean loopback = root.scheme().equals("http") && Set.of("127.0.0.1", "localhost", "::1").contains(root.host());
        if ((!production && !loopback) || !root.username().isEmpty() || !root.password().isEmpty()
                || root.query() != null || root.fragment() != null) throw new IllegalArgumentException("GS1_AGREEMENT_ORIGIN_INVALID");
        var builder = transport.newBuilder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);
        builder.interceptors().clear(); builder.networkInterceptors().clear();
        builder.addNetworkInterceptor(chain -> {
            scopeGuard.run();
            WriteAttempt attempt = chain.call().request().tag(WriteAttempt.class);
            // Disabling connection retries alone does not stop OkHttp's 503/421 follow-ups.
            if (chain.call().request().method().equals("POST") && (attempt == null || !attempt.sent.compareAndSet(false, true)))
                throw new IOException("GS1_AGREEMENT_REPLAY_BLOCKED");
            return chain.proceed(chain.request());
        });
        this.http = builder.build();
    }
    public static final class HttpFailure extends IOException {
        private final int status;
        private HttpFailure(int status) { super("GS1_AGREEMENT_HTTP_" + status); this.status = status; }
        public int status() { return status; }
    }
    public List<OperatorAgreement> list(String token) throws IOException {
        var request = request(endpoint("elk/crm-api/documents"), token).get().build();
        var agreements = parse(json(exchange(request, MAX_JSON)));
        Set<String> required = new HashSet<>();
        for (var agreement : agreements) if (agreement.operatorPending() && !agreement.edoDocumentId().isBlank()) required.add(agreement.edoDocumentId());
        if (required.isEmpty()) return agreements;
        var statuses = outgoingStatuses(token, required); List<OperatorAgreement> result = new ArrayList<>();
        for (var agreement : agreements) result.add(statuses.containsKey(agreement.edoDocumentId())
                ? agreement.withEdoStatus(statuses.get(agreement.edoDocumentId()), agreement.edoDocumentId()) : agreement);
        return List.copyOf(result);
    }
    /** Official 3.7.4: only exact EDO ids count; exhausting this bounded scan leaves others unknown. */
    public Map<String, Integer> outgoingStatuses(String token, Set<String> wanted) throws IOException {
        Set<String> required = Set.copyOf(wanted);
        if (required.isEmpty()) return Map.of();
        if (required.size() > 2000 || required.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,128}"))) throw invalid();
        Map<String, Integer> matched = new HashMap<>(); Set<String> seen = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        for (int page = 0; page < 20 && matched.size() < required.size(); page++) {
            if (System.nanoTime() > deadline) break;
            var url = endpoint("elk/outgoing-documents").newBuilder().addQueryParameter("limit", "10").addQueryParameter("offset", Integer.toString(page * 10)).build();
            JsonElement answer = json(exchange(request(url, token).get().build(), MAX_JSON));
            try {
                JsonObject object = answer.getAsJsonObject();
                requireSuccess(object); int count = number(object, "count");
                JsonArray items = object.getAsJsonArray("items"); if (items == null || items.size() > 10 || count < items.size()) throw invalid();
                for (JsonElement entry : items) {
                    JsonObject item = entry.getAsJsonObject(); String id = text(item, "id"); int status = number(item, "status");
                    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !seen.add(id) || status < 0 || status > 1000) throw invalid();
                    if (required.contains(id)) matched.put(id, status);
                }
                if (items.size() < 10) break;
            } catch (RuntimeException malformed) { throw invalid(); }
        }
        return Map.copyOf(matched);
    }
    public byte[] pdf(String token, OperatorAgreement agreement) throws IOException {
        Objects.requireNonNull(agreement);
        HttpUrl url = endpoint("elk/crm-api/document").newBuilder().addQueryParameter("guid", agreement.id()).build();
        for (int hop = 0; hop < 2; hop++) {
            byte[] bytes = exchange(request(url, token).header("Accept", "*/*").get().build(), MAX_PDF);
            if (isPdf(bytes)) return bytes;
            if (hop != 0 || bytes.length > 8192) throw invalid();
            JsonElement link = json(bytes);
            if (!link.isJsonPrimitive() || !link.getAsJsonPrimitive().isString()) throw invalid();
            HttpUrl next = HttpUrl.parse(link.getAsString());
            if (next == null || !sameOrigin(next) || next.querySize() > 30 || next.fragment() != null
                    || !next.username().isEmpty() || !next.password().isEmpty()) throw invalid();
            url = next;
        }
        throw invalid();
    }
    /** Called once only after a durable SENDING checkpoint. Even HTTP 202 is still pending. */
    public String submit(String token, String documentId, String signature) throws IOException {
        if (documentId == null || !documentId.matches("[0-9]{1,64}") || signature == null
                || signature.isBlank() || signature.length() > MAX_JSON) throw invalid();
        try { if (Base64.getDecoder().decode(signature).length == 0) throw invalid(); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        var request = request(endpoint("elk/outgoing-documents/" + documentId + "/signature"), token)
                .post(RequestBody.create(signature, MediaType.get("text/plain; charset=utf-8"))).tag(WriteAttempt.class, new WriteAttempt()).build();
        JsonElement answer = json(exchange(request, MAX_JSON));
        if (!answer.isJsonObject()) throw invalid();
        JsonObject object = answer.getAsJsonObject(); requireSuccess(object);
        String id = text(object, "id");
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        return id;
    }
    private HttpUrl endpoint(String path) { return Objects.requireNonNull(root.resolve(path)); }
    private boolean sameOrigin(HttpUrl value) { return root.scheme().equals(value.scheme()) && root.host().equals(value.host()) && root.port() == value.port(); }
    private Request.Builder request(HttpUrl url, String token) throws IOException {
        LicenseRuntime.require(Permission.GS1); scopeGuard.run();
        if (!sameOrigin(url) || token == null || token.isBlank() || token.length() > 32768
                || token.chars().anyMatch(Character::isISOControl)) throw invalid();
        return new Request.Builder().url(url).header("Accept", "application/json").header("Authorization", "Bearer " + token);
    }
    private byte[] exchange(Request request, int maximum) throws IOException {
        LicenseRuntime.require(Permission.GS1); scopeGuard.run();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new HttpFailure(response.code());
            if (response.body() == null || response.body().contentLength() > maximum) throw invalid();
            byte[] bytes = response.body().byteStream().readNBytes(maximum + 1);
            if (bytes.length > maximum) throw invalid();
            LicenseRuntime.require(Permission.GS1); scopeGuard.run(); return bytes;
        } catch (HttpFailure failure) { throw failure; }
        catch (IOException failure) { throw new IOException("GS1_AGREEMENT_TRANSPORT_UNCERTAIN"); }
    }
    private static JsonElement json(byte[] bytes) throws IOException {
        try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)); }
        catch (RuntimeException malformed) { throw invalid(); }
    }
    /** The official example splits metadata and status into consecutive array entries. */
    static List<OperatorAgreement> parse(JsonElement value) throws IOException {
        try {
            JsonArray entries;
            if (value.isJsonObject()) { requireSuccess(value.getAsJsonObject()); entries = value.getAsJsonObject().getAsJsonArray("response"); }
            else entries = value.getAsJsonArray();
            if (entries == null || entries.size() > 2000) throw invalid();
            List<JsonObject> documents = new ArrayList<>();
            for (JsonElement entry : entries) {
                if (!entry.isJsonObject()) throw invalid();
                JsonObject object = entry.getAsJsonObject();
                if (object.has("document")) documents.add(object.deepCopy());
                else if (object.has("status") && !documents.isEmpty() && !documents.getLast().has("status")) {
                    JsonObject previous = documents.getLast();
                    for (String key : List.of("status", "signtype", "edoStatus")) if (object.has(key)) previous.add(key, object.get(key));
                } else throw invalid();
            }
            List<OperatorAgreement> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (JsonObject document : documents) {
                var agreement = new OperatorAgreement(text(document, "document"), text(document, "name"), text(document, "fileName"),
                        text(document, "createtime"), number(document, "status"), number(document, "signtype"),
                        document.has("edoStatus") && !document.get("edoStatus").isJsonNull() ? number(document, "edoStatus") : null, text(document, "edoDocumentId"));
                if (!ids.add(agreement.id())) throw invalid(); result.add(agreement);
            }
            return List.copyOf(result);
        } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static int number(JsonObject value, String key) throws IOException {
        String number = text(value, key); if (!number.matches("-?[0-9]{1,9}")) throw invalid(); return Integer.parseInt(number);
    }
    private static String text(JsonObject value, String key) {
        JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() ? found.getAsString() : "";
    }
    private static void requireSuccess(JsonObject value) throws IOException {
        if (value.has("error") || value.has("errors") || value.has("error_message")
                || value.has("status") && !Set.of("200", "201", "202").contains(text(value, "status"))) throw invalid();
    }
    public static boolean isPdf(byte[] value) { return value != null && value.length >= 5 && value.length <= MAX_PDF
            && value[0] == '%' && value[1] == 'P' && value[2] == 'D' && value[3] == 'F' && value[4] == '-'; }
    private static IOException invalid() { return new IOException("GS1_AGREEMENT_RESPONSE_INVALID"); }
}
