package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbApiSchemaMappingTest {
    private static final Gson GSON = new Gson();

    @Test
    void shouldDeserializeProductCardsResponse() {
        String json = """
                {
                  "cards": [
                    {
                      "nmID": 12345678,
                      "imtID": 123654789,
                      "nmUUID": "uuid-1",
                      "subjectID": 7771,
                      "subjectName": "AKF systems",
                      "vendorCode": "wb7f6mumjr1",
                      "kizMarked": true,
                      "needKiz": false,
                      "brand": "Test",
                      "title": "Product title",
                      "description": "Description",
                      "photos": [{"big":"big","c246x328":"c246","c516x688":"c516","square":"square","tm":"tm"}],
                      "video": "video-url",
                      "wholesale": {"enabled": true, "quantum": 112},
                      "dimensions": {"length": 55, "width": 40, "height": 15, "weightBrutto": 6.24, "isValid": false},
                      "characteristics": [{"id": 14177449, "name": "Color", "value": ["red"]}],
                      "sizes": [{"chrtID": 316399238, "techSize": "0", "skus": ["987456321654"]}],
                      "tags": [{"id": 592569, "name": "Popular", "color": "D1CFD7"}],
                      "createdAt": "2023-12-06T11:17:00.96577Z",
                      "updatedAt": "2023-12-06T11:17:00.96577Z"
                    }
                  ],
                  "cursor": {
                    "updatedAt": "2023-12-06T11:17:00.96577Z",
                    "nmID": 123654123,
                    "total": 1
                  }
                }
                """;

        WbProductCardsResponse response = GSON.fromJson(json, WbProductCardsResponse.class);

        assertEquals(1, response.getCards().size());
        assertEquals(12345678L, response.getCards().getFirst().getNmID());
        assertTrue(response.getCards().getFirst().getKizMarked());
        assertFalse(response.getCards().getFirst().getNeedKiz());
        assertEquals("987456321654", response.getCards().getFirst().getSizes().getFirst().getSkus().getFirst());
        assertEquals(1, response.getCursor().getTotal());
    }

    @Test
    void shouldDeserializeOrdersResponse() {
        String json = """
                {
                  "orders": [
                    {
                      "address": {"fullAddress":"Address 1","longitude":44.519068,"latitude":40.20192},
                      "ddate":"17.05.2024",
                      "sellerDate":"02.06.2025",
                      "salePrice":504600,
                      "requiredMeta":["uin"],
                      "optionalMeta":["sgtin"],
                      "deliveryType":"fbs",
                      "comment":"comment",
                      "scanPrice":1500,
                      "userId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "orderUid":"uid-1",
                      "article":"one-ring-7548",
                      "colorCode":"RAL 3017",
                      "rid":"rid-1",
                      "createdAt":"2022-05-04T07:56:29Z",
                      "offices":["Kaluga"],
                      "skus":["6665956397512"],
                      "id":13833711,
                      "warehouseId":658434,
                      "officeId":123,
                      "nmId":12345678,
                      "chrtId":987654321,
                      "price":1014,
                      "finalPrice":1014,
                      "convertedPrice":28322,
                      "convertedFinalPrice":1014,
                      "currencyCode":933,
                      "convertedCurrencyCode":643,
                      "cargoType":1,
                      "crossBorderType":1,
                      "isZeroOrder":false,
                      "options":{"isB2B":true},
                      "supplyId":"WB-GI-92937123"
                    }
                  ]
                }
                """;

        WbOrdersResponse response = GSON.fromJson(json, WbOrdersResponse.class);

        assertEquals(1, response.getOrders().size());
        assertEquals("WB-GI-92937123", response.getOrders().getFirst().getSupplyId());
        assertEquals("uin", response.getOrders().getFirst().getRequiredMeta().getFirst());
        assertEquals(12345678L, response.getOrders().getFirst().getNmId());
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                response.getOrders().getFirst().getUserId());
        assertTrue(response.getOrders().getFirst().getOptions().getIsB2B());
    }

    @Test
    void shouldDeserializeNumericOrderUserIdAsString() {
        String json = """
                {
                  "orders": [
                    {
                      "id": 13833711,
                      "userId": 123456789
                    }
                  ]
                }
                """;

        WbOrdersResponse response = GSON.fromJson(json, WbOrdersResponse.class);

        assertEquals("123456789", response.getOrders().getFirst().getUserId());
    }

    @Test
    void shouldDeserializeOrderMetaDetailsAndBuildDeliveryDecision() {
        String json = """
                {
                  "orders": [
                    {
                      "orderId": 13833711,
                      "requiredMeta": ["sgtin"],
                      "metaDetails": [
                        {"key": "sgtin", "requirementType": "required", "filled": false, "valid": false, "status": "missing"},
                        {"key": "gtin", "requirementType": "optional", "filled": false, "valid": true}
                      ]
                    }
                  ]
                }
                """;

        WbOrderMetaDetailsResponse response = GSON.fromJson(json, WbOrderMetaDetailsResponse.class);
        WbMetadataDecision decision = WbMetadataDecision.from(response.getOrders().getFirst());

        assertEquals(13833711L, decision.orderId());
        assertTrue(decision.blocksDelivery());
        assertEquals("sgtin", decision.missingRequiredMeta().getFirst());
        assertTrue(decision.invalidMeta().isEmpty());
    }

    @Test
    void shouldDeserializeCrossBorderStickerStatus() {
        String json = """
                {
                  "stickers": [
                    {"orderId": 1, "status": "awaitingTrackNumber"},
                    {"orderId": 2, "status": "ready", "partA": 11, "partB": 22, "barcode": "abc", "file": "base64"}
                  ]
                }
                """;

        WbCrossBorderStickerResponse response = GSON.fromJson(json, WbCrossBorderStickerResponse.class);

        assertTrue(response.getStickers().getFirst().isAwaitingTrackNumber());
        assertTrue(response.getStickers().get(1).isReady());
        assertEquals("abc", response.getStickers().get(1).getBarcode());
    }

    @Test
    void shouldDeserializeSupplyBoxes() {
        String json = """
                {
                  "trbxes": [
                    {"trbxId": "TRBX-1", "orders": [100, 101]}
                  ]
                }
                """;

        WbSupplyBoxesResponse response = GSON.fromJson(json, WbSupplyBoxesResponse.class);

        assertEquals("TRBX-1", response.getBoxes().getFirst().getTrbxId());
        assertEquals(2, response.getBoxes().getFirst().getOrders().size());
    }

}
