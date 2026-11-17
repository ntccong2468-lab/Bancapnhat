package com.vncode.app.features.kiz;

import com.vncode.app.models.Shop;
import java.util.Collection;
import java.util.Map;

/** Opt-in allocation counts for a frozen purchase scope; never substitute broader stock. */
@FunctionalInterface
public interface KizPurchaseAvailability {
    Map<String,Integer> counts(Shop shop, Collection<String> gtins);
}
