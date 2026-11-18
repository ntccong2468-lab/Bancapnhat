package com.vncode.app.ui.znackregistration;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Suggests a reviewed GS1 action from a registration failure; never retries or allocates a GTIN. */
final class RegistrationGs1Recovery {
    enum Action { MEMBERSHIP, PREFIX }
    private static final int MAX_DETAIL = 200_000;
    private static final Pattern MEMBERSHIP_CODE = Pattern.compile(
            "(?iu)(?:(?<![\\p{L}\\p{N}_])[\\\"']?(?:code|error_?code|код(?: ошибки)?)[\\\"']?\\s*[:=]\\s*[\\\"']?166(?![\\p{L}\\p{N}_])|(?:^|[\\r\\n])\\s*166\\s*[:—-])");

    private RegistrationGs1Recovery() {}

    static Optional<Action> detect(String failure) {
        if (failure == null || failure.isBlank()) return Optional.empty();
        String bounded = failure.substring(0, Math.min(MAX_DETAIL, failure.length()));
        String normalized = bounded.toUpperCase(Locale.ROOT);
        if (MEMBERSHIP_CODE.matcher(bounded).find() || normalized.contains("ZNACK-GS1-MEMBERSHIP")
                || normalized.contains("GS1_MEMBERSHIP_EXPIRED")) return Optional.of(Action.MEMBERSHIP);
        if (normalized.contains("ZNACK-GS1-PREFIX") || normalized.contains("GS1_GTIN_QUOTA_EXHAUSTED")
                || normalized.contains("V3_GTIN_QUOTA_UNVERIFIED")
                || normalized.contains("GS1/GTIN QUOTA IS UNAVAILABLE OR EXHAUSTED")) return Optional.of(Action.PREFIX);
        return Optional.empty();
    }
}
