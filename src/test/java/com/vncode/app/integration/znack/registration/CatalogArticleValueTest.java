package com.vncode.app.integration.znack.registration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogArticleValueTest {
    @Test void onlyCatalogDroppedCharactersAreRemovedAndModelSeparatorsAreKept() {
        assertEquals("ABC-1 /_#",CatalogArticleValue.stored("ABCấ-1 /_#"));
        assertEquals("Đđ Àê ñ Кириллица",CatalogArticleValue.stored("Đđ Àê ñ Кириллица"));
        assertEquals("ê-1",CatalogArticleValue.stored("ê\u0301-1"));
        assertEquals("a b",CatalogArticleValue.stored("  a\t\n b  "));
        assertEquals("",CatalogArticleValue.stored(null));
    }
}
