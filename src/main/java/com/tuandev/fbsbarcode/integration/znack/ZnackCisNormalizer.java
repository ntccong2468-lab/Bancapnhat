package com.tuandev.fbsbarcode.integration.znack;

/**
 * Converts a downloaded GS1 DataMatrix payload into the normalized CIS form
 * required by True API. The original payload must still be retained for
 * printing and inventory allocation.
 */
public final class ZnackCisNormalizer {
    private static final char GROUP_SEPARATOR = '\u001D';
    private static final int GTIN_AI_LENGTH = 2;
    private static final int GTIN_LENGTH = 14;
    private static final int SERIAL_AI_OFFSET = GTIN_AI_LENGTH + GTIN_LENGTH;
    private static final int SERIAL_OFFSET = SERIAL_AI_OFFSET + 2;
    private static final int LP_SERIAL_LENGTH = 13;
    private static final int LP_NORMALIZED_LENGTH = SERIAL_OFFSET + LP_SERIAL_LENGTH;

    private ZnackCisNormalizer() {
    }

    public static String forTrueApi(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("KIZ code is required.");
        }
        String code = stripScannerPrefix(rawCode.trim());
        if (!isUnitIdentificationCode(code)) {
            return code;
        }

        int separator = code.indexOf(GROUP_SEPARATOR, SERIAL_OFFSET);
        if (separator >= SERIAL_OFFSET) {
            return code.substring(0, separator);
        }

        // Light-industry SUZ codes use a 13-character serial. Some integrations
        // omit GS before the crypto tail, so recognize the documented 91/92 tail.
        if (code.length() > LP_NORMALIZED_LENGTH + 6
                && code.startsWith("91", LP_NORMALIZED_LENGTH)
                && (code.indexOf(GROUP_SEPARATOR, LP_NORMALIZED_LENGTH) >= 0
                || code.startsWith("92", LP_NORMALIZED_LENGTH + 6))) {
            return code.substring(0, LP_NORMALIZED_LENGTH);
        }
        return code;
    }

    private static String stripScannerPrefix(String value) {
        String result = value;
        if (result.startsWith("]d2")) result = result.substring(3);
        while (!result.isEmpty() && result.charAt(0) == GROUP_SEPARATOR) result = result.substring(1);
        return result;
    }

    private static boolean isUnitIdentificationCode(String value) {
        if (value.length() < SERIAL_OFFSET || !value.startsWith("01") || !value.startsWith("21", SERIAL_AI_OFFSET)) {
            return false;
        }
        for (int index = GTIN_AI_LENGTH; index < SERIAL_AI_OFFSET; index++) {
            if (!Character.isDigit(value.charAt(index))) return false;
        }
        return true;
    }
}
