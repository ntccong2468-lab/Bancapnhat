package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.shared.SellerArticle;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** A confirmed own-card update may trim its article, but may never take another card's article. */
final class WbArticlePolicy {
    private WbArticlePolicy() {}
    static String checked(JsonObject own, ListingTransport client) throws IOException {
        if(client instanceof com.vncode.app.integration.listing.GtinBatchTransport batch)return batch.checkedWbArticle(own);
        String original = text(own, "vendorCode"), trimmed = SellerArticle.trim(original);
        if (trimmed.isBlank()) throw new IllegalArgumentException("WB_ARTICLE_REQUIRED");
        if (original.equals(trimmed)) return trimmed;
        JsonObject cursor = new JsonObject(); cursor.addProperty("limit", 100);
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 200; page++) {
            var filter = new JsonObject(); filter.addProperty("textSearch", trimmed); filter.addProperty("withPhoto", -1);
            var settings = new JsonObject(); settings.add("filter", filter); settings.add("cursor", cursor);
            var body = new JsonObject(); body.add("settings", settings);
            var result = object(client.call("content/v2/get/cards/list", body, false));
            if (!result.has("cards") || !result.get("cards").isJsonArray() || "true".equals(text(result,"error"))) throw new IOException("WB_ARTICLE_LOOKUP_FAILED");
            for (var item : array(result, "cards")) {
                var other = object(item);
                if (!text(own,"nmID").equals(text(other,"nmID")) && trimmed.equalsIgnoreCase(SellerArticle.trim(text(other,"vendorCode"))))
                    throw new IllegalArgumentException("WB_ARTICLE_TRIM_COLLISION");
            }
            if (array(result, "cards").size() < 100) return trimmed;
            cursor = obj(result, "cursor").deepCopy();
            if (text(cursor,"updatedAt").isBlank() || text(cursor,"nmID").isBlank() || !seen.add(cursor.toString())) throw new IOException("WB_ARTICLE_CURSOR_INVALID");
            cursor.addProperty("limit",100);
        }
        throw new IOException("WB_ARTICLE_PAGE_LIMIT");
    }
}
