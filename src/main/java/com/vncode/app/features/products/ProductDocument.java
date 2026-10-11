package com.vncode.app.features.products;

import com.vncode.app.integration.marketplace.Marketplace;
import java.net.URI;
import java.time.*;
import java.util.Objects;
public record ProductDocument(String id,String type,String number,LocalDate validFrom,LocalDate validUntil,String owner,String scope,URI file,Marketplace marketplace,DocumentState state,String source,Instant checkedAt,java.util.Map<String,String> metadata) {
    public ProductDocument(String id,String type,String number,LocalDate validFrom,LocalDate validUntil,String owner,String scope,URI file,Marketplace marketplace,DocumentState state,String source,Instant checkedAt){this(id,type,number,validFrom,validUntil,owner,scope,file,marketplace,state,source,checkedAt,java.util.Map.of());}
    public ProductDocument {
        metadata=metadata==null?java.util.Map.of():java.util.Map.copyOf(metadata);
        Objects.requireNonNull(marketplace);Objects.requireNonNull(state);
        if(state==DocumentState.VALID && (checkedAt==null || source==null || !source.startsWith("https://")))throw new IllegalArgumentException("Marketplace verification is missing");
        if(validFrom!=null&&validUntil!=null&&validUntil.isBefore(validFrom))throw new IllegalArgumentException("Invalid document dates");
    }
    @Override public String toString(){return "ProductDocument[state="+state+"]";}
}
