package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.ZnackModels.KizOrder;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import java.time.Instant;

/** Reads immutable paid-order routing; legacy callers supply their original bound settings. */
final class ZnackPurchaseProtocolContext {
    private ZnackPurchaseProtocolContext() {}

    static ZnackPurchaseContext resolve(ZnackRepository repository, Settings original, long orderId) {
        return new ZnackPurchaseBatchStore(repository).contextForOrder(orderId,original);
    }

    static Instant purchasedAt(ZnackRepository repository, KizOrder order) {
        return new ZnackPurchaseBatchStore(repository).findByOrder(order.id())
                .map(ZnackPurchaseBatchStore.Batch::attemptedAt).orElse(order.createdAt());
    }
}
