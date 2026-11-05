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
                .filter(g -> normalizeColor(g.color()).equals(normalizeColor(product.color())))
                .filter(g -> normalize(g.size()).equals(normalize(product.size()))).distinct().toList();
        return new MatchResult(product.key(), candidates, candidates.size() == 1 ? "" : candidates.isEmpty() ? "no_match" : "ambiguous");
    }
    /** Only remove characters known to be dropped by Catalog; keep model punctuation and size boundaries. */
    public static String normalizeArticle(String value) {return normalize(com.vncode.app.integration.znack.registration.CatalogArticleValue.stored(java.text.Normalizer.normalize(value==null?"":value,java.text.Normalizer.Form.NFKC)));}
    /** Closed spelling/translation equivalents only. Never discard a shade or a second color. */
    public static String normalizeColor(String value) {
        return switch(normalize(value)) {
            case "NÂU", "NAU", "BROWN", "КОРИЧНЕВЫЙ" -> "BROWN";
            case "ĐEN", "DEN", "BLACK", "ЧЕРНЫЙ", "ЧЁРНЫЙ" -> "BLACK";
            case "TRẮNG", "TRANG", "WHITE", "БЕЛЫЙ" -> "WHITE";
            case "BE", "BEIGE", "БЕЖЕВЫЙ" -> "BEIGE";
            case "ЗЕЛЁНЫЙ", "ЗЕЛЕНЫЙ" -> "ЗЕЛЕНЫЙ";
            case "ТЁМНО-ЗЕЛЁНЫЙ", "ТЕМНО-ЗЕЛЁНЫЙ", "ТЁМНО-ЗЕЛЕНЫЙ", "ТЕМНО-ЗЕЛЕНЫЙ" -> "ТЕМНО-ЗЕЛЕНЫЙ";
            case "СВЕТЛО-ЗЕЛЁНЫЙ", "СВЕТЛО-ЗЕЛЕНЫЙ" -> "СВЕТЛО-ЗЕЛЕНЫЙ";
            default -> normalize(value);
        };
    }
    public static String normalize(String value) { return java.text.Normalizer.normalize(value==null?"":value,java.text.Normalizer.Form.NFKC).strip().replaceAll("[\\p{Z}\\s]+"," ").toUpperCase(Locale.ROOT); }
}
