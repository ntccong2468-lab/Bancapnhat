package com.tuandev.fbsbarcode.shared;

public final class NaturalOrderComparator {
    private NaturalOrderComparator() {
    }

    public static int compareIgnoreCase(String s1, String s2) {
        if (s1 == null && s2 == null) return 0;
        if (s1 == null) return 1;
        if (s2 == null) return -1;

        String a = s1.trim().toLowerCase();
        String b = s2.trim().toLowerCase();

        int i = 0;
        int j = 0;

        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i;
                int startJ = j;

                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;

                String numA = a.substring(startI, i);
                String numB = b.substring(startJ, j);

                String cleanA = numA.replaceFirst("^0+", "");
                String cleanB = numB.replaceFirst("^0+", "");

                if (cleanA.isEmpty()) cleanA = "0";
                if (cleanB.isEmpty()) cleanB = "0";

                if (cleanA.length() != cleanB.length()) {
                    return Integer.compare(cleanA.length(), cleanB.length());
                }

                int cmp = cleanA.compareTo(cleanB);
                if (cmp != 0) return cmp;

                cmp = Integer.compare(numA.length(), numB.length());
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(ca, cb);
                if (cmp != 0) return cmp;

                i++;
                j++;
            }
        }

        return Integer.compare(a.length(), b.length());
    }
}
