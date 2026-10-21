package com.vncode.app.features.products;

import java.math.BigDecimal;
public record PackageDimensions(BigDecimal lengthCm,BigDecimal widthCm,BigDecimal heightCm,BigDecimal weightKg) {
    public PackageDimensions {
        for(BigDecimal n:new BigDecimal[]{lengthCm,widthCm,heightCm,weightKg})if(n!=null&&n.signum()<0)throw new IllegalArgumentException("Negative dimensions");
    }
}
