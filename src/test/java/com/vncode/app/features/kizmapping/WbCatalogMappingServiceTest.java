package com.vncode.app.features.kizmapping;

import static org.junit.jupiter.api.Assertions.*;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WbCatalogMappingServiceTest {
    private static final String SOURCE = "National Catalog@fixture";
    private static final String GTIN = "00000000000017";

    @Test void verifiedMatchingBarcodeVariantAndActiveDocumentCanMap() {
        var result = review(List.of(product(1, "Article", "Brown", "52")), card());
        assertEquals(1, result.mapped());
        assertTrue(result.issues().isEmpty());
    }

    @Test void sameGtinOnDifferentColorsAtTheSameSizeBlocksEveryConflictingRow() {
        var result = review(List.of(product(1, "Article", "Brown", "52"),
                product(2, "Article", "Black", "52")), card());
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_MULTIPLE_SIZES", "GTIN_MULTIPLE_SIZES"), reasons(result));
    }

    @Test void sameGtinOnDifferentModelsAtTheSameSizeBlocksEveryConflictingRow() {
        var result = review(List.of(product(1, "Article", "Brown", "52"),
                product(2, "Other article", "Brown", "52")), card());
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_MULTIPLE_SIZES", "GTIN_MULTIPLE_SIZES"), reasons(result));
    }

    @Test void sameGtinOnDifferentSizesBlocksEveryConflictingRow() {
        var result = review(List.of(product(1, "Article", "Brown", "52"),
                product(2, "Article", "Brown", "54")), card());
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_MULTIPLE_SIZES", "GTIN_MULTIPLE_SIZES"), reasons(result));
    }

    @Test void repeatedIdenticalStableVariantDoesNotInventADuplicatePhysicalProduct() {
        var row = product(1, "Article", "Brown", "52");
        var result = review(List.of(row, row), card());
        assertTrue(result.issues().isEmpty());
        assertTrue(result.mapped() > 0);
    }

    @Test void sameProvenPhysicalVariantOnTwoCardsMayShareItsGtin() {
        var result = review(List.of(product(1, "Article", "Brown", "52"),
                product(2, "Article", "Brown", "52")), card());
        assertEquals(2, result.mapped());
        assertTrue(result.issues().isEmpty());
    }

    @Test void exactBarcodeDoesNotOverrideContradictoryColorOrSize() {
        assertEquals(List.of("GTIN_COLOR_MISMATCH"),
                reasons(review(List.of(product(1, "Article", "Black", "52")), card())));
        assertEquals(List.of("GTIN_SIZE_MISMATCH"),
                reasons(review(List.of(product(1, "Article", "Brown", "54")), card())));
    }

    @Test void aDifferentKnownTnvedGroupCannotUseTheOldCard() {
        var row = product(1, "Article", "Brown", "52", "6201000000");
        var result = review(List.of(row), card());
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_TNVED_GROUP_CHANGED"), reasons(result));
    }

    @Test void expiredAndRevokedDocumentsDoNotAuthorizeMapping() {
        var expired = new CatalogProductData.Document("DECLARATION", "fixture", "2020-01-01", "2020-02-01", false, "", Map.of());
        var revoked = new CatalogProductData.Document("DECLARATION", "fixture", "2020-01-01", "", true, "", Map.of("registryStatus", "revoked"));
        for (var document : List.of(expired, revoked)) {
            var result = review(List.of(product(1, "Article", "Brown", "52")), card(SOURCE, Instant.now(), List.of(document)));
            assertEquals(0, result.mapped());
            assertEquals(List.of("GTIN_DOCUMENT_MISSING"), reasons(result));
        }
    }

    @Test void activeRegistryCertificateCanAuthorizeItsObservedVariant() {
        var document = new CatalogProductData.Document("CERTIFICATE_OF_CONFORMITY", "fixture", "2020-01-01", "", false, "", Map.of("registryStatus", "active"));
        var result = review(List.of(product(1, "Article", "Brown", "52")), card(SOURCE, Instant.now(), List.of(document)));
        assertEquals(1, result.mapped());
        assertTrue(result.issues().isEmpty());
    }

    @Test void staleAndFutureCatalogObservationsCannotBecomeFreshMappingProof() {
        for (var time : List.of(Instant.now().minusSeconds(86401), Instant.now().plusSeconds(3600))) {
            var result = review(List.of(product(1, "Article", "Brown", "52")), card(SOURCE, time, documents()));
            assertEquals(0, result.mapped());
            assertEquals(List.of("GTIN_CATALOG_STALE"), reasons(result));
        }
    }

    @Test void oldAccountScopeCannotAuthorizeTheCurrentShopMapping() {
        var result = review(List.of(product(1, "Article", "Brown", "52")), card("National Catalog@another", Instant.now(), documents()));
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_OWNER_NOT_VERIFIED"), reasons(result));
    }

    @Test void failedVerificationStaysUnknownInsteadOfPretendingTheCardDoesNotExist() {
        var rows = List.of(product(1, "Article", "Brown", "52"));
        for (var code : List.of("NETWORK_TIMEOUT", "ZNACK_AUTH_REQUIRED")) {
            var result = WbCatalogMappingService.review(rows, Map.of(), Map.of(GTIN, code), SOURCE);
            assertEquals(0, result.mapped());
            assertEquals(List.of(code.equals("ZNACK_AUTH_REQUIRED") ? "GTIN_ACCESS_DENIED" : code), reasons(result));
            assertFalse(reasons(result).contains("GTIN_CARD_NOT_FOUND"));
        }
    }

    @Test void aMatchingChecksumWithoutACatalogObservationIsNotRegisteredGtinProof() {
        var result = WbCatalogMappingService.review(List.of(product(1, "Article", "Brown", "52")), Map.of(), Map.of(), SOURCE);
        assertEquals(0, result.mapped());
        assertEquals(List.of("GTIN_CARD_NOT_FOUND"), reasons(result));
    }

    private static WbCatalogMappingService.Result review(List<ProductRow> rows, RegisteredGtin card) {
        return WbCatalogMappingService.review(rows, Map.of(GTIN, card), Map.of(), SOURCE);
    }
    private static List<String> reasons(WbCatalogMappingService.Result result) {
        return result.issues().stream().map(WbCatalogMappingService.Issue::reason).toList();
    }
    private static ProductRow product(int id, String article, String color, String size) {
        return product(id, article, color, size, "6103109000");
    }
    private static ProductRow product(int id, String article, String color, String size, String tnved) {
        return new ProductRow(new ProductKey(1, Marketplace.WILDBERRIES, Integer.toString(100 + id), Integer.toString(id)),
                "Fixture", article, "", "Sets", "Brand", color, size, List.of(GTIN), "", "", "cotton", tnved, DocumentState.VALID);
    }
    private static RegisteredGtin card() { return card(SOURCE, Instant.now(), documents()); }
    private static RegisteredGtin card(String source, Instant observed, List<CatalogProductData.Document> documents) {
        var details = new CatalogProductData(Map.of("tnved", List.of("6103109000")), List.of(), documents);
        return new RegisteredGtin(GTIN, "Article", "Brown", "52", true, true, source, observed, details);
    }
    private static List<CatalogProductData.Document> documents() {
        return List.of(new CatalogProductData.Document("DECLARATION", "fixture", "2020-01-01", "", true, "", Map.of()));
    }
}
