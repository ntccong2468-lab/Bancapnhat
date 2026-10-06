package com.tuandev.fbsbarcode.models;

public class Sticker {
    private Long orderId;
    private Long partA;
    private Long partB;
    private String barcode;
    private String file;

    public Sticker() {
    }

    public Sticker(Long orderId, Long partA, Long partB, String barcode, String file) {
        this.orderId = orderId;
        this.partA = partA;
        this.partB = partB;
        this.barcode = barcode;
        this.file = file;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getPartA() {
        return partA;
    }

    public void setPartA(Long partA) {
        this.partA = partA;
    }

    public Long getPartB() {
        return partB;
    }

    public void setPartB(Long partB) {
        this.partB = partB;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    @Override
    public String toString() {
        return "Sticker{" +
                "orderId='" + orderId + '\'' +
                ", partA='" + partA + '\'' +
                ", partB='" + partB + '\'' +
                ", barcode='" + barcode + '\'' +
                ", file='" + file + '\'' +
                '}';
    }
}
