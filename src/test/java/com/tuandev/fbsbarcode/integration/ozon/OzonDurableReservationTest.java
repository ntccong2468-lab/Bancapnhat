package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonDurableReservationTest {
    @TempDir Path temp;

    @AfterEach
    void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void duplicateResumeKeepsOneNonRecoverableKizReservation() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','42','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04600000000001','Marked item','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'04600000000001',1,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) "
                    + "VALUES(1,1,1,'010460000000000121ABC','010460000000000121ABC','04600000000001','AVAILABLE','IN_CIRCULATION',"
                    + "'2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        OzonPostingDto posting = new OzonPostingDto("POST-1", "", "", "awaiting_packaging", "", "", "", "",
                "", "", new OzonRequirements(List.of("101"), List.of(), List.of()), List.of("ship"), true,
                List.of(new OzonPostingItemDto(0, "101", "sku-1", "offer", "Item", 1, "RUB", "1")));
        new OzonPostingRepository().upsertDetail(1, posting);
        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(
                posting, java.util.Map.of("sku-1", "04600000000001"));
        OzonExemplarJobRepository repository = new OzonExemplarJobRepository();

        OzonExemplarJob created = repository.findOrCreate(1, "POST-1");
        repository.persistRemoteExemplars(created, plan, List.of("7001"));
        repository.reserveAndLink(created, plan);
        OzonExemplarJob resumed = repository.findOrCreate(1, "POST-1");

        assertEquals(created.id(), resumed.id());
        assertEquals(OzonExemplarJobStage.RESERVED, resumed.stage());
        assertEquals(1, repository.bindings(created.id()).size());
        assertEquals(0, new ZnackGtinInventoryService().releaseRecoverableReservations());
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals("0", scalar("SELECT reservation_recoverable FROM kiz_codes WHERE id=1"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection(); ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
