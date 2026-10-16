package com.vncode.app.features.tnved;

import com.vncode.app.features.products.*;
import com.vncode.app.features.tnved.FashionTnvedCatalog.Entry;
import java.util.*;
import java.util.regex.*;

/** Conservative proposals from product facts; incomplete or mixed facts never pick a code automatically. */
public final class ProductTnvedAdvisor {
    public record Proposal(ProductKey key,String title,String current,List<Entry> candidates,boolean unique,String reason){
        public Proposal {candidates=List.copyOf(candidates);}
    }
    private final FashionTnvedCatalog catalog=new FashionTnvedCatalog();
    public Proposal propose(ProductSnapshot product){
        String title=product.title(),category=product.metadata().getOrDefault("source.category.name","");
        String text=FashionTnvedCatalog.normalize(category+" "+title);var tokens=stems(text);
        var ranked=new TreeMap<Integer,Set<String>>(Comparator.reverseOrder());
        for(String candidate:catalog.categories()){var words=stems(candidate);if(!words.isEmpty()&&tokens.containsAll(words))ranked.computeIfAbsent(words.stream().mapToInt(String::length).sum(),k->new LinkedHashSet<>()).add(candidate);}
        Set<String> categories=ranked.isEmpty()?Set.of():ranked.firstEntry().getValue();
        String materials=field(product,"material"),gender=gender(field(product,"gender")),material=dominantMaterial(materials),fabric=fabric(product);
        var candidates=categories.isEmpty()?List.<Entry>of():catalog.search("",categories,gender,material,fabric);
        boolean complete=!categories.isEmpty()&&!gender.isBlank()&&!material.isBlank()&&(!fabric.isBlank()||candidates.stream().allMatch(e->e.fabric().isBlank()));
        String current=field(product,"tnved");String reason=categories.isEmpty()?"category":gender.isBlank()?"gender":material.isBlank()?"material":!complete?"fabric":candidates.size()!=1?"ambiguous":"unique";
        return new Proposal(product.key(),title,current,candidates,candidates.size()==1&&complete,reason);
    }
    public Proposal unavailable(ProductKey key){return new Proposal(key,key.cardId(),"",List.of(),false,"unloaded");}
    private static Set<String> stems(String text){
        var result=new HashSet<String>();for(String token:FashionTnvedCatalog.normalize(text).split("[^\\p{L}\\p{N}]+"))if(token.length()>=4){String stem=token.replaceFirst("(?:овые|овый|овая|ские|ский|ская|ные|ный|ная|ые|ый|ая|ие|ий|\u044b|а|я|и)$","");if(stem.length()>=4)result.add(stem);}return result;
    }
    private static String field(ProductSnapshot p,String name){return String.join(", ",p.attributes().stream().filter(a->ProductFieldNames.matches(name,a.name())&&(a.complexId().isBlank()||a.complexId().equals("0"))).flatMap(a->a.values().stream()).toList());}
    private static String gender(String value){String text=FashionTnvedCatalog.normalize(value);if(text.contains("унисекс")||text.equals("unisex"))return "Унисекс";if(text.contains("муж")||text.equals("male"))return "Мужской";if(text.contains("жен")||text.equals("female"))return "Женский";return "";}
    private static String fabric(ProductSnapshot p){
        String value=String.join(" ",p.attributes().stream().filter(a->FashionTnvedCatalog.normalize(a.name()).matches(".*(?:тип ткани|вид ткани|вид полотна|фактура материала|плетение).*" )).flatMap(a->a.values().stream()).toList());
        String text=FashionTnvedCatalog.normalize(value);if(text.contains("трикотаж"))return "Трикотаж";if(text.contains("тканый")||text.equals("ткань"))return "Ткань";return "";
    }
    static String dominantMaterial(String value){
        String text=FashionTnvedCatalog.normalize(value);var found=new LinkedHashMap<String,Integer>();
        var pattern=Pattern.compile("(?:(\\d{1,3})\\s*%?\\s*)?(хлоп[а-я]*|cotton|полиэстер|polyester|полиамид|нейлон|эластан|акрил|синтет[а-я]*|вискоз[а-я]*|модал|лиоцелл|шерст[а-я]*|кашемир|лен|лён|шелк|шёлк)(?:\\s*[:\\-]?\\s*(\\d{1,3})\\s*%?)?");
        var matcher=pattern.matcher(text);int total=0;boolean missing=false;
        while(matcher.find()){
            String name=matcher.group(2);String category=name.startsWith("хлоп")||name.equals("cotton")?"Хлопок":name.startsWith("вискоз")||Set.of("модал","лиоцелл").contains(name)?"Искусственные нити":name.startsWith("шерст")||name.equals("кашемир")?"Шерсть":name.equals("лен")?"Лён":name.equals("шелк")?"Шёлк":"Синтетика";
            String amount=matcher.group(1)!=null?matcher.group(1):matcher.group(3);int percentage=amount==null?0:Integer.parseInt(amount);missing|=amount==null;total+=percentage;found.merge(category,percentage,Integer::sum);
        }
        if(found.isEmpty()||total>100)return "";
        if(found.size()==1){
            String residue=pattern.matcher(text).replaceAll("").replaceAll("[\\s,;/+%()\\-]+","");
            if(residue.isBlank()&&(total==0||total==100))return found.keySet().iterator().next();
            if(total>50){int declared=0;var numbers=Pattern.compile("([0-9]{1,3})\\s*%").matcher(text);while(numbers.find())declared+=Integer.parseInt(numbers.group(1));if(declared==100)return found.keySet().iterator().next();}
            return "";
        }
        if(missing||total!=100)return "";
        return found.entrySet().stream().filter(e->e.getValue()>50).map(Map.Entry::getKey).findFirst().orElse("");
    }
}
