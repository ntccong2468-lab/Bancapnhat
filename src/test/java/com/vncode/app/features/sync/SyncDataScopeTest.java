package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncDataScopeTest {
    @TempDir Path directory;
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void lateResponseCannotOpenAnotherApplicationsDatabaseAndNestedScopesRestoreTheirOwner()throws Exception{
        var first=directory.resolve("first");var second=directory.resolve("second");
        System.setProperty("vncode.appdata.dir",first.toString());Database.initDatabase();
        try(var owner=SyncDataScope.enter(first)){
            try(var c=Database.getConnection()){assertNotNull(c);}
            System.setProperty("vncode.appdata.dir",second.toString());
            assertEquals("SYNC_DATA_DIRECTORY_CHANGED",assertThrows(IllegalStateException.class,Database::getConnection).getMessage());
            assertFalse(Files.exists(second),"A stale callback must not even create another data directory");
            try(var nested=SyncDataScope.enter(second)){assertDoesNotThrow(SyncDataScope::requireCurrent);}
            assertThrows(IllegalStateException.class,SyncDataScope::requireCurrent);
            System.setProperty("vncode.appdata.dir",first.toString());assertDoesNotThrow(SyncDataScope::requireCurrent);
        }
        System.setProperty("vncode.appdata.dir",second.toString());assertDoesNotThrow(SyncDataScope::requireCurrent);
    }
}
