package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.ZnackModels.Product;
import java.util.Locale;

/** The SUZ/True API group is fixed when purchase consent is recorded. */
public enum ZnackProductGroup {
    LP("lp", 10), FOOTWEAR("shoes", 1);

    private final String apiCode;
    private final int templateId;
    ZnackProductGroup(String apiCode, int templateId) { this.apiCode = apiCode; this.templateId = templateId; }
    public String apiCode() { return apiCode; }
    public int templateId() { return templateId; }
    public static ZnackProductGroup fromCode(String value) {
        if (value == null || value.isBlank()) return LP;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lp" -> LP;
            case "shoes", "footwear" -> FOOTWEAR;
            default -> throw new IllegalArgumentException("Unsupported purchase product group");
        };
    }
    public static ZnackProductGroup fromProduct(Product product) {
        return fromTnVed(product == null ? null : product.tnVed());
    }
    public static ZnackProductGroup fromTnVed(String tnVed) {
        String code = tnVed == null ? "" : tnVed.replaceAll("[^0-9]", "");
        return code.length() >= 4 && code.substring(0, 4).matches("640[1-5]") ? FOOTWEAR : LP;
    }
}
