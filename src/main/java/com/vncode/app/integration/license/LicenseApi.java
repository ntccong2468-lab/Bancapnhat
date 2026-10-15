package com.vncode.app.integration.license;

import java.io.IOException;
import static com.vncode.app.features.license.LicenseModels.*;

public interface LicenseApi {
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
