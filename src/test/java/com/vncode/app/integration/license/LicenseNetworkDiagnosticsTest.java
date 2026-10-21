package com.vncode.app.integration.license;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.vncode.app.integration.license.LicenseApi.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseNetworkDiagnosticsTest {
    @TempDir Path root;
    private static MockResponse json(String body, int status) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body);
    }
    private static void probes(MockWebServer server) {
        for (int i=0;i<2;i++) {
            server.enqueue(json("{\"desktop_registration_mode\":\"gmail_admin\",\"portal_version\":\"0.3.5\"}",200));
            server.enqueue(json("{\"error\":\"AUTH_REQUIRED\"}",401));
        }
    }
    @Test void realTlsNegotiationUsesHttp2AutomaticallyAndHttp11InCompatibleModeWithoutCredentials() throws Exception {
        var cert=new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1").build();
        var serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();
        var clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        try(var server=new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(),false);
            server.start(InetAddress.getByName("127.0.0.1"),0);probes(server);
            var client=new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                    .sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).build();
            var origin=HttpUrl.get("https://127.0.0.1:"+server.getPort()+"/");
            var api=new WebLicenseApi(client,origin,root.resolve("mode.txt"));
            var report=api.checkConnection();assertEquals(4,report.probes().size());
            assertTrue(report.probes().stream().allMatch(NetworkProbe::reachable), report.toString());
            assertEquals(List.of("h2","h2","http/1.1","http/1.1"),report.probes().stream().map(NetworkProbe::protocol).toList());
            for(int i=0;i<4;i++) {
                var request=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(request);
                assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"));
                assertEquals(i%2==0?"/api/config":"/api/client/license/activate",request.getPath());
                assertEquals(i%2==0?"GET":"POST",request.getMethod());
                assertEquals(i%2==0?"":"{}",request.getBody().readUtf8());
            }
            api.setNetworkMode(NetworkMode.COMPATIBLE);
            server.enqueue(json("{\"email\":\"customer@gmail.com\",\"status\":\"approved\",\"expires_at\":null}",200));
            assertEquals("approved",api.status("A".repeat(43)).status());
            var active=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(active);
            assertEquals("Bearer "+"A".repeat(43),active.getHeader("Authorization"));
            server.enqueue(json("{\"registration_id\":\"12345678-1234-1234-1234-123456789abc\",\"registration_token\":\""+"B".repeat(43)
                    +"\",\"email\":\"customer@gmail.com\",\"status\":\"approved\",\"expires_in\":2592000}",201));
            assertEquals("approved",api.enroll("VNC"+"-1234".repeat(8)).status());
            var enrolled=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(enrolled);
            assertEquals("POST",enrolled.getMethod());assertEquals("/api/client/registration/license",enrolled.getPath());
            assertEquals("{\"code\":\"VNC"+"-1234".repeat(8)+"\"}",enrolled.getBody().readUtf8());
            assertEquals(NetworkMode.COMPATIBLE,new WebLicenseApi(client,origin,root.resolve("mode.txt")).networkMode());
            assertEquals("COMPATIBLE",Files.readString(root.resolve("mode.txt")));
        }
    }
    @Test void compatibleModeStillRejectsAnUntrustedTlsCertificate() throws Exception {
        var cert=new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        var tls=new HandshakeCertificates.Builder().heldCertificate(cert).build();
        try(var server=new MockWebServer()) {
            server.useHttps(tls.sslSocketFactory(),false);server.start();
            var api=new WebLicenseApi(new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(),server.url("/"));
            var report=api.checkConnection();
            assertTrue(report.probes().stream().noneMatch(NetworkProbe::reachable));
            assertTrue(report.probes().stream().allMatch(p->p.code().equals("LICENSE_TLS")&&p.phase().equals("TLS")));
            assertEquals(0,server.getRequestCount());
        }
    }
    @Test void dnsDiagnosticsContainOnlySafePhaseAndCodeAndDoNotSwitchModeAutomatically() throws Exception {
        String secret="private-proxy-credential";
        var client=new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(host->{throw new java.net.UnknownHostException(secret);}).build();
        var api=new WebLicenseApi(client,HttpUrl.get("http://localhost:12345/"));
        var report=api.checkConnection();
        assertTrue(report.probes().stream().allMatch(p->p.code().equals("LICENSE_DNS")&&p.phase().equals("DNS")));
        assertFalse(report.toString().contains(secret));assertEquals(NetworkMode.AUTOMATIC,api.networkMode());
    }
    @Test void noResponseIsBoundedAndReportedAsResponseTimeout() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();
            // The first request stalls. Later probes must still complete and report independently.
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(json("{\"error\":\"AUTH_REQUIRED\"}",401));
            server.enqueue(json("{\"desktop_registration_mode\":\"gmail_admin\"}",200));
            server.enqueue(json("{\"error\":\"AUTH_REQUIRED\"}",401));
            var report=new WebLicenseApi(new OkHttpClient(),server.url("/")).checkConnection();
            var first=report.probes().getFirst();assertFalse(first.reachable());
            assertEquals("LICENSE_TIMEOUT",first.code());assertEquals("RESPONSE",first.phase());
            assertTrue(first.elapsedMillis()<15000);assertTrue(report.probes().subList(1,4).stream().allMatch(NetworkProbe::reachable));
        }
    }
    @Test void compatibleDnsPrefersIpv4AndRetainsIpv6OnlyNetworks() throws Exception {
        var v6=InetAddress.getByName("::1");var v4=InetAddress.getByName("127.0.0.1");
        assertEquals(List.of(v4,v6),WebLicenseApi.preferIpv4(List.of(v6,v4)));
        assertEquals(List.of(v6),WebLicenseApi.preferIpv4(List.of(v6)));
    }
    @Test void failedPreferenceWriteDoesNotSwitchClientAndInvalidPreferenceCannotChangeOrigin() throws Exception {
        var file=root.resolve("mode.txt");Files.createDirectory(file);
        var api=new WebLicenseApi(new OkHttpClient(),HttpUrl.get("http://localhost:12345/"),file);
        assertThrows(java.io.IOException.class,()->api.setNetworkMode(NetworkMode.COMPATIBLE));
        assertEquals(NetworkMode.AUTOMATIC,api.networkMode());
        Files.delete(file);Files.writeString(file,"https://other-host.invalid");
        assertEquals(NetworkMode.AUTOMATIC,new WebLicenseApi(new OkHttpClient(),HttpUrl.get("http://localhost:12345/"),file).networkMode());
    }
    @Test void diagnosticsNeverFollowRedirectsToAnotherOrigin() throws Exception {
        try(var server=new MockWebServer();var other=new MockWebServer()) {
            server.start();other.start();
            for(int i=0;i<4;i++)server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location",other.url("/collect")));
            var report=new WebLicenseApi(new OkHttpClient(),server.url("/")).checkConnection();
            assertTrue(report.probes().stream().allMatch(p->p.code().equals("LICENSE_REDIRECT_REJECTED")));
            assertEquals(0,other.getRequestCount());
        }
    }
}
