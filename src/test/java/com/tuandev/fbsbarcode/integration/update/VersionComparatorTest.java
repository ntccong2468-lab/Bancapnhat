package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionComparatorTest {

    @Test
    void equalVersionsReturnZero() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"));
        assertEquals(0, VersionComparator.compare("2.1.3", "2.1.3"));
        assertEquals(0, VersionComparator.compare("0.0.0", "0.0.0"));
    }

    @Test
    void newerMajorVersionReturnsPositive() {
        assertTrue(VersionComparator.compare("2.0.0", "1.0.0") > 0);
        assertTrue(VersionComparator.compare("10.0.0", "9.0.0") > 0);
    }

    @Test
    void olderVersionReturnsNegative() {
        assertTrue(VersionComparator.compare("1.0.0", "2.0.0") < 0);
        assertTrue(VersionComparator.compare("1.0.0", "1.1.0") < 0);
        assertTrue(VersionComparator.compare("1.0.0", "1.0.1") < 0);
    }

    @Test
    void minorVersionComparison() {
        assertTrue(VersionComparator.compare("1.2.0", "1.1.0") > 0);
        assertTrue(VersionComparator.compare("1.1.0", "1.2.0") < 0);
    }

    @Test
    void patchVersionComparison() {
        assertTrue(VersionComparator.compare("1.0.1", "1.0.0") > 0);
        assertTrue(VersionComparator.compare("1.0.0", "1.0.1") < 0);
    }

    @Test
    void nullVersionIsZero() {
        assertTrue(VersionComparator.compare("1.0.0", null) > 0);
        assertEquals(0, VersionComparator.compare(null, null));
    }

    @Test
    void invalidSemverParsesAsZero() {
        assertEquals(0, VersionComparator.compare("invalid", "0.0.0"));
        assertEquals(0, VersionComparator.compare("", "0.0.0"));
    }

    @Test
    void preReleaseSuffixesAreIgnored() {
        assertTrue(VersionComparator.compare("1.1.0", "1.0.0-beta") > 0);
    }
}
