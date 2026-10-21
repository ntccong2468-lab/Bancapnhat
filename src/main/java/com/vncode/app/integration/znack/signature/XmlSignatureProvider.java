package com.vncode.app.integration.znack.signature;

import java.time.Duration;

@FunctionalInterface
public interface XmlSignatureProvider {
    String signXml(String xml) throws CryptoProException;
    static XmlSignatureProvider forCertificate(String fingerprint, Duration timeout) {
        if (!WindowsCadesSignatureProvider.isWindows()) return xml -> {
            throw new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,"GS1 XML signing requires Windows CryptoPro CAdESCOM");
        };
        return new WindowsCadesSignatureProvider(fingerprint, timeout);
    }
}
