package com.vncode.app.features.gs1;
import java.util.OptionalLong;
public record GtinAvailability(OptionalLong available,boolean complete,int activePrefixes,int unknownPrefixes) {
    public GtinAvailability {if(available==null||activePrefixes<0||unknownPrefixes<0||unknownPrefixes>activePrefixes||available.isPresent()&&available.getAsLong()<0)throw new IllegalArgumentException("Invalid allowance summary");}
}
