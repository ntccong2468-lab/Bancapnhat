package com.vncode.app.integration.ozon;

public record OzonShipResult(String postingNumber, String status, boolean reconciledAfterAmbiguousResponse) {
}
