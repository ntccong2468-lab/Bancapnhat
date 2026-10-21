package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OzonPerUnitPackageTest {
    @Test void twoIdenticalUnitsBecomeTwoSeparateOneUnitPackages() {
        var posting = posting(List.of(item(0, "901", "101", 2)));
        var request = OzonPackageBuilder.perUnitPackages(posting);
        assertEquals(2, OzonPackageBuilder.unitCount(posting));
        assertEquals(2, request.getAsJsonArray("packages").size());
        assertEquals(Map.of(101L, 2), quantities(request));
        assertEquals("POST-1", request.get("posting_number").getAsString());
        assertTrue(request.getAsJsonObject("with").get("additional_data").getAsBoolean(),
                "The caller needs returned child-posting products and marking information");
    }

    @Test void differentProductsStayInDifferentOneUnitPackages() {
        var posting = posting(List.of(item(0, "901", "101", 1), item(1, "902", "102", 1)));
        var request = OzonPackageBuilder.perUnitPackages(posting);
        assertEquals(Map.of(101L, 1, 102L, 1), quantities(request));
        assertEquals(2, OzonPackageBuilder.unitCount(posting));
    }

    @Test void duplicateRowsConserveEveryUnitWithoutMergingPackages() {
        var posting = posting(List.of(item(0, "901", "101", 2), item(1, "901", "101", 3),
                item(2, "902", "102", 1)));
        var request = OzonPackageBuilder.perUnitPackages(posting);
        assertEquals(6, request.getAsJsonArray("packages").size());
        assertEquals(Map.of(101L, 5, 102L, 1), quantities(request));
        assertEquals(6, OzonPackageBuilder.unitCount(posting));
    }

    @Test void shippingProductIdUsesOzonSkuRatherThanInternalCatalogProductId() {
        var posting = posting(List.of(item(0, "999999", "101", 1), item(1, "internal-catalog-id", "102", 1)));
        assertEquals(Map.of(101L, 1, 102L, 1), quantities(OzonPackageBuilder.perUnitPackages(posting)));
    }

    @Test void blankSkuCanUseThePositiveNumericPostingProductId() {
        var posting = posting(List.of(item(0, "103", "", 1)));
        assertEquals(Map.of(103L, 1), quantities(OzonPackageBuilder.perUnitPackages(posting)));
    }

    @Test void presentInvalidSkuNeverFallsBackToAnotherProductIdentity() {
        for (String invalid : List.of("0", "-1", "+101", "101.0", "1e3", "not-a-sku", "9223372036854775808")) {
            var posting = posting(List.of(item(0, "103", invalid, 1)));
            assertEquals("OZON_SPLIT_INVALID_SKU", assertThrows(IllegalStateException.class,
                    () -> OzonPackageBuilder.unitCount(posting), invalid).getMessage());
            assertThrows(IllegalStateException.class, () -> OzonPackageBuilder.perUnitPackages(posting), invalid);
        }
    }

    @Test void blankSkuAndInvalidFallbackAreRejected() {
        for (String invalid : List.of("", "0", "-1", "internal-id", "9223372036854775808")) {
            assertThrows(IllegalStateException.class,
                    () -> OzonPackageBuilder.perUnitPackages(posting(List.of(item(0, invalid, "", 1)))), invalid);
        }
    }

    @Test void largestPositiveInt64SkuIsSerializedAsANumberWithoutPrecisionLoss() {
        var request = OzonPackageBuilder.perUnitPackages(posting(List.of(item(0, "1", "9223372036854775807", 1))));
        var value = request.getAsJsonArray("packages").get(0).getAsJsonObject()
                .getAsJsonArray("products").get(0).getAsJsonObject().get("product_id");
        assertTrue(value.getAsJsonPrimitive().isNumber());
        assertEquals(Long.MAX_VALUE, value.getAsLong());
    }

    @Test void exactApplicationLimitIsAllowedAndExcessNeverBuildsAnUnboundedRequest() {
        assertEquals(100, OzonPackageBuilder.MAX_UNIT_PACKAGES);
        var boundary = posting(List.of(item(0, "901", "101", 100)));
        assertEquals(100, OzonPackageBuilder.unitCount(boundary));
        assertEquals(100, OzonPackageBuilder.perUnitPackages(boundary).getAsJsonArray("packages").size());
        for (var oversized : List.of(
                posting(List.of(item(0, "901", "101", 101))),
                posting(List.of(item(0, "901", "101", 100), item(1, "902", "102", 1))),
                posting(List.of(item(0, "901", "101", Integer.MAX_VALUE), item(1, "902", "102", Integer.MAX_VALUE))))) {
            assertEquals("OZON_SPLIT_UNIT_LIMIT", assertThrows(IllegalStateException.class,
                    () -> OzonPackageBuilder.unitCount(oversized)).getMessage());
            assertThrows(IllegalStateException.class, () -> OzonPackageBuilder.perUnitPackages(oversized));
        }
    }

    @Test void unsupportedRequirementsAndParsedMultiboxAreRejectedBeforeBuilding() {
        for (String unsupported : List.of("multibox_package", "products_requiring_weight", "products_requiring_rnpt")) {
            var requirements = new OzonRequirements(List.of(), List.of(), List.of(unsupported));
            var posting = posting(List.of(item(0, "901", "101", 2)), requirements);
            var failure = assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                    () -> OzonPackageBuilder.perUnitPackages(posting));
            assertEquals(List.of(unsupported), failure.requirements());
            assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                    () -> OzonPackageBuilder.unitCount(posting));
        }
        var multibox = OzonJson.parsePostingDetail(JsonParser.parseString("""
                {"posting_number":"POST-1","status":"awaiting_packaging","is_multibox":true,
                 "multi_box_qty":2,"products":[{"sku":101,"quantity":2}]}
                """).getAsJsonObject());
        assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                () -> OzonPackageBuilder.perUnitPackages(multibox));
    }

    @Test void nullEmptyAndNonPositiveItemQuantitiesCannotProduceRequests() {
        assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                () -> OzonPackageBuilder.perUnitPackages(null));
        assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                () -> OzonPackageBuilder.unitCount(posting(List.of())));
        for (int quantity : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> item(0, "901", "101", quantity));
        }
    }

    @Test void mandatoryAndOptionalMarkingRequirementsDoNotChangeUnitQuantities() {
        var requirements = new OzonRequirements(List.of("101"), List.of("102"), List.of());
        var posting = posting(List.of(item(0, "901", "101", 2), item(1, "902", "102", 1)), requirements);
        var request = OzonPackageBuilder.perUnitPackages(posting);
        assertEquals(Map.of(101L, 2, 102L, 1), quantities(request));
        assertFalse(request.toString().contains("exemplar"),
                "Main v4 ship packages do not accept invented per-package exemplar selectors");
    }

    private static Map<Long, Integer> quantities(JsonObject request) {
        var quantities = new LinkedHashMap<Long, Integer>();
        for (var pack : request.getAsJsonArray("packages")) {
            var products = pack.getAsJsonObject().getAsJsonArray("products");
            assertEquals(1, products.size(), "A package must contain exactly one physical item");
            var product = products.get(0).getAsJsonObject();
            assertEquals(1, product.get("quantity").getAsInt());
            assertTrue(product.get("product_id").getAsJsonPrimitive().isNumber());
            quantities.merge(product.get("product_id").getAsLong(), 1, Math::addExact);
        }
        return quantities;
    }

    private static OzonPostingItemDto item(int index, String productId, String sku, int quantity) {
        return new OzonPostingItemDto(index, productId, sku, "ARTICLE-" + index, "Fixture", quantity, "RUB", "100");
    }

    private static OzonPostingDto posting(List<OzonPostingItemDto> items) {
        return posting(items, new OzonRequirements(List.of(), List.of(), List.of()));
    }

    private static OzonPostingDto posting(List<OzonPostingItemDto> items, OzonRequirements requirements) {
        return new OzonPostingDto("POST-1", "ORDER-1", "ORDER-NO", "awaiting_packaging", "posting_created",
                "warehouse", "", "", "", "", requirements, List.of("ship"), true, items);
    }
}
