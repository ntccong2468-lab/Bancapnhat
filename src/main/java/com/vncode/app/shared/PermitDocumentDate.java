package com.vncode.app.shared;

import java.time.*;
import java.time.format.*;

/** Unambiguous Catalog/registry date formats; invalid dates remain invalid evidence. */
public final class PermitDocumentDate {
    private static final DateTimeFormatter DOTTED=DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private PermitDocumentDate(){}
    public static String normalize(String value){
        String text=value==null?"":value.strip();if(text.isBlank())return "";
        for(var format:new DateTimeFormatter[]{DateTimeFormatter.ISO_LOCAL_DATE,DOTTED}){
            try{return LocalDate.parse(text,format).toString();}
            catch(DateTimeParseException invalid){/* Try only the next explicitly supported format. */}
        }
        return text;
    }
}
