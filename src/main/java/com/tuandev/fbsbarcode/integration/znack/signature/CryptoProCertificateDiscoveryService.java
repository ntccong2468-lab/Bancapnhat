package com.tuandev.fbsbarcode.integration.znack.signature;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CryptoProCertificateDiscoveryService {
    private static final Pattern FIELD = Pattern.compile("^\\s*([^:=]+?)\\s*[:=]\\s*(.*)$");
    private static final Pattern INN = Pattern.compile("(?:ИНН|INN)\\s*[=:]?\\s*(\\d{12}|\\d{10})", Pattern.CASE_INSENSITIVE);
    private final CryptoProCommandRunner runner;

    public CryptoProCertificateDiscoveryService() {
        this(new CryptoProCommandRunner());
    }

    CryptoProCertificateDiscoveryService(CryptoProCommandRunner runner) {
        this.runner = runner;
    }

    public List<CryptoProCertificateInfo> discover(String certmgrOverride, String csptestOverride, Duration timeout) throws CryptoProException {
        String certmgr = runner.resolve(certmgrOverride, "certmgr");
        CryptoProCommandRunner.Result result = runner.run(List.of(certmgr, "-list", "-store", "uMy"), timeout);
        if (result.exitCode() != 0) {
            String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
            if (diagnostic.contains("empty certificate list") || diagnostic.contains("список сертификатов пуст")) {
                return List.of();
            }
            throw new CryptoProException(CryptoProErrorCode.DISCOVERY_FAILED,
                    "CryptoPro certificate discovery failed (exit " + result.exitCode() + "): " + result.diagnostic());
        }
        return parse(result.stdoutText());
    }

    public List<CryptoProCertificateInfo> usable(List<CryptoProCertificateInfo> certificates, Instant now) {
        return certificates.stream().filter(c -> c.usable(now)).toList();
    }

    public List<CryptoProCertificateInfo> parse(String output) {
        List<CryptoProCertificateInfo> result = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        String lastKey = null;
        for (String line : output.replace("\r", "").split("\n")) {
            Matcher matcher = FIELD.matcher(line);
            String key = matcher.matches() ? normalize(matcher.group(1)) : "";
            boolean numberedBoundary = line.trim().matches("\\d+\\s*-{3,}");
            boolean nextCertificate = !fields.isEmpty() && isSelector(key) && hasSelector(fields);
            if (!fields.isEmpty() && (numberedBoundary || nextCertificate)) {
                addCertificate(result, fields, raw);
                fields.clear();
                raw.setLength(0);
                lastKey = null;
            }
            if (matcher.matches() && lastKey != null && isMultilineField(lastKey)
                    && Character.isWhitespace(line.charAt(0)) && isDnAttribute(key)) {
                fields.computeIfPresent(lastKey, (ignored, value) -> value + ", " + line.trim());
            } else if (matcher.matches()) {
                fields.put(key, matcher.group(2).trim());
                lastKey = key;
            } else if (lastKey != null && isMultilineField(lastKey) && !line.isBlank()
                    && Character.isWhitespace(line.charAt(0))) {
                fields.computeIfPresent(lastKey, (ignored, value) -> value + ", " + line.trim());
            }
            if (!line.isBlank()) raw.append(line.trim()).append('\n');
        }
        if (!fields.isEmpty()) addCertificate(result, fields, raw);
        return List.copyOf(result);
    }

    private void addCertificate(List<CryptoProCertificateInfo> result, Map<String, String> fields, StringBuilder raw) {
        CryptoProCertificateInfo certificate = certificate(fields, raw.toString());
        if (certificate.selector() != null && !certificate.selector().isBlank()) result.add(certificate);
    }

    private CryptoProCertificateInfo certificate(Map<String, String> fields, String raw) {
        String thumbprint = field(fields, "sha1 thumbprint", "sha1 hash", "thumbprint", "отпечаток",
                "отпечаток sha1", "sha1 отпечаток", "хэш sha1", "hash");
        String selector = field(fields, "selector", "идентификатор", "id");
        if (selector.isBlank()) selector = thumbprint.replace(" ", "");
        String subject = field(fields, "subject", "субъект");
        String inn = "";
        Matcher innMatcher = INN.matcher(subject);
        if (innMatcher.find()) inn = innMatcher.group(1);
        return new CryptoProCertificateInfo(selector, thumbprint.replace(" ", ""), subject,
                field(fields, "issuer", "издатель"), inn,
                date(field(fields, "not valid before", "valid from", "действителен с", "выдан")),
                date(field(fields, "not valid after", "valid to", "действителен до", "истекает")),
                privateKey(field(fields, "privatekey link", "private key", "ссылка на закрытый ключ",
                        "ссылка на ключ", "закрытый ключ")),
                field(fields, "provider", "провайдер"), raw.strip());
    }

    private static boolean privateKey(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return !(normalized.contains("no") || normalized.contains("нет") || normalized.contains("absent") || normalized.contains("missing"));
    }

    private static Instant date(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceFirst("\\s+(?:UTC|GMT|MSK)$", "");
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_INSTANT, DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd.MM.yyyy"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("MM/dd/yyyy"))) {
            try {
                if (formatter == DateTimeFormatter.ISO_INSTANT) return Instant.from(formatter.parse(normalized));
                if (formatter == DateTimeFormatter.ISO_LOCAL_DATE || !normalized.contains(":")) {
                    return LocalDate.parse(normalized, formatter).atStartOfDay(ZoneId.systemDefault()).toInstant();
                }
                return LocalDateTime.parse(normalized, formatter).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private static String field(Map<String, String> fields, String... names) {
        for (String name : names) if (fields.containsKey(name)) return fields.get(name);
        return "";
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean isSelector(String key) {
        return Set.of("sha1 thumbprint", "sha1 hash", "thumbprint", "отпечаток", "отпечаток sha1",
                "sha1 отпечаток", "хэш sha1", "hash", "selector", "идентификатор", "id").contains(key);
    }

    private static boolean hasSelector(Map<String, String> fields) {
        return fields.keySet().stream().anyMatch(CryptoProCertificateDiscoveryService::isSelector);
    }

    private static boolean isMultilineField(String key) {
        return Set.of("subject", "субъект", "issuer", "издатель").contains(key);
    }

    private static boolean isDnAttribute(String key) {
        return Set.of("cn", "o", "ou", "sn", "g", "gn", "inn", "инн", "e", "email", "emailaddress",
                "c", "s", "st", "l", "street", "t", "ogrn", "огрн", "snils", "снилс").contains(key);
    }

}
