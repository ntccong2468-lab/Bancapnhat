package com.vncode.app.shared;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalDataMigrationActivationTest {
    @TempDir Path directory;
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void startupWindowCanBeRaisedBeforeRecoveryAndFailedPreparationReleasesItsListener()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        var restored=new AtomicInteger();
        var failure=assertThrows(IllegalStateException.class,()->LocalDataMigrationGate.prepare(directory,"1.5.4","javafx",phase->{
            assertEquals("RECOVERING_DATA",phase);
            assertFalse(Files.exists(directory.resolve("database.db")));
            assertThrows(AppDataLock.AlreadyRunningException.class,()->AppDataLock.acquire(directory,"second"));
            try{assertTrue(AppInstanceActivation.requestActivation(directory,Duration.ofSeconds(2)));}
            catch(Exception error){throw new AssertionError(error);}
            throw new IllegalStateException("fixture preparation stopped");
        },()->{restored.incrementAndGet();return CompletableFuture.completedFuture(null);}));
        assertEquals("fixture preparation stopped",failure.getMessage());assertEquals(1,restored.get());
        assertFalse(AppInstanceActivation.requestActivation(directory,Duration.ofMillis(150)));
        try(var ignored=AppDataLock.acquire(directory,"restart")){assertFalse(Files.exists(directory.resolve("database.db")));}
    }
}
