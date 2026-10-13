package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CryptoProSignedCertificateReaderTest {
    private static final AlgorithmIdentifier HASH = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
    private static final AlgorithmIdentifier SIGNATURE = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);

    @Test void readsCompanyIdentityFromActualSignerAndNotFirstIncludedCertificate() throws Exception {
        var actual = certificate(2, "CN=Representative,SN=Иванов,GIVENNAME=Иван,1.2.643.3.131.1.1=770123456789,O=ООО Фикстура,1.2.643.100.4=7707083893");
        var unrelated = certificate(1, "CN=Unrelated,1.2.643.3.131.1.1=781123456789");
        var result = CryptoProSignedCertificateReader.read(cms(actual, List.of(unrelated, actual), 1), fingerprint(actual));
        assertEquals("7707083893", result.inn()); assertEquals("ООО Фикстура", result.ownerName());
        assertEquals("ООО Фикстура", result.organizationName()); assertEquals("Иванов Иван", result.signerFullName());
        assertEquals(fingerprint(actual), result.thumbprint()); assertTrue(result.hasPrivateKey());
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), result.validFrom());
    }
    @Test void rejectsExpectedCertificatePresentButNotTheSigner() throws Exception {
        var actual = certificate(2, "CN=Signer,1.2.643.3.131.1.1=770123456789");
        var unrelated = certificate(1, "CN=Selected,1.2.643.3.131.1.1=781123456789");
        assertThrows(CryptoProException.class, () -> CryptoProSignedCertificateReader.read(
                cms(actual, List.of(unrelated, actual), 1), fingerprint(unrelated)));
    }
    @Test void rejectsMissingSignerCertificateOrMultipleSigners() throws Exception {
        var signer = certificate(2, "CN=Signer,1.2.643.3.131.1.1=770123456789");
        var unrelated = certificate(1, "CN=Other,1.2.643.3.131.1.1=781123456789");
        assertThrows(CryptoProException.class, () -> CryptoProSignedCertificateReader.read(cms(signer, List.of(unrelated), 1), fingerprint(signer)));
        assertThrows(CryptoProException.class, () -> CryptoProSignedCertificateReader.read(cms(signer, List.of(signer), 2), fingerprint(signer)));
    }
    @Test void rejectsMissingInnAndMalformedDataWithoutLeakingIdentity() throws Exception {
        var signer = certificate(1, "CN=Confidential company");
        var error = assertThrows(CryptoProException.class,
                () -> CryptoProSignedCertificateReader.read(cms(signer, List.of(signer), 1), fingerprint(signer)));
        assertEquals(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, error.code());
        assertFalse(error.getMessage().contains("Confidential"));
        assertThrows(CryptoProException.class, () -> CryptoProSignedCertificateReader.read(new byte[] {1, 2}, "a".repeat(40)));
    }
    @Test void discoveryPrefersCompanyInnAndPreservesQuotedAndEscapedOrganization() {
        var cert = new CryptoProCertificateDiscoveryService().parse("""
                SHA1 Thumbprint: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                Subject: INN=770123456789, CN=Иван Иванов,
                         ИНН ЮЛ=7707083893,
                         O="ООО Фикстура, группа", SN=Иванов, G=Иван
                Not valid before: 01.01.2025
                Not valid after: 01.01.2029
                Private key: Yes
                """).getFirst();
        assertEquals("7707083893", cert.inn()); assertEquals("ООО Фикстура, группа", cert.ownerName());
        assertEquals("Иванов Иван", cert.signerFullName());
        assertEquals("7707083893", CryptoProCertificateInfo.enterpriseInn("OID.1.2.643.3.131.1.1=770123456789, OID.1.2.643.100.4=7707083893"));
        var escaped = new CryptoProCertificateInfo("", "", "O=Company\\, group, INNLE=7707083893", "", "7707083893", null, null, false, "", "");
        assertEquals("Company, group", escaped.organizationName());
    }

    private static Certificate certificate(int serial, String subject) throws Exception {
        var generator = new V3TBSCertificateGenerator();
        generator.setSerialNumber(new ASN1Integer(serial)); generator.setSignature(SIGNATURE);
        generator.setIssuer(new X500Name("CN=Fixture CA")); generator.setSubject(new X500Name(subject));
        generator.setStartDate(new Time(Date.from(Instant.parse("2025-01-01T00:00:00Z"))));
        generator.setEndDate(new Time(Date.from(Instant.parse("2029-01-01T00:00:00Z"))));
        generator.setSubjectPublicKeyInfo(new SubjectPublicKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), new byte[]{1, 2, 3}));
        return Certificate.getInstance(new DERSequence(new ASN1Encodable[]{generator.generateTBSCertificate(), SIGNATURE, new DERBitString(new byte[]{1})}));
    }
    private static String fingerprint(Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded("DER")));
    }
    private static byte[] cms(Certificate signerCertificate, List<Certificate> certificates, int signers) throws Exception {
        var signer = new SignerInfo(new ASN1Integer(1), new IssuerAndSerialNumber(signerCertificate.getIssuer(),
                signerCertificate.getSerialNumber().getValue()), HASH, null, SIGNATURE, new DEROctetString(new byte[]{1}), null);
        var signerList = new ASN1Encodable[signers]; java.util.Arrays.fill(signerList, signer);
        var data = new SignedData(new ASN1Integer(1), new DERSet(HASH), new ContentInfo(PKCSObjectIdentifiers.data, null),
                new DERSet(certificates.toArray(ASN1Encodable[]::new)), null, new DERSet(signerList));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, data).getEncoded("DER");
    }
}
