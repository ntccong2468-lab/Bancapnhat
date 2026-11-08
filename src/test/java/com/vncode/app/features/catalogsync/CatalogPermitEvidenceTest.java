package com.vncode.app.features.catalogsync;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPermitEvidenceTest {
    private static CatalogProductData.Document document(String type,String from,String until,boolean endless,String status){
        return new CatalogProductData.Document(type,"SYNTHETIC-PERMIT",from,until,endless,"",Map.of("registryStatus",status));
    }
    private static CatalogProductData data(CatalogProductData.Document... documents){return new CatalogProductData(Map.of(),List.of(),List.of(documents));}
    static Stream<Arguments> validity(){
        String yesterday=LocalDate.now().minusDays(1).toString(),tomorrow=LocalDate.now().plusDays(1).toString();
        return Stream.of(
            Arguments.of(document("DECLARATION","2020-01-01",tomorrow,false,""),""),
            Arguments.of(document("DECLARATION","03.02.2020","04.05.2999",false,""),""),
            Arguments.of(document("DECLARATION","2020-01-01","",true,""),""),
            Arguments.of(document("DECLARATION","2020-01-01","",false,"active"),""),
            Arguments.of(document("DECLARATION","2020-01-01",yesterday,false,"active"),"GTIN_DOCUMENT_EXPIRED"),
            Arguments.of(document("DECLARATION","2020-01-01",yesterday,true,"active"),"GTIN_DOCUMENT_EXPIRED"),
            Arguments.of(document("DECLARATION","2020-01-01",tomorrow,false,"revoked"),"GTIN_DOCUMENT_INACTIVE"),
            Arguments.of(document("DECLARATION",tomorrow,"",true,"active"),"GTIN_DOCUMENT_NOT_YET_VALID"),
            Arguments.of(document("DECLARATION","31.02.2020","",true,"active"),"GTIN_DOCUMENT_DATE_INVALID"),
            Arguments.of(document("DECLARATION","2020-01-01","2027-02-30",true,"active"),"GTIN_DOCUMENT_DATE_INVALID"),
            Arguments.of(document("DECLARATION","02/03/2020","",true,"active"),"GTIN_DOCUMENT_DATE_INVALID"),
            Arguments.of(document("DECLARATION","","",true,"active"),"GTIN_DOCUMENT_INCOMPLETE"),
            Arguments.of(document("UNKNOWN","2020-01-01",tomorrow,false,"active"),"GTIN_DOCUMENT_TYPE_UNSUPPORTED"),
            Arguments.of(document("DECLARATION","2020-01-01","",false,""),"GTIN_DOCUMENT_VALIDITY_UNKNOWN")
        );
    }
    @ParameterizedTest @MethodSource("validity")
    void datesAndStatusesExplainWhyObservedPaperIsUnusable(CatalogProductData.Document document,String reason){
        assertEquals(reason,CatalogPermitEvidence.problem(data(document)));
        assertEquals(reason.isBlank(),CatalogPermitEvidence.active(document));
    }
    @Test void unresolvedRegistryKeepsThePaperVisibleButNeverAuthorizesMapping(){
        var paper=document("DECLARATION","2020-01-01","",true,"");
        for(String status:List.of("REGISTRY_DOCUMENTS_PENDING","REGISTRY_DOCUMENTS_UNAVAILABLE")){
            var observed=new CatalogProductData(Map.of("@documents_status",List.of(status)),List.of(),List.of(paper));
            assertEquals(status,CatalogPermitEvidence.problem(observed));assertEquals(List.of(paper),observed.documents());
        }
    }
    @Test void emptyRegistryIsDifferentFromRegistryWithUnverifiedPapers(){
        assertEquals("GTIN_DOCUMENT_MISSING",CatalogPermitEvidence.problem(CatalogProductData.EMPTY));
        assertEquals("GTIN_DOCUMENT_MISSING",CatalogPermitEvidence.problem(new CatalogProductData(Map.of("@registry_document_count",List.of("0")),List.of(),List.of())));
        assertEquals("GTIN_DOCUMENT_UNVERIFIED",CatalogPermitEvidence.problem(new CatalogProductData(Map.of("@registry_document_count",List.of("1")),List.of(),List.of())));
    }
    @Test void oneActivePaperCanCoexistWithExpiredPapersWithoutBorrowingTheirValidity(){
        var expired=document("DECLARATION","2020-01-01","2020-02-01",false,"");
        var active=document("DECLARATION","2020-01-01","",true,"");
        assertEquals("",CatalogPermitEvidence.problem(data(expired,active)));
        assertFalse(CatalogPermitEvidence.active(expired));assertTrue(CatalogPermitEvidence.active(active));
    }
}
