package com.vncode.app.features.gtinsync;

/** Only thrown while preparing/awaiting the first mutation attempt. After the dispatch boundary,
 * transport failures remain ambiguous and must reconcile instead of replaying. */
public final class GtinKnownUnsentException extends RuntimeException {
    private final String code;
    public GtinKnownUnsentException() {this("GTIN_KNOWN_UNSENT");}
    public GtinKnownUnsentException(String code) { super("gtin_known_unsent");this.code=com.vncode.app.features.sync.SyncRunRepository.code(code); }
    public String code(){return code;}
}
