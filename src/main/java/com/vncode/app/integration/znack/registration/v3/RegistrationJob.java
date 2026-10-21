package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Credential-free immutable snapshots. Instants are ISO text for stable SQLite/Gson storage. */
public record RegistrationJob(String id, Owner owner, Product product, RegistrationState state,
                              int revision, Data data, String updatedAt) {
    public enum Action { ALLOCATE_GTIN, SUBMIT, SIGN, SYNC_WB }
    public record Owner(int shopId, String accountHash, String environment, String scopeHash) {
        public Owner {
            if (shopId <= 0 || blank(accountHash) || blank(scopeHash)
                    || !List.of("production", "sandbox").contains(environment)) throw new IllegalArgumentException("V3_OWNER_INVALID");
        }
        public String key() { return hash(shopId + "|" + accountHash + "|" + environment + "|" + scopeHash); }
        @Override public String toString() { return "RegistrationOwner[redacted]"; }
    }
    public record Product(long nmId, long chrtId, int subjectId, String vendorCode, String title,
                          String brand, String color, String size, String russianSize,
                          List<String> barcodes, String imageUrl, List<WbCharacteristic> characteristics, String subjectName) {
        public Product(long nmId,long chrtId,int subjectId,String vendorCode,String title,String brand,String color,String size,String russianSize,
                       List<String> barcodes,String imageUrl,List<WbCharacteristic> characteristics){
            this(nmId,chrtId,subjectId,vendorCode,title,brand,color,size,russianSize,barcodes,imageUrl,characteristics,"");
        }
        public Product {
            if (nmId <= 0 || chrtId <= 0) throw new IllegalArgumentException("V3_PRODUCT_INVALID");
            barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
            characteristics = characteristics == null ? List.of() : List.copyOf(characteristics);
            subjectName=Objects.toString(subjectName,"");
        }
    }
    public record Data(Draft draft, String gtin, Long goodId, String feedId, String payload,
                       String signingXml, String signingHash, String detail, String verifiedAt, String wbPreview) {
        public Data { wbPreview=Objects.toString(wbPreview,""); }
        public static Data empty() { return new Data(null, "", null, "", "", "", "", "", "", ""); }
        public Data withGtin(String value) { return new Data(draft,value,goodId,feedId,payload,signingXml,signingHash,"",verifiedAt,wbPreview); }
        public Data withDraft(Draft value, String body) { return new Data(value,gtin,goodId,feedId,body,"","","","",""); }
        public Data withRemote(Long good, String feed, String message) { return new Data(draft,gtin,good,feed,payload,signingXml,signingHash,message,verifiedAt,wbPreview); }
        public Data withDocument(String xml, String digest) { return new Data(draft,gtin,goodId,feedId,payload,xml,digest,"",verifiedAt,wbPreview); }
        public Data withDetail(String value) { return new Data(draft,gtin,goodId,feedId,payload,signingXml,signingHash,value,verifiedAt,wbPreview); }
        public Data verified(String time) { return new Data(draft,gtin,goodId,feedId,payload,signingXml,signingHash,"",time,wbPreview); }
        public Data withWbPreview(String value) {return new Data(draft,gtin,goodId,feedId,payload,signingXml,signingHash,detail,verifiedAt,value);}
    }
    public RegistrationJob {
        Objects.requireNonNull(owner); Objects.requireNonNull(product); Objects.requireNonNull(state); Objects.requireNonNull(data);
        if (blank(id) || revision < 0) throw new IllegalArgumentException("V3_JOB_INVALID");
    }
    public RegistrationJob next(RegistrationState target, Data values) {
        if (!state.permits(target)) throw new IllegalStateException("V3_STATE_TRANSITION_INVALID");
        return new RegistrationJob(id, owner, product, target, revision + 1, values, Instant.now().toString());
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean blank(String value) { return value == null || value.isBlank(); }
}
