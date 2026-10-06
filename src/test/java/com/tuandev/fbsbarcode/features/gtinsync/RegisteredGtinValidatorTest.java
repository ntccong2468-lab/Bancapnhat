package com.tuandev.fbsbarcode.features.gtinsync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredGtinValidatorTest {
    @Test void validatesGs1LengthsAndLeadingZeros() {
        for (String gtin : new String[]{"00000000000017", "4006381333931", "036000291452", "96385074"})
            assertTrue(RegisteredGtinValidator.isValid(gtin), gtin);
        for (String gtin : new String[]{"00000000000018", "123", " 00000000000017", "１２３４５６７８", "00000000000000"})
            assertFalse(RegisteredGtinValidator.isValid(gtin), gtin);
        assertFalse(RegisteredGtinValidator.isValid(null));
    }
}
