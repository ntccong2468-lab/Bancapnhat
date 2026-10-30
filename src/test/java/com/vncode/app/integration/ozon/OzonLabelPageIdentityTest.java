package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OzonLabelPageIdentityTest {
    @Test void tokenIndexPreservesBoundariesUnicodeAndSharedTransportOwners() throws Exception {
        var numbers=List.of("900-1", "900-12", "900-2");
        var postings=Map.of("900-1", posting("900-1", "ОЗОН-42", "SHARED"),
                "900-12", posting("900-12", "ＡＢＣ-４２", "SHARED"),
                "900-2", posting("900-2", "ROUTE-3", ""));
        try(var identity=new OzonLabelPageIdentity(null,null,numbers,postings)) {
            assertEquals(Set.of("900-12"),identity.matches("route: 900 – 12!"));
            assertEquals(Set.of("900-1"),identity.matches("ОЗОН − 42"));
            assertEquals(Set.of("900-12"),identity.matches("ABC-42"));
            assertEquals(Set.of("900-1","900-12"),identity.matches("[SHARED]"));
            assertEquals(Set.of(),identity.matches("X900-1 / 900-12X / 900-2-3"));
            assertEquals(Set.of("900-2"),identity.matches("_900-2_"));
        }
    }

    @Test void punctuationIdentifiersKeepLongestFirstNonOverlappingMatcher() throws Exception {
        var numbers=List.of("900-1", "900-2");
        var postings=Map.of("900-1",posting("900-1","ABC",""),
                "900-2",posting("900-2","ABC.",""));
        try(var identity=new OzonLabelPageIdentity(null,null,numbers,postings)) {
            assertEquals(Set.of("900-2"),identity.matches("ABC."));
            assertEquals(Set.of("900-1","900-2"),identity.matches("ABC ABC."));
            assertEquals(Set.of(),identity.matches("XABC."));
        }
    }

    @Test void thousandIdentifiersMatchThePreviousMatcherForReversedAndNoisyPages() throws Exception {
        var numbers=new ArrayList<String>();var postings=new LinkedHashMap<String,OzonPostingDto>();
        var owners=new LinkedHashMap<String,Set<String>>();
        for(int index=0;index<1000;index++) {
            String number="90000000-"+String.format(java.util.Locale.ROOT,"%04d",index)+"-1";
            String route="ROUTE-"+index;
            numbers.add(number);postings.put(number,posting(number,route,""));
            add(owners,number,number);add(owners,route,number);
        }
        String alternatives=owners.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        var previous=Pattern.compile("(?<![\\p{L}\\p{N}-])(?:"+alternatives+")(?![\\p{L}\\p{N}-])");
        var random=new Random(19024);
        try(var identity=new OzonLabelPageIdentity(null,null,numbers,postings)) {
            for(int page=999;page>=0;page--) {
                String number=numbers.get(page);
                String text=switch(page%5) {
                    case 0 -> "номер: "+number.replace("-", " – ");
                    case 1 -> "prefix"+number+"suffix";
                    case 2 -> "route (ROUTE-"+page+") and "+numbers.get(random.nextInt(numbers.size()));
                    case 3 -> "before_"+number+"_after";
                    default -> number+"-extra";
                };
                var expected=new LinkedHashSet<String>();var matcher=previous.matcher(OzonLabelPageIdentity.normalize(text));
                while(matcher.find())expected.addAll(owners.getOrDefault(matcher.group(),Set.of()));
                assertEquals(expected,identity.matches(text),"page "+page);
            }
        }
    }

    private static OzonPostingDto posting(String number,String lower,String upper) {
        return new OzonPostingDto(number,"","","awaiting_deliver","","","","",lower,upper,
                null,List.of(),false,List.of());
    }

    private static void add(Map<String,Set<String>> owners,String value,String number) {
        owners.computeIfAbsent(OzonLabelPageIdentity.normalize(value),ignored->new LinkedHashSet<>()).add(number);
    }
}
