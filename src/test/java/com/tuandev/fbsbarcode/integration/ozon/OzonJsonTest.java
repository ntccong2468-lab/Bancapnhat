package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class OzonJsonTest {
    @Test
    void parsesLargeIdsAsTextAndRecognizesMandatoryAndOptionalMarking() {
        var response = JsonParser.parseString("""
                {"result":{"posting_number":"99123456789012345678-1",
                  "order_id":99123456789012345678,"status":"awaiting_packaging",
                  "tpl_integration_type":"ozon","multi_box_qty":1,"is_multibox":false,
                  "requirements":{"products_requiring_mandatory_mark":[987654321012345678]},
                  "optional":{"products_with_possible_mandatory_mark":[123456789012345678]},
                  "products":[
                    {"sku":987654321012345678,"offer_id":"mandatory","quantity":2,"name":"A"},
                    {"sku":123456789012345678,"offer_id":"optional","quantity":1,"name":"B"}
                  ],"available_actions":["ship"]}}
                """).getAsJsonObject();

        OzonPostingDto posting = OzonJson.parsePostingDetail(response);

        assertEquals("99123456789012345678", posting.orderId());
        assertEquals("987654321012345678", posting.items().getFirst().productId());
        assertEquals("987654321012345678", posting.items().getFirst().sku());
        assertEquals("987654321012345678", posting.requirements().mandatoryMarkProductIds().getFirst());
        assertEquals("123456789012345678", posting.requirements().optionalMarkProductIds().getFirst());
        assertTrue(posting.shipAvailable());
        assertFalse(posting.requirements().blocksPreparation());
    }

    @Test
    void failsClosedForRfbsMultiboxAndUnknownRequirements() {
        var response = JsonParser.parseString("""
                {"result":{"posting_number":"P-1","status":"awaiting_packaging",
                  "tpl_integration_type":"rfbs","multi_box_qty":2,"is_multibox":true,
                  "requirements":{"products_requiring_country":[42],"future_requirement":true},
                  "products":[{"sku":42,"quantity":1}]}}
                """).getAsJsonObject();

        OzonPostingDto posting = OzonJson.parsePostingDetail(response);

        assertTrue(posting.requirements().unsupportedRequirements().contains("products_requiring_country"));
        assertTrue(posting.requirements().unsupportedRequirements().contains("future_requirement"));
        assertTrue(posting.requirements().unsupportedRequirements().contains("non_standard_fbs"));
        assertTrue(posting.requirements().unsupportedRequirements().contains("multibox_package"));
    }
}
