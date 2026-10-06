package com.tuandev.fbsbarcode.integration.update;

import java.util.Locale;
import java.util.Map;

public class UpdateInfo {

    private String version;
    private String releaseDate;
    private String changelog;
    private Map<String, String> downloadUrls;
    private boolean mandatory;

    public String getVersion() { return version; }
    public String getReleaseDate() { return releaseDate; }
    public String getChangelog() { return changelog; }
    public Map<String, String> getDownloadUrls() { return downloadUrls; }
    public boolean isMandatory() { return mandatory; }

    public void setVersion(String version) { this.version = version; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public void setDownloadUrls(Map<String, String> downloadUrls) { this.downloadUrls = downloadUrls; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }

    public String getBestDownloadUrl() {
        if (downloadUrls == null || downloadUrls.isEmpty()) {
            return null;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return firstAvailable("exe", "msi", "zip", "release");
        }
        if (os.contains("mac")) {
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String nativeDmg = architecture.contains("aarch64") || architecture.contains("arm64")
                    ? "dmg-arm64" : "dmg-x64";
            return firstAvailable(nativeDmg, "dmg", "release");
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return firstAvailable("deb", "release");
        }
        return firstAvailable("release");
    }

    private String firstAvailable(String... keys) {
        if (downloadUrls == null || downloadUrls.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String url = downloadUrls.get(key);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return downloadUrls.values().stream()
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
    }

    public String getDisplayChangelog() {
        if (changelog == null || changelog.isBlank()) {
            return com.tuandev.fbsbarcode.shared.I18nService.getInstance().tr("update.dialog.no_changelog");
        }
        return changelog.replace("\r\n", "\n").trim();
    }
}
