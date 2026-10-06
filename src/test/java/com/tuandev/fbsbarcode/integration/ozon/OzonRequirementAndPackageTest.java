package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OzonRequirementAndPackageTest {
    @Test
    void buildsOneCompleteV4ShipRequestForManyItemsAndQuantities() {
        OzonPostingDto posting = posting(
                new OzonRequirements(List.of(), List.of(), List.of()),
                List.of(item(0, "101", "sku-a", 2), item(1, "102", "sku-b", 3)));

        var request = OzonPackageBuilder.singleCompletePackage(posting);

        assertEquals("POST-1", request.get("posting_number").getAsString());
        var products = request.getAsJsonArray("packages")
                .get(0).getAsJsonObject().getAsJsonArray("products");
        assertEquals(2, products.size());
        assertEquals(101, products.get(0).getAsJsonObject().get("product_id").getAsLong());
        assertEquals(2, products.get(0).getAsJsonObject().get("quantity").getAsInt());
        assertEquals(102, products.get(1).getAsJsonObject().get("product_id").getAsLong());
        assertEquals(3, products.get(1).getAsJsonObject().get("quantity").getAsInt());
        assertFalse(request.getAsJsonObject("with").get("additional_data").getAsBoolean());
    }

    @Test
    void mergesDuplicateProductRowsIntoTheSinglePackage() {
        OzonPostingDto posting = posting(
                new OzonRequirements(List.of(), List.of(), List.of()),
                List.of(item(0, "101", "sku-a", 2), item(1, "101", "sku-a", 3)));

        var products = OzonPackageBuilder.singleCompletePackage(posting)
                .getAsJsonArray("packages").get(0).getAsJsonObject().getAsJsonArray("products");

        assertEquals(1, products.size());
        assertEquals(5, products.get(0).getAsJsonObject().get("quantity").getAsInt());
    }

    @Test
    void everyProductRequiresMappingUnlessExplicitlyExemptAndOzonMandatoryWins() {
        OzonPostingDto mandatory = posting(
                new OzonRequirements(List.of("101"), List.of(), List.of()),
                List.of(item(0, "101", "sku-a", 2)));
        assertThrows(OzonRequirementGuard.MissingMappingException.class,
                () -> OzonRequirementGuard.plan(mandatory, Map.of()));

        OzonPostingDto optional = posting(
                new OzonRequirements(List.of(), List.of("101"), List.of()),
                List.of(item(0, "101", "sku-a", 2)));
        assertThrows(OzonRequirementGuard.MissingMappingException.class,
                () -> OzonRequirementGuard.plan(optional, Map.of()));
        assertEquals(2, OzonRequirementGuard.plan(optional, Map.of("sku-a", "04600000000001")).exemplarCount());

        OzonPostingDto notMarkedByOzon = posting(
                new OzonRequirements(List.of(), List.of(), List.of()),
                List.of(item(0, "101", "sku-a", 2)));
        assertThrows(OzonRequirementGuard.MissingMappingException.class,
                () -> OzonRequirementGuard.plan(notMarkedByOzon, Map.of()));
        assertEquals(2, OzonRequirementGuard.plan(
                notMarkedByOzon, Map.of("sku-a", "04600000000001")).exemplarCount());
        assertEquals(0, OzonRequirementGuard.plan(
                notMarkedByOzon, Map.of(), Set.of("sku-a")).exemplarCount());
        assertThrows(OzonRequirementGuard.MissingMappingException.class,
                () -> OzonRequirementGuard.plan(mandatory, Map.of(), Set.of("sku-a")));
    }

    @Test
    void onePhysicalUnitProducesOneKizEvenIfOzonListsItInMultipleRequirementGroups() {
        OzonPostingDto posting = posting(
                new OzonRequirements(List.of("101"), List.of("101"), List.of()),
                List.of(item(0, "101", "sku-a", 1)));

        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(
                posting, Map.of("sku-a", "04600000000001"));

        assertEquals(1, plan.items().size());
        assertEquals(1, plan.exemplarCount());
    }

    @Test
    void unsupportedRequirementBlocksPreparationAndShipping() {
        OzonPostingDto posting = posting(
                new OzonRequirements(List.of(), List.of(), List.of("multibox_package")),
                List.of(item(0, "101", "sku-a", 1)));
        assertThrows(OzonRequirementGuard.UnsupportedRequirementException.class,
                () -> OzonRequirementGuard.plan(posting, Map.of()));
    }

    @Test
    void unknownActionContainingShipDoesNotEnableMutation() {
        OzonPostingDto posting = new OzonPostingDto(
                "POST-1", "", "", "awaiting_packaging", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()),
                List.of("unship_preview"), false, List.of(item(0, "101", "sku-a", 1)));

        assertFalse(posting.canShip());
    }

    private static OzonPostingDto posting(OzonRequirements requirements, List<OzonPostingItemDto> items) {
        return new OzonPostingDto("POST-1", "ORDER-1", "ORDER-1", "awaiting_packaging", "", "1",
                "2026-08-18T00:00:00Z", "", "", "", requirements, List.of("ship"), true, items);
    }

    private static OzonPostingItemDto item(int index, String productId, String sku, int quantity) {
        return new OzonPostingItemDto(index, productId, sku, "offer-" + index, "Item", quantity, "RUB", "1");
    }
}
