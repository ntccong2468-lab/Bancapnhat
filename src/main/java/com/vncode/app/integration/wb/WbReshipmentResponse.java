package com.vncode.app.integration.wb;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** Response to GET /api/v3/supplies/orders/reshipment. */
public final class WbReshipmentResponse {
    private List<Item> orders;
    public List<Item> getOrders() { return orders; }

    public static final class Item {
        @SerializedName("supplyID") private String supplyId;
        @SerializedName("orderID") private Long orderId;
        public String getSupplyId() { return supplyId; }
        public Long getOrderId() { return orderId; }
    }
}
