package com.vncode.app.features.gtinsync;

/** Only thrown while preparing/awaiting the first mutation attempt. After the dispatch boundary,
 * transport failures remain ambiguous and must reconcile instead of replaying. */
public final class GtinKnownUnsentException extends RuntimeException {
    public GtinKnownUnsentException() { super("gtin_known_unsent"); }
}
