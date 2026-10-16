package com.vncode.app.features.products;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Dictionary names, never assumed marketplace attribute IDs. */
public final class ProductFieldNames {
    private ProductFieldNames() {}
    public static boolean matches(String field, String name) {
        String normalized = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return switch (field) {
            case "material" -> Set.of("состав", "материал", "основнойматериал", "материализделия", "material").contains(normalized);
            case "tnved" -> Set.of("тнвэд", "кодтнвэд", "тнвэдкодыеаэс", "кодтнвэдеаэс", "tnved").contains(normalized);
            case "color" -> Set.of("цвет", "цветтовара", "color", "màusắc").contains(normalized);
            case "gender" -> Set.of("пол", "полребенка", "gender", "giớitính").contains(normalized);
            case "russianSize" -> Set.of("российскийразмер", "размерроссийский", "russiansize", "rusize", "sizenga").contains(normalized);
            default -> false;
        };
    }
}
