package com.tuandev.fbsbarcode.integration.wb;

import java.util.Collections;
import java.util.List;

public class WbSupplyBoxDto {
    private String id;
    private String trbxId;
    private List<Long> orders;

    public String getId() {
        return id == null || id.isBlank() ? trbxId : id;
    }

    public String getTrbxId() {
        return trbxId == null || trbxId.isBlank() ? id : trbxId;
    }

    public List<Long> getOrders() {
        return orders == null ? Collections.emptyList() : orders;
    }
}
