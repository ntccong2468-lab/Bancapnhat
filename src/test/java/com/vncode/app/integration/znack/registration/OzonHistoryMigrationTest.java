package com.vncode.app.integration.znack.registration;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonHistoryMigrationTest {
    @TempDir Path root;
    @Test void addsUnknownStatusWithoutChangingExistingHistory() throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("legacy.sqlite"));var s=db.createStatement()){
            s.execute("CREATE TABLE ozon_card_registration_history(id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT NOT NULL,gtin TEXT NOT NULL,payload_json TEXT NOT NULL,feed_id TEXT NOT NULL,good_id INTEGER,created_at TEXT NOT NULL)");
            s.execute("INSERT INTO ozon_card_registration_history(shop_id,product_id,offer_id,gtin,payload_json,feed_id,good_id,created_at) VALUES(2,'501','offer','04601234567893','old-json','old-feed',91,'old-time')");
            OzonCardRegistrationRepository.initialize(db);OzonCardRegistrationRepository.initialize(db);
            try(var r=s.executeQuery("SELECT gtin,payload_json,feed_id,good_id,created_at,status FROM ozon_card_registration_history")){
                assertTrue(r.next());assertEquals("04601234567893",r.getString(1));assertEquals("old-json",r.getString(2));assertEquals("old-feed",r.getString(3));assertEquals(91,r.getLong(4));assertEquals("old-time",r.getString(5));assertEquals("",r.getString(6));assertFalse(r.next());
            }
        }
    }
}
