package com.vncode.app.integration.wb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Russia FBS contract from the official WB 03-orders-fbs OpenAPI, retrieved 2026-10-08. */
public final class WbShippingContract {
    private WbShippingContract() { }

    public record Parameters(String supplyId, String shippingType, LocalDate shippingDate, long shippingPointId) {
        public JsonObject payload(Clock clock) {
            if (supplyId == null || supplyId.isBlank() || shippingDate == null
                    || shippingDate.isBefore(LocalDate.now(clock)) || shippingPointId <= 0
                    || !("selfShipping".equals(shippingType) || "transportCompany".equals(shippingType))) {
                throw new IllegalArgumentException("Invalid WB shipping parameters.");
            }
            JsonObject item = new JsonObject();
            item.addProperty("supplyId", supplyId);
            item.addProperty("shippingType", shippingType);
            item.addProperty("shippingDt", shippingDate.toString());
            item.addProperty("shippingPointId", shippingPointId);
            JsonArray data = new JsonArray();
            data.add(item);
            JsonObject request = new JsonObject();
            request.add("data", data);
            return request;
        }
    }

    public record Point(long id, String name, String address, String city, List<Integer> cargoTypes) {
        public Point {
            cargoTypes = cargoTypes == null ? List.of() : List.copyOf(cargoTypes);
        }
        @Override public String toString() { return name + " — " + address; }
    }

    /** The API has no paging parameter: the UI pages the returned list locally. */
    public static List<Point> page(List<Point> points, String search, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative page offset.");
        String query = normalized(search);
        return points.stream().filter(p -> normalized(p.name() + " " + p.address() + " " + p.city())
                .contains(query)).skip(offset).limit(20).toList();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
    }

    /** HTTP 200 can contain a per-supply rejection; never interpret it as a successful write. */
    public static void requireSuccess(JsonElement response, String supplyId) throws IOException {
        try {
            JsonArray results = response.getAsJsonObject().getAsJsonArray("results");
            if (results == null || results.size() != 1) throw new IllegalArgumentException();
            JsonObject result = results.get(0).getAsJsonObject();
            if(supplyId.equals(result.has("supplyId")?result.get("supplyId").getAsString():null)
                    &&result.has("error")&&result.get("error").isJsonObject()) {
                JsonElement code=result.getAsJsonObject("error").get("code");
                if(code!=null&&code.isJsonPrimitive()&&code.getAsJsonPrimitive().isNumber()) {
                    int status=code.getAsBigDecimal().intValueExact();
                    if(status>=400&&status<500)throw new WbApiException("WB_SHIPPING_REJECTED",status,"");
                }
            }
            JsonElement success = result.get("success");
            if (!supplyId.equals(result.get("supplyId").getAsString())
                    || success == null || !success.isJsonPrimitive()
                    || !success.getAsJsonPrimitive().isBoolean() || !success.getAsBoolean()
                    || (result.has("error") && !result.get("error").isJsonNull())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            // Do not expose remote error.detail, which may contain account data.
            throw new IOException("WB_SHIPPING_NOT_CONFIRMED", invalid);
        }
    }
}
