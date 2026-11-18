package com.vncode.app.shared;

import com.vncode.app.config.Database;
import com.vncode.app.ui.workspace.HomeController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StartupDatabaseInitializationTest {
    @TempDir Path directory;
    private String previousDirectory;

    @BeforeEach void configureDirectory() {
        previousDirectory = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", directory.toString());
    }

    @AfterEach void restoreDirectory() {
        if (previousDirectory == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousDirectory);
    }

    @Test void migratedDatabaseIsNotInitializedAgainBeforeLocalRecovery() throws Exception {
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(41,'Startup sentinel','fixture-only')");
            statement.execute("PRAGMA user_version=" + (Database.currentSchemaVersion() - 1));
        }
        try (var session = LocalDataMigrationGate.prepare(directory, "1.6.12", "javafx")) {
            assertEquals(Database.currentSchemaVersion(), scalar("PRAGMA user_version"));
            int ddlVersion = scalar("PRAGMA schema_version");
            assertNotNull(HomeController.prepareLocalData(session));
            assertEquals(ddlVersion, scalar("PRAGMA schema_version"),
                    "The completed gate initialization must not run its DDL/repairs again");
            assertEquals(1, scalar("SELECT count(*) FROM shops WHERE id=41"));
            assertEquals(0, foreignKeyErrors());
        }
    }

    @Test void readyStartupStillInitializesCurrentSchemaOnce() throws Exception {
        try (var session = LocalDataMigrationGate.prepare(directory, "1.6.12", "javafx")) { }
        int before = scalar("PRAGMA schema_version");
        try (var session = LocalDataMigrationGate.prepare(directory, "1.6.12", "javafx")) {
            assertEquals(before, scalar("PRAGMA schema_version"), "Ready marker skips gate migrations");
            assertNotNull(HomeController.prepareLocalData(session));
            int initialized = scalar("PRAGMA schema_version");
            assertTrue(initialized > before, "Normal same-schema startup must retain initialization/repair");
            assertNotNull(HomeController.prepareLocalData(session));
            assertEquals(initialized, scalar("PRAGMA schema_version"));
            assertEquals(Database.currentSchemaVersion(), scalar("PRAGMA user_version"));
        }
    }

    @Test void directFxmlPreparationStillInitializesTheDatabase() throws Exception {
        Database.initDatabase();
        int before = scalar("PRAGMA schema_version");
        assertNotNull(HomeController.prepareLocalData());
        assertTrue(scalar("PRAGMA schema_version") > before);
        assertEquals(1, scalar("SELECT count(*) FROM print_templates WHERE is_default=1"));
        assertEquals(Database.currentSchemaVersion(), scalar("PRAGMA user_version"));
    }

    @Test void initializedSessionCannotAuthorizeAnotherProfileOrAClosedLock() throws Exception {
        var session = LocalDataMigrationGate.prepare(directory, "1.6.12", "javafx");
        Path other = directory.resolve("another-profile");
        try {
            System.setProperty("vncode.appdata.dir", other.toString());
            assertThrows(IOException.class, session::initializeDatabase);
            assertFalse(Files.exists(other.resolve("database.db")));
            System.setProperty("vncode.appdata.dir", directory.toString());
            session.initializeDatabase();
        } finally {
            System.setProperty("vncode.appdata.dir", directory.toString());
            session.close();
        }
        assertThrows(IOException.class, session::initializeDatabase);
    }

    private int scalar(String sql) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private int foreignKeyErrors() throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            int errors = 0;
            while (result.next()) errors++;
            return errors;
        }
    }
}
