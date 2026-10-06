package com.tuandev.fbsbarcode.features.supply;

/** Order sorting value shared by the JavaFX controllers and supply services. */
public record OrderSortOptions(
        boolean bySubject,
        boolean byArticle,
        boolean byColor,
        boolean bySize
) {
    public static OrderSortOptions defaultOptions() {
        return new OrderSortOptions(true, true, true, true);
    }
}
