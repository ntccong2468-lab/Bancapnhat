package com.tuandev.fbsbarcode.ui.gtinsync;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GtinTranslationTest {
    @Test void hasEquivalentNonemptyTranslationsInAllLanguages() {
        Set<String> expected=null;
        for(String language:List.of("vi","en","ru","zh")) {
            var bundle=ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages",Locale.forLanguageTag(language));
            var keys=new TreeSet<String>();bundle.keySet().stream().filter(k->k.startsWith("gtinsync.")).forEach(keys::add);
            assertTrue(keys.size()>40);
            for(String key:keys)assertFalse(bundle.getString(key).isBlank(),key);
            if(expected==null)expected=keys;else assertEquals(expected,keys);
        }
    }
}
