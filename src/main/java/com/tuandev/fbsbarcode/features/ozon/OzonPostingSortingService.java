package com.tuandev.fbsbarcode.features.ozon;

import com.tuandev.fbsbarcode.features.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.shared.NaturalOrderComparator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Applies the same product/article/color/size grouping order used by the WB supply page. */
public final class OzonPostingSortingService {
    public List<OzonPostingDto> sort(List<OzonPostingDto> postings, OrderSortOptions options) {
        return sort(postings, options, List.of());
    }

    public List<OzonPostingDto> sort(
            List<OzonPostingDto> postings, OrderSortOptions options, List<OzonProductDto> products) {
        List<OzonPostingDto> sorted = new ArrayList<>(postings == null ? List.of() : postings);
        sorted.sort(comparator(options == null ? OrderSortOptions.defaultOptions() : options,
                products == null ? List.of() : products));
        return List.copyOf(sorted);
    }

    private static Comparator<OzonPostingDto> comparator(OrderSortOptions options, List<OzonProductDto> products) {
        Comparator<OzonPostingDto> result = compare(OzonPostingDto::postingNumber);
        if (options.bySize()) result = compare(value -> variant(value, products).size()).thenComparing(result);
        if (options.byColor()) result = compare(value -> variant(value, products).color()).thenComparing(result);
        if (options.byArticle()) result = compare(value -> variant(value, products).article()).thenComparing(result);
        if (options.bySubject()) result = compare(value -> variant(value, products).product()).thenComparing(result);
        return result;
    }

    private static Comparator<OzonPostingDto> compare(Function<OzonPostingDto, String> extractor) {
        return Comparator.comparing(extractor, (left, right) ->
                NaturalOrderComparator.compareIgnoreCase(blankLast(left), blankLast(right)));
    }

    private static OzonProductVariant variant(OzonPostingDto posting, List<OzonProductDto> products) {
        return posting == null || posting.items().isEmpty()
                ? new OzonProductVariant("", "", "", "")
                : OzonProductVariant.from(posting.items().getFirst(), find(products, posting.items().getFirst()));
    }

    private static OzonProductDto find(List<OzonProductDto> products, OzonPostingItemDto item) {
        return products.stream().filter(product -> matches(product, item)).findFirst().orElse(null);
    }

    private static boolean matches(OzonProductDto product, OzonPostingItemDto item) {
        return (!item.productId().isBlank() && item.productId().equals(product.productId()))
                || (!item.sku().isBlank() && item.sku().equals(product.sku()))
                || (!item.offerId().isBlank() && item.offerId().equals(product.offerId()));
    }

    private static String blankLast(String value) {
        return value == null || value.isBlank() ? "~~~~" : value;
    }
}
