package com.vncode.app.features.ozon;

import com.vncode.app.integration.ozon.OzonPostingItemDto;
import com.vncode.app.integration.ozon.OzonProductDto;
import com.vncode.app.features.supply.OrderSortOptions;
import com.vncode.app.shared.NaturalOrderComparator;
import java.util.Comparator;
import java.util.function.Function;

/** Product grouping fields loaded from the synchronized Ozon product card. */
public record OzonProductVariant(String product, String article, String color, String size) {
    /** The WB grouping order, shared by the Ozon table and its warehouse picking rows. */
    public static Comparator<OzonProductVariant> comparator(OrderSortOptions options) {
        var selected = options == null ? OrderSortOptions.defaultOptions() : options;
        Comparator<OzonProductVariant> result = (left, right) -> 0;
        if (selected.bySize()) result = compare(OzonProductVariant::size).thenComparing(result);
        if (selected.byColor()) result = compare(OzonProductVariant::color).thenComparing(result);
        if (selected.byArticle()) result = compare(OzonProductVariant::article).thenComparing(result);
        if (selected.bySubject()) result = compare(OzonProductVariant::product).thenComparing(result);
        return result;
    }

    private static Comparator<OzonProductVariant> compare(Function<OzonProductVariant, String> value) {
        return Comparator.comparing(value, (left, right) -> NaturalOrderComparator.compareIgnoreCase(
                left == null || left.isBlank() ? "~~~~" : left,
                right == null || right.isBlank() ? "~~~~" : right));
    }

    public static OzonProductVariant from(OzonPostingItemDto item) {
        if (item == null) return new OzonProductVariant("", "", "", "");
        return new OzonProductVariant(first(item.name(), item.sku()), first(item.offerId(), item.sku()), "", "");
    }

    public static OzonProductVariant from(OzonPostingItemDto item, OzonProductDto productCard) {
        if (item == null) return new OzonProductVariant("", "", "", "");
        if (productCard == null) return from(item);
        return new OzonProductVariant(
                first(productCard.name(), first(item.name(), item.sku())),
                first(productCard.article(), first(item.offerId(), item.sku())),
                safe(productCard.color()), safe(productCard.size()));
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
