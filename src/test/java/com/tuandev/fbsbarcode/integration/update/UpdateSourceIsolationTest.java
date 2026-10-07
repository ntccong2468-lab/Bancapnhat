package com.tuandev.fbsbarcode.integration.update;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.shared.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateSourceIsolationTest {
    @TempDir Path temp;
    @Test void forkUsesItsOwnRepositoryAndMigratesOnlyTheLegacyVendorSource() {
        String original=System.getProperty("wcode.appdata.dir");
        try {
            System.setProperty("wcode.appdata.dir",temp.toString());
            Database.initDatabase();
            assertEquals("https://github.com/ntccong2468-lab/Vncode",BuildConfig.getUpdateUrl());
            UpdateApiClient client=new UpdateApiClient();
            assertEquals("https://api.github.com/repos/ntccong2468-lab/Vncode",client.resolveConfiguredSource());
            for(String legacy:new String[]{"https://github.com/rupphi/relatest-wcode/",
                    "https://api.github.com/repos/rupphi/relatest-wcode"}) {
                ConfigService.setConfigValue("update_api_url",legacy);
                assertEquals("https://api.github.com/repos/ntccong2468-lab/Vncode",client.resolveConfiguredSource());
            }
            ConfigService.setConfigValue("update_api_url","https://updates.example.com");
            assertEquals("https://updates.example.com",client.resolveConfiguredSource());
        } finally {
            if(original==null)System.clearProperty("wcode.appdata.dir");
            else System.setProperty("wcode.appdata.dir",original);
        }
    }
}
