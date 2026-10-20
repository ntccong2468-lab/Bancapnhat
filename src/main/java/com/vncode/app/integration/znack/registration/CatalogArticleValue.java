package com.vncode.app.integration.znack.registration;

/** Catalog's article storage behavior observed in WBCode 1.3.9. Separators remain identity data. */
public final class CatalogArticleValue {
    private CatalogArticleValue(){}
    public static String stored(String value) {
        if(value==null)return "";
        var output=new StringBuilder();
        for(int code:value.codePoints().toArray()) {
            boolean combining=code>=0x0300&&code<=0x036f;
            boolean unsupportedLatin=code>255&&code!=0x0110&&code!=0x0111&&Character.UnicodeScript.of(code)==Character.UnicodeScript.LATIN;
            if(!combining&&!unsupportedLatin)output.appendCodePoint(code);
        }
        return output.toString().replaceAll("(?U)\\s+"," ").strip();
    }
}
