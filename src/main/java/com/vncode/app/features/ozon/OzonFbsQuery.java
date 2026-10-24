package com.vncode.app.features.ozon;
import java.util.*;
public record OzonFbsQuery(int shopId,String status,String text,String sort,int offset,int limit,PrintState printState) {
    public enum PrintState { ALL, UNPRINTED, PRINTED }
    public OzonFbsQuery(int shopId,String status,String text,String sort,int offset,int limit){this(shopId,status,text,sort,offset,limit,PrintState.ALL);}
    public OzonFbsQuery {
        printState=Objects.requireNonNull(printState);
        if(printState!=PrintState.ALL&&!"awaiting_deliver".equals(status))throw new IllegalArgumentException("Print state requires received Ozon postings");
        if(shopId<=0||offset<0||limit<1||limit>1000)throw new IllegalArgumentException("Invalid posting page");
        status=Objects.toString(status,"");text=Objects.toString(text,"").strip();sort=Objects.toString(sort,"");
        if(!Set.of("","awaiting_packaging","awaiting_deliver","delivering").contains(status)||text.length()>512||!sort.isBlank()&&Arrays.stream(sort.split(",",-1)).anyMatch(v->!Set.of("product","article","color","size").contains(v)))throw new IllegalArgumentException("Invalid posting filter");
    }
}
