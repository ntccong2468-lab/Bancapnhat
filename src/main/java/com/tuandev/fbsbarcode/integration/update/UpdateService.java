package com.tuandev.fbsbarcode.integration.update;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateService.class);
    private final UpdateApiClient apiClient = new UpdateApiClient();

    public UpdateInfo checkForUpdate() {
        try {
            UpdateInfo info = apiClient.fetchLatestVersion();
            if (info == null || info.getVersion() == null) return null;

            String currentVersion = BuildConfig.getAppVersion();
            String latestVersion = info.getVersion();

            if (VersionComparator.compare(latestVersion, currentVersion) <= 0) {
                return null;
            }

            String skipped = ConfigService.getSkippedVersion();
            if (latestVersion.equals(skipped)) {
                return null;
            }
            return info;

        } catch (Exception e) {
            LOGGER.warn("Update check failed", e);
            return null;
        }
    }
}
