package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps the official WB cards/list schema to mandatory National Catalog attributes. */
public final class ZnackWbAttributeMapper {
    static final String NO_BRAND = "Нет бренда";
    private static final long GOOD_NAME = 2478L;
    private static final long BRAND = 2504L;
    private static final long FULL_TNVED = 13933L;
    private static final long TNVED_GROUP = 3959L;
    private static final long MODEL = 13914L;
    private static final long SIZE = 35L;
    private static final long COLOR = 36L;
    private static final long COMPOSITION = 2483L;
    private static final long GENDER = 14013L;
    private static final long GOODS_KIND = 12L;

    public MappingResult map(Sku sku, List<WbCharacteristic> characteristics, List<Attribute> required,
                             String fullTnved, String feedTnved, ZnackModels.GoodsDocument document) {
        String goodName = defaultName(sku);
        String brand = brand(sku, characteristics);
        Map<Long, String> values = new LinkedHashMap<>();
        Map<Long, String> types = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        if (goodName.isBlank()) missing.add("Наименование товара");
        for (Attribute attribute : required) {
            long id = attribute.id();
            if (id == TNVED_GROUP) {
                // True API v5.62: when feed.tnved is a registered four-digit group, 3959 must be absent.
                continue;
            }
            if (id == FULL_TNVED && clean(feedTnved).length() == 10) {
                // With an exact ten-digit feed.tnved, neither 3959 nor 13933 may be duplicated.
                continue;
            }
            if (id == ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID
                    || id == ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID) continue;

            String automatic = automaticValue(attribute, sku, characteristics, fullTnved, feedTnved);
            String resolved = resolvePreset(attribute, automatic);
            if (resolved.isBlank()) missing.add(attribute.name() + " [" + attribute.id() + "]");
            else {
                values.put(id, resolved);
                String type = resolveValueType(attribute, resolved, sku.wbSize());
                if (type == null) {
                    missing.add(attribute.name() + " [" + id + "]: attr_value_type "
                            + attribute.valueTypes());
                } else types.put(id, type);
            }
        }

        if (document == null || !document.complete()) {
            missing.add("Giấy tờ hàng hoá (Декларация/Сертификат соответствия)");
        } else {
            long documentAttribute = isCertificate(document.type())
                    ? ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID
                    : ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID;
            values.put(documentAttribute, clean(document.number()) + ":::" + clean(document.date()));
        }
        values.put(GOOD_NAME, goodName);
        values.put(BRAND, brand);
        return new MappingResult(goodName, brand, Map.copyOf(values),
                List.copyOf(new LinkedHashSet<>(missing)), Map.copyOf(types));
    }

    /** Return the literal type from the catalog schema, never the attr_field_type. */
    static String resolveValueType(Attribute attribute, String value, String wbSize) {
        List<String> types = attribute.valueTypes();
        if (types.isEmpty()) return "";
        if (attribute.id() == MODEL) {
            for (String type : types) if (normalize(type).contains("артикул")) return type;
        }
        if (attribute.id() == SIZE) {
            String size = clean(value).toUpperCase(Locale.ROOT);
            boolean international = size.matches("(?:[2-9]?[XSML]+)(?:[-/](?:[2-9]?[XSML]+))*");
            for (String type : types) {
                String normalized = normalize(type);
                if (international && (normalized.contains("международ") || normalized.equals("int"))) return type;
                if (!international && !clean(wbSize).isBlank() && clean(wbSize).equalsIgnoreCase(clean(value))
                        && normalized.contains("росси")) return type;
            }
            // Numeric techSize alone cannot distinguish Russian, EU, US or other sizing.
            // Do not label an international size as Russian just because it is the sole option.
            if (types.contains("")) return "";
            if (types.contains("---")) return "---";
            return null;
        }
        if (types.size() == 1) return types.getFirst();
        if (types.contains("")) return "";
        if (types.contains("---")) return "---";
        return null;
    }

    static String automaticValue(Attribute attribute, Sku sku, List<WbCharacteristic> characteristics,
                                 String fullTnved, String feedTnved) {
        String name = normalize(attribute.name());
        long id = attribute.id();
        if (id == GOOD_NAME || name.contains("полное наименование")) return defaultName(sku);
        if (id == BRAND || name.contains("товарный знак") || name.equals("бренд")) {
            return brand(sku, characteristics);
        }
        if (id == FULL_TNVED && clean(feedTnved).length() == 4) return digits(fullTnved);
        if (id == MODEL || name.contains("артикул производителя") || name.contains("модель")) {
            return clean(sku.vendorCode());
        }
        if (id == SIZE || name.contains("размер одежды") || name.equals("размер")) return clean(sku.size());
        if (id == COLOR || name.contains("цвет")) return firstCharacteristic(characteristics,
                List.of("цвет"), clean(sku.color()));
        if (id == COMPOSITION || name.contains("состав")) return firstCharacteristic(characteristics,
                List.of("состав"), "");
        if (id == GENDER || name.contains("целевой пол") || name.equals("пол")) {
            return gender(attribute, sku, characteristics);
        }
        if (id == GOODS_KIND || name.contains("вид товара")) return clean(sku.subjectName());
        if (name.contains("технического регламента") || name.contains("технический регламент")) {
            return presetContaining(attribute, "017/2011");
        }

        String exact = matchingCharacteristic(characteristics, name);
        if (!exact.isBlank()) return exact;
        if (name.contains("страна происхождения") || name.contains("страна производства")) {
            return firstCharacteristic(characteristics, List.of("страна производства", "страна происхождения"), "");
        }
        if (name.contains("производител")) {
            return firstCharacteristic(characteristics, List.of("производитель", "изготовитель"), "");
        }
        return "";
    }

    static String resolvePreset(Attribute attribute, String source) {
        String value = clean(source);
        if (attribute.presets().isEmpty()) return value;
        if (value.isBlank()) return "";
        String normalized = normalize(value);
        return attribute.presets().stream()
                .map(preset -> new ScoredPreset(preset, scorePreset(normalized, normalize(preset))))
                .filter(item -> item.score > 0)
                .max(Comparator.comparingInt(ScoredPreset::score))
                .map(ScoredPreset::value)
                .orElse(attribute.presetOnly() ? "" : value);
    }

    private static int scorePreset(String source, String preset) {
        if (source.equals(preset)) return 1000;
        if (source.contains(preset) || preset.contains(source)) return 500 + Math.min(source.length(), preset.length());
        Set<String> sourceWords = words(source);
        Set<String> presetWords = words(preset);
        int overlap = 0;
        for (String word : sourceWords) if (word.length() > 2 && presetWords.contains(word)) overlap++;
        return overlap * 20;
    }

    private static String matchingCharacteristic(List<WbCharacteristic> characteristics, String targetName) {
        WbCharacteristic best = null;
        int bestScore = 0;
        for (WbCharacteristic characteristic : safe(characteristics)) {
            String source = normalize(characteristic.name());
            int score = source.equals(targetName) ? 1000
                    : source.length() > 3 && (source.contains(targetName) || targetName.contains(source)) ? 100 : 0;
            if (score > bestScore && !characteristic.joinedValue().isBlank()) {
                best = characteristic;
                bestScore = score;
            }
        }
        return best == null ? "" : best.joinedValue();
    }

    private static String firstCharacteristic(List<WbCharacteristic> values, List<String> names, String fallback) {
        for (String expected : names) {
            for (WbCharacteristic value : safe(values)) {
                String name = normalize(value.name());
                if ((name.equals(expected) || name.contains(expected)) && !value.joinedValue().isBlank()) {
                    return value.joinedValue();
                }
            }
        }
        return clean(fallback);
    }

    private static String gender(Attribute attribute, Sku sku, List<WbCharacteristic> characteristics) {
        String explicit = firstCharacteristic(characteristics,
                List.of("пол", "целевой пол", "для кого", "назначение", "целевая аудитория"), "");
        String source = normalize(explicit + " " + clean(sku.title()) + " "
                + clean(sku.subjectName()) + " " + clean(sku.vendorCode()));
        GenderKind kind = genderKind(source);
        if (kind == GenderKind.UNKNOWN) kind = GenderKind.UNISEX;

        // National Catalog validates preset values literally. Classify the WB wording first,
        // then return the exact value (including spelling/case) supplied by the Znack schema.
        for (String preset : attribute.presets()) {
            if (genderKind(normalize(preset)) == kind) return preset;
        }

        // Do not guess a binary gender from the grammar of the product name. If this category
        // explicitly supports a neutral preset, it is the only safe fully automatic fallback.
        return attribute.presets().isEmpty() ? kind.canonicalValue : "";
    }

    private static String brand(Sku sku, List<WbCharacteristic> characteristics) {
        String wbBrand = clean(sku.brand());
        if (!wbBrand.isBlank()) return wbBrand;
        return firstCharacteristic(characteristics, List.of("бренд", "товарный знак"), NO_BRAND);
    }

    private static String presetContaining(Attribute attribute, String needle) {
        return attribute.presets().stream().filter(value -> value.contains(needle)).findFirst().orElse("");
    }

    private static GenderKind genderKind(String value) {
        String source = normalize(value);
        Set<String> sourceWords = words(source);
        boolean female = containsAny(source, "женск", "женщин", "девоч", "девуш")
                || sourceWords.contains("female") || sourceWords.contains("women") || sourceWords.contains("woman");
        boolean male = containsAny(source, "мужск", "мужчин", "мальчик", "парн")
                || sourceWords.contains("male") || sourceWords.contains("men") || sourceWords.contains("man");
        boolean neutral = source.contains("унисекс") || sourceWords.contains("unisex") || female && male;
        if (neutral) return GenderKind.UNISEX;
        if (female) return GenderKind.FEMALE;
        if (male) return GenderKind.MALE;
        return GenderKind.UNKNOWN;
    }

    private static boolean containsAny(String source, String... needles) {
        for (String needle : needles) if (source.contains(needle)) return true;
        return false;
    }

    private enum GenderKind {
        MALE("МУЖСКОЙ"), FEMALE("ЖЕНСКИЙ"), UNISEX("УНИСЕКС"), UNKNOWN("");

        private final String canonicalValue;

        GenderKind(String canonicalValue) {
            this.canonicalValue = canonicalValue;
        }
    }

    public static String defaultName(Sku sku) {
        List<String> parts = new ArrayList<>();
        add(parts, first(sku.title(), sku.subjectName()));
        add(parts, sku.brand());
        if (!clean(sku.vendorCode()).isBlank()) add(parts, "арт. " + clean(sku.vendorCode()));
        if (!clean(sku.color()).isBlank()) add(parts, "цвет " + clean(sku.color()));
        if (!clean(sku.size()).isBlank()) add(parts, "размер " + clean(sku.size()));
        return String.join(", ", parts).replaceAll("\\s+", " ").trim();
    }

    public static String findTnved(List<WbCharacteristic> characteristics) {
        for (WbCharacteristic characteristic : safe(characteristics)) {
            String name = normalize(characteristic.name());
            if (name.contains("тн вэд") || name.contains("тнвэд")) {
                String value = characteristic.values().stream().findFirst().orElse("");
                String digits = digits(value);
                if (digits.length() == 10) return digits;
            }
        }
        return "";
    }

    private static boolean isCertificate(String type) {
        return normalize(type).contains("certificate") || normalize(type).contains("сертификат");
    }

    private static List<WbCharacteristic> safe(List<WbCharacteristic> values) {
        return values == null ? List.of() : values;
    }

    private static Set<String> words(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : value.split("[^\\p{L}\\p{N}]+")) if (!word.isBlank()) result.add(word);
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(clean(value).toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFKC).trim();
    }

    private static String digits(String value) { return clean(value).replaceAll("\\D", ""); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String first(String... values) {
        for (String value : values) if (!clean(value).isBlank()) return clean(value);
        return "";
    }
    private static void add(List<String> values, String value) { if (!clean(value).isBlank()) values.add(clean(value)); }

    public record MappingResult(String goodName, String brand, Map<Long, String> attributes,
                                List<String> missingFields, Map<Long, String> attributeTypes) {
        public boolean complete() { return missingFields.isEmpty(); }
    }

    private record ScoredPreset(String value, int score) { }
}
