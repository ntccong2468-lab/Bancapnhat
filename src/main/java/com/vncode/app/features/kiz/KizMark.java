package com.vncode.app.features.kiz;

import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import java.util.*;

/** Parses explicit GS1 delimiters; it never guesses where an undelimited serial ends. */
public record KizMark(String raw,String gtin,String cis) {
    public static KizMark parse(String input){
        if(input==null||input.length()>1024)throw new IllegalArgumentException("KIZ_FORMAT_INVALID");
        String raw=input.strip();
        if(raw.startsWith("]d2"))raw=raw.substring(3);
        raw=KizService.scannerSafeCode(raw);
        // Punctuation inside a 13-character serial is data, including commas and '<GS>'.
        if(raw.length()==31&&raw.startsWith("01")&&raw.substring(16,18).equals("21")&&raw.chars().allMatch(c->c>=33&&c<=126)){
            String gtin=raw.substring(2,16);if(!RegisteredGtinValidator.isValid(gtin))throw new IllegalArgumentException("KIZ_GTIN_INVALID");return new KizMark(raw,gtin,raw);
        }
        raw=raw.replace("\\u001d","\u001d").replace("\\u001D","\u001d").replace("<GS>","\u001d").replace("_x001D_","\u001d").replace("_x001d_","\u001d");
        if(raw.startsWith("(01)")){
            raw=raw.replace("(01)","01").replace("(21)","21");
            for(String ai:List.of("91","92","93"))raw=raw.replace("("+ai+")","\u001d"+ai);
        }
        if(raw.length()<23||!raw.startsWith("01")||!raw.substring(16,18).equals("21")||raw.chars().anyMatch(c->c!=29&&(c<33||c>126)))throw new IllegalArgumentException("KIZ_FORMAT_INVALID");
        String gtin=raw.substring(2,16);if(!RegisteredGtinValidator.isValid(gtin))throw new IllegalArgumentException("KIZ_GTIN_INVALID");
        int end=raw.indexOf(29,18);
        if(end<0){if(raw.length()==31)return new KizMark(raw,gtin,raw);throw new IllegalArgumentException("KIZ_CRYPTO_MISSING");}
        int serialLength=end-18;if(serialLength<1||serialLength>20)throw new IllegalArgumentException("KIZ_SERIAL_INVALID");
        var ais=new HashSet<String>();
        for(String segment:raw.substring(end+1).split("\u001d",-1)){
            if(segment.length()<3||!Set.of("91","92","93").contains(segment.substring(0,2))||!ais.add(segment.substring(0,2)))throw new IllegalArgumentException("KIZ_FORMAT_INVALID");
        }
        if(!(ais.contains("91")&&ais.contains("92"))&&!ais.contains("93"))throw new IllegalArgumentException("KIZ_CRYPTO_MISSING");
        return new KizMark(raw,gtin,raw.substring(0,end));
    }
    public boolean fullDataMatrix(){return raw.indexOf(29)>=0;}
    @Override public String toString(){return "KizMark[redacted]";}
}
