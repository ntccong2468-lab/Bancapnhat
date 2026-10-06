package com.tuandev.fbsbarcode.integration.wb;

import java.util.List;

public class WbOrdersResponse {
    private Long next;
    private List<WbOrderDto> orders;

    public Long getNext() {
        return next;
    }

    public List<WbOrderDto> getOrders() {
        return orders;
    }
}
