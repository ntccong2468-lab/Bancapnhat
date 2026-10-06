package com.tuandev.fbsbarcode.integration.wb;

public class WbSupplyDto {
    private String id;
    private Boolean isB2b;
    private Boolean done;
    private String createdAt;
    private String closedAt;
    private String scanDt;
    private String rejectDt;
    private String name;
    private Integer cargoType;
    private Integer crossBorderType;
    private Integer destinationOfficeId;
    private Integer recommendedWhId;

    public String getId() { return id; }
    public Boolean getIsB2b() { return isB2b; }
    public Boolean getDone() { return done; }
    public String getCreatedAt() { return createdAt; }
    public String getClosedAt() { return closedAt; }
    public String getScanDt() { return scanDt; }
    public String getRejectDt() { return rejectDt; }
    public String getName() { return name; }
    public Integer getCargoType() { return cargoType; }
    public Integer getCrossBorderType() { return crossBorderType; }
    public Integer getDestinationOfficeId() { return destinationOfficeId; }
    public Integer getRecommendedWhId() { return recommendedWhId; }
}
