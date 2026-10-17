package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SellerArticleTest {
 @Test void removesUnicodeWhitespaceOnlyAtTheEdges(){
  assertEquals("152DASH-52",SellerArticle.trim("\u00a0\t152DASH-52\r\n\u202f"));
  assertEquals("bo  polo - den",SellerArticle.trim(" bo  polo - den "));
  assertEquals("",SellerArticle.trim("\u2007\u00a0 "));
  assertEquals("",SellerArticle.trim(null));
 }
}
