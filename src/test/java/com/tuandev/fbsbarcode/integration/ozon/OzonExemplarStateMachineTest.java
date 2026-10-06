package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OzonExemplarStateMachineTest {
    @Test
    void acceptsOnlyDurableForwardAndReconciliationTransitions() {
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.CREATED, OzonExemplarJobStage.RESERVED));
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.VALIDATED, OzonExemplarJobStage.SET_PENDING));
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.SET_PENDING, OzonExemplarJobStage.RECONCILE_REQUIRED));
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.RECONCILE_REQUIRED, OzonExemplarJobStage.ACCEPTED));
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.VALIDATED, OzonExemplarJobStage.ACCEPTED));
        assertTrue(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.REJECTED, OzonExemplarJobStage.CREATED));
        assertFalse(OzonExemplarStateMachine.canTransition(
                OzonExemplarJobStage.ACCEPTED, OzonExemplarJobStage.RESERVED));
        assertThrows(IllegalStateException.class, () -> OzonExemplarStateMachine.requireTransition(
                OzonExemplarJobStage.RESERVED, OzonExemplarJobStage.ACCEPTED));
    }

    @Test
    void consumesOnlyWhenEveryMarkPassedAndPostingIsShipAvailable() {
        var accepted = OzonExemplarJson.status(JsonParser.parseString("""
                {"status":"ship_available","products":[{"exemplars":[
                  {"exemplar_id":11,"marks":[{"check_status":"passed"}]},
                  {"exemplar_id":12,"marks":[{"check_status":"passed"}]}
                ]}]}
                """).getAsJsonObject(), 2);
        assertTrue(accepted.accepted());

        var pending = OzonExemplarJson.status(JsonParser.parseString("""
                {"status":"validation_in_process","products":[{"exemplars":[
                  {"exemplar_id":11,"marks":[{"check_status":"passed"}]}
                ]}]}
                """).getAsJsonObject(), 1);
        assertFalse(pending.accepted());

        var rejected = OzonExemplarJson.status(JsonParser.parseString("""
                {"products":[{"valid":false,"exemplars":[{"valid":false}]}]}
                """).getAsJsonObject(), 1);
        assertTrue(rejected.rejected());
    }

    @Test
    void productValidityCannotSubstituteForEveryRemoteMarkPassing() {
        var incomplete = OzonExemplarJson.status(JsonParser.parseString("""
                {"status":"ship_available","products":[{"valid":true,"exemplars":[
                  {"valid":true,"marks":[{"check_status":"passed"}]},
                  {"valid":true,"marks":[]}
                ]}]}
                """).getAsJsonObject(), 2);

        assertFalse(incomplete.allMarksPassed());
        assertFalse(incomplete.accepted());
    }

    @Test
    void validationRequiresOneValidExemplarPerExpectedCodeAndNoErrors() {
        var incomplete = OzonExemplarJson.validation(JsonParser.parseString("""
                {"products":[{"valid":true,"exemplars":[
                  {"valid":true,"marks":[{"valid":true}]}
                ]}]}
                """).getAsJsonObject(), 2);
        assertFalse(incomplete.allMarksPassed());

        var errored = OzonExemplarJson.validation(JsonParser.parseString("""
                {"products":[{"valid":true,"exemplars":[
                  {"valid":true,"errors":["mark_invalid"],"marks":[{"valid":true}]}
                ]}]}
                """).getAsJsonObject(), 1);
        assertTrue(errored.rejected());
        assertFalse(errored.allMarksPassed());
    }

    @Test
    void buildsCurrentV6PayloadWithoutPuttingCodesInLogsOrCreateRequest() {
        OzonPostingDto posting = new OzonPostingDto("POST-1", "", "", "awaiting_packaging", "", "", "", "",
                "", "", new OzonRequirements(List.of("101"), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "101", "101", "offer", "Item", 1, "RUB", "1")));
        var create = OzonExemplarService.createRequest(posting);
        assertEquals("POST-1", create.get("posting_number").getAsString());
        assertFalse(create.has("products"));

        String rawCode = "010460123456789021SERIAL";
        var binding = new OzonExemplarJobRepository.KizBinding(0, "101", "7001", 0, 9L, rawCode);
        var set = OzonExemplarService.exemplarPayload("POST-1", List.of(binding), true);
        var exemplar = set.getAsJsonArray("products").get(0).getAsJsonObject()
                .getAsJsonArray("exemplars").get(0).getAsJsonObject();
        assertFalse(set.has("multi_box_qty"));
        assertEquals(7001, exemplar.get("exemplar_id").getAsLong());
        assertFalse(exemplar.has("is_gtd_absent"));
        assertFalse(exemplar.has("is_rnpt_absent"));
        assertEquals("mandatory_mark", exemplar.getAsJsonArray("marks").get(0).getAsJsonObject()
                .get("mark_type").getAsString());
        assertFalse(binding.toString().contains(rawCode));
    }

    @Test
    void ozonPayloadRemovesOnlyLeadingGroupSeparatorAndKeepsTheGs1Data() {
        String rawCode = "\u001d010460123456789021SERIAL\u001d91ABCD\u001d92SIGNATURE";
        var binding = new OzonExemplarJobRepository.KizBinding(0, "101", "7001", 0, 9L, rawCode);

        String mark = OzonExemplarService.exemplarPayload("POST-1", List.of(binding), true)
                .getAsJsonArray("products").get(0).getAsJsonObject()
                .getAsJsonArray("exemplars").get(0).getAsJsonObject()
                .getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString();

        assertEquals("010460123456789021SERIAL\u001d91ABCD\u001d92SIGNATURE", mark);
    }

    @Test
    void matchesRemoteExemplarIdsByProductInsteadOfResponseOrder() {
        OzonRequirementGuard.PreparationPlan plan = new OzonRequirementGuard.PreparationPlan(
                "POST-1",
                List.of(
                        new OzonRequirementGuard.RequiredItem(0, "101", "sku-1", "04600000000001", 1, true),
                        new OzonRequirementGuard.RequiredItem(1, "202", "sku-2", "04600000000002", 1, true)));
        var response = JsonParser.parseString("""
                {"result":{"products":[
                  {"product_id":202,"exemplars":[{"exemplar_id":8002}]},
                  {"product_id":101,"exemplars":[{"exemplar_id":7001}]}
                ]}}
                """).getAsJsonObject();

        assertEquals(List.of("7001", "8002"), OzonExemplarService.remoteExemplarIds(response, plan));
        assertEquals(Map.of("101", List.of("7001"), "202", List.of("8002")),
                OzonExemplarJson.exemplarIdsByProduct(response));
    }
}
