package com.vncode.app.features.products;

import java.util.List;
public record ProductAttribute(String id,String name,List<String> values,String unit,String complexId,int groupIndex) {
    public ProductAttribute(String id,String name,List<String> values,String unit){this(id,name,values,unit,"",0);}
    public ProductAttribute {values=List.copyOf(values);}
    public String mappingKey(){return complexId==null||complexId.isBlank()||complexId.equals("0")?id:complexId+":"+id;}
    @Override public String toString(){return "ProductAttribute[id="+id+"]";}
}
