package com.tuandev.fbsbarcode.integration.wb;

import java.util.List;

public class WbProductCard {
    private Long nmID;
    private Long imtID;
    private String nmUUID;
    private Integer subjectID;
    private String subjectName;
    private String vendorCode;
    private Boolean kizMarked;
    private Boolean needKiz;
    private String brand;
    private String title;
    private String description;
    private String video;
    private Boolean isSwatchTryOn;
    private Wholesale wholesale;
    private Dimensions dimensions;
    private List<Characteristic> characteristics;
    private List<Size> sizes;
    private List<Photo> photos;
    private List<Tag> tags;
    private String createdAt;
    private String updatedAt;

    public Long getNmID() { return nmID; }
    public Long getImtID() { return imtID; }
    public String getNmUUID() { return nmUUID; }
    public Integer getSubjectID() { return subjectID; }
    public String getSubjectName() { return subjectName; }
    public String getVendorCode() { return vendorCode; }
    public Boolean getKizMarked() { return kizMarked; }
    public Boolean getNeedKiz() { return needKiz; }
    public String getBrand() { return brand; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getVideo() { return video; }
    public Boolean getIsSwatchTryOn() { return isSwatchTryOn; }
    public Wholesale getWholesale() { return wholesale; }
    public Dimensions getDimensions() { return dimensions; }
    public List<Characteristic> getCharacteristics() { return characteristics; }
    public List<Size> getSizes() { return sizes; }
    public List<Photo> getPhotos() { return photos; }
    public List<Tag> getTags() { return tags; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }

    public static class Wholesale {
        private Boolean enabled;
        private Integer quantum;
        public Boolean getEnabled() { return enabled; }
        public Integer getQuantum() { return quantum; }
    }

    public static class Dimensions {
        private Double length;
        private Double width;
        private Double height;
        private Double weightBrutto;
        private Boolean isValid;
        public Double getLength() { return length; }
        public Double getWidth() { return width; }
        public Double getHeight() { return height; }
        public Double getWeightBrutto() { return weightBrutto; }
        public Boolean getIsValid() { return isValid; }
    }

    public static class Characteristic {
        private Integer id;
        private String name;
        private Object value;
        public Integer getId() { return id; }
        public String getName() { return name; }
        public Object getValue() { return value; }
    }

    public static class Size {
        private Long chrtID;
        private String techSize;
        private String wbSize;
        private List<String> skus;
        public Long getChrtID() { return chrtID; }
        public String getTechSize() { return techSize; }
        public String getWbSize() { return wbSize; }
        public List<String> getSkus() { return skus; }
    }

    public static class Photo {
        private String big;
        private String c246x328;
        private String c516x688;
        private String hq;
        private String square;
        private String tm;
        public String getBig() { return big; }
        public String getC246x328() { return c246x328; }
        public String getC516x688() { return c516x688; }
        public String getHq() { return hq; }
        public String getSquare() { return square; }
        public String getTm() { return tm; }
    }

    public static class Tag {
        private Integer id;
        private String name;
        private String color;
        public Integer getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
    }
}
