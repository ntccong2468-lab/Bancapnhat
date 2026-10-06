package com.tuandev.fbsbarcode.integration.wb;

public class WbOrderStatusDto {
    private Long id;
    private String supplierStatus;
    private String wbStatus;
    private Boolean isCancellable;

    public Long getId() { return id; }
    public String getSupplierStatus() { return supplierStatus; }
    public String getWbStatus() { return wbStatus; }
    public Boolean getIsCancellable() { return isCancellable; }
}
