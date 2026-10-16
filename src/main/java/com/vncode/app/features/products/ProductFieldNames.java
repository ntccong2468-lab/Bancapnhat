package com.vncode.app.features.products;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Dictionary names, never assumed marketplace attribute IDs. */
public final class ProductFieldNames {
    private ProductFieldNames() {}
    /** Ozon's TN VED dictionary returns the ten-digit code followed by its description. */
    public static String tnvedCode(String value) {
        String normalized=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).strip();
        if(normalized.matches("[0-9]{4,10}"))return normalized;
        var label=java.util.regex.Pattern.compile("^([0-9]{10})\\s*[-–—]\\s*\\p{L}.*$",java.util.regex.Pattern.DOTALL).matcher(normalized);
        if(!label.matches()||java.util.regex.Pattern.compile("(?<![0-9])[0-9]{10}(?![0-9])").matcher(normalized.substring(10)).find())return "";
        return label.group(1);
    }
    public static boolean matches(String field, String name) {
        String normalized = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return switch (field) {
            case "material" -> Set.of("состав", "материал", "основнойматериал", "материализделия", "material").contains(normalized);
            case "tnved" -> Set.of("тнвэд", "тнвэдеаэс", "кодтнвэд", "тнвэдкодыеаэс", "кодтнвэдеаэс", "tnved").contains(normalized);
            case "color" -> Set.of("цвет", "цветтовара", "color", "màusắc").contains(normalized);
            case "gender" -> Set.of("пол", "полребенка", "gender", "giớitính").contains(normalized);
            case "russianSize" -> Set.of("российскийразмер", "размерроссийский", "russiansize", "rusize", "sizenga").contains(normalized);
            default -> false;
        };
    }
}
