package com.vncode.app.integration.update;

import java.util.Locale;
import java.util.Map;

public class UpdateInfo {

    private String version;
    private String releaseDate;
    private String changelog;
    private Map<String, String> downloadUrls;
    private Map<String, String> downloadSha256;
    private Map<String, Long> downloadSizes;
    private boolean mandatory;
    private String manifestUrl;
    public String getManifestUrl(){return manifestUrl;}
    public void setManifestUrl(String value){manifestUrl=value;}

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
    public void setDownloadSha256(Map<String,String> values) { downloadSha256 = values; }
    public void setDownloadSizes(Map<String,Long> values) { downloadSizes = values; }
    public String getBestDownloadSha256() { return valueForSelectedUrl(downloadSha256); }
    public Long getBestDownloadSize() { return valueForSelectedUrl(downloadSizes); }
    private <T> T valueForSelectedUrl(Map<String,T> values) {
        String selected=getBestDownloadUrl();
        if(values==null||selected==null||downloadUrls==null)return null;
        return downloadUrls.entrySet().stream().filter(e->selected.equals(e.getValue()))
                .map(e->values.get(e.getKey())).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

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
        return null;
    }

    public String getDisplayChangelog() {
        if (changelog == null || changelog.isBlank()) {
            return com.vncode.app.shared.I18nService.getInstance().tr("update.dialog.no_changelog");
        }
        return changelog.replace("\r\n", "\n").trim();
    }
}
