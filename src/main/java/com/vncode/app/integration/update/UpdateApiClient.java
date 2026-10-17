package com.vncode.app.integration.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.BuildConfig;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.ConfigService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public class UpdateApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateApiClient.class);
    private static final Gson GSON = new Gson();
    static final String ZNACK_TEST_UPDATE_SOURCE = "https://api.github.com/repos/ntccong2468-lab/Vncode";
    static final String ZNACK_TEST_TAG_PREFIX = "znack-registration-test-v";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build();
    private final OkHttpClient http;
    private final String pinnedUpdateKey;
    public UpdateApiClient(){this(CLIENT);}
    UpdateApiClient(OkHttpClient http){this(http,BuildConfig.getUpdateManifestPublicKey());}
    UpdateApiClient(OkHttpClient http,String pinnedUpdateKey){this.http=http;this.pinnedUpdateKey=pinnedUpdateKey;}

    public UpdateInfo fetchLatestVersion() throws IOException {
        return fetchWithRetry(0);
    }

    public String resolveConfiguredSource() {
        return normalizeUpdateSource(getEffectiveUpdateUrl());
    }

    private UpdateInfo fetchWithRetry(int attempt) throws IOException {
        String sourceUrl = resolveConfiguredSource();
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return null;
        }
        boolean githubSource = isGitHubRepoApi(sourceUrl);
        boolean testChannel = AppPaths.isZnackRegistrationTestProfile();
        Request request = new Request.Builder()
                .url(githubSource
                        ? sourceUrl + (testChannel ? "/releases?per_page=30" : "/releases/latest")
                        : sourceUrl + "/api/versions/latest")
                .header("User-Agent", "VNcode/" + BuildConfig.getAppVersion())
                .header("Accept", githubSource ? "application/vnd.github+json" : "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 204) {
                return null;
            }
            if (githubSource && response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new IOException("UPDATE_HTTP_"+response.code());
            }
            if(response.body()==null||response.body().contentLength()>1024*1024)throw new IOException("UPDATE_INVALID_RESPONSE");
            byte[] bytes=response.body().byteStream().readNBytes(1024*1024+1);
            if(bytes.length>1024*1024)throw new IOException("UPDATE_INVALID_RESPONSE");
            String body = new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if (body.isEmpty()) return null;
            if (!githubSource) {
                UpdateInfo info=GSON.fromJson(body, UpdateInfo.class);
                if(info==null||info.getVersion()==null||!info.getVersion().matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))throw new IOException("UPDATE_INVALID_RESPONSE");
                return verifySignedIfConfigured(info,testChannel);
            }
            UpdateInfo info=testChannel
                    ? parseGitHubReleaseList(body, ZNACK_TEST_TAG_PREFIX)
                    : parseGitHubRelease(body);
            return verifySignedIfConfigured(info,testChannel);
        } catch (IOException e) {
            if (attempt < 1 && (e.getMessage()==null||!e.getMessage().startsWith("UPDATE_"))) {
                LOGGER.debug("Update check attempt {} failed, retrying...", attempt + 1);
                return fetchWithRetry(attempt + 1);
            }
            if (e instanceof UnknownHostException) {
                LOGGER.info("Update endpoint unavailable: {}", sourceUrl);
            } else {
                LOGGER.warn("Update check failed after retry", e);
            }
            throw e;
        } catch(RuntimeException malformed) {
            throw new IOException("UPDATE_INVALID_RESPONSE");
        }
    }

    private UpdateInfo verifySignedIfConfigured(UpdateInfo info,boolean testChannel)throws IOException {
            if(info!=null&&!testChannel&&pinnedUpdateKey!=null&&!pinnedUpdateKey.isBlank()) {
                String url=info.getManifestUrl();
                if(url==null||!url.startsWith("https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v"+info.getVersion()+"/"))throw new IOException("UPDATE_SIGNATURE_MISSING");
                try(Response signed=http.newCall(new Request.Builder().url(url).header("User-Agent","VN-code-Updater").build()).execute()){
                    if(!signed.isSuccessful()||signed.body()==null||signed.body().contentLength()>128*1024)throw new IOException("UPDATE_SIGNATURE_INVALID");
                    byte[] manifest=signed.body().byteStream().readNBytes(128*1024+1);
                    if(manifest.length>128*1024)throw new IOException("UPDATE_SIGNATURE_INVALID");
                    info=SignedUpdateManifest.verify(new String(manifest,java.nio.charset.StandardCharsets.UTF_8),pinnedUpdateKey,info.getVersion());
                }
            }
            return info;
    }

    static UpdateInfo parseGitHubRelease(String body) {
        return parseGitHubRelease(JsonParser.parseString(body).getAsJsonObject(), "");
    }

    static UpdateInfo parseGitHubReleaseList(String body, String tagPrefix) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            return null;
        }
        UpdateInfo latest = null;
        for (JsonElement element : parsed.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) {
                continue;
            }
            UpdateInfo candidate = parseGitHubRelease(release, tagPrefix);
            if (candidate != null
                    && candidate.getDownloadUrls() != null
                    && candidate.getDownloadUrls().containsKey("exe")
                    && (latest == null
                    || VersionComparator.compare(candidate.getVersion(), latest.getVersion()) > 0)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private static UpdateInfo parseGitHubRelease(JsonObject root, String tagPrefix) {
        String tagName = getString(root, "tag_name");
        if (tagName == null || tagName.isBlank()) {
            return null;
        }
        String prefix = tagPrefix == null ? "" : tagPrefix;
        if(root.has("draft")&&root.get("draft").getAsBoolean())return null;
        if(prefix.isBlank()&&root.has("prerelease")&&root.get("prerelease").getAsBoolean())return null;
        if (!prefix.isBlank() && !tagName.startsWith(prefix)) {
            return null;
        }

        UpdateInfo info = new UpdateInfo();
        info.setVersion(prefix.isBlank()
                ? stripLeadingV(tagName)
                : stripLeadingV(tagName.substring(prefix.length())));
        if(!info.getVersion().matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))return null;
        info.setReleaseDate(getString(root, "published_at"));
        info.setChangelog(getString(root, "body"));
        info.setMandatory(false);

        Map<String, String> downloadUrls = new LinkedHashMap<>();
        Map<String,String> digests=new LinkedHashMap<>();
        Map<String,Long> sizes=new LinkedHashMap<>();
        JsonArray assets = root.has("assets") && root.get("assets").isJsonArray() ? root.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) {
                continue;
            }
            JsonObject asset = assetElement.getAsJsonObject();
            String assetName = getString(asset, "name");
            String downloadUrl = getString(asset, "browser_download_url");
            if (assetName == null || downloadUrl == null || downloadUrl.isBlank()) {
                continue;
            }
            String key=resolveAssetKey(assetName);
            if(assetName.equals("update-manifest.json"))info.setManifestUrl(downloadUrl);
            downloadUrls.put(key, downloadUrl);
            String digest=getString(asset,"digest");
            if(digest!=null&&digest.matches("sha256:[a-fA-F0-9]{64}"))digests.put(key,digest.substring(7).toLowerCase(java.util.Locale.ROOT));
            if(asset.has("size")&&asset.get("size").isJsonPrimitive()) {
                try{long size=asset.get("size").getAsLong();if(size>0&&size<=1024L*1024*1024)sizes.put(key,size);}catch(RuntimeException ignored){}
            }
        }

        String htmlUrl = getString(root, "html_url");
        if ((downloadUrls.isEmpty() || !downloadUrls.containsKey("release")) && htmlUrl != null && !htmlUrl.isBlank()) {
            downloadUrls.put("release", htmlUrl);
        }
        info.setDownloadUrls(downloadUrls);
        info.setDownloadSha256(digests);info.setDownloadSizes(sizes);
        return info;
    }

    private String getEffectiveUpdateUrl() {
        // The isolated test package must never read a configured production update URL.
        if (AppPaths.isZnackRegistrationTestProfile()) {
            return ZNACK_TEST_UPDATE_SOURCE;
        }
        try {
            String dbUrl = ConfigService.getConfigValue("update_api_url");
            if (dbUrl != null && !dbUrl.isBlank()) {
                String normalized=normalizeUpdateSource(dbUrl);
                // Migrate the inherited vendor default; preserve explicitly configured custom sources.
                if (!"https://api.github.com/repos/rupphi/relatest-wcode".equalsIgnoreCase(normalized)
                        && !"https://api.github.com/repos/ntccong2468-lab/Vncode".equalsIgnoreCase(normalized)) return dbUrl;
            }
        } catch (Exception ignored) {}
        return BuildConfig.getUpdateUrl();
    }

    private static String normalizeUpdateSource(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.isBlank()) {
            return url;
        }
        if (url.startsWith("https://github.com/")) {
            String path = url.substring("https://github.com/".length());
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                return "https://api.github.com/repos/" + parts[0] + "/" + parts[1].replaceFirst("\\.git$", "");
            }
        }
        return url;
    }

    private static boolean isGitHubRepoApi(String url) {
        return url != null && url.startsWith("https://api.github.com/repos/");
    }

    private static String stripLeadingV(String value) {
        return value != null && (value.startsWith("v") || value.startsWith("V"))
                ? value.substring(1)
                : value;
    }

    private static String resolveAssetKey(String assetName) {
        String name = assetName.toLowerCase();
        if (name.endsWith(".dmg")) {
            if (name.contains("arm64") || name.contains("aarch64")) return "dmg-arm64";
            if (name.contains("x64") || name.contains("x86_64") || name.contains("intel")) return "dmg-x64";
            return "dmg";
        }
        if (name.endsWith(".tar.gz")) return "tar.gz";
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && lastDot < name.length() - 1) {
            return name.substring(lastDot + 1);
        }
        return name;
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
