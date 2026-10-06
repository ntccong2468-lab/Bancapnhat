package com.tuandev.fbsbarcode.integration.ozon;

public record OzonShipResult(String postingNumber, String status, boolean reconciledAfterAmbiguousResponse) {
}
