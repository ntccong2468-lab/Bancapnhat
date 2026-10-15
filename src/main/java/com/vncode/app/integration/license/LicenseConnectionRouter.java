package com.vncode.app.integration.license;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import java.io.IOException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.vncode.app.integration.license.LicenseApi.*;

/** Select a reachable fixed origin without disclosing account or device credentials. */
final class LicenseConnectionRouter {
    record Route(HttpUrl origin, OkHttpClient client, NetworkMode mode) {}
    private final HttpUrl primary, backup;
    private final OkHttpClient automatic, compatible;
    private final Map<String, PublicKey> keys;
    private Route selected, failed;
    private long validUntil, failedUntil;
    LicenseConnectionRouter(HttpUrl primary, HttpUrl backup, OkHttpClient automatic,
                            OkHttpClient compatible, Map<String, PublicKey> keys) {
        this.primary=primary;this.backup=backup;this.automatic=automatic;this.compatible=compatible;this.keys=Map.copyOf(keys);
    }
    synchronized Route select(NetworkMode preferred) throws IOException {
        long now=System.nanoTime();
        if(selected!=null&&now<validUntil)return selected;
        NetworkProbe last=null;
        for(HttpUrl origin:backup==null?List.of(primary):List.of(primary,backup)) {
            for(NetworkMode mode:preferred==NetworkMode.COMPATIBLE
                    ?List.of(NetworkMode.COMPATIBLE,NetworkMode.AUTOMATIC):List.of(NetworkMode.AUTOMATIC,NetworkMode.COMPATIBLE)) {
                var route=new Route(origin,mode==NetworkMode.COMPATIBLE?compatible:automatic,mode);
                if(route.equals(failed)&&now<failedUntil)continue;
                last=origin.equals(primary)
                        ?LicenseNetworkDiagnostics.probe(origin,route.client(),mode,ProbeEndpoint.ACTIVATION,5)
                        :LicenseEndpointProof.probe(origin,route.client(),mode,keys);
                if(last.reachable()) {
                    selected=route;
                    validUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(origin.equals(primary)?900:60);
                    return route;
                }
            }
        }
        String code=last==null?"LICENSE_NETWORK":last.code();
        // No reachable, authenticated endpoint is a transport outage, not an account denial.
        if(!Set.of("LICENSE_NETWORK","LICENSE_DNS","LICENSE_TIMEOUT","LICENSE_TLS","LICENSE_EDGE_CHALLENGE").contains(code))code="LICENSE_NETWORK";
        throw new Failure(code,0);
    }
    synchronized void recover() {
        failed=selected;failedUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);selected=null;validUntil=0;
        automatic.connectionPool().evictAll();compatible.connectionPool().evictAll();
    }
    synchronized void reset(){selected=null;failed=null;validUntil=0;failedUntil=0;}
    NetworkProbe backupProbe(NetworkMode mode) {
        return LicenseEndpointProof.probe(backup,mode==NetworkMode.COMPATIBLE?compatible:automatic,mode,keys);
    }
}
