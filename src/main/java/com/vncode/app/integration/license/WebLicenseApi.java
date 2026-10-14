package com.vncode.app.integration.license;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseJson;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.vncode.app.features.license.LicenseModels.Account;
import static com.vncode.app.features.license.LicenseModels.Envelope;

/** Passwordless Portal transport. A returned grant is untrusted until its lease is verified. */
public final class WebLicenseApi implements LicenseApi {
    private static final HttpUrl PRODUCTION = HttpUrl.get("https://vncode.id.vn/");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY_BYTES = 65536;
    private static final Set<String> STATUSES = Set.of("pending", "approved", "suspended");
    // Only protocol codes are surfaced. Arbitrary upstream text never reaches logs or the UI.
    private static final Set<String> ERROR_CODES = Set.of(
            "EMAIL_NOT_CONFIGURED", "GMAIL_REQUIRED", "RATE_LIMIT", "EMAIL_DELIVERY_FAILED",
            "AUTH_REQUIRED", "INVALID_CODE", "REGISTRATION_EXPIRED", "INVALID_ACTION",
            "DEVICE_REVOKED", "CHALLENGE_EXPIRED_OR_USED", "INVALID_PROOF", "SESSION_EXPIRED",
            "ACCOUNT_NOT_APPROVED", "INVALID_LICENSE", "SUBSCRIPTION_INACTIVE",
            "DEVICE_LIMIT_OR_LICENSE_CHANGED", "LICENSE_CHANGED", "SIGNING_NOT_CONFIGURED", "NOT_FOUND",
            "INVALID_JSON", "BODY_TOO_LARGE");

    private final HttpUrl origin;
    private final OkHttpClient http;

    public WebLicenseApi() {
        this(new OkHttpClient(), PRODUCTION, false);
    }

    /** Local HTTP is intentionally accessible only to tests in this package. */
    WebLicenseApi(OkHttpClient client, HttpUrl localOrigin) {
        this(client, localOrigin, true);
    }

    private WebLicenseApi(OkHttpClient client, HttpUrl origin, boolean localTest) {
        boolean local = origin != null && Set.of("localhost", "127.0.0.1", "::1").contains(origin.host());
        if (origin == null || (!PRODUCTION.equals(origin) && !(localTest && local))
                || !origin.username().isEmpty() || !origin.password().isEmpty()
                || !origin.encodedPath().equals("/") || origin.query() != null || origin.fragment() != null) {
            throw new IllegalArgumentException("LICENSE_ORIGIN_INVALID");
        }
        this.origin = origin;
        var builder = client.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE);
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        this.http = builder.build();
    }

    @Override
    public Registration start(String email) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("email", gmail(email));
        JsonObject response;
        try { response = exchange("registration/register", null, request, 201); }
        catch (Failure failure) {
            if (failure.status() == 404 || failure.status() == 401 && failure.code().equals("AUTH_REQUIRED")) throw new Failure("REGISTRATION_UPDATE_REQUIRED", 404);
            throw failure;
        }
        uuid(response, "registration_id");
        long expires = number(response, "expires_in");
        if (expires < 1 || expires > 30L * 86400) throw protocol();
        RegistrationStatus registered = registrationStatus(response);
        if (!registered.email().equals(gmail(email))) throw protocol();
        return new Registration(responseToken(string(response, "registration_token", 43)), registered.email(), registered.status());
    }

    @Override
    public Registration enroll(String code) throws IOException {
        if (code == null || !code.strip().matches("(?i)VNC(?:-[A-F0-9]{4}){8}")) throw input();
        JsonObject request = new JsonObject();
        request.addProperty("code", code.strip().toUpperCase(Locale.ROOT));
        JsonObject response;
        try { response = exchange("registration/license", null, request, 201); }
        catch (Failure failure) {
            if (failure.status() == 404 || failure.status() == 401 && failure.code().equals("AUTH_REQUIRED")) throw new Failure("REGISTRATION_UPDATE_REQUIRED", 404);
            throw failure;
        }
        uuid(response, "registration_id");
        long expires = number(response, "expires_in");
        RegistrationStatus registered = registrationStatus(response);
        if (expires < 1 || expires > 30L * 86400 || !registered.status().equals("approved")) throw protocol();
        return new Registration(responseToken(string(response, "registration_token", 43)), registered.email(), registered.status());
    }

    @Override
    public RegistrationStatus verify(String token, String otp) throws IOException {
        if (otp == null || !otp.matches("[0-9]{6}")) throw input();
        JsonObject request = new JsonObject();
        request.addProperty("code", otp);
        JsonObject response = exchange("registration/verify", token, request, 200);
        long expires = number(response, "registration_expires_in");
        if (expires < 1 || expires > 30L * 86400) throw protocol();
        return registrationStatus(response);
    }

    @Override
    public RegistrationStatus status(String token) throws IOException {
        JsonObject response = exchange("registration/status", token, null, 200);
        JsonElement expiry = response.get("expires_at");
        if (expiry == null || (!expiry.isJsonNull() && number(response, "expires_at") < 0)) {
            throw protocol();
        }
        return registrationStatus(response);
    }

    @Override
    public Challenge challenge(String token, String publicKey, String action) throws IOException {
        if (action == null || !Set.of("activate", "renew").contains(action)) throw input();
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.addProperty("public_key", base64url(publicKey, 43));
        JsonObject response = exchange("license/challenge", token, request, 200);
        String id = uuid(response, "challenge_id");
        String message = string(response, "message", 1024);
        long expires = number(response, "expires_at");
        if (message.isEmpty() || expires < 1) throw protocol();
        return new Challenge(id, message, expires);
    }

    @Override
    public Grant activate(String token, Challenge challenge, String publicKey, String signature, String code)
            throws IOException {
        if (code == null || !code.matches("(?i)VNC(?:-[A-F0-9]{4}){8}")) throw input();
        JsonObject request = proof(challenge, publicKey, signature);
        request.addProperty("code", code.toUpperCase(Locale.ROOT));
        request.addProperty("device_name", "VNCode Windows");
        return grant(exchange("license/activate", token, request, 200), true);
    }

    @Override
    public Grant renew(String token, Challenge challenge, String publicKey, String signature) throws IOException {
        return grant(exchange("license/renew", token, proof(challenge, publicKey, signature), 200), false);
    }

    private static JsonObject proof(Challenge challenge, String publicKey, String signature) throws IOException {
        if (challenge == null || !isUuid(challenge.id())) throw input();
        JsonObject request = new JsonObject();
        request.addProperty("challenge_id", challenge.id());
        request.addProperty("public_key", base64url(publicKey, 43));
        request.addProperty("signature", base64url(signature, 86));
        return request;
    }

    private static RegistrationStatus registrationStatus(JsonObject response) throws IOException {
        String email = string(response, "email", 254);
        String status = string(response, "status", 16);
        if (!isGmail(email) || !STATUSES.contains(status)) throw protocol();
        return new RegistrationStatus(email, status);
    }

    private static Grant grant(JsonObject response, boolean activation) throws IOException {
        JsonObject user = object(response, "user");
        JsonObject lease = object(response, "lease");
        String id = uuid(user, "id");
        String email = string(user, "email", 254);
        String status = string(user, "status", 16);
        JsonElement active = user.get("active");
        long expires = number(user, "expires_at");
        if (!isGmail(email) || !"approved".equals(status) || active == null || !active.isJsonPrimitive()
                || !active.getAsJsonPrimitive().isBoolean() || !active.getAsBoolean() || expires < 1) {
            throw protocol();
        }
        uuid(response, "device_id");
        long online = number(response, "online_valid_until");
        if (online < 1 || online > expires) throw protocol();
        String keyId = string(lease, "key_id", 64);
        String payload = string(lease, "payload", 16000);
        String signature = string(lease, "signature", 86);
        if (!keyId.matches("[A-Za-z0-9_-]{1,64}") || !payload.matches("[A-Za-z0-9_-]+")
                || !signature.matches("[A-Za-z0-9_-]{86}")) throw protocol();
        String access = null;
        if (activation) {
            access = responseToken(string(response, "access_token", 43));
            long lifetime = number(response, "expires_in");
            if (lifetime < 1 || lifetime > 30L * 86400) throw protocol();
        } else if (response.has("access_token")) {
            // Renewal never rotates credentials in Portal 0.3.0.
            throw protocol();
        }
        return new Grant(access, new Account(id, email, status, expires),
                new Envelope(keyId, payload, signature), online);
    }

    private JsonObject exchange(String path, String token, JsonObject body, int expectedStatus) throws IOException {
        if (token == null && !Set.of("registration/register", "registration/license").contains(path)) throw input();
        Request.Builder request = new Request.Builder().url(origin.resolve("api/client/" + path))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store");
        if (token != null) request.header("Authorization", "Bearer " + token(token));
        if (body == null) request.get();
        else {
            String json = body.toString();
            if (json.length() > 12000) throw input();
            request.post(RequestBody.create(json, JSON));
        }
        Response response;
        try { response = http.newCall(request.build()).execute(); }
        catch (IOException failure) { throw new Failure("LICENSE_NETWORK", 0); }
        try (response) {
            if (response.code() >= 300 && response.code() < 400) {
                throw new Failure("LICENSE_REDIRECT_REJECTED", response.code());
            }
            JsonObject parsed;
            try {
                ResponseBody responseBody = response.body();
                MediaType contentType = responseBody == null ? null : responseBody.contentType();
                if (contentType == null || !contentType.type().equals("application")
                        || !contentType.subtype().equals("json")
                        || (contentType.parameter("charset") != null
                        && !StandardCharsets.UTF_8.equals(contentType.charset()))) throw protocol();
                String decoded;
                try { decoded = utf8(responseBody); }
                catch (Failure failure) { throw failure; }
                catch (IOException transport) { throw new Failure("LICENSE_NETWORK", 0); }
                try { parsed = LicenseJson.object(decoded); }
                catch (IOException | RuntimeException malformed) { throw protocol(); }
            } catch (Failure failure) { throw failure; }
            if (response.code() != expectedStatus) {
                String code = "LICENSE_HTTP_ERROR";
                JsonElement error = parsed.get("error");
                if (error == null || !error.isJsonPrimitive() || !error.getAsJsonPrimitive().isString()
                        || error.getAsString().length() > 256) throw protocol();
                if (ERROR_CODES.contains(error.getAsString())) code = error.getAsString();
                throw new Failure(code, response.code());
            }
            return parsed;
        }
    }

    private static String utf8(ResponseBody body) throws IOException {
        if (body == null || body.contentLength() > MAX_BODY_BYTES) throw protocol();
        byte[] bytes;
        try (var stream = body.byteStream()) {
            bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw protocol();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) { throw protocol(); }
    }

    private static JsonObject object(JsonObject parent, String name) throws IOException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonObject()) throw protocol();
        return value.getAsJsonObject();
    }

    private static String uuid(JsonObject response, String field) throws IOException {
        String value = string(response, field, 36);
        if (!isUuid(value)) throw protocol();
        return value;
    }

    private static boolean isUuid(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String gmail(String email) throws IOException {
        if (email == null) throw input();
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!isGmail(normalized)) throw input();
        return normalized;
    }

    private static boolean isGmail(String value) {
        return value != null && value.length() <= 254
                && value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\\.com");
    }

    private static String base64url(String value, int length) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{" + length + "}")) throw input();
        return value;
    }

    private static String string(JsonObject object, String field, int maximum) throws IOException {
        try { return LicenseJson.string(object, field, maximum); }
        catch (IOException | RuntimeException malformed) { throw protocol(); }
    }

    private static long number(JsonObject object, String field) throws IOException {
        try { return LicenseJson.number(object, field); }
        catch (IOException | RuntimeException malformed) { throw protocol(); }
    }

    private static String token(String value) throws IOException { return base64url(value, 43); }
    private static String responseToken(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw protocol();
        return value;
    }
    private static Failure protocol() { return new Failure("LICENSE_PROTOCOL_INVALID", 0); }
    private static Failure input() { return new Failure("LICENSE_INPUT_INVALID", 0); }
}
