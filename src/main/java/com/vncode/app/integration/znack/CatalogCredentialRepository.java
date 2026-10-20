package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.WindowsSecretProtector;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A Catalog key belongs to one shop, verified enterprise, certificate and environment. */
public final class CatalogCredentialRepository {
    public interface Protection {
        String protect(String value) throws IOException;
        String reveal(String value) throws IOException;
    }
    private final Protection protection;
    public CatalogCredentialRepository() {
        this(new Protection() {
            private final WindowsSecretProtector dpapi = new WindowsSecretProtector(WindowsSecretProtector.CATALOG_PURPOSE, 8192);
            public String protect(String value) throws IOException { return dpapi.protect(value); }
            public String reveal(String value) throws IOException { return dpapi.reveal(value); }
        });
    }
    CatalogCredentialRepository(Protection protection) { this.protection = Objects.requireNonNull(protection); }
    private Connection open() throws SQLException {
        Connection c = Database.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS znack_catalog_credentials(shop_id INTEGER PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,source TEXT NOT NULL,protected_key TEXT NOT NULL,key_digest TEXT NOT NULL,checked_at TEXT NOT NULL)");
        } catch (SQLException failure) { c.close(); throw failure; }
        return c;
    }
    public static String normalize(String key) throws IOException {
        String value = key == null ? "" : key.strip();
        if (value.isEmpty() || value.length() > 8192 || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
            throw new IOException("CATALOG_KEY_INVALID");
        return value;
    }
    public boolean hasSaved(int shopId) {
        try (var c = open(); var q = c.prepareStatement("SELECT 1 FROM znack_catalog_credentials WHERE shop_id=?")) {
            q.setInt(1, shopId); try (var r = q.executeQuery()) { return r.next(); }
        } catch (SQLException failure) { throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED"); }
    }
    /** Only call after remote identity verification and a successful read-only API request. */
    public void saveVerified(Shop shop, RegisteredGtinProof.Scope scope, String key) throws IOException {
        saveVerified(shop, scope, key, () -> {});
    }
    void saveVerified(Shop shop, RegisteredGtinProof.Scope scope, String key, Runnable activeGuard) throws IOException {
        String normalized = normalize(key), encrypted = protection.protect(normalized);
        activeGuard.run();
        requireScope(shop, scope);
        try (var c = open(); var q = c.prepareStatement("INSERT INTO znack_catalog_credentials VALUES(?,?,?,?,?) ON CONFLICT(shop_id) DO UPDATE SET source=excluded.source,protected_key=excluded.protected_key,key_digest=excluded.key_digest,checked_at=excluded.checked_at")) {
            q.setInt(1, shop.getId()); q.setString(2, scope.source()); q.setString(3, encrypted);
            q.setString(4, ListingIdentity.hash(normalized)); q.setString(5, Instant.now().toString()); activeGuard.run(); q.executeUpdate();
        } catch (SQLException failure) { throw new IOException("CATALOG_KEY_STORAGE_FAILED"); }
    }
    public Optional<String> read(Shop shop, RegisteredGtinProof.Scope scope) throws IOException {
        requireScope(shop, scope);
        try (var c = open(); var q = c.prepareStatement("SELECT source,protected_key FROM znack_catalog_credentials WHERE shop_id=?")) {
            q.setInt(1, shop.getId()); try (var r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                if (!scope.source().equals(r.getString(1))) throw new IOException("CATALOG_KEY_SCOPE_CHANGED");
                return Optional.of(normalize(protection.reveal(r.getString(2))));
            }
        } catch (SQLException failure) { throw new IOException("CATALOG_KEY_STORAGE_FAILED"); }
    }
    public void requireUnchanged(Shop shop, RegisteredGtinProof.Scope scope, String digest) {
        requireScope(shop, scope);
        try (var c = open(); var q = c.prepareStatement("SELECT source,key_digest FROM znack_catalog_credentials WHERE shop_id=?")) {
            q.setInt(1, shop.getId()); try (var r = q.executeQuery()) {
                if (!r.next() || !scope.source().equals(r.getString(1)) || !digest.equals(r.getString(2)))
                    throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");
            }
        } catch (SQLException failure) { throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED"); }
    }
    private static void requireScope(Shop shop, RegisteredGtinProof.Scope scope) {
        ListingIdentity.require(shop);
        var current = RegisteredGtinProof.scope(shop, new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings(), Instant.now());
        if (!current.source().equals(scope.source())) throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");
    }
    public void remove(Shop shop) {
        ListingIdentity.require(shop);
        try (var c = open(); var q = c.prepareStatement("DELETE FROM znack_catalog_credentials WHERE shop_id=?")) {
            q.setInt(1, shop.getId()); q.executeUpdate();
        } catch (SQLException failure) { throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED"); }
    }
}
