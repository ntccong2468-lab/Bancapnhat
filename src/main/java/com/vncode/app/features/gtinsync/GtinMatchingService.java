package com.vncode.app.features.gtinsync;

import java.util.List;
import java.util.Locale;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class GtinMatchingService {
    public MatchResult match(ProductSnapshot product, List<RegisteredGtin> catalog) {
        if (normalizeArticle(product.article()).isBlank() || product.color().isBlank() || product.size().isBlank())
            return new MatchResult(product.key(), List.of(), "missing_attributes");
        var candidates = catalog.stream().filter(g -> g.tradeUnit() && g.published() && RegisteredGtinValidator.isValid(g.gtin()))
                .filter(g -> normalizeArticle(g.article()).equals(normalizeArticle(product.article())))
                .filter(g -> normalize(g.color()).equals(normalize(product.color())))
                .filter(g -> normalize(g.size()).equals(normalize(product.size()))).distinct().toList();
        return new MatchResult(product.key(), candidates, candidates.size() == 1 ? "" : candidates.isEmpty() ? "no_match" : "ambiguous");
    }
    /** Only remove characters known to be dropped by Catalog; keep model punctuation and size boundaries. */
    public static String normalizeArticle(String value) {return normalize(com.vncode.app.integration.znack.registration.CatalogArticleValue.stored(value));}
    public static String normalize(String value) { return java.text.Normalizer.normalize(value==null?"":value,java.text.Normalizer.Form.NFKC).strip().replaceAll("[\\p{Z}\\s]+"," ").toUpperCase(Locale.ROOT); }
}
