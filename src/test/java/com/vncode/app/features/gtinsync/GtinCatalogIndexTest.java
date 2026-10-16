package com.vncode.app.features.gtinsync;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GtinCatalogIndexTest {
 GtinSyncModels.RegisteredGtin row(String gtin,String article,String color,String size,boolean published){return new GtinSyncModels.RegisteredGtin(gtin,article,color,size,true,published,"National Catalog@fixture",Instant.now());}
 @Test void indexesNormalizedArticlesWithoutInventingPunctuationOrSizeEquivalence(){
  var index=new GtinCatalogIndex(List.of(row("04645588781154","ＴＲＯＵＳＥＲＳ\u00a0  1","Black","XL",true)));
  assertEquals(1,index.attributes("TROUSERS 1"," black ","xl").size());
  assertTrue(index.attributes("TROUSERS-1","Black","XL").isEmpty());assertTrue(index.attributes("TROUSERS 1","Black","48").isEmpty());
  assertEquals(1,index.code("4645588781154").size());
 }
 @Test void missingAttributesDraftsAndAmbiguousMatchesDoNotSelectAnArbitraryGtin(){
  var index=new GtinCatalogIndex(List.of(row("00000000000017","A","Black","M",true),row("00000000000024","A","Black","M",true),row("04645588781154","DRAFT","Black","M",false)));
  assertEquals(2,index.attributes("A","Black","M").size());assertTrue(index.attributes("A","","M").isEmpty());assertTrue(index.attributes("DRAFT","Black","M").isEmpty());assertTrue(index.code("04645588781154").isEmpty());
 }
}
