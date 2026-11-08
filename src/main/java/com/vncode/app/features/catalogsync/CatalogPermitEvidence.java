package com.vncode.app.features.catalogsync;

import java.time.*;
import java.util.*;

/** Preserve the distinction between absent paper, invalid paper and an unresolved registry read. */
public final class CatalogPermitEvidence {
    private static final Set<String> TYPES=Set.of("DECLARATION","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","CERTIFICATE_OF_REGISTRATION","CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE");
    private CatalogPermitEvidence(){}
    public static String problem(CatalogProductData data){
        for(String state:data.values("@documents_status"))if(state.startsWith("REGISTRY_DOCUMENTS_"))return state;
        if(data.documents().stream().anyMatch(CatalogPermitEvidence::active))return "";
        if(data.documents().isEmpty())return data.values("@registry_document_count").stream().anyMatch(v->!v.equals("0"))?"GTIN_DOCUMENT_UNVERIFIED":"GTIN_DOCUMENT_MISSING";
        var reasons=data.documents().stream().map(CatalogPermitEvidence::problem).toList();
        for(String code:List.of("GTIN_DOCUMENT_INACTIVE","GTIN_DOCUMENT_EXPIRED","GTIN_DOCUMENT_NOT_YET_VALID","GTIN_DOCUMENT_DATE_INVALID","GTIN_DOCUMENT_INCOMPLETE","GTIN_DOCUMENT_TYPE_UNSUPPORTED","GTIN_DOCUMENT_VALIDITY_UNKNOWN"))if(reasons.contains(code))return code;
        return "GTIN_DOCUMENT_UNVERIFIED";
    }
    public static boolean active(CatalogProductData.Document document){return problem(document).isBlank();}
    private static String problem(CatalogProductData.Document document){
        if(!TYPES.contains(document.type()))return "GTIN_DOCUMENT_TYPE_UNSUPPORTED";
        if(document.number().isBlank()||document.from().isBlank())return "GTIN_DOCUMENT_INCOMPLETE";
        String status=document.metadata().getOrDefault("registryStatus","").strip().toLowerCase(Locale.ROOT);
        if(!status.isBlank()&&!Set.of("active","valid").contains(status))return "GTIN_DOCUMENT_INACTIVE";
        try{
            var now=LocalDate.now();if(LocalDate.parse(document.from()).isAfter(now))return "GTIN_DOCUMENT_NOT_YET_VALID";
            if(!document.until().isBlank()&&LocalDate.parse(document.until()).isBefore(now))return "GTIN_DOCUMENT_EXPIRED";
            if(document.endless()||!document.until().isBlank()||Set.of("active","valid").contains(status))return "";
            return "GTIN_DOCUMENT_VALIDITY_UNKNOWN";
        }catch(DateTimeException invalid){return "GTIN_DOCUMENT_DATE_INVALID";}
    }
}
