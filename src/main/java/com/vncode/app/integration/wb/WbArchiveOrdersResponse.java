package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Archived FBS orders use a different nested schema from /api/v3/orders. */
public final class WbArchiveOrdersResponse {
    private Long next;
    private List<Item> orders;
    public Long getNext() { return next; }
    public List<Item> getOrders() { return orders; }

    public static final class Item {
        private Long id;
        private String supplyId;
        private String createdAt;
        private String colorCode;
        private Product product;
        private Price priceInfo;
        public Long getId() { return id; }
        public boolean hasProduct() { return product != null && product.nmId != null && product.nmId > 0 && product.chrtId != null && product.chrtId > 0; }
        WbOrderDto toOrder() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id); values.put("supplyId", supplyId); values.put("createdAt", createdAt); values.put("colorCode", colorCode);
            if (product != null) { values.put("article", product.article); values.put("nmId", product.nmId); values.put("chrtId", product.chrtId); values.put("skus", product.skus); }
            if (priceInfo != null) { values.put("price", priceInfo.price); values.put("convertedPrice", priceInfo.convertedPrice); values.put("currencyCode", priceInfo.currencyCode); values.put("convertedCurrencyCode", priceInfo.convertedCurrencyCode); }
            return new Gson().fromJson(new Gson().toJson(values), WbOrderDto.class);
        }
    }
    static final class Product { private String article; private Long nmId; private Long chrtId; private List<String> skus; }
    static final class Price { private Integer price; private Integer convertedPrice; private Integer currencyCode; private Integer convertedCurrencyCode; }
}
