package com.vncode.app.features.finance;

import java.time.LocalDate;

/** A saved penalty transaction. Description is the marketplace's label, not an inferred cause. */
public record FinancePenaltyEntry(String transactionId, LocalDate date, String currency,
                                  String description, String orderId, String productId,
                                  String article, String sku, double amount) { }
