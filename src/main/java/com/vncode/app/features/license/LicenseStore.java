package com.vncode.app.features.license;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import static com.vncode.app.features.license.LicenseModels.*;

public final class LicenseStore {
    public interface SecretCodec { String protect(String plain) throws IOException; String reveal(String protectedValue) throws IOException; }
    private final Path directory, file, blocked;
    private final SecretCodec codec;
    private final Gson gson=new Gson();
    public LicenseStore(Path directory, SecretCodec codec) { this.directory=directory;this.file=directory.resolve("account-state.dpapi");this.blocked=directory.resolve("online-check-required");this.codec=codec; }
    public synchronized Optional<State> load() throws IOException {
        if(!Files.exists(file))return Optional.empty();
        if(Files.isSymbolicLink(file)||Files.isSymbolicLink(directory)||Files.size(file)>512*1024)throw new IOException("LICENSE_STORE_INVALID");
        String decoded=codec.reveal(Files.readString(file,StandardCharsets.UTF_8));
        if(decoded.length()>65536)throw new IOException("LICENSE_STORE_INVALID");
        try { var json=LicenseJson.object(decoded);State state=gson.fromJson(json,State.class);if(state==null||state.publicKey()==null||state.privateKey()==null||state.maxObservedEpoch()<0||state.minRevision()<0)throw new IOException("LICENSE_STORE_INVALID");if(Files.exists(blocked))state=new State(state.publicKey(),state.privateKey(),state.registrationToken(),state.accessToken(),state.account(),state.envelope(),state.maxObservedEpoch(),state.minRevision(),"LICENSE_RECHECK_REQUIRED");return Optional.of(state); }
        catch(RuntimeException failure) { throw new IOException("LICENSE_STORE_INVALID"); }
    }
    public synchronized void blockCachedRights()throws IOException {
        Files.createDirectories(directory);if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(blocked))throw new IOException("LICENSE_STORE_INVALID");
        Files.writeString(blocked,"A new online license check is required.",StandardCharsets.US_ASCII,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }
    public synchronized void save(State state) throws IOException {
        String json=gson.toJson(state);if(json.getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("LICENSE_STORE_INVALID");
        String protectedValue=codec.protect(json);if(protectedValue.length()>512*1024)throw new IOException("LICENSE_STORE_INVALID");
        Files.createDirectories(directory);if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(file))throw new IOException("LICENSE_STORE_INVALID");
        Path temp=Files.createTempFile(directory,"account-",".tmp");
        try { Files.writeString(temp,protectedValue,StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(blocked); }
        finally { Files.deleteIfExists(temp); }
    }
}
