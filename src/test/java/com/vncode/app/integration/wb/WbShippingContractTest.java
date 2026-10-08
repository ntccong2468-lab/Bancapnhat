package com.vncode.app.integration.wb;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WbShippingContractTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    @Test void rejectsPastDateAndUnknownMethodBeforeBuildingMutation() {
        assertThrows(IllegalArgumentException.class, () -> new WbShippingContract.Parameters(
                "WB-GI-100", "selfShipping", LocalDate.parse("2026-10-07"), 100).payload(clock));
        assertThrows(IllegalArgumentException.class, () -> new WbShippingContract.Parameters(
                "WB-GI-100", "courier", LocalDate.parse("2026-10-09"), 100).payload(clock));
        var data = new WbShippingContract.Parameters("WB-GI-100", "transportCompany",
                LocalDate.parse("2026-10-09"), 100).payload(clock).getAsJsonArray("data");
        assertEquals("2026-10-09", data.get(0).getAsJsonObject().get("shippingDt").getAsString());
        assertEquals(100, data.get(0).getAsJsonObject().get("shippingPointId").getAsInt());
    }

    @Test void http200MustContainMatchingExplicitSuccessWithoutError() throws Exception {
        WbShippingContract.requireSuccess(JsonParser.parseString("{\"results\":[{\"supplyId\":\"WB-GI-100\",\"success\":true}]}"), "WB-GI-100");
        for (String result : List.of("{}", "{\"results\":[]}",
                "{\"results\":[{\"supplyId\":\"another\",\"success\":true}]}",
                "{\"results\":[{\"supplyId\":\"WB-GI-100\"}]}",
                "{\"results\":[{\"supplyId\":\"WB-GI-100\",\"success\":true,\"error\":{\"code\":409,\"detail\":\"secret\"}}]}")) {
            IOException error = assertThrows(IOException.class,
                    () -> WbShippingContract.requireSuccess(JsonParser.parseString(result), "WB-GI-100"));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test void russianSearchMatchesYoAndCaseAndPagesLocally() {
        var points = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new WbShippingContract.Point(i + 1, "Орёл " + i, "Адрес", "Орёл", List.of(1)))
                .toList();
        assertEquals(20, WbShippingContract.page(points, "ОРЕЛ", 0).size());
        assertEquals(5, WbShippingContract.page(points, "орёл", 20).size());
        assertTrue(WbShippingContract.page(points, "Москва", 0).isEmpty());
    }
}
