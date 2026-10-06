package com.tuandev.fbsbarcode.integration.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionComparator {

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public static int compare(String v1, String v2) {
        int[] p1 = parse(v1);
        int[] p2 = parse(v2);
        for (int i = 0; i < 3; i++) {
            if (p1[i] != p2[i]) {
                return Integer.compare(p1[i], p2[i]);
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        Matcher m = SEMVER.matcher(version != null ? version : "");
        if (!m.find()) return new int[]{0, 0, 0};
        return new int[]{
            Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3))
        };
    }

    private VersionComparator() {}
}
