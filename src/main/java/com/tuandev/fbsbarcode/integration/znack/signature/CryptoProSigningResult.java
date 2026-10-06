package com.tuandev.fbsbarcode.integration.znack.signature;

import java.util.Base64;

public record CryptoProSigningResult(byte[] cms, String diagnostic) {
    public CryptoProSigningResult {
        cms = cms == null ? new byte[0] : cms.clone();
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    @Override
    public byte[] cms() {
        return cms.clone();
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(cms);
    }
}
