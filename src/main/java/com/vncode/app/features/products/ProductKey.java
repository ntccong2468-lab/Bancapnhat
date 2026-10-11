package com.vncode.app.features.products;

import com.vncode.app.integration.marketplace.Marketplace;
import java.util.Objects;
public record ProductKey(int shopId, Marketplace marketplace, String cardId, String variantId) {
    public ProductKey {
        if(shopId<=0)throw new IllegalArgumentException("Invalid product owner");
        Objects.requireNonNull(marketplace);cardId=required(cardId);variantId=variantId==null?"":variantId.strip();
        if(variantId.length()>512||variantId.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid variant identity");
    }
    private static String required(String value){if(value==null||value.isBlank()||value.length()>512||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid card identity");return value.strip();}
}
