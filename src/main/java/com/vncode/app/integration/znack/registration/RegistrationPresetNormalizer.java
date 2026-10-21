package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts source wording to a unique literal in this category's current Catalog schema. */
final class RegistrationPresetNormalizer {
    private RegistrationPresetNormalizer() {}
    private static final Pattern SOURCE_REGULATION=Pattern.compile("(?iu)(?:ТР\\s*(ТС|ЕАЭС)\\s*)?([0-9]{3})\\s*/\\s*([0-9]{4})");
    private static final Pattern LITERAL_REGULATION=Pattern.compile("(?iu)(?<![\\p{L}0-9])ТР\\s*(ТС|ЕАЭС)\\s*([0-9]{3})\\s*/\\s*([0-9]{4})(?![0-9])");
    // These are grammatical forms of the same garment, not a list of interchangeable products.
    private static final Map<String,String> GARMENT_FORMS=Map.ofEntries(
            Map.entry("костюмы","костюм"),Map.entry("костюмов","костюм"),
            Map.entry("спортивные","спортивный"),Map.entry("спортивная","спортивный"),Map.entry("спортивное","спортивный"),Map.entry("спортивных","спортивный"),
            Map.entry("куртки","куртка"),Map.entry("курток","куртка"),
            Map.entry("жилеты","жилет"),Map.entry("жилетов","жилет"),
            Map.entry("брюк","брюки"),Map.entry("шорт","шорты"),
            Map.entry("свитшоты","свитшот"),Map.entry("свитшотов","свитшот"),
            Map.entry("толстовки","толстовка"),Map.entry("толстовок","толстовка"),
            Map.entry("футболки","футболка"),Map.entry("футболок","футболка"),
            Map.entry("джемперы","джемпер"),Map.entry("джемперов","джемпер"),Map.entry("джемпера","джемпер"),
            Map.entry("свитеры","свитер"),Map.entry("свитеров","свитер"),Map.entry("свитера","свитер"),
            Map.entry("кардиганы","кардиган"),Map.entry("кардиганов","кардиган"),
            Map.entry("пуловеры","пуловер"),Map.entry("пуловеров","пуловер"),
            Map.entry("рубашки","рубашка"),Map.entry("рубашек","рубашка"),
            Map.entry("сорочки","сорочка"),Map.entry("сорочек","сорочка"),
            Map.entry("платья","платье"),Map.entry("платьев","платье"),
            Map.entry("юбки","юбка"),Map.entry("юбок","юбка"),
            Map.entry("пиджаки","пиджак"),Map.entry("пиджаков","пиджак"),
            Map.entry("жакеты","жакет"),Map.entry("жакетов","жакет"),
            Map.entry("комбинезоны","комбинезон"),Map.entry("комбинезонов","комбинезон"),
            Map.entry("комплекты","комплект"),Map.entry("комплектов","комплект")
    );

    static String resolve(Attribute attribute,String source) {
        String value=RegistrationTemplate.clean(source);
        if(value.isBlank()||attribute.presets().isEmpty()||attribute.presets().contains(value))return value;
        String normalized=normalize(value);
        var matches=attribute.presets().stream().filter(preset->normalize(preset).equals(normalized)).toList();
        if(matches.size()==1)return matches.getFirst();
        if(attribute.id()==12L){
            var sourceForms=garmentForms(normalized);
            matches=sourceForms.isEmpty()?List.of():attribute.presets().stream().filter(preset->garmentForms(normalize(preset)).equals(sourceForms)).toList();
            if(matches.size()==1)return matches.getFirst();
        }
        if(attribute.id()==13836L){
            var regulation=SOURCE_REGULATION.matcher(value);
            if(regulation.matches()){
                String jurisdiction=regulation.group(1),code=regulation.group(2)+"/"+regulation.group(3);
                matches=attribute.presets().stream().filter(preset->sameRegulation(preset,jurisdiction,code)).toList();
                if(matches.size()==1)return matches.getFirst();
            }
        }
        return attribute.presetOnly()?"":value;
    }

    private static boolean sameRegulation(String literal,String jurisdiction,String code){
        var candidate=LITERAL_REGULATION.matcher(literal);
        if(!candidate.find())return false;
        boolean same=(jurisdiction==null||jurisdiction.equalsIgnoreCase(candidate.group(1)))
                &&code.equals(candidate.group(2)+"/"+candidate.group(3));
        return same&&!candidate.find();
    }

    private static List<String> garmentForms(String text){
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .filter(word->!word.isBlank()).map(word->GARMENT_FORMS.getOrDefault(word,word)).sorted().toList();
    }
    private static String normalize(String value){
        return Normalizer.normalize(RegistrationTemplate.clean(value),Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ").strip();
    }
}
