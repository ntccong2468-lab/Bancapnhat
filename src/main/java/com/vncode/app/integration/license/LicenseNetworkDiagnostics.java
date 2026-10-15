package com.vncode.app.integration.license;

import com.vncode.app.features.license.LicenseJson;
import okhttp3.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.vncode.app.integration.license.LicenseApi.*;

/** Credential-free probes using the same TLS, DNS and proxy configuration as activation. */
final class LicenseNetworkDiagnostics {
    static NetworkReport check(HttpUrl origin, OkHttpClient automatic, OkHttpClient compatible) {
        List<NetworkProbe> probes = new ArrayList<>();
        for (NetworkMode mode : NetworkMode.values()) {
            OkHttpClient client = mode == NetworkMode.COMPATIBLE ? compatible : automatic;
            for (ProbeEndpoint endpoint : List.of(ProbeEndpoint.CONFIG, ProbeEndpoint.ACTIVATION)) probes.add(probe(origin, client, mode, endpoint));
        }
        return new NetworkReport(probes);
    }

    static NetworkProbe probe(HttpUrl origin, OkHttpClient client, NetworkMode mode, ProbeEndpoint endpoint) {
        return probe(origin, client, mode, endpoint, 10);
    }
    static NetworkProbe probe(HttpUrl origin, OkHttpClient client, NetworkMode mode, ProbeEndpoint endpoint, int seconds) {
        long started = System.nanoTime();
        Phase phase = new Phase();
        var probeClient = client.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(4, seconds)))
                .readTimeout(Duration.ofSeconds(Math.min(6, seconds))).writeTimeout(Duration.ofSeconds(Math.min(6, seconds)))
                .callTimeout(Duration.ofSeconds(seconds)).eventListener(phase).build();
        Request.Builder request = new Request.Builder().url(origin.resolve(endpoint == ProbeEndpoint.CONFIG
                        ? "api/config" : "api/client/license/activate"))
                .header("Accept", "application/json").header("Cache-Control", "no-store");
        // No Authorization, cookies, Gmail, key, device proof or redirect follows.
        if (endpoint == ProbeEndpoint.ACTIVATION) request.post(WebLicenseApi.oneShotJson("{}"));
        int status = 0;
        String protocol = "";
        String code = "LICENSE_NETWORK_CHECK_FAILED";
        boolean reachable = false;
        try (Response response = probeClient.newCall(request.build()).execute()) {
            status = response.code();
            protocol = response.protocol().toString();
            if ("challenge".equalsIgnoreCase(response.header("cf-mitigated"))) code = "LICENSE_EDGE_CHALLENGE";
            else if (status >= 300 && status < 400) code = "LICENSE_REDIRECT_REJECTED";
            else {
                ResponseBody body = response.body();
                MediaType type = body == null ? null : body.contentType();
                if (type == null || !type.type().equals("application") || !type.subtype().equals("json")
                        || type.parameter("charset") != null && !StandardCharsets.UTF_8.equals(type.charset())) {
                    code = "LICENSE_HTTP_ERROR";
                } else {
                    var json = LicenseJson.object(WebLicenseApi.utf8(body));
                    if (endpoint == ProbeEndpoint.CONFIG) {
                        reachable = status == 200 && "gmail_admin".equals(LicenseJson.string(json, "desktop_registration_mode", 32));
                    } else {
                        reachable = status == 401 && "AUTH_REQUIRED".equals(LicenseJson.string(json, "error", 64));
                    }
                    code = reachable ? "OK" : "LICENSE_HTTP_ERROR";
                }
            }
        } catch (LicenseApi.Failure invalid) { code = invalid.code(); }
        catch (IOException failure) {
            code = "LICENSE_PROTOCOL_INVALID".equals(failure.getMessage())
                    ? "LICENSE_PROTOCOL_INVALID" : WebLicenseApi.networkFailure(failure).code();
        } catch (RuntimeException invalid) { code = "LICENSE_NETWORK_CHECK_FAILED"; }
        return new NetworkProbe(mode, endpoint, reachable, status, code, phase.value, protocol,
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    static final class Phase extends EventListener {
        volatile String value = "START";
        @Override public void proxySelectStart(Call call, HttpUrl url) { value = "PROXY"; }
        @Override public void dnsStart(Call call, String domainName) { value = "DNS"; }
        @Override public void dnsEnd(Call call, String domainName, List<InetAddress> addresses) { value = "CONNECT"; }
        @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) { value = "CONNECT"; }
        @Override public void secureConnectStart(Call call) { value = "TLS"; }
        @Override public void requestHeadersStart(Call call) { value = "SEND"; }
        @Override public void requestHeadersEnd(Call call, Request request) { if (request.body() == null) value = "RESPONSE"; }
        @Override public void requestBodyEnd(Call call, long byteCount) { value = "RESPONSE"; }
        @Override public void responseHeadersStart(Call call) { value = "RESPONSE"; }
        @Override public void responseBodyStart(Call call) { value = "BODY"; }
    }
}
