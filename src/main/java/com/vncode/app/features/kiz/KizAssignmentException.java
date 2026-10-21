package com.vncode.app.features.kiz;

/** Only public order/product identifiers belong in this error; never marks or credentials. */
public final class KizAssignmentException extends IllegalStateException {
    private final String code,order,product;
    public KizAssignmentException(String code,String order,String product){
        super(code);if(!code.matches("KIZ_[A-Z0-9_]{1,70}"))throw new IllegalArgumentException("Invalid assignment code");
        this.code=code;this.order=safe(order);this.product=safe(product);
    }
    public String code(){return code;}
    public String order(){return order;}
    public String product(){return product;}
    private static String safe(String value){if(value==null)return "";String cleaned=value.replaceAll("[^A-Za-z0-9._-]","_");return cleaned.substring(0,Math.min(cleaned.length(),100));}
}
