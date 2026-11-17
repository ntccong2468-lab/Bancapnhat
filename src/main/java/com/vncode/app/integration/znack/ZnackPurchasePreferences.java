package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import java.sql.*;
import java.util.Objects;

/** New-purchase preferences; historical paid connection snapshots are deliberately separate. */
public final class ZnackPurchasePreferences {
    private final int shopId;
    public ZnackPurchasePreferences(int shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("A persisted shop is required.");
        this.shopId = shopId;
    }
    public ZnackPurchaseMethod method() {
        try (Connection c = Database.getConnection()) { return method(c, shopId); }
        catch (SQLException error) { throw new IllegalStateException("PURCHASE_PREFERENCES_STORAGE", error); }
    }
    static ZnackPurchaseMethod method(Connection c, int shopId) throws SQLException {
        try (var p = c.prepareStatement("SELECT value FROM app_config WHERE key=?")) {
            p.setString(1, "znack.purchase.method." + shopId);
            try (var row = p.executeQuery()) { return row.next() ? ZnackPurchaseMethod.fromStored(row.getString(1)) : ZnackPurchaseMethod.PRODUCTION; }
        }
    }
    public void saveMethod(ZnackPurchaseMethod method) {
        Objects.requireNonNull(method, "method");
        try { SqliteWriteTransaction.run(c -> {
            saveMethod(c,shopId,method);
            return null;
        }); } catch (SQLException error) { throw new IllegalStateException("PURCHASE_PREFERENCES_STORAGE", error); }
    }
    static void saveMethod(Connection c,int shopId,ZnackPurchaseMethod method)throws SQLException{
        try (var p = c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1, "znack.purchase.method." + shopId); p.setString(2, Objects.requireNonNull(method).name()); p.executeUpdate();
        }
    }
}
