package com.vncode.app.features.kiz;

import java.sql.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SellerKizImportSchemaTest {
    @TempDir Path root;
    @Test void upgradesOldUniqueConstraintsWithoutLosingRowsReservationProofOrSequence()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("old.db"));var s=c.createStatement()){
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO shops VALUES(1)");
            s.execute("CREATE TABLE kiz_codes(id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,raw_code TEXT NOT NULL,status TEXT,UNIQUE(shop_id,id),UNIQUE(shop_id,raw_code),FOREIGN KEY(shop_id) REFERENCES shops(id))");
            s.execute("CREATE UNIQUE INDEX uq_kiz_codes_raw_code ON kiz_codes(raw_code)");
            s.execute("CREATE INDEX old_status ON kiz_codes(status)");
            s.execute("CREATE TABLE kiz_import_entries(code_id INTEGER PRIMARY KEY REFERENCES kiz_codes(id) ON DELETE CASCADE,cis TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE proof(code_id INTEGER REFERENCES kiz_import_entries(code_id),token TEXT)");
            s.execute("INSERT INTO kiz_codes VALUES(7,1,'synthetic','RESERVED')");s.execute("INSERT INTO kiz_import_entries VALUES(7,'cis')");s.execute("INSERT INTO proof VALUES(7,'reservation')");
            s.execute("UPDATE sqlite_sequence SET seq=100 WHERE name='kiz_codes'");
            SellerKizImportSchema.codes(c);SellerKizImportSchema.entries(c);
            try(var r=s.executeQuery("SELECT k.id,k.status,p.token FROM kiz_codes k JOIN proof p ON p.code_id=k.id")){assertTrue(r.next());assertEquals(7,r.getInt(1));assertEquals("RESERVED",r.getString(2));assertEquals("reservation",r.getString(3));}
            assertThrows(SQLException.class,()->s.execute("INSERT INTO kiz_codes(shop_id,raw_code) VALUES(1,'synthetic')"),"Purchased code uniqueness remains active");
            s.execute("INSERT INTO kiz_codes(shop_id,raw_code,stock_instance) VALUES(1,'synthetic','upload-1'),(1,'synthetic','upload-2')");
            s.execute("INSERT INTO kiz_import_entries SELECT id,'cis' FROM kiz_codes WHERE stock_instance<>''");
            try(var r=s.executeQuery("SELECT MIN(id) FROM kiz_codes WHERE stock_instance<>''")){r.next();assertTrue(r.getInt(1)>100);}
            SellerKizImportSchema.codes(c);SellerKizImportSchema.entries(c);
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
            try(var r=s.executeQuery("PRAGMA foreign_keys")){r.next();assertEquals(1,r.getInt(1));}
            try(var r=s.executeQuery("SELECT count(*) FROM kiz_codes")){r.next();assertEquals(3,r.getInt(1));}
        }
    }
}
