package com.tuandev.fbsbarcode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = BuildConfig.class.getResourceAsStream("/app.properties")) {
            if (is != null) {
                PROPS.load(is);
            }
        } catch (IOException ignored) {
            // use defaults
        }
    }

    public static String getAppVersion() {
        return PROPS.getProperty("app.version", "0.0.0");
    }

    public static String getUpdateUrl() {
        return PROPS.getProperty("app.update.url", "https://github.com/rupphi/relatest-wcode");
    }

    public static String getUpdateManifestPublicKey() {
        return PROPS.getProperty("app.update.public-key", "");
    }

    public static String getUpdateSigningPublisher() {
        return PROPS.getProperty("app.update.publisher", "");
    }

    public static String getLicenseServerUrl() {
        return PROPS.getProperty("app.license.url", "https://wcode.online");
    }

    private BuildConfig() {}
}
