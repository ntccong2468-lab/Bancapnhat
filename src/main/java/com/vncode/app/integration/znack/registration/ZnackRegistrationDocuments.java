package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.DocumentState;
import com.vncode.app.features.products.ProductDocument;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Chooses goods documents without inferring validity or substituting a different product's permit. */
public final class ZnackRegistrationDocuments {
    private ZnackRegistrationDocuments() {}
    public enum Source { PRODUCT, SHOP, NONE }
    public record Selection(List<GoodsDocument> candidates, GoodsDocument automatic,
                            Source source, boolean requiresReview,List<String> issues) {
        public Selection(List<GoodsDocument> candidates,GoodsDocument automatic,Source source,boolean requiresReview){this(candidates,automatic,source,requiresReview,List.of());}
        public Selection { candidates=List.copyOf(candidates);issues=List.copyOf(issues); }
        @Override public String toString(){ return "Selection[source="+source+", count="+candidates.size()+"]"; }
    }

    public static Selection select(ProductSnapshot snapshot, ZnackModels.Settings settings, LocalDate today) {
        // The stored type can have a UI default even when the seller has configured no paper.
        if(settings!=null&&java.util.stream.Stream.of(settings.documentNumber(),settings.documentDate())
                .anyMatch(value->value!=null&&!value.isBlank()))return configured(settings,today);
        var candidates=new LinkedHashMap<GoodsDocument,Boolean>();
        boolean productEvidence=false;
        if(snapshot!=null)for(ProductDocument document:snapshot.documents()) {
            String type=type(document.type());
            if(type.isBlank())continue;
            productEvidence=true;
            if(document.state()!=DocumentState.VALID && document.state()!=DocumentState.UNVERIFIED)continue;
            if(document.number()==null || document.number().isBlank() || document.validFrom()==null
                    || document.validFrom().isAfter(today)
                    || (document.validUntil()!=null && document.validUntil().isBefore(today)))continue;
            var goods=new GoodsDocument(type,document.number().strip(),document.validFrom().toString());
            boolean verified=document.state()==DocumentState.VALID && document.validUntil()!=null
                    && document.checkedAt()!=null && document.source()!=null && document.source().startsWith("https://");
            candidates.merge(goods,verified,Boolean::logicalOr);
        }
        if(productEvidence) {
            var list=List.copyOf(candidates.keySet());
            GoodsDocument automatic=list.size()==1 && Boolean.TRUE.equals(candidates.get(list.getFirst()))?list.getFirst():null;
            return new Selection(list,automatic,Source.PRODUCT,automatic==null);
        }
        return new Selection(List.of(),null,Source.NONE,true);
    }
    /** An explicit registration configuration takes precedence; never silently use another permit. */
    private static Selection configured(ZnackModels.Settings settings,LocalDate today){
        try {
            settings.validateDefaultGoodsDocument();
            GoodsDocument document=settings.defaultGoodsDocument();
            String kind=type(document.type());
            if(!document.complete())return configuredIssue("ZNACK_MISSING_PRODUCT_DOCUMENT");
            if(kind.isBlank())return configuredIssue("ZNACK_DOCUMENT_TYPE_UNSUPPORTED");
            LocalDate date=parseDate(document.date());
            if(date.isAfter(today))return configuredIssue("ZNACK_DOCUMENT_NOT_YET_VALID");
            if(settings.documentExpiryDate()!=null&&!settings.documentExpiryDate().isBlank()
                    &&parseDate(settings.documentExpiryDate()).isBefore(today))return configuredIssue("ZNACK_DOCUMENT_EXPIRED");
            var normalized=new GoodsDocument(kind,document.number().strip(),date.toString());
            return new Selection(List.of(normalized),normalized,Source.SHOP,false);
        }catch(IllegalArgumentException|java.time.DateTimeException invalid){
            return configuredIssue("ZNACK_DOCUMENT_REGISTRY_DATE_INVALID");
        }
    }
    private static Selection configuredIssue(String code){return new Selection(List.of(),null,Source.SHOP,true,List.of(code));}
    private static LocalDate parseDate(String value) {
        if(value.matches("\\d{2}\\.\\d{2}\\.\\d{4}"))return LocalDate.parse(value,java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        return LocalDate.parse(value);
    }
    private static String type(String value) {
        String normalized=value==null?"":value.strip().toUpperCase(Locale.ROOT);
        return switch(normalized) {
            case "2", "CONFORMITY_DECLARATION", "DECLARATION", "DECLARATION_OF_CONFORMITY", "ДЕКЛАРАЦИЯ", "ДЕКЛАРАЦИЯ О СООТВЕТСТВИИ" -> "CONFORMITY_DECLARATION";
            case "1", "CONFORMITY_CERTIFICATE", "CERTIFICATE", "CERTIFICATE_OF_CONFORMITY", "СЕРТИФИКАТ", "СЕРТИФИКАТ СООТВЕТСТВИЯ" -> "CONFORMITY_CERTIFICATE";
            default -> "";
        };
    }
}
