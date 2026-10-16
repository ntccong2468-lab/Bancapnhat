package com.vncode.app.shared;

import com.vncode.app.models.Order;
import com.vncode.app.integration.wb.WbSupplySummary;
import java.util.Arrays;
import java.util.Objects;

/** Rendered value comparison for the existing mutable WB models, without changing their identity semantics. */
public final class FbsRowValues {
    private FbsRowValues() {}
    public static Object snapshot(Order row){
        return new View(row.getId(),row.getNmId(),row.getArticle(),row.getBrand(),row.getName(),row.getSubjectName(),row.getSize(),row.getRuSize(),row.getColor(),row.getBarcode(),row.getKiz(),row.getSticker(),row.getStickerTail(),row.getStickerCode(),row.getImageUrl(),row.getCreatedAt(),row.getPrice(),row.getSupplierStatus(),row.getWbStatus(),row.isRequiresKiz(),row.getImage()==null?null:java.nio.ByteBuffer.wrap(row.getImage()).asReadOnlyBuffer());
    }
    private static final class View {
        private final Object[] values;
        View(Object... values){this.values=values;}
        @Override public boolean equals(Object other){return other instanceof View view&&Arrays.equals(values,view.values);}
        @Override public int hashCode(){return Arrays.hashCode(values);}
        @Override public String toString(){return "RenderedOrder[redacted]";}
    }
    public static boolean sameOrder(Order a,Order b){
        return Objects.equals(a.getId(),b.getId())&&Objects.equals(a.getNmId(),b.getNmId())
            &&Objects.equals(a.getArticle(),b.getArticle())&&Objects.equals(a.getBrand(),b.getBrand())
            &&Objects.equals(a.getName(),b.getName())&&Objects.equals(a.getSubjectName(),b.getSubjectName())
            &&Objects.equals(a.getSize(),b.getSize())&&Objects.equals(a.getRuSize(),b.getRuSize())
            &&Objects.equals(a.getColor(),b.getColor())&&Objects.equals(a.getBarcode(),b.getBarcode())
            &&Objects.equals(a.getKiz(),b.getKiz())&&Objects.equals(a.getSticker(),b.getSticker())
            &&Objects.equals(a.getStickerTail(),b.getStickerTail())&&Objects.equals(a.getStickerCode(),b.getStickerCode())
            &&Objects.equals(a.getImageUrl(),b.getImageUrl())&&Objects.equals(a.getCreatedAt(),b.getCreatedAt())
            &&Objects.equals(a.getPrice(),b.getPrice())&&Objects.equals(a.getSupplierStatus(),b.getSupplierStatus())
            &&Objects.equals(a.getWbStatus(),b.getWbStatus())&&a.isRequiresKiz()==b.isRequiresKiz()
            &&Arrays.equals(a.getImage(),b.getImage());
    }
    public static boolean sameSupply(WbSupplySummary a,WbSupplySummary b){
        return Objects.equals(a.getSupplyId(),b.getSupplyId())&&Objects.equals(a.getName(),b.getName())
            &&a.isDone()==b.isDone()&&Objects.equals(a.getB2b(),b.getB2b())
            &&Objects.equals(a.getCreatedAt(),b.getCreatedAt())&&a.getItemCount()==b.getItemCount();
    }
}
