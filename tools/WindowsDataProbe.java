import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Native packaging probe; all data lives in an explicitly supplied CI directory. */
public final class WindowsDataProbe {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[1]).toAbsolutePath();
        System.setProperty("wcode.appdata.dir", data.toString());
        if ("seed".equals(args[0])) {
            Database.initDatabase();
            try (var c = Database.getConnection(); var s = c.createStatement()) {
                s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'native-fixture','WILDBERRIES','fixture')");
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'native-fixture','Fixture','2026-10-07T00:00:00Z')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
                s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,good_id,wb_updated,status,created_at,updated_at) VALUES(1,11,101,'04631993764363','native-feed',91,1,'ERROR','fixture','fixture')");
                s.execute("PRAGMA user_version=3");
            }
            return;
        }
        Path database = "snapshot".equals(args[0]) ? data : data.resolve("database.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) {
            try (var r = s.executeQuery("PRAGMA user_version")) {
                int expected = "snapshot".equals(args[0]) ? 3 : Database.currentSchemaVersion();
                if (!r.next() || r.getInt(1) != expected) throw new IllegalStateException("Schema is not ready");
            }
            if ("ready".equals(args[0])) return;
            try (var r = s.executeQuery("PRAGMA integrity_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) throw new IllegalStateException("Invalid database");
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                if (r.next()) throw new IllegalStateException("Invalid foreign keys");
            }
            try (var r = s.executeQuery("SELECT gtin,feed_id,good_id,wb_updated,status FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=11")) {
                if (!r.next() || !"04631993764363".equals(r.getString(1))
                        || !"native-feed".equals(r.getString(2)) || r.getLong(3) != 91
                        || r.getInt(4) != 1 || !"ERROR".equals(r.getString(5))) {
                    throw new IllegalStateException("Registration history changed during startup");
                }
            }
        }
    }
}
