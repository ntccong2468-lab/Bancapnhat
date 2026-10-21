package com.vncode.app.features.products;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Marketplace barcodes and a Catalog observation have different provenance, even when their text matches. */
public record ProductVariant(String id,String sellerArticle,String color,String size,String gtin,boolean gtinVerified,
                             String russianSize,List<String> barcodes,GtinEvidence gtinEvidence) {
    public enum Verification { UNVERIFIED, HISTORICAL, VERIFIED }
    public record GtinEvidence(Verification status,String source,Instant checkedAt,String observationHash,
                               String catalogId,String catalogStatus) {
        public static final GtinEvidence UNVERIFIED=new GtinEvidence(Verification.UNVERIFIED,"",null,"","","");
        public static final GtinEvidence HISTORICAL=new GtinEvidence(Verification.HISTORICAL,"",null,"","","");
        public GtinEvidence {
            status=Objects.requireNonNullElse(status,Verification.UNVERIFIED);
            source=Objects.toString(source,"");observationHash=Objects.toString(observationHash,"");
            catalogId=Objects.toString(catalogId,"");catalogStatus=Objects.toString(catalogStatus,"");
        }
        public boolean current(){
            var now=Instant.now();
            return status==Verification.VERIFIED&&source.matches("National Catalog@[0-9a-f]{64}")
                    &&observationHash.matches("[0-9a-f]{64}")&&checkedAt!=null
                    &&!checkedAt.isBefore(now.minusSeconds(86400))&&!checkedAt.isAfter(now.plusSeconds(300));
        }
        public GtinEvidence historical(){return new GtinEvidence(Verification.HISTORICAL,source,checkedAt,observationHash,catalogId,catalogStatus);}
    }
    public ProductVariant(String id,String sellerArticle,String color,String size,String gtin,boolean gtinVerified){this(id,sellerArticle,color,size,gtin,gtinVerified,null);}
    public ProductVariant(String id,String sellerArticle,String color,String size,String gtin,boolean gtinVerified,String russianSize){this(id,sellerArticle,color,size,gtin,gtinVerified,russianSize,null,null);}
    public ProductVariant {
        gtin=Objects.toString(gtin,"");
        // Old JSON put a barcode in gtin. Migrate its representation on read; never rewrite its digits.
        boolean legacy=gtinEvidence==null;
        barcodes=barcodes==null?(gtin.isBlank()?List.of():List.of(gtin)):List.copyOf(barcodes);
        gtinEvidence=gtinEvidence==null?(gtinVerified&&!gtin.isBlank()?GtinEvidence.HISTORICAL:GtinEvidence.UNVERIFIED):gtinEvidence;
        if(legacy&&!gtinVerified)gtin="";
        if(gtinEvidence.status()==Verification.VERIFIED&&(gtin.isBlank()||!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin)||!gtinEvidence.current()))gtinEvidence=gtinEvidence.historical();
        gtinVerified=gtinVerified&&!gtin.isBlank()&&com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin)&&gtinEvidence.current();
    }
}
