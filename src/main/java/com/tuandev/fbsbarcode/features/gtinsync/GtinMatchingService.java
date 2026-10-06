package com.tuandev.fbsbarcode.features.gtinsync;

import java.util.List;
import java.util.Locale;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

public final class GtinMatchingService {
    public MatchResult match(ProductSnapshot product, List<RegisteredGtin> catalog) {
        if (product.article().isBlank() || product.color().isBlank() || product.size().isBlank())
            return new MatchResult(product.key(), List.of(), "missing_attributes");
        var candidates = catalog.stream().filter(g -> g.tradeUnit() && g.published() && RegisteredGtinValidator.isValid(g.gtin()))
                .filter(g -> normalize(g.article()).equals(normalize(product.article())))
                .filter(g -> normalize(g.color()).equals(normalize(product.color())))
                .filter(g -> normalize(g.size()).equals(normalize(product.size()))).distinct().toList();
        return new MatchResult(product.key(), candidates, candidates.size() == 1 ? "" : candidates.isEmpty() ? "no_match" : "ambiguous");
    }
    private static String normalize(String value) { return value.strip().toUpperCase(Locale.ROOT); }
}
