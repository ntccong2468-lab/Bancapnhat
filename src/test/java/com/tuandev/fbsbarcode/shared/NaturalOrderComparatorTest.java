package com.tuandev.fbsbarcode.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalOrderComparatorTest {
    @Test
    void comparesNumericSuffixNaturally() {
        assertTrue(NaturalOrderComparator.compareIgnoreCase("A2", "A10") < 0);
        assertTrue(NaturalOrderComparator.compareIgnoreCase("SKU09", "SKU9") > 0);
    }
}
