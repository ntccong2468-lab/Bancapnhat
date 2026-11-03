package com.vncode.app.features.finance;

import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Only pages read with a proven, current shop credential are visible here. */
public final class FinancePenaltyRepository {
    private static final int MAX_PAGE_SIZE = 500;
    public record ScopeStatus(boolean verified, boolean complete, Instant lastReadAt) { }

    /** Additive SQLite transaction: legacy finance rows and checkpoints remain untouched. */
    static void createScopedSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS finance_penalty_scoped (
                        shop_id INTEGER NOT NULL, owner_fingerprint TEXT NOT NULL, rrd_id TEXT NOT NULL,
                        business_date TEXT NOT NULL, currency TEXT NOT NULL, operation_name TEXT,
                        order_id TEXT, product_id TEXT, vendor_code TEXT, sku TEXT, penalty_cost REAL NOT NULL,
                        PRIMARY KEY(shop_id, owner_fingerprint, rrd_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS finance_penalty_scope_state (
                        shop_id INTEGER NOT NULL, owner_fingerprint TEXT NOT NULL, last_read_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, owner_fingerprint))
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_finance_penalty_scope_date ON finance_penalty_scoped(shop_id,owner_fingerprint,business_date)");
        }
    }

    private static void ensureScopedSchema() {
        AnalyticsDatabase.initialize();
        try (var connection = AnalyticsDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try { createScopedSchema(connection); connection.commit(); }
            catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot prepare penalty scope", failure); }
    }

    /** HTTP must be checked before sending; this method rechecks inside the owner lock and transaction. */
    public void recordVerifiedPage(Shop shop, Collection<FinanceRawRow> page, Instant receivedAt) {
        recordVerifiedPage(shop,page,receivedAt,()->{});
    }
    void recordVerifiedPage(Shop shop, Collection<FinanceRawRow> page, Instant receivedAt, Runnable beforeCommit) {
        Objects.requireNonNull(shop, "shop"); Objects.requireNonNull(page, "page"); Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(beforeCommit, "beforeCommit");
        ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop);
            String owner = ListingIdentity.fingerprint(shop);
            ensureScopedSchema();
            String upsert = """
                    INSERT INTO finance_penalty_scoped(shop_id,owner_fingerprint,rrd_id,business_date,currency,operation_name,order_id,product_id,vendor_code,sku,penalty_cost)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(shop_id,owner_fingerprint,rrd_id) DO UPDATE SET
                        business_date=excluded.business_date,currency=excluded.currency,operation_name=excluded.operation_name,
                        order_id=excluded.order_id,product_id=excluded.product_id,vendor_code=excluded.vendor_code,
                        sku=excluded.sku,penalty_cost=excluded.penalty_cost
                    """;
            try (var connection = AnalyticsDatabase.getConnection()) {
                connection.setAutoCommit(false);
                try (var write = connection.prepareStatement(upsert);
                     var state = connection.prepareStatement("""
                       INSERT INTO finance_penalty_scope_state(shop_id,owner_fingerprint,last_read_at) VALUES(?,?,?)
                       ON CONFLICT(shop_id,owner_fingerprint) DO UPDATE SET last_read_at=excluded.last_read_at
                       """)) {
                    var seen = new java.util.HashSet<String>(); var zeroIds = new ArrayList<String>();
                    for (FinanceRawRow row : page) {
                        Objects.requireNonNull(row, "row");
                        if (row.rrdId() == null || row.rrdId().isBlank() || row.businessDate() == null || row.currency() == null)
                            throw new IllegalArgumentException("Invalid finance row");
                        if (!seen.add(row.rrdId())) throw new IllegalArgumentException("Duplicate finance row in page");
                        LocalDate.parse(row.businessDate());
                        if (!Double.isFinite(row.penaltyCost())) throw new IllegalArgumentException("Invalid penalty amount");
                        if (row.penaltyCost() == 0) {
                            zeroIds.add(row.rrdId());
                        } else {
                            write.setInt(1,shop.getId()); write.setString(2,owner); write.setString(3,row.rrdId());
                            write.setString(4,row.businessDate()); write.setString(5,row.currency()); write.setString(6,row.operationName());
                            write.setString(7,row.orderId()); write.setString(8,row.nmId()); write.setString(9,row.vendorCode());
                            write.setString(10,row.sku()); write.setDouble(11,row.penaltyCost()); write.addBatch();
                        }
                    }
                    write.executeBatch();
                    // Most finance rows are not penalties; clear corrections in bounded batches,
                    // rather than holding the shop owner lock for one SQL update per sale.
                    for (int start=0;start<zeroIds.size();start+=500) {
                        var ids=zeroIds.subList(start,Math.min(start+500,zeroIds.size()));
                        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
                        try(var zero=connection.prepareStatement("UPDATE finance_penalty_scoped SET penalty_cost=0 WHERE shop_id=? AND owner_fingerprint=? AND rrd_id IN ("+marks+")")) {
                            zero.setInt(1,shop.getId());zero.setString(2,owner);
                            for(int i=0;i<ids.size();i++)zero.setString(i+3,ids.get(i));
                            zero.executeUpdate();
                        }
                    }
                    state.setInt(1,shop.getId()); state.setString(2,owner); state.setString(3,receivedAt.toString()); state.executeUpdate();
                    // Shop updates use the exclusive lock; recheck before commit as a defense against stale snapshots.
                    beforeCommit.run();
                    ListingIdentity.require(shop);
                    if (!owner.equals(ListingIdentity.fingerprint(shop))) throw new SecurityException("Finance owner changed");
                    connection.commit();
                } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot save verified penalty page", failure); }
            return null;
        });
    }

    public ScopeStatus scopeStatus(Shop shop) {
        return readOwned(shop, owner -> {
            try (var connection = AnalyticsDatabase.getConnection(); var statement = connection.prepareStatement(
                    "SELECT last_read_at FROM finance_penalty_scope_state WHERE shop_id=? AND owner_fingerprint=?")) {
                statement.setInt(1,shop.getId()); statement.setString(2,owner);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? new ScopeStatus(true,false,Instant.parse(rows.getString(1))) : new ScopeStatus(false,false,null);
                }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read penalty scope", failure); }
        });
    }

    public List<FinancePenaltyEntry> load(Shop shop, LocalDate from, LocalDate to, int limit, int offset) {
        validate(shop,from,to);
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) throw new IllegalArgumentException("Invalid penalty page");
        return readOwned(shop, owner -> {
            String sql = """
                    SELECT rrd_id,business_date,currency,operation_name,order_id,product_id,vendor_code,sku,penalty_cost
                    FROM finance_penalty_scoped WHERE shop_id=? AND owner_fingerprint=? AND business_date BETWEEN ? AND ? AND penalty_cost != 0
                    ORDER BY business_date DESC,rrd_id DESC LIMIT ? OFFSET ?
                    """;
            List<FinancePenaltyEntry> result = new ArrayList<>();
            try (var connection = AnalyticsDatabase.getConnection(); var statement = connection.prepareStatement(sql)) {
                bindFilter(statement,shop.getId(),owner,from,to); statement.setInt(5,limit); statement.setInt(6,offset);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new FinancePenaltyEntry(rows.getString("rrd_id"),LocalDate.parse(rows.getString("business_date")),
                            rows.getString("currency"),rows.getString("operation_name"),rows.getString("order_id"),rows.getString("product_id"),
                            rows.getString("vendor_code"),rows.getString("sku"),rows.getDouble("penalty_cost")));
                }
                return List.copyOf(result);
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read verified penalty transactions", failure); }
        });
    }

    public long count(Shop shop, LocalDate from, LocalDate to) {
        validate(shop,from,to);
        return readOwned(shop, owner -> {
            try (var connection = AnalyticsDatabase.getConnection(); var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM finance_penalty_scoped WHERE shop_id=? AND owner_fingerprint=? AND business_date BETWEEN ? AND ? AND penalty_cost != 0")) {
                bindFilter(statement,shop.getId(),owner,from,to);
                try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot count verified penalty transactions", failure); }
        });
    }

    private static <T> T readOwned(Shop shop, java.util.function.Function<String,T> read) {
        Objects.requireNonNull(shop, "shop");
        return ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop); String owner=ListingIdentity.fingerprint(shop);
            ensureScopedSchema(); T result=read.apply(owner);
            ListingIdentity.require(shop);
            if (!owner.equals(ListingIdentity.fingerprint(shop))) throw new SecurityException("Finance owner changed");
            return result;
        });
    }

    private static void validate(Shop shop, LocalDate from, LocalDate to) {
        if (shop == null || shop.getId() <= 0 || Objects.requireNonNull(from, "from").isAfter(Objects.requireNonNull(to, "to")))
            throw new IllegalArgumentException("Invalid penalty filter");
    }
    private static void bindFilter(PreparedStatement statement,int shopId,String owner,LocalDate from,LocalDate to) throws SQLException {
        statement.setInt(1,shopId); statement.setString(2,owner); statement.setString(3,from.toString()); statement.setString(4,to.toString());
    }
}
