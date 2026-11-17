package com.vncode.app.integration.znack.signature;

public interface ZnackSignatureProvider {
    CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException;

    /** Optional non-interactive check before a background signing attempt. */
    default void requireKeyConnected() throws CryptoProException { }

    static ZnackSignatureProvider unconfigured() {
        return (payload, context) -> {
            throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "CryptoPro signing is not configured.");
        };
    }
}
