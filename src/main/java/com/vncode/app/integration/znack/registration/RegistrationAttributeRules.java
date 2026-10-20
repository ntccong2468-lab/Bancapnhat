package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import java.util.*;

/** Published /attributes rules: conditional requirements and literal field types. */
public final class RegistrationAttributeRules {
    private RegistrationAttributeRules(){}
    public static List<Attribute> required(List<Attribute> base,Map<Long,String> values){
        var required=new LinkedHashMap<Long,Attribute>();var queue=new ArrayDeque<>(base);
        while(!queue.isEmpty()){
            var a=queue.removeFirst();if(required.putIfAbsent(a.id(),a)!=null)continue;
            String actual=Objects.toString(values.get(a.id()),"").strip();
            a.dependencies().forEach((value,children)->{if(value.equals(actual))queue.addAll(children);});
        }
        return List.copyOf(required.values());
    }
    public static boolean validFieldValue(Attribute attribute,String value){
        if(value==null||value.isBlank())return false;
        try{return switch(Objects.toString(attribute.fieldType(),"").toLowerCase(Locale.ROOT)){
            case "number"->{new java.math.BigDecimal(value.strip());yield true;}
            case "date"->{java.time.LocalDate.parse(value.strip());yield true;}
            default->true;
        };}catch(NumberFormatException|java.time.DateTimeException invalid){return false;}
    }
    /** Short dependent references get their label/presets/type from the current complete model. */
    static List<Attribute> resolve(List<Attribute> base,List<Attribute> all){
        var index=new HashMap<Long,Attribute>();all.forEach(a->index.put(a.id(),a));
        return base.stream().map(a->resolve(a,index,new HashSet<>())).toList();
    }
    private static Attribute resolve(Attribute reference,Map<Long,Attribute> index,Set<Long> path){
        if(!index.containsKey(reference.id())&&(reference.name().isBlank()||Objects.toString(reference.fieldType(),"").isBlank()))throw new IllegalStateException("CATALOG_CONDITIONAL_SCHEMA_UNAVAILABLE: "+reference.id());
        var source=index.getOrDefault(reference.id(),reference);if(!path.add(source.id()))return new Attribute(source.id(),source.name(),source.fieldType(),source.presetOnly(),source.multiple(),source.firstLayer(),source.secondLayer(),source.presets(),source.valueTypes());
        var dependencies=new LinkedHashMap<String,List<Attribute>>();var from=reference.dependencies().isEmpty()?source.dependencies():reference.dependencies();
        from.forEach((value,children)->dependencies.put(value,children.stream().map(a->resolve(a,index,new HashSet<>(path))).toList()));
        return new Attribute(source.id(),source.name().isBlank()?"Catalog #"+source.id():source.name(),source.fieldType(),source.presetOnly(),source.multiple(),source.firstLayer(),source.secondLayer(),source.presets(),source.valueTypes(),dependencies);
    }
}
