package com.vncode.app.features.listingcopy;
public record TargetAttribute(String id,String name,String type,boolean required,String unit,int maxValues,String dictionaryId,String complexId,boolean namedField) {
    @Override public String toString(){return name+" · "+id+(required?" *":"");}
}
