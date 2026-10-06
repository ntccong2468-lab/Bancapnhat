package com.tuandev.fbsbarcode.integration.wb;

import java.util.List;

public class WbSuppliesResponse {
    private Long next;
    private List<WbSupplyDto> supplies;

    public Long getNext() {
        return next;
    }

    public List<WbSupplyDto> getSupplies() {
        return supplies;
    }
}
