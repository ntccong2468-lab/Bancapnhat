package com.vncode.app.integration.ozon;

public record OzonShipResult(String postingNumber, String status, boolean reconciledAfterAmbiguousResponse,
        java.util.List<String> resultingPostings) {
    public OzonShipResult { resultingPostings=java.util.List.copyOf(resultingPostings); }
    public OzonShipResult(String postingNumber,String status,boolean reconciledAfterAmbiguousResponse){
        this(postingNumber,status,reconciledAfterAmbiguousResponse,java.util.List.of(postingNumber));
    }
}
