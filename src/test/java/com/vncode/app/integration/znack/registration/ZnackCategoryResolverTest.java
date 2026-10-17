package com.vncode.app.integration.znack.registration;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ZnackCategoryResolverTest {
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
}
