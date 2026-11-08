package com.vncode.app.shared;

import java.util.regex.Pattern;

/** Corrects visual keyboard-script substitutions only inside recognized EAEU registry blocks. */
public final class PermitDocumentNumber {
    private static final Pattern REGISTRY=Pattern.compile(
            "((?:ЕАЭС )?(?:N )?RU [ДС]-[A-Z]{2}\\.)([А-ЯЁABCEHKMOPTXY]{2})([0-9]{2}\\.)([А-ЯЁABCEHKMOPTXY])(\\.[0-9]{5}/[0-9]{2})");
    private static final String LATIN="ABCEHKMOPTXY",CYRILLIC="АВСЕНКМОРТХУ";
    private PermitDocumentNumber(){}

    public static String normalize(String value){
        String text=value==null?"":value.strip();
        var match=REGISTRY.matcher(text);
        if(!match.matches())return text;
        // Country codes, N, digits, separators and unsupported formats are never transliterated.
        return match.group(1)+registryLetters(match.group(2))+match.group(3)+registryLetters(match.group(4))+match.group(5);
    }
    private static String registryLetters(String value){
        var result=new StringBuilder(value.length());
        for(int i=0;i<value.length();i++){
            char letter=value.charAt(i);int position=LATIN.indexOf(letter);
            result.append(position<0?letter:CYRILLIC.charAt(position));
        }
        return result.toString();
    }
}
