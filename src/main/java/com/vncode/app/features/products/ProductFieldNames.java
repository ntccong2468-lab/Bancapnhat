package com.vncode.app.features.products;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import com.vncode.app.features.listingcopy.TargetAttribute;

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
            case "material" -> Set.of("состав", "составматериала", "составосновногоматериала", "материал", "основнойматериал", "материализделия", "material").contains(normalized);
            case "tnved" -> Set.of("тнвэд", "тнвэдеаэс", "кодтнвэд", "тнвэдкодыеаэс", "кодтнвэдеаэс", "tnved").contains(normalized);
            case "color" -> Set.of("цвет", "цветтовара", "color", "màusắc").contains(normalized);
            case "gender" -> Set.of("пол", "полребенка", "gender", "giớitính").contains(normalized);
            case "russianSize" -> Set.of("российскийразмер", "размерроссийский", "russiansize", "rusize", "sizenga").contains(normalized);
            default -> false;
        };
    }
    /** Composition with percentages is distinct from Ozon's material-name dictionary or lining. */
    public static int priority(String field,String name){
        if(!matches(field,name))return Integer.MAX_VALUE;
        if(field.equals("material")&&name.toLowerCase(Locale.ROOT).contains("состав"))return 0;
        return 1;
    }
    public static List<TargetAttribute> targets(String field,List<TargetAttribute> dictionary){
        var eligible=dictionary.stream().filter(a->!a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"))&&matches(field,a.name())).toList();
        int priority=eligible.stream().mapToInt(a->priority(field,a.name())).min().orElse(Integer.MAX_VALUE);
        return eligible.stream().filter(a->priority(field,a.name())==priority).toList();
    }
    public static List<ProductAttribute> sourceAttributes(String field,List<ProductAttribute> attributes){
        var eligible=attributes.stream().filter(a->(a.complexId()==null||a.complexId().isBlank()||a.complexId().equals("0"))&&matches(field,a.name())).toList();
        int best=eligible.stream().mapToInt(a->priority(field,a.name())).min().orElse(Integer.MAX_VALUE);
        return eligible.stream().filter(a->priority(field,a.name())==best).toList();
    }
}
