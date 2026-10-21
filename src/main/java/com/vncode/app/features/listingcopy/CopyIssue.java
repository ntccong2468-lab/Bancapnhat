package com.vncode.app.features.listingcopy;
public record CopyIssue(String code,String field,boolean blocking) {
    public CopyIssue{if(code==null||!code.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Invalid issue code");}
}
