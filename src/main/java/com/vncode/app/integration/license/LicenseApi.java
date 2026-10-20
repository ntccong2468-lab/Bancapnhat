package com.vncode.app.integration.license;

import java.io.IOException;
import static com.vncode.app.features.license.LicenseModels.*;

public interface LicenseApi {
    enum NetworkMode { AUTOMATIC, COMPATIBLE }
    enum ProbeEndpoint { CONFIG, ACTIVATION, BACKUP }
    record NetworkProbe(NetworkMode mode, ProbeEndpoint endpoint, boolean reachable, int httpStatus,
                        String code, String phase, String protocol, long elapsedMillis) {}
    record NetworkReport(java.util.List<NetworkProbe> probes) {
        public NetworkReport { probes = java.util.List.copyOf(probes); }
    }
    default boolean supportsNetworkDiagnostics() { return false; }
    default NetworkMode networkMode() { return NetworkMode.AUTOMATIC; }
    default void setNetworkMode(NetworkMode mode) throws IOException { throw new IOException("LICENSE_NETWORK_SETTINGS_FAILED"); }
    default NetworkReport checkConnection() throws IOException { throw new IOException("LICENSE_NETWORK_CHECK_FAILED"); }
    /** A complete operation must obtain a fresh challenge; this never replays a sent proof. */
    default boolean recoverConnection() { return false; }
    record Registration(String token,String email,String status) { @Override public String toString(){return "Registration[redacted]";} }
    record RegistrationStatus(String email,String status) { @Override public String toString(){return "RegistrationStatus[redacted]";} }
    record Challenge(String id,String message,long expiresAt) { @Override public String toString(){return "Challenge[redacted]";} }
    record Grant(String accessToken,Account account,Envelope envelope,long onlineValidUntil) { @Override public String toString(){return "Grant[redacted]";} }
    Registration start(String email) throws IOException;
    Registration enroll(String code) throws IOException;
    RegistrationStatus verify(String token,String otp) throws IOException;
    RegistrationStatus status(String token) throws IOException;
    Challenge challenge(String token,String publicKey,String action) throws IOException;
    Grant activate(String token,Challenge challenge,String publicKey,String signature,String code) throws IOException;
    Grant renew(String token,Challenge challenge,String publicKey,String signature) throws IOException;
    final class Failure extends IOException {
        private final String code;private final int status;
        public Failure(String code,int status){super(code);this.code=code;this.status=status;}
        public String code(){return code;}public int status(){return status;}
        public boolean transientFailure(){return status==0&&java.util.Set.of("LICENSE_NETWORK","LICENSE_TLS","LICENSE_DNS","LICENSE_TIMEOUT","LICENSE_EDGE_CHALLENGE").contains(code)||status==429||status>=500&&!code.equals("SIGNING_NOT_CONFIGURED")&&!code.equals("LICENSE_PROTOCOL_INVALID");}
    }
}
