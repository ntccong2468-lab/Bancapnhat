package com.tuandev.fbsbarcode.models;

public class Order {
    private Long id;
    private byte[] image;
    private String brand;
    private String name;
    private String subjectName;
    private String size;
    private String ruSize;
    private String color;
    private String article;
    private String sticker;
    private String stickerTail;
    private String barcode;
    private String kiz;
    private String stickerCode;
    private String imageUrl;
    private String createdAt;
    private Integer price;
    private String supplierStatus;
    private String wbStatus;
    private Long nmId;
    private boolean requiresKiz;

    public Order() {
    }

    public Order(Long id, byte[] image, String brand, String name, String size, String color, String article, String sticker, String barcode) {
        this.id = id;
        this.image = image;
        this.brand = brand;
        this.name = name;
        this.size = size;
        this.color = color;
        this.article = article;
        this.sticker = sticker;
        this.barcode = barcode;
    }

    public byte[] getImage() {
        return image;
    }

    public void setImage(byte[] image) {
        this.image = image;
    }

    public String getArticle() {
        return article;
    }

    public void setArticle(String article) {
        this.article = article;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public void setSubjectName(String subjectName) {
        this.subjectName = subjectName;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getRuSize() {
        return ruSize;
    }

    public void setRuSize(String ruSize) {
        this.ruSize = ruSize;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getSticker() {
        return sticker;
    }

    public void setSticker(String sticker) {
        this.sticker = sticker;
    }

    public String getStickerTail() {
        return stickerTail;
    }

    public void setStickerTail(String stickerTail) {
        this.stickerTail = stickerTail;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getKiz() {
        return kiz;
    }

    public void setKiz(String kiz) {
        this.kiz = kiz;
    }

    public String getStickerCode() {
        return stickerCode;
    }

    public void setStickerCode(String stickerCode) {
        this.stickerCode = stickerCode;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public String getSupplierStatus() {
        return supplierStatus;
    }

    public void setSupplierStatus(String supplierStatus) {
        this.supplierStatus = supplierStatus;
    }

    public String getWbStatus() {
        return wbStatus;
    }

    public void setWbStatus(String wbStatus) {
        this.wbStatus = wbStatus;
    }

    public Long getNmId() {
        return nmId;
    }

    public void setNmId(Long nmId) {
        this.nmId = nmId;
    }

    public boolean isRequiresKiz() {
        return requiresKiz;
    }

    public void setRequiresKiz(boolean requiresKiz) {
        this.requiresKiz = requiresKiz;
    }

    @Override
    public String toString() {
        return "Order{" +
                "id='" + id + '\'' +
                ", brand='" + brand + '\'' +
                ", name='" + name + '\'' +
                ", subjectName='" + subjectName + '\'' +
                ", size='" + size + '\'' +
                ", ruSize='" + ruSize + '\'' +
                ", color='" + color + '\'' +
                ", article='" + article + '\'' +
                ", sticker='" + sticker + '\'' +
                ", barcode='" + barcode + '\'' +
                ", kiz='" + kiz + '\'' +
                ", nmId=" + nmId +
                ", requiresKiz=" + requiresKiz +
                ", stickerCode='" + stickerCode + '\'' +
                '}';
    }
}
