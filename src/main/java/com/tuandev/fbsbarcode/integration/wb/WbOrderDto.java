package com.tuandev.fbsbarcode.integration.wb;

import java.util.List;

public class WbOrderDto {
    private Address address;
    private String ddate;
    private String sellerDate;
    private Integer salePrice;
    private List<String> requiredMeta;
    private List<String> optionalMeta;
    private String deliveryType;
    private String comment;
    private Integer scanPrice;
    private String userId;
    private String orderUid;
    private String article;
    private String colorCode;
    private String rid;
    private String createdAt;
    private List<String> offices;
    private List<String> skus;
    private Long id;
    private Integer warehouseId;
    private Integer officeId;
    private Long nmId;
    private Long chrtId;
    private Integer price;
    private Integer finalPrice;
    private Integer convertedPrice;
    private Integer convertedFinalPrice;
    private Integer currencyCode;
    private Integer convertedCurrencyCode;
    private Integer cargoType;
    private Integer crossBorderType;
    private Boolean isZeroOrder;
    private Options options;
    private String supplyId;

    public Address getAddress() { return address; }
    public String getDdate() { return ddate; }
    public String getSellerDate() { return sellerDate; }
    public Integer getSalePrice() { return salePrice; }
    public List<String> getRequiredMeta() { return requiredMeta; }
    public List<String> getOptionalMeta() { return optionalMeta; }
    public String getDeliveryType() { return deliveryType; }
    public String getComment() { return comment; }
    public Integer getScanPrice() { return scanPrice; }
    public String getUserId() { return userId; }
    public String getOrderUid() { return orderUid; }
    public String getArticle() { return article; }
    public String getColorCode() { return colorCode; }
    public String getRid() { return rid; }
    public String getCreatedAt() { return createdAt; }
    public List<String> getOffices() { return offices; }
    public List<String> getSkus() { return skus; }
    public Long getId() { return id; }
    public Integer getWarehouseId() { return warehouseId; }
    public Integer getOfficeId() { return officeId; }
    public Long getNmId() { return nmId; }
    public Long getChrtId() { return chrtId; }
    public Integer getPrice() { return price; }
    public Integer getFinalPrice() { return finalPrice; }
    public Integer getConvertedPrice() { return convertedPrice; }
    public Integer getConvertedFinalPrice() { return convertedFinalPrice; }
    public Integer getCurrencyCode() { return currencyCode; }
    public Integer getConvertedCurrencyCode() { return convertedCurrencyCode; }
    public Integer getCargoType() { return cargoType; }
    public Integer getCrossBorderType() { return crossBorderType; }
    public Boolean getIsZeroOrder() { return isZeroOrder; }
    public Options getOptions() { return options; }
    public String getSupplyId() { return supplyId; }

    public static class Address {
        private String fullAddress;
        private Double longitude;
        private Double latitude;
        public String getFullAddress() { return fullAddress; }
        public Double getLongitude() { return longitude; }
        public Double getLatitude() { return latitude; }
    }

    public static class Options {
        private Boolean isB2B;
        public Boolean getIsB2B() { return isB2B; }
    }
}
