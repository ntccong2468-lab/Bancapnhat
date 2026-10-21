package com.vncode.app.integration.znack.signature;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record CryptoProCertificateInfo(String selector, String thumbprint, String subject, String issuer, String inn,
                                       Instant validFrom, Instant validTo, boolean hasPrivateKey, String provider,
                                       String rawSummary) {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

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
        String organization = organizationName();
        // A company certificate may name its employee in CN. GS1/CZ account ownership is the
        // company, whose INNLE is distinct from the employee's personal INN.
        if (inn != null && inn.matches("[0-9]{10}") && !organization.isBlank()) return organization;
        String commonName = attribute("CN", "2.5.4.3");
        if (!commonName.isBlank()) return commonName;
        if (!organization.isBlank()) return organization;
        String person = signerFullName();
        if (!person.isBlank()) return person;
        String unit = attribute("OU", "2.5.4.11");
        return unit.isBlank() ? selector : unit;
    }

    public String organizationName() { return attribute("O", "2.5.4.10"); }

    public String signerFullName() {
        String surname = attribute("SN", "SURNAME", "2.5.4.4");
        String given = attribute("G", "GN", "GIVENNAME", "2.5.4.42");
        String person = (surname + " " + given).trim();
        return person.isBlank() ? attribute("CN", "2.5.4.3") : person;
    }

    /** Company INN takes precedence over a representative's personal INN. */
    public static String enterpriseInn(String subject) {
        Map<String, String> values = attributes(subject);
        String company = value(values, "INNLE", "ИННЮЛ", "1.2.643.100.4");
        if (company.matches("[0-9]{10}")) return company;
        String personal = value(values, "INN", "ИНН", "1.2.643.3.131.1.1");
        return personal.matches("[0-9]{10}|[0-9]{12}") ? personal : "";
    }

    private String attribute(String... names) { return value(attributes(subject), names); }

    private static String value(Map<String, String> attributes, String... names) {
        for (String name : names) {
            String value = attributes.get(normalize(name));
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    /** Read CryptoPro's DN display without splitting an escaped or quoted comma in a name. */
    private static Map<String, String> attributes(String subject) {
        var result = new LinkedHashMap<String, String>();
        if (subject == null) return result;
        var part = new StringBuilder(); boolean quoted = false, escaped = false;
        for (int i = 0; i <= subject.length(); i++) {
            char current = i == subject.length() ? ',' : subject.charAt(i);
            if (escaped) { part.append(current); escaped = false; continue; }
            if (current == '\\') { escaped = true; continue; }
            if (current == '"') { quoted = !quoted; continue; }
            if ((current == ',' || current == '+') && !quoted) {
                int equals = part.indexOf("=");
                if (equals > 0) result.putIfAbsent(normalize(part.substring(0, equals)),
                        part.substring(equals + 1).strip());
                part.setLength(0);
            } else part.append(current);
        }
        return result;
    }

    private static String normalize(String name) {
        return name.strip().toUpperCase(Locale.ROOT).replaceFirst("^OID\\.", "").replaceAll("\\s+", "");
    }

    @Override
    public String toString() {
        return displayName();
    }
}
