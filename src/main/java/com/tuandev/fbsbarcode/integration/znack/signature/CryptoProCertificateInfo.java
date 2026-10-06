package com.tuandev.fbsbarcode.integration.znack.signature;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CryptoProCertificateInfo(String selector, String thumbprint, String subject, String issuer, String inn,
                                       Instant validFrom, Instant validTo, boolean hasPrivateKey, String provider,
                                       String rawSummary) {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?:^|,)\\s*([A-ZА-ЯЁ0-9.]+)\\s*=\\s*(?:\"([^\"]+)\"|([^,]+))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean expired(Instant now) {
        return validTo != null && validTo.isBefore(now);
    }

    public boolean usable(Instant now) {
        return selector != null && !selector.isBlank() && !expired(now);
    }

    public String displayName() {
        String owner = ownerName();
        StringBuilder result = new StringBuilder(owner);
        if (inn != null && !inn.isBlank()) result.append(" / INN ").append(inn);
        if (validTo != null) result.append(" / ").append(validToDate().format(DISPLAY_DATE));
        return result.toString();
    }

    public LocalDate validToDate() {
        return validTo == null ? null : validTo.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public String ownerName() {
        String commonName = attribute("CN");
        if (!commonName.isBlank()) return commonName;
        String organization = attribute("O");
        if (!organization.isBlank()) return organization;
        String surname = attribute("SN");
        String givenName = firstNonBlank(attribute("G"), attribute("GN"));
        String person = (surname + " " + givenName).trim();
        if (!person.isBlank()) return person;
        String unit = attribute("OU");
        return unit.isBlank() ? selector : unit;
    }

    private String attribute(String name) {
        Matcher matcher = ATTRIBUTE.matcher(subject == null ? "" : subject);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                return firstNonBlank(matcher.group(2), matcher.group(3)).trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    @Override
    public String toString() {
        return displayName();
    }
}
