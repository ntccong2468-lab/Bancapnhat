package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.shared.AppPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Durable signing admission state. It stores no token, PIN, certificate or account credentials. */
final class ZnackSigningPauseStore {
    record State(long generation, boolean paused, boolean reconfiguring, CryptoProErrorCode cause,
                 String identityHash) { }

    private final int shopId;
    private final Path profile;

    ZnackSigningPauseStore(int shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("Shop is required.");
        this.shopId = shopId;
        this.profile = AppPaths.appDataDir().toAbsolutePath().normalize();
    }

    State read() {
        requireProfile();
        try (Connection c = Database.getConnection()) { return read(c); }
        catch (SQLException error) { throw unavailable(error); }
    }

    State initialize(String identityHash) {
        return update(old -> {
            if (old.identityHash().isEmpty())
                return new State(old.generation(), old.paused(), old.reconfiguring(), old.cause(), identityHash);
            if (old.identityHash().equals(identityHash)) return old;
            return new State(next(old.generation()), true, false, null, identityHash);
        });
    }

    State pause(CryptoProErrorCode cause) {
        return update(old -> new State(old.generation(), true, old.reconfiguring(), cause, old.identityHash()));
    }

    State beginReconfiguration(String nextIdentity) {
        return update(old -> new State(next(old.generation()), true, true, null, nextIdentity));
    }

    State finishReconfiguration(long generation) {
        return update(old -> old.generation() == generation
                ? new State(old.generation(), old.paused(), false, old.cause(), old.identityHash()) : old);
    }

    State clearPause() {
        return update(old -> {
            if (old.reconfiguring()) throw new IllegalStateException("ZNACK_SIGNING_RECONFIGURING");
            return new State(old.generation(), false, false, null, old.identityHash());
        });
    }

    private State update(UnaryOperator<State> change) {
        requireProfile();
        try {
            return SqliteWriteTransaction.run(c -> {
                State before = read(c);
                State after = Objects.requireNonNull(change.apply(before));
                if (!before.equals(after)) {
                    JsonObject value = new JsonObject();
                    value.addProperty("version", 1);
                    value.addProperty("generation", after.generation());
                    value.addProperty("paused", after.paused());
                    value.addProperty("reconfiguring", after.reconfiguring());
                    value.addProperty("cause", after.cause() == null ? "" : after.cause().name());
                    value.addProperty("identityHash", after.identityHash());
                    value.addProperty("updatedAt", Instant.now().toString());
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) "
                            + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                        p.setString(1, key()); p.setString(2, value.toString()); p.executeUpdate();
                    }
                }
                return after;
            });
        } catch (SQLException error) { throw unavailable(error); }
    }

    private State read(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT value FROM app_config WHERE key=?")) {
            p.setString(1, key());
            try (ResultSet row = p.executeQuery()) {
                if (!row.next()) return new State(0, false, false, null, "");
                try {
                    JsonObject value = JsonParser.parseString(row.getString(1)).getAsJsonObject();
                    if (value.get("version").getAsInt() != 1) throw new IllegalArgumentException();
                    long generation = value.get("generation").getAsLong();
                    if (generation < 0) throw new IllegalArgumentException();
                    String cause = value.get("cause").getAsString();
                    String identity = value.get("identityHash").getAsString();
                    if (!identity.isEmpty() && !identity.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                    return new State(generation, value.get("paused").getAsBoolean(),
                            value.get("reconfiguring").getAsBoolean(),
                            cause.isEmpty() ? null : CryptoProErrorCode.valueOf(cause), identity);
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException("ZNACK_SIGNING_STATE_INVALID", invalid);
                }
            }
        }
    }

    private String key() { return "znack.signing.v1." + shopId; }

    private void requireProfile() {
        if (!profile.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))
            throw new IllegalStateException("ZNACK_SIGNING_PROFILE_CHANGED");
    }

    private static long next(long generation) {
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("ZNACK_SIGNING_GENERATION_EXHAUSTED");
        return generation + 1;
    }

    static String identity(ZnackModels.Settings s) {
        Objects.requireNonNull(s, "settings");
        String[] parts = {s.resolvedTrueApiBaseUrl(), s.resolvedSuzBaseUrl(), s.omsId(), s.omsConnection(),
                s.participantInn(), s.producerInn(), s.ownerInn(), s.signerCertificate(), s.cryptcpPath(),
                s.signerExecutable(), s.signerArgumentsJson()};
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = Objects.toString(part, "").trim().getBytes(StandardCharsets.UTF_8);
                hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                hash.update((byte) ':'); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalStateException unavailable(SQLException error) {
        return new IllegalStateException("ZNACK_SIGNING_STATE_UNAVAILABLE", error);
    }
}
