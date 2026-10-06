package com.tuandev.fbsbarcode.integration.license;

import com.tuandev.fbsbarcode.shared.ConfigService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Định danh máy để gắn license với thiết bị: SHA-256 của Windows MachineGuid.
 * Trên hệ khác (máy dev macOS/Linux) dùng UUID sinh một lần và lưu trong app_config.
 */
public final class DeviceFingerprint {

    private static final Logger log = LoggerFactory.getLogger(DeviceFingerprint.class);
    private static volatile String cached;

    private DeviceFingerprint() {}

    public static String get() {
        String value = cached;
        if (value == null) {
            synchronized (DeviceFingerprint.class) {
                value = cached;
                if (value == null) {
                    value = sha256Hex(rawMachineId());
                    cached = value;
                }
            }
        }
        return value;
    }

    private static String rawMachineId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String guid = windowsMachineGuid();
            if (guid != null && !guid.isBlank()) {
                return "machine-guid:" + guid.trim().toLowerCase(Locale.ROOT);
            }
            log.warn("Không đọc được MachineGuid, chuyển sang device id lưu trong config");
        }
        return "stored-device-id:" + storedDeviceId();
    }

    private static String windowsMachineGuid() {
        try {
            Process process =
                    new ProcessBuilder(
                                    "reg",
                                    "query",
                                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                                    "/v",
                                    "MachineGuid")
                            .redirectErrorStream(true)
                            .start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf("REG_SZ");
                    if (idx >= 0) {
                        process.waitFor(5, TimeUnit.SECONDS);
                        return line.substring(idx + "REG_SZ".length()).trim();
                    }
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Lỗi khi đọc MachineGuid: {}", e.getMessage());
        }
        return null;
    }

    private static String storedDeviceId() {
        String existing = ConfigService.getConfigValue("device_id");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        ConfigService.setConfigValue("device_id", generated);
        return generated;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
