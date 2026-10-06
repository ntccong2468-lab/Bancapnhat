package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateApiClientTest {

    @Test
    void parseGitHubReleaseMapsVersionChangelogAndAssets() {
        String body = """
                {
                  "tag_name": "v1.2.3",
                  "published_at": "2026-05-11T00:00:00Z",
                  "body": "Bug fixes and improvements",
                  "html_url": "https://github.com/tuanworlddev/WCode/releases/tag/v1.2.3",
                  "assets": [
                    {
                      "name": "WCode.exe",
                      "browser_download_url": "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe"
                    },
                    {
                      "name": "WCode-portable.zip",
                      "browser_download_url": "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode-portable.zip"
                    }
                  ]
                }
                """;

        UpdateInfo info = UpdateApiClient.parseGitHubRelease(body);

        assertNotNull(info);
        assertEquals("1.2.3", info.getVersion());
        assertEquals("2026-05-11T00:00:00Z", info.getReleaseDate());
        assertEquals("Bug fixes and improvements", info.getChangelog());
        assertEquals(
                "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe",
                info.getDownloadUrls().get("exe")
        );
        assertWithOs(
                "Windows 11",
                "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe",
                info
        );
    }

    @Test
    void bestDownloadUrlPrefersInstallerForCurrentOperatingSystem() {
        UpdateInfo info = new UpdateInfo();
        info.setDownloadUrls(Map.of(
                "exe", "https://example.com/WCode.exe",
                "msi", "https://example.com/WCode.msi",
                "zip", "https://example.com/WCode-portable.zip",
                "dmg", "https://example.com/WCode-mac-arm64.dmg",
                "deb", "https://example.com/WCode-linux-amd64.deb",
                "release", "https://example.com/releases/latest"
        ));

        assertWithOs("Windows 11", "https://example.com/WCode.exe", info);
        assertWithOs("Mac OS X", "https://example.com/WCode-mac-arm64.dmg", info);
        assertWithOs("Linux", "https://example.com/WCode-linux-amd64.deb", info);
    }

    @Test
    void bestDownloadUrlFallsBackWhenPlatformAssetIsMissing() {
        UpdateInfo info = new UpdateInfo();
        info.setDownloadUrls(Map.of(
                "release", "https://example.com/releases/latest",
                "zip", "https://example.com/WCode-portable.zip"
        ));

        assertWithOs("Mac OS X", "https://example.com/releases/latest", info);
    }

    @Test
    void githubReleaseKeepsSeparateMacDownloadsForIntelAndAppleSilicon() {
        String body = """
                {
                  "tag_name": "v1.1.10",
                  "html_url": "https://example.com/releases/v1.1.10",
                  "assets": [
                    {"name":"WCode-macos-x64.dmg","browser_download_url":"https://example.com/x64.dmg"},
                    {"name":"WCode-macos-arm64.dmg","browser_download_url":"https://example.com/arm64.dmg"}
                  ]
                }
                """;

        UpdateInfo info = UpdateApiClient.parseGitHubRelease(body);

        assertNotNull(info);
        assertWithPlatform("Mac OS X", "x86_64", "https://example.com/x64.dmg", info);
        assertWithPlatform("Mac OS X", "aarch64", "https://example.com/arm64.dmg", info);
    }

    @Test
    void testChannelSelectsHighestMatchingPrereleaseAndIgnoresOtherReleases() {
        String body = """
                [
                  {
                    "tag_name": "v9.9.9",
                    "draft": false,
                    "assets": [{"name":"WCode.exe","browser_download_url":"https://example.com/prod.exe"}]
                  },
                  {
                    "tag_name": "znack-registration-test-v1.1.22",
                    "draft": false,
                    "prerelease": true,
                    "assets": [{"name":"WCode-1.1.22-Znack-Registration-Test.exe","browser_download_url":"https://example.com/122.exe"}]
                  },
                  {
                    "tag_name": "znack-registration-test-v1.1.24",
                    "draft": false,
                    "prerelease": true,
                    "assets": [{"name":"WCode-1.1.24-Znack-Registration-Test.exe","browser_download_url":"https://example.com/124.exe"}]
                  },
                  {
                    "tag_name": "znack-registration-test-v1.1.25",
                    "draft": true,
                    "assets": []
                  }
                ]
                """;

        UpdateInfo info = UpdateApiClient.parseGitHubReleaseList(body, UpdateApiClient.ZNACK_TEST_TAG_PREFIX);

        assertNotNull(info);
        assertEquals("1.1.24", info.getVersion());
        assertEquals("https://example.com/124.exe", info.getDownloadUrls().get("exe"));
    }

    @Test
    void testChannelReturnsNullWhenNoMatchingReleaseExists() {
        assertNull(UpdateApiClient.parseGitHubReleaseList(
                "[{\"tag_name\":\"v1.1.99\",\"draft\":false}]",
                UpdateApiClient.ZNACK_TEST_TAG_PREFIX));
    }

    @Test
    void testProfileAlwaysUsesTheDedicatedPublicRepository() {
        String originalProfile = System.getProperty("wcode.data.profile");
        try {
            System.setProperty("wcode.data.profile", "znack-registration-test");
            assertEquals(UpdateApiClient.ZNACK_TEST_UPDATE_SOURCE,
                    new UpdateApiClient().resolveConfiguredSource());
        } finally {
            if (originalProfile == null) System.clearProperty("wcode.data.profile");
            else System.setProperty("wcode.data.profile", originalProfile);
        }
    }

    private static void assertWithOs(String osName, String expectedUrl, UpdateInfo info) {
        assertWithPlatform(osName, System.getProperty("os.arch"), expectedUrl, info);
    }

    private static void assertWithPlatform(String osName, String osArch, String expectedUrl, UpdateInfo info) {
        String originalOs = System.getProperty("os.name");
        String originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", osName);
            System.setProperty("os.arch", osArch);
            assertEquals(expectedUrl, info.getBestDownloadUrl());
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
            if (originalArch == null) {
                System.clearProperty("os.arch");
            } else {
                System.setProperty("os.arch", originalArch);
            }
        }
    }
}
