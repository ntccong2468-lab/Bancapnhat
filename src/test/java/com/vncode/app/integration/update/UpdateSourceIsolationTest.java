package com.vncode.app.integration.update;

import com.vncode.app.BuildConfig;
import com.vncode.app.config.Database;
import com.vncode.app.shared.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateSourceIsolationTest {
    @TempDir Path temp;
    @Test void updateUsesNewRepositoryAndMigratesOnlyKnownDefaults() {
        String original=System.getProperty("vncode.appdata.dir");
        try {
            System.setProperty("vncode.appdata.dir",temp.toString());
            Database.initDatabase();
            assertEquals("https://github.com/ntccong2468-lab/Bancapnhat",BuildConfig.getUpdateUrl());
            UpdateApiClient client=new UpdateApiClient();
            assertEquals("https://api.github.com/repos/ntccong2468-lab/Bancapnhat",client.resolveConfiguredSource());
            for(String legacy:new String[]{"https://github.com/rupphi/relatest-wcode/",
                    "https://api.github.com/repos/rupphi/relatest-wcode",
                    "https://github.com/ntccong2468-lab/Vncode",
                    "https://api.github.com/repos/ntccong2468-lab/Vncode"}) {
                ConfigService.setConfigValue("update_api_url",legacy);
                assertEquals("https://api.github.com/repos/ntccong2468-lab/Bancapnhat",client.resolveConfiguredSource());
            }
            ConfigService.setConfigValue("update_api_url","https://updates.example.com");
            assertEquals("https://updates.example.com",client.resolveConfiguredSource());
        } finally {
            if(original==null)System.clearProperty("vncode.appdata.dir");
            else System.setProperty("vncode.appdata.dir",original);
        }
    }
}
