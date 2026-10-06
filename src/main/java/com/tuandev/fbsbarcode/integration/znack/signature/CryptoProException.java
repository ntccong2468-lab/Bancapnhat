package com.tuandev.fbsbarcode.integration.znack.signature;

public class CryptoProException extends Exception {
    private final CryptoProErrorCode code;

    public CryptoProException(CryptoProErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CryptoProException(CryptoProErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public CryptoProErrorCode code() {
        return code;
    }
}
