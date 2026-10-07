package com.vncode.app.features.gtinsync;

public final class RegisteredGtinValidator {
    private RegisteredGtinValidator() {}
    public static boolean isValid(String gtin) {
        if (gtin == null || !(gtin.length() == 8 || gtin.length() == 12 || gtin.length() == 13 || gtin.length() == 14)) return false;
        if (!gtin.matches("[0-9]+") || gtin.chars().allMatch(c -> c == '0')) return false;
        int sum = 0, weight = 3;
        for (int i = gtin.length() - 2; i >= 0; i--) {
            sum += (gtin.charAt(i) - '0') * weight;
            weight = 4 - weight;
        }
        return (10 - sum % 10) % 10 == gtin.charAt(gtin.length() - 1) - '0';
    }
}
