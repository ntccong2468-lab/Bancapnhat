package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.ProductRow;
import java.util.*;
import java.util.regex.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Seller offer_id is a model/variant code; the numeric Ozon SKU is never a model number. */
public final class OzonSkuGtinMatcher {
    private static final Pattern SIZED = Pattern.compile("^([\\p{L}\\p{N} ]+)[-_]([0-9]{2,3}(?:[-/][0-9]{2,3})?|[2-9]?XL|XXXL|XXL|XS|S|M|L)$");
    private final Map<String,List<RegisteredGtin>> models = new HashMap<>();
    public OzonSkuGtinMatcher(List<RegisteredGtin> catalog) { this(catalog, false); }
    public OzonSkuGtinMatcher(List<RegisteredGtin> catalog, boolean includeDrafts) {
        for (var row : catalog) if (row.tradeUnit() && (row.published() || includeDrafts) && RegisteredGtinValidator.isValid(row.gtin())
                && !com.vncode.app.integration.znack.GtinNormalizer.isTechnicalRange(row.gtin())) {
            String model = model(row.article());
            if (!model.isBlank()) models.computeIfAbsent(model, key -> new ArrayList<>()).add(row);
        }
    }
    public List<RegisteredGtin> candidates(ProductRow row) {
        return candidates(row.key().variantId(), row.vendorArticle(), row.color(), GtinVariantIdentity.primarySize(row), row.russianSize(), row.title(), row.category(), row.brand());
    }
    public List<RegisteredGtin> candidates(String offerId, String article, String color, String size, String russianSize, String title, String category, String brand) {
        var parsed = parse(offerId);
        if (parsed == null || color(color).isBlank() || size.isBlank()) return List.of();
        // A suffix is only evidence when it agrees with an explicit marketplace size field.
        if (!parsed[1].equals(n(size)) && !parsed[1].equals(n(russianSize))) return List.of();
        var articleParts = parse(article);
        String articleModel = articleParts == null ? model(article) : articleParts[0];
        if (!articleModel.isBlank() && !articleModel.equals(parsed[0])) return List.of();
        String productKind = kind(title + " " + category);
        return models.getOrDefault(parsed[0], List.of()).stream()
                .filter(card -> color(card.color()).equals(color(color)))
                .filter(card -> GtinVariantIdentity.sizeMatches(card, size, russianSize))
                .filter(card -> compatibleKind(productKind, kind(card.details().single("title"))))
                .filter(card -> compatibleBrand(brand, card.details().single("brand")))
                .distinct().toList();
    }
    private static String[] parse(String value) {
        var m = SIZED.matcher(n(value));
        if (!m.matches()) return null;
        String model = model(m.group(1));
        return model.isBlank() ? null : new String[]{model, m.group(2)};
    }
    private static String model(String value) {
        String normalized = n(value);
        // Explicit DASH/DASHOA spelling from the seller's example, never arbitrary prefix matching.
        normalized = normalized.replaceFirst("DASHOA$", "DASH");
        if (!normalized.matches("(?=.*[0-9])(?=.*[A-Z])[A-Z0-9]+(?: [A-Z]+)?") || normalized.length() > 80) return "";
        return normalized.replace(" ", "");
    }
    private static String color(String value) {
        return switch(n(value)) {
            case "NÂU", "BROWN", "КОРИЧНЕВЫЙ" -> "BROWN";
            case "ĐEN", "BLACK", "ЧЕРНЫЙ", "ЧЁРНЫЙ" -> "BLACK";
            case "TRẮNG", "WHITE", "БЕЛЫЙ" -> "WHITE";
            default -> n(value);
        };
    }
    public static boolean sameColor(String left,String right){return !color(left).isBlank()&&color(left).equals(color(right));}
    private static String kind(String value) {
        String n=n(value);
        if(n.matches(".*(?:КОСТЮМ|КОМПЛЕКТ|\\bSUIT\\b|\\bSET\\b|\\bBỘ\\b).*"))return "SET";
        if(n.matches(".*(?:БРЮК|ШТАН|\\bTROUSERS\\b|\\bPANTS\\b).*"))return "TROUSERS";
        if(n.matches(".*(?:ФУТБОЛК|\\bT-SHIRT\\b).*"))return "TSHIRT";
        return "";
    }
    private static boolean compatibleKind(String left,String right){return left.isBlank()||right.isBlank()||left.equals(right);}
    private static boolean compatibleBrand(String left,String right){return left==null||right==null||left.isBlank()||right.isBlank()||n(left).equals(n(right));}
    private static String n(String value) { return GtinMatchingService.normalize(value); }
}
