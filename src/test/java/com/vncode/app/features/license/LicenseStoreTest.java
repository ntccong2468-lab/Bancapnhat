package com.vncode.app.features.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.license.LicenseModels.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseStoreTest {
    @TempDir Path directory;
    /** Serialization fixture only: production uses Windows DPAPI, never this codec. */
    private static final LicenseStore.SecretCodec FIXTURE_CODEC=new LicenseStore.SecretCodec() {
        public String protect(String value){return "fixture-protected:"+Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
        public String reveal(String value)throws IOException {
            if(!value.startsWith("fixture-protected:"))throw new IOException("FIXTURE_CORRUPT");
            try{return new String(Base64.getDecoder().decode(value.substring(18)),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException invalid){throw new IOException("FIXTURE_CORRUPT");}
        }
    };
    @Test void preservesDeviceKeysTokensLeaseAndPersistentHighWater() throws Exception {
        var f=new LicenseTestFixtures();var store=new LicenseStore(directory,FIXTURE_CODEC);
        assertTrue(store.load().isEmpty()); State state=f.state(); store.save(state);
        State restored=store.load().orElseThrow(); assertEquals(state,restored);
        assertEquals(f.device.hash(),LicenseDeviceKeys.restore(restored.publicKey(),restored.privateKey()).hash());
        assertNotNull(f.verifier.verify(restored.envelope(),restored.account().id(),f.device.hash(),restored.minRevision(),restored.maxObservedEpoch(),false));
        try(var files=Files.list(directory)){assertEquals(1,files.count(),"Atomic writes must clean up their temporary files");}
    }
    @Test void failedProtectionPreservesTheExistingState() throws Exception {
        var f=new LicenseTestFixtures();var store=new LicenseStore(directory,FIXTURE_CODEC);var state=f.state(); store.save(state);
        var failing=new LicenseStore(directory,new LicenseStore.SecretCodec(){
            public String protect(String value)throws IOException{throw new IOException("FIXTURE_PROTECTION_FAILED");}
            public String reveal(String value)throws IOException{return FIXTURE_CODEC.reveal(value);}
        });
        assertThrows(IOException.class,()->failing.save(state)); assertEquals(state,store.load().orElseThrow());
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void corruptionIsRejectedAndDoesNotSilentlyResetDeviceKeys() throws Exception {
        var store=new LicenseStore(directory,FIXTURE_CODEC);Path file=directory.resolve("account-state.dpapi");
        Files.writeString(file,"unprotected or corrupt data",StandardCharsets.UTF_8);
        assertThrows(IOException.class,store::load);
        Files.writeString(file,FIXTURE_CODEC.protect("{\"publicKey\":\"a\",\"privateKey\":\"b\",\"maxObservedEpoch\":1,\"maxObservedEpoch\":2}"),StandardCharsets.UTF_8);
        assertThrows(IOException.class,store::load);
        Files.writeString(file,FIXTURE_CODEC.protect("{\"publicKey\":\"a\",\"privateKey\":\"b\",\"maxObservedEpoch\":-1,\"minRevision\":0}"),StandardCharsets.UTF_8);
        assertThrows(IOException.class,store::load);
        Files.writeString(file,"x".repeat(512*1024+1),StandardCharsets.UTF_8);
        assertThrows(IOException.class,store::load);
    }
    @Test void repeatedSaveReplacesCompleteStateWithoutLeavingTemporaryFiles() throws Exception {
        var f=new LicenseTestFixtures();var store=new LicenseStore(directory,FIXTURE_CODEC);State first=f.state();store.save(first);
        State next=new State(first.publicKey(),first.privateKey(),first.registrationToken(),"new-fixture-access",first.account(),first.envelope(),first.maxObservedEpoch()+10,first.minRevision()+1,"LICENSE_REVOKED");
        store.save(next);assertEquals(next,store.load().orElseThrow());
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void knownRefusalMarkerBlocksOldCacheUntilAnAtomicNewStateIsSaved()throws Exception {
        var fixture=new LicenseTestFixtures();var store=new LicenseStore(directory,FIXTURE_CODEC);State state=fixture.state();store.save(state);
        store.blockCachedRights();assertEquals("LICENSE_RECHECK_REQUIRED",store.load().orElseThrow().denialCode());
        store.save(new State(state.publicKey(),state.privateKey(),state.registrationToken(),state.accessToken(),state.account(),state.envelope(),state.maxObservedEpoch(),state.minRevision(),"DEVICE_REVOKED"));
        assertEquals("DEVICE_REVOKED",store.load().orElseThrow().denialCode());
    }

}
