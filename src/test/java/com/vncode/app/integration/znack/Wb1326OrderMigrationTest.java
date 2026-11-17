package com.vncode.app.integration.znack;

import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real transactional DDL on synthetic old databases, including failure after DROP. */
class Wb1326OrderMigrationTest {
    @TempDir Path directory;
    Connection c;
    @BeforeEach void setup() throws Exception {
        c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("old.db"));
        try(var s=c.createStatement()){
            s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA user_version=6");
            s.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO shops VALUES(1),(2)");
            s.execute("CREATE TABLE znack_products(shop_id INTEGER NOT NULL,gtin TEXT NOT NULL,PRIMARY KEY(shop_id,gtin),FOREIGN KEY(shop_id) REFERENCES shops(id))");
            s.execute("INSERT INTO znack_products VALUES(1,'A'),(1,'B'),(2,'A')");
            s.execute("CREATE TABLE kiz_orders(id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,external_order_id TEXT,gtin TEXT NOT NULL,quantity INTEGER NOT NULL,local_status TEXT NOT NULL,custom_payload TEXT DEFAULT 'kept',UNIQUE(shop_id,id),UNIQUE(shop_id,external_order_id),FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin),FOREIGN KEY(shop_id) REFERENCES shops(id))");
            s.execute("INSERT INTO kiz_orders(id,shop_id,external_order_id,gtin,quantity,local_status,custom_payload) VALUES(7,1,'remote-before','A',2,'CODES_DOWNLOADED','original')");
            s.execute("CREATE TABLE kiz_codes(id INTEGER PRIMARY KEY,shop_id INTEGER,order_id INTEGER,raw_code TEXT,status TEXT,legal_status TEXT,FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id) ON DELETE CASCADE)");
            s.execute("INSERT INTO kiz_codes VALUES(101,1,7,'SYNTHETIC-NOT-A-MARK','CONSUMED','IN_CIRCULATION')");
            s.execute("CREATE TABLE znack_documents(id INTEGER PRIMARY KEY,shop_id INTEGER,order_id INTEGER,payload_json TEXT,FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id) ON DELETE CASCADE)");
            s.execute("INSERT INTO znack_documents VALUES(202,1,7,'fixture-document')");
            s.execute("CREATE TABLE znack_purchase_pipelines(id INTEGER PRIMARY KEY,shop_id INTEGER,order_id INTEGER,request_key TEXT,FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id))");
            s.execute("INSERT INTO znack_purchase_pipelines VALUES(303,1,7,'approved-fixture')");
            s.execute("CREATE TABLE audit(value INTEGER)");
            s.execute("CREATE INDEX fixture_order_index ON kiz_orders(custom_payload)");
            s.execute("CREATE TRIGGER fixture_order_trigger AFTER INSERT ON kiz_orders BEGIN INSERT INTO audit VALUES(NEW.id); END");
            s.execute("UPDATE sqlite_sequence SET seq=120 WHERE name='kiz_orders'");
        }
    }
    @AfterEach void cleanup() throws Exception {c.close();}
    @Test void multipleGtinsShareRemoteOrderWithoutChangingAnyHistoricalIdentity() throws Exception {
        ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});assertOldData();
        try(var s=c.createStatement()){
            s.execute("INSERT INTO kiz_orders(shop_id,external_order_id,gtin,quantity,local_status) VALUES(1,'remote-before','B',3,'SUBMITTED')");
            assertEquals(121,number("SELECT id FROM kiz_orders WHERE gtin='B'"));
            assertThrows(SQLException.class,()->s.execute("INSERT INTO kiz_orders(shop_id,external_order_id,gtin,quantity,local_status) VALUES(1,'remote-before','A',1,'DRAFT')"));
            s.execute("INSERT INTO kiz_orders(shop_id,external_order_id,gtin,quantity,local_status) VALUES(2,'remote-before','A',1,'DRAFT')");
        }
        assertEquals(2,number("SELECT COUNT(*) FROM audit"));assertNoForeignKeys();
    }
    @Test void errorAfterDroppingOldParentRollsBackDataConstraintsIndexesAndSequence() throws Exception {
        assertThrows(SQLException.class,()->ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{throw new SQLException("fixture rollback");}));
        assertOldData(6);assertEquals(120,number("SELECT seq FROM sqlite_sequence WHERE name='kiz_orders'"));
        assertEquals(1,number("SELECT COUNT(*) FROM sqlite_master WHERE name='fixture_order_index'"));
        assertEquals(1,number("SELECT COUNT(*) FROM sqlite_master WHERE name='fixture_order_trigger'"));
        try(var s=c.createStatement()){assertThrows(SQLException.class,()->s.execute("INSERT INTO kiz_orders(shop_id,external_order_id,gtin,quantity,local_status) VALUES(1,'remote-before','B',1,'DRAFT')"));}
        assertEquals(0,number("SELECT COUNT(*) FROM sqlite_master WHERE name='kiz_orders_wb1326_upgrade'"));
    }
    @Test void emptyOldTableKeepsDeletedIdHighWaterMark() throws Exception {
        try(var s=c.createStatement()){s.execute("DELETE FROM kiz_codes");s.execute("DELETE FROM znack_documents");s.execute("DELETE FROM znack_purchase_pipelines");s.execute("DELETE FROM kiz_orders");}
        ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});
        try(var s=c.createStatement()){s.execute("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status) VALUES(1,'A',1,'DRAFT')");}
        assertEquals(121,number("SELECT id FROM kiz_orders"));
    }
    @Test void existingViewsAndChildTriggersKeepOriginalParentName() throws Exception {
        try(var s=c.createStatement()){
            s.execute("CREATE VIEW fixture_orders_view AS SELECT id,gtin FROM kiz_orders");
            s.execute("CREATE TRIGGER fixture_code_trigger AFTER INSERT ON kiz_codes BEGIN SELECT id FROM kiz_orders WHERE id=NEW.order_id; END");
        }
        ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});
        assertEquals(7,number("SELECT id FROM fixture_orders_view"));assertOldData();
    }
    @Test void repeatedMigrationDoesNotReplayTriggersOrRewriteRows() throws Exception {
        ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});
        assertOldData();assertEquals(0,number("SELECT COUNT(*) FROM audit"));
    }
    @Test void laterBootstrapFailureCannotLeaveOldSchemaLabelOnCommittedNewUniqueness() throws Exception {
        assertThrows(SQLException.class,()->{ZnackPurchaseSchema.migrateOrderUniqueness(c,()->{});throw new SQLException("fixture later initializer failed");});
        assertOldData(7);assertEquals(7,number("PRAGMA user_version"));
    }
    private long number(String sql) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private String text(String sql) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private void assertNoForeignKeys() throws Exception {try(var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}}
    private void assertOldData() throws Exception {assertOldData(7);}
    private void assertOldData(int expectedVersion) throws Exception {
        assertEquals(7,number("SELECT id FROM kiz_orders WHERE external_order_id='remote-before'"));
        assertEquals("original",text("SELECT custom_payload FROM kiz_orders WHERE id=7"));
        assertEquals("CONSUMED",text("SELECT status FROM kiz_codes WHERE id=101"));
        assertEquals("IN_CIRCULATION",text("SELECT legal_status FROM kiz_codes WHERE id=101"));
        assertEquals("fixture-document",text("SELECT payload_json FROM znack_documents WHERE id=202"));
        assertEquals(7,number("SELECT order_id FROM znack_purchase_pipelines WHERE id=303"));
        assertEquals(1,number("PRAGMA foreign_keys"));assertTrue(c.getAutoCommit());assertEquals(expectedVersion,number("PRAGMA user_version"));assertNoForeignKeys();
    }
}
