package com.tuandev.fbsbarcode.integration.znack.signature;

public interface ZnackSignatureProvider {
    CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException;

    static ZnackSignatureProvider unconfigured() {
        return (payload, context) -> {
            throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "CryptoPro signing is not configured.");
        };
    }
}
