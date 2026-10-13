package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.Certificate;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Reads account identity from the certificate that produced a CMS, not certmgr display metadata.
 * This is identity extraction; the receiving CZ/Catalog service still verifies the signature. */
public final class CryptoProSignedCertificateReader {
    private CryptoProSignedCertificateReader() {}

    public static CryptoProCertificateInfo read(CryptoProSigningResult result, String expectedFingerprint)
            throws CryptoProException {
        if (result == null) throw invalid();
        return read(result.cms(), expectedFingerprint);
    }

    public static CryptoProCertificateInfo read(byte[] cms, String expectedFingerprint) throws CryptoProException {
        String expected = expectedFingerprint == null ? "" : expectedFingerprint.replaceAll("\\s+", "");
        if (!expected.matches("[A-Fa-f0-9]{40}") || cms == null || cms.length == 0 || cms.length > 4 * 1024 * 1024)
            throw invalid();
        try (var input = new ASN1InputStream(cms)) {
            ContentInfo envelope = ContentInfo.getInstance(input.readObject());
            if (input.readObject() != null || !PKCSObjectIdentifiers.signedData.equals(envelope.getContentType()))
                throw invalid();
            SignedData data = SignedData.getInstance(envelope.getContent());
            if (data.getSignerInfos() == null || data.getSignerInfos().size() != 1
                    || data.getCertificates() == null || data.getCertificates().size() > 100) throw invalid();
            SignerInfo signer = SignerInfo.getInstance(data.getSignerInfos().getObjectAt(0));
            Certificate matched = null;
            for (var value : data.getCertificates()) {
                if (!(value instanceof ASN1Sequence)) continue;
                Certificate candidate = Certificate.getInstance(value);
                if (!candidate.getIssuer().equals(signer.getIssuerAndSerialNumber().getName())
                        || !candidate.getSerialNumber().equals(signer.getIssuerAndSerialNumber().getCertificateSerialNumber()))
                    continue;
                if (matched != null) throw invalid();
                matched = candidate;
            }
            if (matched == null) throw invalid();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(matched.getEncoded("DER")));
            if (!fingerprint.equalsIgnoreCase(expected)) throw invalid();
            String subject = subject(matched.getSubject());
            String inn = CryptoProCertificateInfo.enterpriseInn(subject);
            if (inn.isBlank()) throw invalid();
            return new CryptoProCertificateInfo(fingerprint, fingerprint, subject, subject(matched.getIssuer()), inn,
                    matched.getStartDate().getDate().toInstant(), matched.getEndDate().getDate().toInstant(),
                    true, "CryptoPro CMS signer", "");
        } catch (CryptoProException invalid) { throw invalid; }
        catch (Exception invalid) { throw invalid(); }
    }

    private static String subject(X500Name name) {
        var text = new StringBuilder();
        for (var rdn : name.getRDNs()) for (var attribute : rdn.getTypesAndValues()) {
            if (!text.isEmpty()) text.append(',');
            text.append(attribute.getType().getId()).append('=').append(IETFUtils.valueToString(attribute.getValue()));
        }
        return text.toString();
    }

    private static CryptoProException invalid() {
        return new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,
                "Cannot verify the identity of the selected signing certificate.");
    }
}
