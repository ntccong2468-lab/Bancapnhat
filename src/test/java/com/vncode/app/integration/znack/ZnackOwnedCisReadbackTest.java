package com.vncode.app.integration.znack;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;
import static com.vncode.app.integration.znack.ZnackCisReadbackTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic CIS/owners only. Strict legacy purchase reconciliation must prove current ownership. */
class ZnackOwnedCisReadbackTest {
    private static final String OWNER = "1234567890";
    private static final String FOREIGN_OWNER = "0987654321";

    private static JsonObject owned(String cis, String status, String owner) {
        var result = row(cis, GTIN, status);
        result.getAsJsonObject("cisInfo").addProperty("ownerInn", owner);
        return result;
    }

    private static void unconfirmed(JsonElement response, String expectedOwner) {
        var result = ZnackCisReadback.inspectOwned(response, codes(), GTIN, expectedOwner);
        assertEquals(0, result.introduced(), "Unknown or foreign ownership cannot confirm any purchased codes");
        assertEquals(0, result.applied());
        assertEquals(codes().size(), result.pending());
        assertNotNull(result.reason());
        assertFalse(result.reason().isBlank());
    }

    @ParameterizedTest @ValueSource(strings = {OWNER, "123456789012"})
    void exactTenOrTwelveDigitOwnerAndReorderedCisConfirmTheWholeSet(String owner) {
        var response = rows(owned(SECOND, "INTRODUCED", owner), owned(FIRST, "INTRODUCED", owner));
        var result = ZnackCisReadback.inspectOwned(response, codes(), GTIN, owner);
        assertEquals(2, result.introduced());
        assertEquals(0, result.applied());
        assertEquals(0, result.pending());
        assertNull(result.reason());
    }

    @Test void exactOwnedAppliedCodesRemainAppliedRatherThanCirculating() {
        var result = ZnackCisReadback.inspectOwned(rows(owned(FIRST, "APPLIED", OWNER),
                owned(SECOND, "APPLIED", OWNER)), codes(), GTIN, OWNER);
        assertEquals(2, result.applied());
        assertEquals(0, result.introduced());
        assertEquals(0, result.pending());
    }

    static Stream<Object[]> invalidOwnership() {
        return Stream.of(
                new Object[]{"missing owner", null},
                new Object[]{"null owner", JsonNull.INSTANCE},
                new Object[]{"foreign owner", new JsonPrimitive(FOREIGN_OWNER)},
                new Object[]{"numeric owner", new JsonPrimitive(1234567890L)},
                new Object[]{"boolean owner", new JsonPrimitive(true)},
                new Object[]{"object owner", new JsonObject()},
                new Object[]{"array owner", new JsonArray()},
                new Object[]{"empty owner", new JsonPrimitive("")},
                new Object[]{"padded owner", new JsonPrimitive(" " + OWNER + " ")},
                new Object[]{"non-digit owner", new JsonPrimitive("123456789x")}
        );
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidOwnership")
    void oneCodeWithoutExactStringOwnerBlocksWholeBatchConfirmation(String name, JsonElement value) {
        var first = owned(FIRST, "INTRODUCED", OWNER);
        var info = first.getAsJsonObject("cisInfo");
        if (value == null) info.remove("ownerInn"); else info.add("ownerInn", value);
        unconfirmed(rows(first, owned(SECOND, "INTRODUCED", OWNER)), OWNER);
    }

    @Test void allForeignOrMixedOwnersCannotAuthorizeLocalStock() {
        unconfirmed(rows(owned(FIRST, "INTRODUCED", FOREIGN_OWNER),
                owned(SECOND, "INTRODUCED", FOREIGN_OWNER)), OWNER);
        unconfirmed(rows(owned(FIRST, "INTRODUCED", OWNER),
                owned(SECOND, "INTRODUCED", FOREIGN_OWNER)), OWNER);
    }

    static Stream<String> invalidExpectedOwners() {
        return Stream.of(null, "", " ", "owner", "123456789", "12345678901",
                "1234567890123", "123456789x", " " + OWNER, OWNER + " ");
    }

    @ParameterizedTest @MethodSource("invalidExpectedOwners")
    void missingOrMalformedExpectedIdentityCannotBeUsedAsOwnershipProof(String expectedOwner) {
        unconfirmed(rows(owned(FIRST, "INTRODUCED", OWNER), owned(SECOND, "INTRODUCED", OWNER)), expectedOwner);
    }

    @Test void exactOwnerDoesNotOverrideDuplicateOrForeignCisIdentity() {
        unconfirmed(rows(owned(FIRST, "INTRODUCED", OWNER), owned(FIRST, "INTRODUCED", OWNER)), OWNER);
        var differentGtin = owned(FIRST, "INTRODUCED", OWNER);
        differentGtin.getAsJsonObject("cisInfo").addProperty("gtin", "04601234567891");
        unconfirmed(rows(differentGtin, owned(SECOND, "INTRODUCED", OWNER)), OWNER);
    }

    @Test void legacyStatusInspectionStillAcceptsExactCodesWithoutOptionalOwnerMetadata() {
        var response = rows(row(FIRST, GTIN, "INTRODUCED"), row(SECOND, GTIN, "INTRODUCED"));
        var existing = ZnackCisReadback.inspect(response, codes(), GTIN);
        assertEquals(2, existing.introduced());
        assertEquals(0, existing.pending());
        unconfirmed(response, OWNER);
    }
}
