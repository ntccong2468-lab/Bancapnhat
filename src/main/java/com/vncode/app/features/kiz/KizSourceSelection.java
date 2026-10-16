package com.vncode.app.features.kiz;

/** Immutable consent captured when a shipment print/assignment starts. */
public record KizSourceSelection(boolean sellerImported, boolean purchased) {
    public static final KizSourceSelection SELLER_ONLY = new KizSourceSelection(true, false);
    public boolean anySelected() { return sellerImported || purchased; }
}
