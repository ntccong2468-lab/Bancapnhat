package com.vncode.app.features.print;

import com.vncode.app.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizAttachmentCheckpointRepositoryTest {
    @TempDir Path directory;
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void additiveCheckpointTableWorksWithOldSnapshotAndPreservesShopForeignKeys()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            st.execute("INSERT INTO shops VALUES(1,'Old shop A','fixture-a'),(2,'Old shop B','fixture-b')");
        }
        var repository=new KizAttachmentCheckpointRepository();String first=KizAttachmentCoordinator.hash("fixture-mark-a"),second=KizAttachmentCoordinator.hash("fixture-mark-b");
        var a=new KizAttachmentCoordinator.OrderKey(1,101);var b=new KizAttachmentCoordinator.OrderKey(2,101);
        repository.write(a,"SUP-A",first,"ATTACH_PENDING");repository.write(b,"SUP-B",second,"CONFIRMED");
        var reopened=new KizAttachmentCheckpointRepository();assertTrue(reopened.read(a).ambiguous());assertEquals(second,reopened.read(b).codeHash());
        assertThrows(IOException.class,()->repository.write(new KizAttachmentCoordinator.OrderKey(3,101),"SUP-X",first,"ATTACH_PENDING"));
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            try(ResultSet rs=st.executeQuery("SELECT count(*) FROM shops")){assertTrue(rs.next());assertEquals(2,rs.getInt(1));}
            st.execute("DELETE FROM shops WHERE id=1");
            try(ResultSet rs=st.executeQuery("PRAGMA foreign_key_check")){assertFalse(rs.next());}
            try(ResultSet rs=st.executeQuery("SELECT code_hash,phase FROM wb_kiz_attachment_checkpoints")){assertTrue(rs.next());assertEquals(second,rs.getString(1));assertEquals("CONFIRMED",rs.getString(2));assertFalse(rs.next());}
        }
        assertNull(repository.read(a));assertEquals(second,repository.read(b).codeHash());
    }
    @Test void pendingWriteBlocksNewPrintBeforeMetadataOrCodeReservation()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB shop','fixture-token')");}
        var repository=new KizAttachmentCheckpointRepository();repository.write(new KizAttachmentCoordinator.OrderKey(1,101),"SUP-1",KizAttachmentCoordinator.hash("fixture-mark"),"ATTACH_PENDING");
        var order=new com.vncode.app.models.Order();order.setId(101L);order.setNmId(1L);order.setRequiresKiz(true);
        var failure=assertThrows(IOException.class,()->new OrderExportWorkflow().verifyKizAvailability(java.util.List.of(order),new com.vncode.app.models.Shop(1,"WB shop","fixture-token")));
        assertEquals("KIZ_RECONCILIATION_REQUIRED",failure.getMessage());
        try(Connection c=Database.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM kiz_codes")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
    }
}
