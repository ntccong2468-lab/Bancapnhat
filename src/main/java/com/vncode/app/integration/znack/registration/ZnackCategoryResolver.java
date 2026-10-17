package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category;
import java.text.Normalizer;
import java.util.*;

/** Select only within the TN VED preflight candidates, using the marketplace product type. */
public final class ZnackCategoryResolver {
    private static final List<String> FAMILIES=List.of("костюм","брюк","шорт","футбол","рубаш","плать","юбк","свитшот","худи","жилет","куртк","обув","носк");
    private static final Set<String> GENERIC=Set.of("товар","одежд","издел","прочи","мужск","женск","детск","спорти","тексти");
    private ZnackCategoryResolver(){}
    public static Optional<Category> resolve(ProductSnapshot product,List<Category> candidates){return resolve(product.metadata().getOrDefault("source.category.name",""),product.title(),candidates);}
    public static Optional<Category> resolve(String marketplaceCategory,String title,List<Category> candidates){
        if(candidates==null||candidates.isEmpty())return Optional.empty();
        if(candidates.size()==1)return Optional.of(candidates.getFirst());
        String source=normalize(marketplaceCategory),fallback=normalize(title);boolean categoryKnown=!source.isBlank();if(!categoryKnown)source=fallback;
        Set<String> family=families(source);Category winner=null;int best=0;boolean tied=false;
        for(var candidate:candidates){
            String name=normalize(candidate.name());Set<String> target=families(name);
            if(!family.isEmpty()&&!target.isEmpty()&&Collections.disjoint(family,target))continue;
            int score=0;
            if(!source.isBlank()&&source.equals(name))score=1000;
            else if(source.length()>4&&name.contains(source))score=800;
            else {
                var tokens=tokens(source);var other=tokens(name);
                boolean equivalent=!tokens.isEmpty()&&tokens.equals(other);tokens.retainAll(other);score=equivalent?950:tokens.size()*20;
                var overlap=new HashSet<>(family);overlap.retainAll(target);score+=overlap.size()*100;
            }
            // Prefer the primary garment over a secondary mention, e.g. trousers versus skirt-trousers.
            if(score>0&&family.stream().anyMatch(name::startsWith))score+=50;
            if(score>best){winner=candidate;best=score;tied=false;}else if(score==best&&score>0)tied=true;
        }
        return best>=20&&!tied?Optional.of(winner):Optional.empty();
    }
    private static String normalize(String text){return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
    private static Set<String> families(String text){var out=new HashSet<String>();for(String stem:FAMILIES)if(text.contains(stem))out.add(stem);return out;}
    private static Set<String> tokens(String text){var out=new HashSet<String>();for(String token:text.split(" +")){String stem=token.substring(0,Math.min(5,token.length()));if(stem.length()>=4&&!GENERIC.contains(stem))out.add(stem);}return out;}
}
