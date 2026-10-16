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
 @Test void automaticIndexAndManualMatchingUseTheSameCatalogArticleRules(){
  var first=row("00000000000017","PANTS-1","Black","M",true);
  var index=new GtinCatalogIndex(List.of(first));
  assertEquals(List.of(first),index.attributes("PANTSấ-1","Black","M"));
  assertTrue(index.attributes("PANTS1","Black","M").isEmpty());
  assertTrue(index.attributes("ấố","Black","M").isEmpty());
  var second=row("00000000000024","PANTSố-1","Black","M",true);
  assertEquals(2,new GtinCatalogIndex(List.of(first,second)).attributes("PANTSấ-1","Black","M").size());
  var key=new GtinSyncModels.ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"1","1");
  var product=new GtinSyncModels.ProductSnapshot(key,"ＰＡＮＴＳ-1","Black","M",List.of(),"fixture",Instant.now());
  assertEquals(index.attributes(product.article(),product.color(),product.size()),new GtinMatchingService().match(product,List.of(first)).candidates());
 }
 @Test void russianSizeRequiresTheCatalogTypeAndTheMarketplaceExplicitRussianSize(){
  var source=new GtinSyncModels.RegisteredGtin("00000000000017","SUIT-1","Beige","54",true,true,"fixture",Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@size_type",List.of("РОССИЯ")),List.of(),List.of()));
  var index=new GtinCatalogIndex(List.of(source));assertEquals(List.of(source),index.attributes("SUIT-1","Beige","3XL","54"));
  assertTrue(index.attributes("SUIT-1","Beige","3XL","52").isEmpty());assertTrue(index.attributes("SUIT-1","Beige","3XL").isEmpty());
  var european=new GtinSyncModels.RegisteredGtin("00000000000024","SUIT-1","Beige","54",true,true,"fixture",Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@size_type",List.of("ЕВРОПА")),List.of(),List.of()));
  assertTrue(new GtinCatalogIndex(List.of(european)).attributes("SUIT-1","Beige","3XL","54").isEmpty());
 }
}
