package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1ParsingException;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.PKIXNameConstraintValidator;
import org.bouncycastle.asn1.x509.NameConstraintValidatorException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression fixtures for upstream GHSA-9pwp-9qqc-pr26 and GHSA-qp49-qgx5-5m26. */
class BouncyCastleAdvisoryRegressionTest {
    @Test void excludedEmailDomainCannotBeBypassedByRootDot() {
        var validator = new PKIXNameConstraintValidator();
        validator.addExcludedSubtree(new GeneralSubtree(new GeneralName(GeneralName.rfc822Name, "example.com")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.rfc822Name, "user@example.com.")));
    }

    @Test void excludedUriDomainCannotBeBypassedByRootDot() {
        var validator = new PKIXNameConstraintValidator();
        validator.addExcludedSubtree(new GeneralSubtree(new GeneralName(GeneralName.uniformResourceIdentifier, "example.com")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.uniformResourceIdentifier, "https://example.com./")));
    }

    @Test void lazyAsn1ForcingEnforcesConstructionDepthLimit() throws Exception {
        ASN1Encodable node = new DERSequence();
        for (int i = 0; i < 256; i++) node = new DERSequence(node);
        var der = node.toASN1Primitive().getEncoded(ASN1Encoding.DER);
        try (var input = new ASN1InputStream(der, true)) {
            var parsed = input.readObject();
            assertThrows(ASN1ParsingException.class, parsed::hashCode);
        }
    }
}
