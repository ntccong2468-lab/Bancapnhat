package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.config.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class ConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    public static String getConfigValue(String key) {
        String sql = "SELECT value FROM app_config WHERE key = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            if (isMissingAppConfigTable(e)) {
                return null;
            }
            LOGGER.error("Failed to read config key: " + key, e);
        }
        return null;
    }

    public static void setConfigValue(String key, String value) {
        String sql = "INSERT INTO app_config (key, value) VALUES (?, ?) " +
                     "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                if (attempt < 3 && isBusy(e)) {
                    sleepQuietly(150L * attempt);
                    continue;
                }
                LOGGER.error("Failed to write config key: " + key, e);
                return;
            }
        }
    }

    public static String getSkippedVersion() {
        return getConfigValue("update_skipped_version");
    }

    public static void setSkippedVersion(String version) {
        setConfigValue("update_skipped_version", version);
    }

    public static String getLastUpdateCheck() {
        return getConfigValue("update_last_check");
    }

    public static void setLastUpdateCheck(String timestamp) {
        setConfigValue("update_last_check", timestamp);
    }

    public static String getLastUpdateCheckSource() {
        return getConfigValue("update_last_check_source");
    }

    public static void setLastUpdateCheckSource(String source) {
        setConfigValue("update_last_check_source", source);
    }

    public static String getPrintAccessToken() {
        return getConfigValue("print_access_token");
    }

    public static void setPrintAccessToken(String token) {
        setConfigValue("print_access_token", token);
    }

    public static void clearPrintAccessToken() {
        setConfigValue("print_access_token", "");
    }

    public static Integer getLastSelectedShopId() {
        String value = getConfigValue("last_selected_shop_id");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid last_selected_shop_id value: {}", value);
            return null;
        }
    }

    public static void setLastSelectedShopId(Integer shopId) {
        setConfigValue("last_selected_shop_id", shopId == null ? "" : String.valueOf(shopId));
    }

    public static String getAppLanguage() {
        return getConfigValue("app_language");
    }

    public static void setAppLanguage(String languageCode) {
        setConfigValue("app_language", languageCode == null ? "" : languageCode);
    }

    public static String getActivatedAt() {
        return getConfigValue("activated_at");
    }

    public static String getLicenseKey() {
        return getConfigValue("license_key");
    }

    public static void setLicenseKey(String licenseKey) {
        setConfigValue("license_key", licenseKey == null ? "" : licenseKey);
    }

    public static String getLicenseServerUrl() {
        return getConfigValue("license_server_url");
    }

    public static void setActivatedAt(String timestamp) {
        setConfigValue("activated_at", timestamp == null ? "" : timestamp);
    }

    private static boolean isBusy(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("busy");
    }

    private static boolean isMissingAppConfigTable(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("no such table: app_config");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

}
