package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NationalCatalogBatchReadTest {
    @Test void thousandGtinsUseFortyBoundedReadsAndAnUnknownCodeCannotExpandTheRequest()throws Exception{
        var requests=new ArrayList<String>();var cards=new NationalCatalogGtinSource.BatchedCards(group->{requests.add(group);return new JsonPrimitive(group);});
        var gtins=java.util.stream.IntStream.range(0,1000).mapToObj(i->String.format(Locale.ROOT,"%014d",i)).toList();cards.prepare(gtins);
        for(String gtin:gtins)assertTrue(cards.read(gtin).getAsString().contains(gtin));
        assertEquals(40,requests.size());assertTrue(requests.stream().allMatch(r->r.split(";").length==25));
        assertThrows(IllegalArgumentException.class,()->cards.read("unselected"));assertEquals(40,requests.size());
    }
    @Test void failedBatchIsNotRequestedAgainForEveryGtinAndANewSnapshotMayRetry()throws Exception{
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var cards=new NationalCatalogGtinSource.BatchedCards(group->{if(attempts.incrementAndGet()==1)throw new java.io.IOException("fixture failure");return new JsonPrimitive(group);});
        cards.prepare(List.of("first"));assertThrows(java.io.IOException.class,()->cards.read("first"));assertThrows(java.io.IOException.class,()->cards.read("first"));assertEquals(1,attempts.get());cards.prepare(List.of("first"));assertEquals("first",cards.read("first").getAsString());
        cards.prepare(List.of("second"));assertEquals("second",cards.read("second").getAsString());assertThrows(IllegalArgumentException.class,()->cards.read("first"));
    }
}
