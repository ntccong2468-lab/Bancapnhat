package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import java.util.List;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

/** Blocking I/O boundary. Production uses CatalogRegistrationGateway, never a mock transport. */
public interface RegistrationGateway {
    enum RemoteState { DRAFT, MODERATION, REJECTED, APPROVED, PUBLISHED }
    record Observation(String gtin, Long goodId, String feedId, RemoteState state,
                       String environment, String checkedAt, String evidenceHash, String matchedPayloadHash, String detail) {
        public Observation(String gtin,Long goodId,String feedId,RemoteState state,String environment,String checkedAt,String evidenceHash) {
            this(gtin,goodId,feedId,state,environment,checkedAt,evidenceHash,"","");
        }
    }
    record Document(long goodId, String xml) {
        public Document {
            if(goodId<=0||xml==null||xml.isBlank()||xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024)
                throw new IllegalArgumentException("V3_SIGNING_DOCUMENT_INVALID");
        }
        public String hash(){return RegistrationJob.hash(xml);}
        @Override public String toString(){return "SigningDocument[redacted]";}
    }
    Owner owner();
    List<Product> products() throws Exception;
    List<Attribute> attributes(RegistrationJob job) throws Exception;
    List<com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category> categories(RegistrationJob job,String tnved) throws Exception;
    void validate(RegistrationJob job) throws Exception;
    String allocate(RegistrationJob job) throws Exception;
    String submit(RegistrationJob job) throws Exception;
    Observation observe(RegistrationJob job) throws Exception;
    Document document(RegistrationJob job) throws Exception;
    void sign(RegistrationJob job) throws Exception;
    String previewWb(RegistrationJob job) throws Exception;
    boolean syncWb(RegistrationJob job) throws Exception;
    default boolean verifyWb(RegistrationJob job) throws Exception { return false; }
}
