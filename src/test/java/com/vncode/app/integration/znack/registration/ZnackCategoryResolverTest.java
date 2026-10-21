package com.vncode.app.integration.znack.registration;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ZnackCategoryResolverTest {
 @Test void hoodiesAndSweatshirtsResolveToTheCurrentActiveKnitwearLeaf(){
  var knitwear=new Category(30694,"Джемперы, свитеры, пуловеры");
  var choices=List.of(new Category(31326,"Одежда второго и третьего слоя"),new Category(214940,"Кардиганы и жилеты"),knitwear);
  for(String subject:List.of("Свитшоты","Худи","Толстовки"))assertEquals(knitwear,ZnackCategoryResolver.resolve(subject,"Худи или свитшот",choices).orElseThrow());
 }
 @Test void selectsTheMarketplaceTypeRatherThanAnAccessoryMentionedInTheTitle(){
  var suit=new Category(1,"Костюмы спортивные");var pants=new Category(2,"Брюки мужские");
  assertEquals(suit,ZnackCategoryResolver.resolve("Костюмы спортивные","Костюм с брюками",List.of(pants,suit)).orElseThrow());
 }
 @Test void resolvesInflectionButDoesNotPickTheFirstAmbiguousCategory(){
  var one=new Category(1,"Футболки мужские");var two=new Category(2,"Рубашки мужские");
  assertEquals(one,ZnackCategoryResolver.resolve("Футболка","",List.of(two,one)).orElseThrow());
  assertTrue(ZnackCategoryResolver.resolve("Одежда","",List.of(two,one)).isEmpty());
  assertTrue(ZnackCategoryResolver.resolve("Костюмы спортивные","",List.of(new Category(3,"Костюмы спортивные хлопок"),new Category(4,"Костюмы спортивные синтетика"))).isEmpty());
 }
 @Test void singlePreflightCategoryIsAuthoritativeAndEmptyListCannotRegister(){
  var category=new Category(19,"Одежда");assertEquals(category,ZnackCategoryResolver.resolve("Брюки","",List.of(category)).orElseThrow());assertTrue(ZnackCategoryResolver.resolve("Брюки","",List.of()).isEmpty());
 }
 @Test void actualCatalogTaxonomySelectsSuitsAndTrousersWithoutConfusingSkirtTrousers()throws Exception{
  try(var input=getClass().getResourceAsStream("/catalog-active-categories-6112.json")){
   var array=com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();var categories=new ArrayList<Category>();
   for(var row:array){var object=row.getAsJsonObject();categories.add(new Category(object.get("id").getAsLong(),object.get("name").getAsString()));}
   assertEquals(30733,ZnackCategoryResolver.resolve("Костюмы спортивные","Спортивный костюм",categories).orElseThrow().id());
   assertEquals(30683,ZnackCategoryResolver.resolve("Брюки","Брюки спортивные",categories).orElseThrow().id());
   assertEquals(30683,ZnackCategoryResolver.resolve("Брюки спортивные","Брюки мужские",categories).orElseThrow().id());
   assertEquals(30685,ZnackCategoryResolver.resolve("Юбки","Юбка",categories).orElseThrow().id());
  }
 }

}
