package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationScopeTest {
    @TempDir Path temp;
    Shop shop=new Shop(1,"Fixture","fixture");
    ZnackRepository repository;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture')");}
        repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void verifiesActualCmsOwnerAndChecksSettingsAgainAfterPin()throws Exception {
        Certificate certificate=certificate("7707083893",Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600));
        String fingerprint=fingerprint(certificate);repository.saveSettings(settings("7707083893",fingerprint));
        var scope=ZnackRegistrationScope.fromSettings(repository.getSettings());
        var signer=scope.verifyingSigner(shop,(payload,context)->new CryptoProSigningResult(cms(certificate),""));
        assertTrue(signer.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE).cms().length>0);
        var drift=scope.verifyingSigner(shop,(payload,context)->{
            repository.saveSettings(settings("7807083893",fingerprint));
            return new CryptoProSigningResult(cms(certificate),"");
        });
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->drift.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
    }
    @Test void rejectsWrongOwnerEvenIfFingerprintMetadataWasChosenAndRejectsExpiredCertificate()throws Exception {
        Certificate foreign=certificate("7807083893",Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600));
        String fingerprint=fingerprint(foreign);repository.saveSettings(settings("7707083893",fingerprint));
        var scope=ZnackRegistrationScope.fromSettings(repository.getSettings());
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->scope.verifyingSigner(shop,
                (payload,context)->new CryptoProSigningResult(cms(foreign),"")).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        Certificate expired=certificate("7707083893",Instant.now().minusSeconds(100),Instant.now().minusSeconds(10));
        repository.saveSettings(settings("7707083893",fingerprint(expired)));
        var current=ZnackRegistrationScope.fromSettings(repository.getSettings());
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->current.verifyingSigner(shop,
                (payload,context)->new CryptoProSigningResult(cms(expired),"")).sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
    }
    @Test void rejectsMissingVerificationAndMetadataOwnershipMismatchWithoutLeakingIdentifiers() {
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->ZnackRegistrationScope.fromSettings(ZnackModels.Settings.empty()));
        var error=assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->ZnackRegistrationScope.fromSettings(
                new ZnackModels.Settings("","","","","7807083893","","","","a".repeat(40),"[]","","","",false,"","[]",
                        "{\"inn\":\"7707083893\",\"thumbprint\":\""+"a".repeat(40)+"\"}",Instant.now())));
        assertFalse(error.getMessage().contains("7707083893"));
    }
    static ZnackModels.Settings settings(String inn,String fingerprint) {
        return new ZnackModels.Settings("","","","",inn,inn,inn,"",fingerprint,"[]","","","",false,"","[]",
                "{\"inn\":\""+inn+"\",\"thumbprint\":\""+fingerprint+"\",\"hasPrivateKey\":true,\"validFrom\":\""+Instant.now().minusSeconds(86400)+"\",\"validTo\":\""+Instant.now().plusSeconds(86400)+"\"}",Instant.now());
    }
    private static final AlgorithmIdentifier HASH=new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
    private static final AlgorithmIdentifier SIGNATURE=new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption,DERNull.INSTANCE);
    private static Certificate certificate(String inn,Instant from,Instant until)throws Exception {
        var generator=new V3TBSCertificateGenerator();generator.setSerialNumber(new ASN1Integer(1));generator.setSignature(SIGNATURE);
        generator.setIssuer(new X500Name("CN=Fixture CA"));generator.setSubject(new X500Name("CN=Fixture,1.2.643.100.4="+inn));
        generator.setStartDate(new Time(Date.from(from)));generator.setEndDate(new Time(Date.from(until)));
        generator.setSubjectPublicKeyInfo(new SubjectPublicKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption,DERNull.INSTANCE),new byte[]{1,2,3}));
        return Certificate.getInstance(new DERSequence(new ASN1Encodable[]{generator.generateTBSCertificate(),SIGNATURE,new DERBitString(new byte[]{1})}));
    }
    private static String fingerprint(Certificate certificate)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded("DER")));}
    private static byte[] cms(Certificate certificate)throws CryptoProException {
        try {
            var signer=new SignerInfo(new ASN1Integer(1),new IssuerAndSerialNumber(certificate.getIssuer(),certificate.getSerialNumber().getValue()),HASH,null,SIGNATURE,new DEROctetString(new byte[]{1}),null);
            var data=new SignedData(new ASN1Integer(1),new DERSet(HASH),new ContentInfo(PKCSObjectIdentifiers.data,null),new DERSet(certificate),null,new DERSet(signer));
            return new ContentInfo(PKCSObjectIdentifiers.signedData,data).getEncoded("DER");
        }catch(Exception error){throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,"Fixture CMS invalid.");}
    }
}
