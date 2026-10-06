package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OzonExemplarJobRepository {
    public OzonExemplarJob findOrCreate(int shopId, String postingNumber) {
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO ozon_exemplar_jobs(
                        shop_id,posting_number,stage,created_at,updated_at) VALUES(?,?,'CREATED',?,?)
                    """)) {
                insert.setInt(1, shopId);
                insert.setString(2, safePosting);
                insert.setString(3, now);
                insert.setString(4, now);
                insert.executeUpdate();
                OzonExemplarJob job = find(connection, shopId, safePosting);
                connection.commit();
                if (job == null) throw new SQLException("Cannot create Ozon exemplar job");
                return job;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public OzonExemplarJob find(int shopId, String postingNumber) {
        try (Connection connection = Database.getConnection()) {
            return find(connection, shopId, OzonApiClient.requireExternalId(postingNumber, "posting number"));
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void persistRemoteExemplars(
            OzonExemplarJob job,
            OzonRequirementGuard.PreparationPlan plan,
            List<String> exemplarIds) {
        if (exemplarIds.size() != plan.exemplarCount()) {
            throw new IllegalStateException("Ozon returned an unexpected exemplar count.");
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,
                        exemplar_id,exemplar_index,updated_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(job_id,item_index,exemplar_index) DO UPDATE SET
                        exemplar_id=excluded.exemplar_id,product_id=excluded.product_id,updated_at=excluded.updated_at
                    """)) {
                int remoteIndex = 0;
                String now = Instant.now().toString();
                for (OzonRequirementGuard.RequiredItem item : plan.items()) {
                    for (int exemplarIndex = 0; exemplarIndex < item.quantity(); exemplarIndex++) {
                        insert.setLong(1, job.id());
                        insert.setInt(2, job.shopId());
                        insert.setString(3, job.postingNumber());
                        insert.setInt(4, item.itemIndex());
                        insert.setString(5, item.productId());
                        insert.setString(6, OzonApiClient.requireExternalId(exemplarIds.get(remoteIndex++), "exemplar id"));
                        insert.setInt(7, exemplarIndex);
                        insert.setString(8, now);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Creates durable local print slots without requesting exemplar ids from Ozon. */
    public void persistLocalPrintSlots(
            OzonExemplarJob job,
            OzonRequirementGuard.PreparationPlan plan) {
        try (Connection connection = Database.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,
                            exemplar_id,exemplar_index,updated_at)
                        VALUES(?,?,?,?,?,NULL,?,?)
                        ON CONFLICT(job_id,item_index,exemplar_index) DO UPDATE SET
                            product_id=excluded.product_id,updated_at=excluded.updated_at
                        """)) {
            String now = Instant.now().toString();
            for (OzonRequirementGuard.RequiredItem item : plan.items()) {
                for (int exemplarIndex = 0; exemplarIndex < item.quantity(); exemplarIndex++) {
                    insert.setLong(1, job.id());
                    insert.setInt(2, job.shopId());
                    insert.setString(3, job.postingNumber());
                    insert.setInt(4, item.itemIndex());
                    insert.setString(5, item.productId());
                    insert.setInt(6, exemplarIndex);
                    insert.setString(7, now);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * Converts a legacy remote-preparation stage into a local print-ready stage. Existing KIZ
     * bindings are deliberately retained so an upgrade can print the exact codes already reserved.
     */
    public OzonExemplarJob markPrintReady(OzonExemplarJob job) {
        if (job.stage() == OzonExemplarJobStage.VALIDATED
                || job.stage() == OzonExemplarJobStage.ACCEPTED) return job;
        try (Connection connection = Database.getConnection()) {
            transition(connection, job.id(), job.stage(), OzonExemplarJobStage.VALIDATED,
                    job.requestFingerprint(), null, false);
            OzonExemplarJob updated = find(connection, job.shopId(), job.postingNumber());
            if (updated == null) throw new IllegalStateException("Ozon print job disappeared.");
            return updated;
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Reopens an old, locally released Ozon rejection so its KIZ can be reserved for printing. */
    public OzonExemplarJob reopenRejectedForPrint(OzonExemplarJob job) {
        if (job.stage() != OzonExemplarJobStage.REJECTED) return job;
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clear = connection.prepareStatement(
                    "DELETE FROM ozon_exemplars WHERE job_id=?")) {
                clear.setLong(1, job.id());
                clear.executeUpdate();
                transition(connection, job.id(), job.stage(), OzonExemplarJobStage.CREATED,
                        null, null, false);
                connection.commit();
                return find(connection, job.shopId(), job.postingNumber());
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Selects, durably reserves and links all KIZ rows in one BEGIN IMMEDIATE transaction. */
    public void reserveAndLink(OzonExemplarJob job, OzonRequirementGuard.PreparationPlan plan) {
        String reservationToken = "ozon:" + job.id();
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                for (OzonRequirementGuard.RequiredItem required : plan.items()) {
                    List<Long> exemplarRows = exemplarRows(connection, job.id(), required.itemIndex());
                    if (exemplarRows.size() != required.quantity()) {
                        throw new IllegalStateException("Ozon exemplar rows are incomplete for this posting.");
                    }
                    List<Long> available = availableKiz(connection, job.shopId(), required.gtin(), required.quantity());
                    if (available.size() != required.quantity()) {
                        throw new InsufficientKizException();
                    }
                    for (int index = 0; index < available.size(); index++) {
                        long kizId = available.get(index);
                        String now = Instant.now().toString();
                        try (PreparedStatement reserve = connection.prepareStatement("""
                                UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,
                                    reservation_recoverable=0,updated_at=?
                                WHERE id=? AND shop_id=? AND status='AVAILABLE' AND legal_status='IN_CIRCULATION'
                                """)) {
                            reserve.setString(1, reservationToken);
                            reserve.setString(2, now);
                            reserve.setString(3, now);
                            reserve.setLong(4, kizId);
                            reserve.setInt(5, job.shopId());
                            if (reserve.executeUpdate() != 1) {
                                throw new IllegalStateException("KIZ inventory changed while reserving Ozon marks.");
                            }
                        }
                        try (PreparedStatement link = connection.prepareStatement("""
                                UPDATE ozon_exemplars SET kiz_id=?,updated_at=? WHERE id=? AND kiz_id IS NULL
                                """)) {
                            link.setLong(1, kizId);
                            link.setString(2, now);
                            link.setLong(3, exemplarRows.get(index));
                            if (link.executeUpdate() != 1) {
                                throw new IllegalStateException("Ozon exemplar KIZ link changed while reserving.");
                            }
                        }
                    }
                }
                OzonExemplarJobStage current = find(connection, job.shopId(), job.postingNumber()).stage();
                if (current != OzonExemplarJobStage.CREATED && current != OzonExemplarJobStage.RECONCILE_REQUIRED) {
                    throw new IllegalStateException("Ozon exemplar job cannot reserve KIZ in stage " + current + ".");
                }
                transition(connection, job.id(), current, OzonExemplarJobStage.RESERVED, null, null, false);
                transaction.execute("COMMIT");
            } catch (RuntimeException | SQLException exception) {
                transaction.execute("ROLLBACK");
                throw exception;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<KizBinding> bindings(long jobId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT e.item_index,e.product_id,e.exemplar_id,e.exemplar_index,e.kiz_id,k.raw_code
                        FROM ozon_exemplars e JOIN kiz_codes k ON k.id=e.kiz_id
                        WHERE e.job_id=? ORDER BY e.item_index,e.exemplar_index
                        """)) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                List<KizBinding> bindings = new ArrayList<>();
                while (result.next()) bindings.add(new KizBinding(
                        result.getInt(1), result.getString(2), result.getString(3), result.getInt(4),
                        result.getLong(5), result.getString(6)));
                return List.copyOf(bindings);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<ExemplarSummary> summaries(long jobId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT item_index,exemplar_index,exemplar_id,kiz_id,check_status
                        FROM ozon_exemplars WHERE job_id=? ORDER BY item_index,exemplar_index
                        """)) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                List<ExemplarSummary> summaries = new ArrayList<>();
                while (result.next()) summaries.add(new ExemplarSummary(
                        result.getInt(1), result.getInt(2), result.getString(3),
                        result.getObject(4) == null ? null : result.getLong(4), result.getString(5)));
                return List.copyOf(summaries);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public OzonExemplarJob transition(
            OzonExemplarJob job,
            OzonExemplarJobStage next,
            String fingerprint,
            String safeError,
            boolean mutationAttempted) {
        OzonExemplarStateMachine.requireTransition(job.stage(), next);
        try (Connection connection = Database.getConnection()) {
            transition(connection, job.id(), job.stage(), next, fingerprint, safeError, mutationAttempted);
            OzonExemplarJob updated = find(connection, job.shopId(), job.postingNumber());
            if (updated == null) throw new IllegalStateException("Ozon exemplar job disappeared.");
            return updated;
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public OzonExemplarJob consumeAccepted(OzonExemplarJob job) {
        if (job.stage() != OzonExemplarJobStage.VERIFYING
                && job.stage() != OzonExemplarJobStage.RECONCILE_REQUIRED) {
            throw new IllegalStateException("Ozon KIZ can only be consumed after remote verification.");
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement accepted = connection.prepareStatement("""
                    UPDATE ozon_exemplars SET check_status='passed',updated_at=? WHERE job_id=?
                    """);
                    PreparedStatement consume = connection.prepareStatement("""
                    UPDATE kiz_codes SET status='CONSUMED',reservation_token=NULL,reserved_at=NULL,
                        reservation_recoverable=NULL,consumed_at=?,updated_at=?
                    WHERE id IN (SELECT kiz_id FROM ozon_exemplars WHERE job_id=? AND kiz_id IS NOT NULL)
                      AND status='RESERVED' AND reservation_token=?
                    """)) {
                String now = Instant.now().toString();
                accepted.setString(1, now);
                accepted.setLong(2, job.id());
                accepted.executeUpdate();
                consume.setString(1, now);
                consume.setString(2, now);
                consume.setLong(3, job.id());
                consume.setString(4, "ozon:" + job.id());
                int expected = bindings(job.id()).size();
                if (consume.executeUpdate() != expected) {
                    throw new IllegalStateException("One or more durable Ozon KIZ reservations are no longer owned by the job.");
                }
                transition(connection, job.id(), job.stage(), OzonExemplarJobStage.ACCEPTED,
                        job.requestFingerprint(), null, false);
                connection.commit();
                return find(connection, job.shopId(), job.postingNumber());
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Consumes locally reserved KIZ only after the printable PDF has been published successfully. */
    public OzonExemplarJob consumePrinted(OzonExemplarJob job) {
        if (job.stage() == OzonExemplarJobStage.ACCEPTED) return job;
        if (job.stage() != OzonExemplarJobStage.VALIDATED) {
            throw new IllegalStateException("Ozon KIZ can only be consumed after it is ready for printing.");
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement invalid = connection.prepareStatement("""
                    SELECT COUNT(*) FROM ozon_exemplars e
                    JOIN kiz_codes k ON k.id=e.kiz_id
                    WHERE e.job_id=? AND NOT (
                        k.status='CONSUMED' OR (k.status='RESERVED' AND k.reservation_token=?))
                    """);
                    PreparedStatement printed = connection.prepareStatement("""
                    UPDATE ozon_exemplars SET check_status='printed',updated_at=? WHERE job_id=?
                    """);
                    PreparedStatement consume = connection.prepareStatement("""
                    UPDATE kiz_codes SET status='CONSUMED',reservation_token=NULL,reserved_at=NULL,
                        reservation_recoverable=NULL,consumed_at=?,updated_at=?
                    WHERE id IN (SELECT kiz_id FROM ozon_exemplars WHERE job_id=? AND kiz_id IS NOT NULL)
                      AND status='RESERVED' AND reservation_token=?
                    """)) {
                String token = "ozon:" + job.id();
                invalid.setLong(1, job.id());
                invalid.setString(2, token);
                try (ResultSet result = invalid.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        throw new IllegalStateException("One or more Ozon KIZ bindings are no longer owned by the print job.");
                    }
                }
                String now = Instant.now().toString();
                printed.setString(1, now);
                printed.setLong(2, job.id());
                printed.executeUpdate();
                consume.setString(1, now);
                consume.setString(2, now);
                consume.setLong(3, job.id());
                consume.setString(4, token);
                consume.executeUpdate();
                transition(connection, job.id(), job.stage(), OzonExemplarJobStage.ACCEPTED,
                        job.requestFingerprint(), null, false);
                connection.commit();
                return find(connection, job.shopId(), job.postingNumber());
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Allowed only before set, or after readback proved Ozon stores no marks. */
    public OzonExemplarJob releaseRejected(OzonExemplarJob job, boolean remoteProvedEmpty, String safeError) {
        boolean beforeMutation = job.stage() == OzonExemplarJobStage.CREATED
                || job.stage() == OzonExemplarJobStage.RESERVED
                || job.stage() == OzonExemplarJobStage.VALIDATED;
        if (!beforeMutation && !remoteProvedEmpty) {
            throw new IllegalStateException("Ozon KIZ reservation cannot be released before a conclusive readback.");
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement release = connection.prepareStatement("""
                    UPDATE kiz_codes SET status='AVAILABLE',reservation_token=NULL,reserved_at=NULL,
                        reservation_recoverable=NULL,updated_at=?
                    WHERE id IN (SELECT kiz_id FROM ozon_exemplars WHERE job_id=? AND kiz_id IS NOT NULL)
                      AND status='RESERVED' AND reservation_token=?
                    """)) {
                release.setString(1, Instant.now().toString());
                release.setLong(2, job.id());
                release.setString(3, "ozon:" + job.id());
                release.executeUpdate();
                transition(connection, job.id(), job.stage(), OzonExemplarJobStage.REJECTED,
                        job.requestFingerprint(), safeError, false);
                connection.commit();
                return find(connection, job.shopId(), job.postingNumber());
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void logAction(
            int shopId, String action, String postingNumber, String status, String error, String fingerprint) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_action_log(shop_id,action_type,posting_number,status,safe_error_code,
                            request_fingerprint,created_at) VALUES(?,?,?,?,?,?,?)
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safeToken(action));
            statement.setString(3, postingNumber);
            statement.setString(4, safeToken(status));
            statement.setString(5, error == null ? null : safeToken(error));
            statement.setString(6, fingerprint);
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public String latestActionStatus(int shopId, String action, String postingNumber) {
        try (Connection connection = Database.getConnection()) {
            return latestActionStatus(
                    connection,
                    shopId,
                    safeToken(action),
                    OzonApiClient.requireExternalId(postingNumber, "posting number"));
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Atomically prevents a second mutation while an earlier result still needs reconciliation. */
    public boolean tryBeginAction(int shopId, String action, String postingNumber, String fingerprint) {
        String safeAction = safeToken(action);
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A safe Ozon action fingerprint is required.");
        }
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String latest = latestActionStatus(connection, shopId, safeAction, safePosting);
                if (blocksMutationRetry(latest)) {
                    transaction.execute("COMMIT");
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_action_log(shop_id,action_type,posting_number,status,
                            request_fingerprint,created_at) VALUES(?,?,?,'pending',?,?)
                        """)) {
                    statement.setInt(1, shopId);
                    statement.setString(2, safeAction);
                    statement.setString(3, safePosting);
                    statement.setString(4, fingerprint);
                    statement.setString(5, Instant.now().toString());
                    statement.executeUpdate();
                }
                transaction.execute("COMMIT");
                return true;
            } catch (RuntimeException | SQLException exception) {
                transaction.execute("ROLLBACK");
                throw exception;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static OzonExemplarJob find(Connection connection, int shopId, String postingNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,shop_id,posting_number,stage,request_fingerprint,safe_error_code,
                       mutation_attempted_at,created_at,updated_at
                FROM ozon_exemplar_jobs WHERE shop_id=? AND posting_number=?
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new OzonExemplarJob(
                        result.getLong(1), result.getInt(2), result.getString(3),
                        OzonExemplarJobStage.valueOf(result.getString(4)), result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8), result.getString(9));
            }
        }
    }

    private static String latestActionStatus(
            Connection connection, int shopId, String action, String postingNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM ozon_action_log
                WHERE shop_id=? AND action_type=? AND posting_number=?
                ORDER BY id DESC LIMIT 1
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, action);
            statement.setString(3, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void transition(
            Connection connection,
            long jobId,
            OzonExemplarJobStage from,
            OzonExemplarJobStage to,
            String fingerprint,
            String safeError,
            boolean mutationAttempted) throws SQLException {
        OzonExemplarStateMachine.requireTransition(from, to);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ozon_exemplar_jobs SET stage=?,request_fingerprint=COALESCE(?,request_fingerprint),
                    safe_error_code=?,mutation_attempted_at=CASE WHEN ? THEN ? ELSE mutation_attempted_at END,
                    updated_at=? WHERE id=? AND stage=?
                """)) {
            String now = Instant.now().toString();
            statement.setString(1, to.name());
            statement.setString(2, fingerprint);
            statement.setString(3, safeError == null ? null : safeToken(safeError));
            statement.setBoolean(4, mutationAttempted);
            statement.setString(5, now);
            statement.setString(6, now);
            statement.setLong(7, jobId);
            statement.setString(8, from.name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Ozon exemplar job was changed by another operation.");
            }
        }
    }

    private static List<Long> exemplarRows(Connection connection, long jobId, int itemIndex) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM ozon_exemplars WHERE job_id=? AND item_index=? AND kiz_id IS NULL
                ORDER BY exemplar_index
                """)) {
            statement.setLong(1, jobId);
            statement.setInt(2, itemIndex);
            try (ResultSet result = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) ids.add(result.getLong(1));
                return ids;
            }
        }
    }

    private static List<Long> availableKiz(Connection connection, int shopId, String gtin, int quantity)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM kiz_codes
                WHERE shop_id=? AND gtin=? AND status='AVAILABLE' AND legal_status='IN_CIRCULATION'
                ORDER BY id LIMIT ?
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            statement.setInt(3, quantity);
            try (ResultSet result = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) ids.add(result.getLong(1));
                return ids;
            }
        }
    }

    private static String safeToken(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}") ? value : "internal";
    }

    private static boolean blocksMutationRetry(String status) {
        return "pending".equals(status)
                || "ambiguous".equals(status)
                || "success".equals(status)
                || "reconciled".equals(status);
    }

    public static final class InsufficientKizException extends IllegalStateException {
        InsufficientKizException() {
            super("The selected Ozon shop does not have enough available KIZ for this posting.");
        }
    }

    public record ExemplarSummary(
            int itemIndex, int exemplarIndex, String exemplarId, Long kizId, String checkStatus) {
    }

    public static final class KizBinding {
        private final int itemIndex;
        private final String productId;
        private final String exemplarId;
        private final int exemplarIndex;
        private final long kizId;
        private final String rawCode;

        KizBinding(int itemIndex, String productId, String exemplarId, int exemplarIndex, long kizId, String rawCode) {
            this.itemIndex = itemIndex;
            this.productId = productId;
            this.exemplarId = exemplarId;
            this.exemplarIndex = exemplarIndex;
            this.kizId = kizId;
            this.rawCode = rawCode;
        }

        public int itemIndex() { return itemIndex; }
        public String productId() { return productId; }
        public String exemplarId() { return exemplarId; }
        public int exemplarIndex() { return exemplarIndex; }
        public long kizId() { return kizId; }
        String rawCode() { return rawCode; }

        @Override
        public String toString() {
            return "KizBinding{itemIndex=" + itemIndex + ", exemplarIndex=" + exemplarIndex
                    + ", kizId=" + kizId + ", rawCode=<redacted>}";
        }
    }
}
