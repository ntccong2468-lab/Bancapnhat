package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.znack.ZnackModels.*;
import static com.vncode.app.features.products.ProductJson.GSON;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable emission batches. Local pipeline/order identities remain stable for UI and inventory. */
public final class ZnackPurchaseBatchStore {
    public enum State { PREPARED, SUBMITTING, RECONCILING, ACCEPTED, DEFERRED, REJECTED, FAILED }
    public record Approved(String gtin, int quantity, String requestKey) { }
    public record Line(long pipelineId, long orderId, String gtin, int quantity, String requestKey) { }
    public record Batch(long id, int shopId, String identity, State state, String payload, String payloadHash,
                        String externalOrderId, Instant createdAt, Instant attemptedAt,
                        ZnackProductGroup group, ZnackPurchaseMethod method, Settings settings, List<Line> lines,
                        int recoveryAttempt, Instant nextAttemptAt, String error, boolean knownNotCreated,
                        Long parentBatchId, long rootBatchId) {
        public Batch { lines = List.copyOf(lines); }
        public ZnackPurchaseContext context() { return new ZnackPurchaseContext(settings, group, method); }
        public String accountKey() { return context().accountKey(); }
        public String remoteOrderId() { return externalOrderId; }
    }
    private final ZnackRepository repository;
    private final int shop;
    public ZnackPurchaseBatchStore(ZnackRepository repository) {
        this.repository = Objects.requireNonNull(repository); this.shop = repository.shop().shopId();
    }

    /** Atomic local recording only. Never invokes a signer or remote service. */
    public List<Long> enqueueApproved(Settings settings, List<Approved> requests) {
        return enqueueApproved(settings,new ZnackPurchasePreferences(shop).method(),requests);
    }
    public List<Long> enqueueApproved(Settings settings,ZnackPurchaseMethod approvedMethod,List<Approved> requests) {
        Objects.requireNonNull(settings); requests = List.copyOf(requests);
        Objects.requireNonNull(approvedMethod);
        if (requests.isEmpty()) return List.of();
        for (Approved request : requests) {
            GtinNormalizer.requireProductionOrderable(request.gtin());
            if (request.quantity() <= 0) throw new IllegalArgumentException("Quantity must be positive.");
            if (request.requestKey() == null || !UUID.fromString(request.requestKey()).toString().equals(request.requestKey()))
                throw new IllegalArgumentException("Purchase request key is invalid.");
        }
        List<Approved> approved = requests;
        return write(c -> {
            List<Long> ids = new ArrayList<>();
            for (Approved request : approved)
                ids.add(repository.enqueueApproved(c, request.gtin(), request.quantity(), request.requestKey(), settings,approvedMethod));
            return List.copyOf(ids);
        });
    }

    /** Captured with approval, not when a delayed worker happens to prepare its payload. */
    static void pinNewPipeline(Connection c, ZnackRepository repository, long pipelineId, Settings settings,ZnackPurchaseMethod approvedMethod) throws SQLException {
        String gtin, tnVed;
        try (var p = c.prepareStatement("SELECT p.gtin,g.tn_ved FROM znack_purchase_pipelines p JOIN znack_products g ON g.shop_id=p.shop_id AND g.gtin=p.gtin WHERE p.shop_id=? AND p.id=?")) {
            p.setInt(1, repository.shop().shopId()); p.setLong(2, pipelineId);
            try (var row = p.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("PURCHASE_PIPELINE_NOT_FOUND"); gtin = row.getString(1); tnVed = row.getString(2); }
        }
        var context = new ZnackPurchaseContext(settings.withAutoIntroduction(true), ZnackProductGroup.fromTnVed(tnVed),
                approvedMethod);
        try (var p = c.prepareStatement("INSERT OR IGNORE INTO znack_purchase_contexts(pipeline_id,shop_id,group_code,purchase_method,settings_json,identity_key,created_at) VALUES(?,?,?,?,?,?,?)")) {
            p.setLong(1, pipelineId); p.setInt(2, repository.shop().shopId()); p.setString(3, context.group().apiCode());
            p.setString(4, context.method().name()); p.setString(5, GSON.toJson(context.settings()));
            p.setString(6, context.identity()); p.setString(7, Instant.now().toString()); p.executeUpdate();
        }
    }

    public Optional<Batch> prepare(Settings settings, List<Long> pipelineIds) {
        if (pipelineIds == null || pipelineIds.isEmpty()) return Optional.empty();
        List<Long> ids = List.copyOf(pipelineIds);
        return write(c -> prepare(c, ids.getFirst(), ids));
    }
    public Optional<Batch> prepare(long anchorPipelineId) {
        return write(c -> {
            var existing = byPipeline(c, anchorPipelineId); if (existing.isPresent()) return existing;
            var context = contextForPipeline(c, anchorPipelineId);
            List<Long> ids = new ArrayList<>(); ids.add(anchorPipelineId);
            try (var p = c.prepareStatement("""
                    SELECT p.id FROM znack_purchase_pipelines p
                    JOIN znack_purchase_contexts x ON x.pipeline_id=p.id AND x.shop_id=p.shop_id
                    WHERE p.shop_id=? AND x.identity_key=? AND p.stage IN ('QUEUED','VALIDATING')
                      AND NOT EXISTS(SELECT 1 FROM znack_purchase_batch_members m WHERE m.pipeline_id=p.id)
                    ORDER BY p.id
                    """)) {
                p.setInt(1, shop); p.setString(2, context.identity());
                try (var rows = p.executeQuery()) { while (rows.next()) if (rows.getLong(1) != anchorPipelineId) ids.add(rows.getLong(1)); }
            }
            return prepare(c, anchorPipelineId, ids);
        });
    }
    private Optional<Batch> prepare(Connection c, long anchor, List<Long> ids) throws SQLException {
        var existing = byPipeline(c, anchor); if (existing.isPresent()) return existing;
        var context = contextForPipeline(c, anchor);
        if(!eligible(c,anchor))return Optional.empty();
        var lines = new ArrayList<Line>(); var gtins = new HashSet<String>();
        for (long id : ids) {
            if (lines.size() == 10) break;
            if (byPipeline(c, id).isPresent()) continue;
            if(!eligible(c,id))continue;
            ZnackPurchaseContext other = contextForPipeline(c, id);
            if (!context.identity().equals(other.identity()) || context.settings().autoIntroduction()!=other.settings().autoIntroduction()) continue;
            try (var p = c.prepareStatement("SELECT gtin,quantity,request_key,stage,order_id FROM znack_purchase_pipelines WHERE shop_id=? AND id=?")) {
                p.setInt(1, shop); p.setLong(2, id);
                try (var row = p.executeQuery()) {
                    if (!row.next() || !Set.of("QUEUED", "VALIDATING").contains(row.getString("stage"))) continue;
                    String gtin = row.getString("gtin"); if (!gtins.add(gtin)) continue;
                    long order = row.getLong("order_id");
                    if (row.wasNull()) order = draft(c, gtin, row.getInt("quantity"));
                    else if (!unpaidOrder(c, order, gtin, row.getInt("quantity"))) continue;
                    lines.add(new Line(id, order, gtin, row.getInt("quantity"), row.getString("request_key")));
                }
            }
        }
        if (lines.isEmpty() || lines.stream().noneMatch(x -> x.pipelineId() == anchor)) return Optional.empty();
        long batch; String now = Instant.now().toString();
        try (var p = c.prepareStatement("INSERT INTO znack_purchase_batches(shop_id,identity_key,account_key,group_code,purchase_method,settings_json,state,created_at) VALUES(?,?,?,?,?,?,'PREPARED',?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1, shop); p.setString(2, context.identity()); p.setString(3, context.accountKey());
            p.setString(4, context.group().apiCode()); p.setString(5, context.method().name()); p.setString(6, GSON.toJson(context.settings())); p.setString(7, now); p.executeUpdate();
            try (var row = p.getGeneratedKeys()) { if (!row.next()) throw new SQLException("Purchase batch ID missing"); batch = row.getLong(1); }
        }
        update(c,"UPDATE znack_purchase_batches SET root_batch_id=id WHERE shop_id=? AND id=?",shop,batch);
        for (Line line : lines) {
            try (var p = c.prepareStatement("INSERT INTO znack_purchase_batch_members(batch_id,shop_id,pipeline_id,order_id,gtin,approved_quantity,request_key) VALUES(?,?,?,?,?,?,?)")) {
                p.setLong(1, batch); p.setInt(2, shop); p.setLong(3, line.pipelineId()); p.setLong(4, line.orderId());
                p.setString(5, line.gtin()); p.setInt(6, line.quantity()); p.setString(7, line.requestKey()); p.executeUpdate();
            }
            try (var p = c.prepareStatement("UPDATE znack_purchase_pipelines SET order_id=?,stage='CREATING_ORDER',updated_at=? WHERE shop_id=? AND id=?")) {
                p.setLong(1, line.orderId()); p.setString(2, now); p.setInt(3, shop); p.setLong(4, line.pipelineId()); p.executeUpdate();
            }
        }
        return read(c, batch);
    }
    private long draft(Connection c, String gtin, int quantity) throws SQLException {
        try (var p = c.prepareStatement("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,?,?,'DRAFT',?,?)", Statement.RETURN_GENERATED_KEYS)) {
            String now = Instant.now().toString(); p.setInt(1, shop); p.setString(2, gtin); p.setInt(3, quantity); p.setString(4, now); p.setString(5, now); p.executeUpdate();
            try (var row = p.getGeneratedKeys()) { if (!row.next()) throw new SQLException("Purchase order ID missing"); return row.getLong(1); }
        }
    }

    public Optional<Batch> find(long batchId) { return readOnly(c -> read(c, batchId)); }
    public Optional<Batch> findByPipeline(long pipelineId) { return readOnly(c -> byPipeline(c, pipelineId)); }
    public Optional<Batch> findByOrder(long orderId) {
        return readOnly(c -> {
            try (var p = c.prepareStatement("SELECT batch_id FROM znack_purchase_batch_members WHERE shop_id=? AND order_id=? ORDER BY batch_id DESC LIMIT 1")) {
                p.setInt(1, shop); p.setLong(2, orderId); try (var row = p.executeQuery()) { return row.next() ? read(c, row.getLong(1)) : Optional.empty(); }
            }
        });
    }
    private Optional<Batch> byPipeline(Connection c, long pipelineId) throws SQLException {
        try (var p = c.prepareStatement("SELECT m.batch_id FROM znack_purchase_batch_members m JOIN znack_purchase_pipelines p ON p.id=m.pipeline_id AND p.shop_id=m.shop_id AND p.order_id=m.order_id WHERE m.shop_id=? AND m.pipeline_id=? ORDER BY m.batch_id DESC LIMIT 1")) {
            p.setInt(1, shop); p.setLong(2, pipelineId); try (var row = p.executeQuery()) { return row.next() ? read(c, row.getLong(1)) : Optional.empty(); }
        }
    }
    private Optional<Batch> read(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement("SELECT * FROM znack_purchase_batches WHERE shop_id=? AND id=?")) {
            p.setInt(1, shop); p.setLong(2, id);
            try (var r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                List<Line> lines = new ArrayList<>();
                try (var q = c.prepareStatement("SELECT pipeline_id,order_id,gtin,approved_quantity,request_key FROM znack_purchase_batch_members WHERE shop_id=? AND batch_id=? ORDER BY pipeline_id")) {
                    q.setInt(1, shop); q.setLong(2, id); try (var rows = q.executeQuery()) { while (rows.next()) lines.add(new Line(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getInt(4), rows.getString(5))); }
                }
                long parent=r.getLong("parent_batch_id");Long parentId=r.wasNull()?null:parent;
                long root=r.getLong("root_batch_id");if(r.wasNull())root=id;
                return Optional.of(new Batch(id, shop, r.getString("identity_key"), State.valueOf(r.getString("state")),
                        r.getString("payload_json"), r.getString("payload_hash"), r.getString("external_order_id"),
                        instant(r.getString("created_at")), instant(r.getString("attempted_at")),
                        ZnackProductGroup.fromCode(r.getString("group_code")), ZnackPurchaseMethod.fromStored(r.getString("purchase_method")),
                        GSON.fromJson(r.getString("settings_json"), Settings.class), lines, r.getInt("recovery_attempt"),
                        instant(r.getString("next_attempt_at")), r.getString("error_message"), r.getInt("known_not_created") != 0,parentId,root));
            }
        }
    }

    public void savePayload(long id, String canonicalPayload) {
        if (canonicalPayload == null || canonicalPayload.isBlank()) throw new IllegalArgumentException("Purchase payload is required.");
        write(c -> {
            Batch b = required(c, id); validatePayload(b,canonicalPayload); String hash = payloadHash(canonicalPayload);
            if (b.payload() != null && (!b.payload().equals(canonicalPayload) || !Objects.equals(b.payloadHash(), hash)))
                throw new IllegalStateException("PURCHASE_PAYLOAD_CHANGED");
            if (!Set.of(State.PREPARED, State.DEFERRED).contains(b.state())) {
                if (Objects.equals(b.payload(), canonicalPayload)) return null;
                throw new IllegalStateException("PURCHASE_ALREADY_SUBMITTED");
            }
            update(c, "UPDATE znack_purchase_batches SET payload_json=?,payload_hash=? WHERE shop_id=? AND id=?", canonicalPayload, hash, shop, id);
            return null;
        });
    }
    public boolean beginSubmission(long id) {
        return write(c -> {
            Batch b = required(c, id);
            if (!Set.of(State.PREPARED, State.DEFERRED).contains(b.state()) || b.externalOrderId() != null
                    || b.payload() == null || b.payloadHash() == null || b.nextAttemptAt() != null && b.nextAttemptAt().isAfter(Instant.now())) return false;
            if (b.attemptedAt() != null && !b.knownNotCreated()) return false;
            if (capacityBlocked(c, b.accountKey(), Instant.now()) || !validMembers(c, b)) return false;
            int n = update(c, "UPDATE znack_purchase_batches SET state='SUBMITTING',attempted_at=?,next_attempt_at=NULL,error_message=NULL,known_not_created=0 WHERE shop_id=? AND id=? AND state IN ('PREPARED','DEFERRED')",
                    Instant.now().toString(), shop, id);
            if (n == 1) project(c, id, PurchaseStage.CREATING_ORDER, null);
            return n == 1;
        });
    }
    private boolean validMembers(Connection c, Batch b) throws SQLException {
        if (b.lines().isEmpty() || b.lines().size() > 10 || b.lines().stream().map(Line::gtin).distinct().count() != b.lines().size()) return false;
        for (Line line : b.lines()) {
            try (var p = c.prepareStatement("SELECT p.gtin,p.quantity,p.request_key,p.order_id,p.stage,o.external_order_id,o.gtin,o.quantity FROM znack_purchase_pipelines p JOIN kiz_orders o ON o.id=p.order_id AND o.shop_id=p.shop_id WHERE p.shop_id=? AND p.id=?")) {
                p.setInt(1, shop); p.setLong(2, line.pipelineId());
                try (var r = p.executeQuery()) {
                    if (!r.next() || !line.gtin().equals(r.getString(1)) || line.quantity() != r.getInt(2)
                            || !Objects.equals(line.requestKey(), r.getString(3)) || line.orderId() != r.getLong(4)
                            || !Set.of("QUEUED", "VALIDATING", "CREATING_ORDER").contains(r.getString(5))
                            || r.getString(6) != null || !line.gtin().equals(r.getString(7)) || line.quantity() != r.getInt(8)) return false;
                }
            }
            var memberContext=contextForPipeline(c,line.pipelineId());
            if (!b.identity().equals(memberContext.identity()) || memberContext.settings().autoIntroduction()!=b.settings().autoIntroduction()) return false;
        }
        return true;
    }
    private boolean unpaidOrder(Connection c, long id, String gtin, int quantity) throws SQLException {
        try (var p = c.prepareStatement("SELECT gtin,quantity,external_order_id FROM kiz_orders WHERE shop_id=? AND id=?")) {
            p.setInt(1, shop); p.setLong(2, id); try (var row = p.executeQuery()) { return row.next() && gtin.equals(row.getString(1)) && quantity == row.getInt(2) && row.getString(3) == null; }
        }
    }
    private boolean eligible(Connection c,long id)throws SQLException{
        try(var p=c.prepareStatement("""
                SELECT p.gtin,p.quantity,p.order_id FROM znack_purchase_pipelines p
                WHERE p.shop_id=? AND p.id=? AND p.stage IN ('QUEUED','VALIDATING')
                AND NOT EXISTS(SELECT 1 FROM znack_purchase_pipelines other WHERE other.shop_id=p.shop_id
                  AND other.gtin=p.gtin AND other.id<>p.id
                  AND other.stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES'))
                """)){
            p.setInt(1,shop);p.setLong(2,id);try(var row=p.executeQuery()){
                if(!row.next())return false;long order=row.getLong(3);return row.wasNull()||unpaidOrder(c,order,row.getString(1),row.getInt(2));
            }
        }
    }
    public void acceptRemoteOrder(long id, String remoteOrderId) {
        if (remoteOrderId == null || remoteOrderId.isBlank()) throw new IllegalArgumentException("Remote order ID is required.");
        write(c -> {
            Batch b = required(c, id);
            if (b.externalOrderId() != null) {
                if (!b.externalOrderId().equals(remoteOrderId)) throw new IllegalStateException("PURCHASE_REMOTE_ORDER_CHANGED");
                return null;
            }
            if (!Set.of(State.SUBMITTING, State.RECONCILING).contains(b.state())) throw new IllegalStateException("PURCHASE_NOT_SUBMITTED");
            for (Line line : b.lines()) update(c, "UPDATE kiz_orders SET external_order_id=?,local_status='SUBMITTED',error_message=NULL,updated_at=? WHERE shop_id=? AND id=? AND external_order_id IS NULL",
                    remoteOrderId, Instant.now().toString(), shop, line.orderId());
            update(c, "UPDATE znack_purchase_batches SET external_order_id=?,state='ACCEPTED',next_attempt_at=NULL,error_message=NULL,known_not_created=0 WHERE shop_id=? AND id=?", remoteOrderId, shop, id);
            project(c, id, PurchaseStage.POLLING_ORDER, null); return null;
        });
    }
    public void markReconciling(long id, String reason) {
        write(c -> { Batch b = required(c, id); if (b.externalOrderId() != null) return null;
            update(c, "UPDATE znack_purchase_batches SET state='RECONCILING',error_message=?,known_not_created=0 WHERE shop_id=? AND id=?", safe(reason), shop, id);
            project(c, id, PurchaseStage.RECONCILING_ORDER, reason); return null; });
    }
    public void hold(long id, Instant nextAttemptAt, String reason) {
        Objects.requireNonNull(nextAttemptAt);
        write(c -> { Batch b = required(c, id);
            if(b.state()==State.REJECTED)throw new IllegalStateException("PURCHASE_REQUIRES_CREDENTIAL_RECOVERY");
            if (b.externalOrderId() != null || b.attemptedAt() != null && !b.knownNotCreated()) throw new IllegalStateException("PURCHASE_BATCH_OUTCOME_UNKNOWN");
            update(c, "UPDATE znack_purchase_batches SET state='DEFERRED',next_attempt_at=?,error_message=? WHERE shop_id=? AND id=?", nextAttemptAt.toString(), safe(reason), shop, id);
            project(c, id, PurchaseStage.CREATING_ORDER, reason); return null; });
    }
    public void recordKnownNotCreated(long id, String reason) {
        write(c -> { Batch b = required(c, id);
            if (b.externalOrderId() != null || b.attemptedAt() == null) throw new IllegalStateException("PURCHASE_NEGATIVE_PROOF_INVALID");
            Instant next = b.recoveryAttempt() >= 3 ? null : Instant.now().plus(Duration.ofMinutes(b.recoveryAttempt() == 0 ? 0 : b.recoveryAttempt() == 1 ? 5 : 15));
            update(c, "UPDATE znack_purchase_batches SET state=?,known_not_created=1,next_attempt_at=?,error_message=? WHERE shop_id=? AND id=?",
                    b.recoveryAttempt() >= 3 ? "FAILED" : "REJECTED", next == null ? null : next.toString(), safe(reason), shop, id);
            project(c, id, b.recoveryAttempt() >= 3 ? PurchaseStage.FAILED : PurchaseStage.CREATING_ORDER, reason); return null; });
    }
    public boolean claimCredentialRecovery(long id, Settings replacement, Instant now) {
        return !prepareCredentialRecovery(replacement,id,now).isEmpty();
    }
    public List<Batch> prepareCredentialRecovery(Settings replacement,long rejectedBatchId) { return prepareCredentialRecovery(replacement,rejectedBatchId,Instant.now()); }
    /** A definitely rejected multi-line attempt is retried as independent one-GTIN children. */
    public List<Batch> prepareCredentialRecovery(Settings replacement,long rejectedBatchId,Instant now) {
        Objects.requireNonNull(replacement);Objects.requireNonNull(now);
        return write(c->{
            Batch old=required(c,rejectedBatchId);
            if(!sameParty(old.settings(),replacement))return List.of();
            List<Batch> existing=new ArrayList<>();
            try(var p=c.prepareStatement("SELECT DISTINCT child_batch_id FROM znack_purchase_batch_recovery WHERE rejected_batch_id=? ORDER BY child_batch_id")){
                p.setLong(1,rejectedBatchId);try(var rows=p.executeQuery()){while(rows.next())existing.add(required(c,rows.getLong(1)));}
            }
            if(!existing.isEmpty())return List.copyOf(existing);
            if(old.state()!=State.REJECTED||!old.knownNotCreated()||old.externalOrderId()!=null||old.recoveryAttempt()>=3
                    ||old.nextAttemptAt()!=null&&old.nextAttemptAt().isAfter(now))return List.of();
            for(Line line:old.lines()){
                if(!unpaidOrder(c,line.orderId(),line.gtin(),line.quantity()))return List.of();
                try(var p=c.prepareStatement("SELECT 1 FROM kiz_codes WHERE shop_id=? AND order_id=? LIMIT 1")){p.setInt(1,shop);p.setLong(2,line.orderId());try(var row=p.executeQuery()){if(row.next())return List.of();}}
                if(byPipeline(c,line.pipelineId()).map(Batch::id).orElse(-1L)!=rejectedBatchId)return List.of();
            }
            List<Batch> children=new ArrayList<>();
            for(Line line:old.lines()){
                var memberOriginal=contextForPipeline(c,line.pipelineId());
                var context=new ZnackPurchaseContext(ZnackPurchaseContext.withCredentials(memberOriginal.settings(),replacement),old.group(),old.method());
                long localOrder=draft(c,line.gtin(),line.quantity());long child;
                try(var p=c.prepareStatement("INSERT INTO znack_purchase_batches(shop_id,identity_key,account_key,group_code,purchase_method,settings_json,state,created_at,recovery_attempt,parent_batch_id,root_batch_id) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    p.setInt(1,shop);p.setString(2,context.identity());p.setString(3,context.accountKey());p.setString(4,context.group().apiCode());p.setString(5,context.method().name());p.setString(6,GSON.toJson(context.settings()));p.setString(7,now.toString());p.setInt(8,old.recoveryAttempt()+1);p.setLong(9,old.id());p.setLong(10,old.rootBatchId());p.executeUpdate();try(var row=p.getGeneratedKeys()){if(!row.next())throw new SQLException("Recovery batch ID missing");child=row.getLong(1);}
                }
                update(c,"INSERT INTO znack_purchase_batch_members(batch_id,shop_id,pipeline_id,order_id,gtin,approved_quantity,request_key) VALUES(?,?,?,?,?,?,?)",child,shop,line.pipelineId(),localOrder,line.gtin(),line.quantity(),line.requestKey());
                update(c,"INSERT INTO znack_purchase_batch_recovery(rejected_batch_id,child_batch_id,pipeline_id,attempt,previous_settings_json,replacement_settings_json,negative_proof,created_at) VALUES(?,?,?,?,?,?,?,?)",old.id(),child,line.pipelineId(),old.recoveryAttempt()+1,GSON.toJson(old.settings()),GSON.toJson(context.settings()),first(old.error(),"DEFINITE_NOT_CREATED"),now.toString());
                update(c,"UPDATE kiz_orders SET local_status='FAILED',error_message=?,updated_at=? WHERE shop_id=? AND id=? AND external_order_id IS NULL",safe(old.error()),now.toString(),shop,line.orderId());
                update(c,"UPDATE znack_purchase_pipelines SET order_id=?,stage='CREATING_ORDER',error_message=NULL,updated_at=? WHERE shop_id=? AND id=?",localOrder,now.toString(),shop,line.pipelineId());
                update(c,"UPDATE znack_purchase_contexts SET settings_json=?,identity_key=? WHERE shop_id=? AND pipeline_id=?",GSON.toJson(context.settings()),context.identity(),shop,line.pipelineId());
                update(c,"UPDATE znack_pipeline_connections SET settings_json=? WHERE shop_id=? AND pipeline_id=?",GSON.toJson(context.settings()),shop,line.pipelineId());
                children.add(required(c,child));
            }
            return List.copyOf(children);
        });
    }
    /** SUZ code3350 is explicit no-creation proof; quota does not consume credential attempts. */
    public void recordQuotaRejection(long id,Instant until,String reason){
        Objects.requireNonNull(until);
        write(c->{Batch b=required(c,id);
            if(b.state()!=State.SUBMITTING||b.attemptedAt()==null||b.externalOrderId()!=null)throw new IllegalStateException("PURCHASE_QUOTA_PROOF_INVALID");
            update(c,"UPDATE znack_purchase_batches SET state='DEFERRED',known_not_created=1,next_attempt_at=?,error_message=? WHERE shop_id=? AND id=?",until.toString(),safe(reason),shop,id);
            update(c,"INSERT INTO znack_purchase_account_holds(account_key,until_at,reason) VALUES(?,?,?) ON CONFLICT(account_key) DO UPDATE SET until_at=MAX(until_at,excluded.until_at),reason=excluded.reason",b.accountKey(),until.toString(),safe(reason));
            project(c,id,PurchaseStage.CREATING_ORDER,reason);return null;
        });
    }
    /** Caller must hold a typed local-unsent or definitive429 rejection, never a transport error. */
    public void deferKnownUnsent(long id,Instant until,String reason){
        Objects.requireNonNull(until);
        write(c->{Batch b=required(c,id);
            if(!Set.of(State.PREPARED,State.SUBMITTING,State.DEFERRED).contains(b.state())||b.externalOrderId()!=null)
                throw new IllegalStateException("PURCHASE_UNSENT_PROOF_INVALID");
            update(c,"UPDATE znack_purchase_batches SET state='DEFERRED',known_not_created=1,next_attempt_at=?,error_message=? WHERE shop_id=? AND id=?",until.toString(),safe(reason),shop,id);
            project(c,id,PurchaseStage.CREATING_ORDER,reason);return null;
        });
    }
    /** Deterministic no-order rejection eligible only for a later explicit seller retry. */
    public void failKnownNotCreated(long id,String reason){
        write(c->{Batch b=required(c,id);
            if(b.state()!=State.SUBMITTING||b.attemptedAt()==null||b.externalOrderId()!=null)throw new IllegalStateException("PURCHASE_NEGATIVE_PROOF_INVALID");
            update(c,"UPDATE znack_purchase_batches SET state='FAILED',known_not_created=1,next_attempt_at=NULL,error_message=? WHERE shop_id=? AND id=?",safe(reason),shop,id);
            project(c,id,PurchaseStage.FAILED,reason);return null;
        });
    }
    /** Explicit seller retry after deterministic insufficient funds, not an unknown-outcome retry. */
    public boolean retryRejected(long id) {
        return write(c->{Batch b=required(c,id);
            if(!Set.of(State.REJECTED,State.FAILED).contains(b.state())||!b.knownNotCreated()||b.externalOrderId()!=null)return false;
            for(Line line:b.lines())if(!unpaidOrder(c,line.orderId(),line.gtin(),line.quantity())||byPipeline(c,line.pipelineId()).map(Batch::id).orElse(-1L)!=id)return false;
            update(c,"UPDATE znack_purchase_batches SET state='PREPARED',attempted_at=NULL,next_attempt_at=NULL,error_message=NULL,known_not_created=0 WHERE shop_id=? AND id=?",shop,id);
            project(c,id,PurchaseStage.CREATING_ORDER,null);return true;
        });
    }
    private static boolean sameParty(Settings old, Settings next) {
        if (!old.resolvedSuzBaseUrl().equals(next.resolvedSuzBaseUrl()) || !Objects.equals(old.omsId(), next.omsId())) return false;
        String owner = first(old.ownerInn(), old.participantInn()), other = first(next.ownerInn(), next.participantInn());
        return owner.isEmpty() ? other.isEmpty() && Objects.equals(old.signerCertificate(), next.signerCertificate()) : owner.equals(other);
    }
    public void fail(long id, String reason) {
        write(c -> { required(c, id); update(c, "UPDATE znack_purchase_batches SET state='FAILED',error_message=?,next_attempt_at=NULL WHERE shop_id=? AND id=?", safe(reason), shop, id); project(c, id, PurchaseStage.FAILED, reason); return null; });
    }
    public void project(long id, PurchaseStage stage, String error) { write(c -> { required(c, id); project(c, id, stage, error); return null; }); }
    private void project(Connection c, long id, PurchaseStage stage, String error) throws SQLException {
        update(c, "UPDATE znack_purchase_pipelines SET stage=?,error_message=?,updated_at=? WHERE shop_id=? AND id IN (SELECT pipeline_id FROM znack_purchase_batch_members WHERE batch_id=? AND shop_id=? AND order_id=znack_purchase_pipelines.order_id) AND stage NOT IN ('INTRODUCED','COMPLETED')",
                stage.name(), safe(error), Instant.now().toString(), shop, id, shop);
    }
    public void waitForCapacity(String accountKey, Instant until, String reason) {
        Objects.requireNonNull(until);
        write(c -> { update(c, "INSERT INTO znack_purchase_account_holds(account_key,until_at,reason) VALUES(?,?,?) ON CONFLICT(account_key) DO UPDATE SET until_at=MAX(until_at,excluded.until_at),reason=excluded.reason", accountKey, until.toString(), safe(reason)); return null; });
    }
    public boolean capacityBlocked(String accountKey) { return readOnly(c -> capacityBlocked(c, accountKey, Instant.now())); }
    private boolean capacityBlocked(Connection c, String accountKey, Instant now) throws SQLException {
        try (var p = c.prepareStatement("SELECT until_at FROM znack_purchase_account_holds WHERE account_key=?")) {
            p.setString(1, accountKey); try (var row = p.executeQuery()) { return row.next() && Instant.parse(row.getString(1)).isAfter(now); }
        }
    }
    public Optional<Instant> capacityResumeAt(String accountKey) {
        return readOnly(c -> { try (var p = c.prepareStatement("SELECT until_at FROM znack_purchase_account_holds WHERE account_key=?")) {
            p.setString(1, accountKey); try (var row = p.executeQuery()) { return row.next() ? Optional.of(Instant.parse(row.getString(1))) : Optional.empty(); }
        } });
    }

    public Settings settingsForOrder(long id) { return contextForOrder(id).settings(); }
    public Settings settingsForPipeline(long id) { return contextForPipeline(id).settings(); }
    public ZnackPurchaseContext contextForOrder(long id) { return contextForOrder(id, repository.getRawSettings()); }
    public ZnackPurchaseContext contextForOrder(long id, Settings legacyFallback) {
        Optional<Batch> batch = findByOrder(id);
        if(batch.isPresent()){
            Line line=batch.get().lines().stream().filter(x->x.orderId()==id).findFirst().orElseThrow();
            return contextForPipeline(line.pipelineId());
        }
        return readOnly(c -> {
            try (var p = c.prepareStatement("SELECT p.id FROM znack_purchase_pipelines p WHERE p.shop_id=? AND p.order_id=? ORDER BY p.id LIMIT 1")) {
                p.setInt(1, shop); p.setLong(2, id); try (var row = p.executeQuery()) { if (row.next()) return contextForPipeline(c, row.getLong(1)); }
            }
            // Imported historical orders have no pipeline; an explicit caller snapshot wins.
            return new ZnackPurchaseContext(Objects.requireNonNull(legacyFallback), ZnackProductGroup.LP, ZnackPurchaseMethod.PRODUCTION);
        });
    }
    public ZnackPurchaseContext contextForPipeline(long id) { return readOnly(c -> contextForPipeline(c, id)); }
    private ZnackPurchaseContext contextForPipeline(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement("SELECT settings_json,group_code,purchase_method FROM znack_purchase_contexts WHERE shop_id=? AND pipeline_id=?")) {
            p.setInt(1, shop); p.setLong(2, id);
            try (var row = p.executeQuery()) { if (row.next()) return new ZnackPurchaseContext(GSON.fromJson(row.getString(1), Settings.class), ZnackProductGroup.fromCode(row.getString(2)), ZnackPurchaseMethod.fromStored(row.getString(3))); }
        }
        try (var p = c.prepareStatement("SELECT x.settings_json FROM znack_pipeline_connections x JOIN znack_purchase_pipelines p ON p.id=x.pipeline_id AND p.shop_id=x.shop_id WHERE x.shop_id=? AND x.pipeline_id=?")) {
            p.setInt(1, shop); p.setLong(2, id);
            try (var row = p.executeQuery()) { if (row.next()) return new ZnackPurchaseContext(GSON.fromJson(row.getString(1), Settings.class), ZnackProductGroup.LP, ZnackPurchaseMethod.PRODUCTION); }
        }
        throw new IllegalStateException("KIZ_CIRCULATION_ORIGINAL_PROFILE_MISSING");
    }
    private Batch required(Connection c, long id) throws SQLException { return read(c, id).orElseThrow(() -> new IllegalArgumentException("PURCHASE_BATCH_NOT_FOUND")); }
    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]); return p.executeUpdate(); }
    }
    private static String first(String a, String b) { return a != null && !a.isBlank() ? a.strip() : b == null ? "" : b.strip(); }
    private static String payloadHash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void validatePayload(Batch batch,String payload){
        try{
            var root=com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            if(!batch.group().apiCode().equals(root.get("productGroup").getAsString())
                    ||!batch.method().name().equals(root.getAsJsonObject("attributes").get("releaseMethodType").getAsString()))throw new IllegalArgumentException();
            var products=root.getAsJsonArray("products");if(products.size()!=batch.lines().size())throw new IllegalArgumentException();
            var expected=new HashMap<String,Integer>();for(Line line:batch.lines())expected.put(line.gtin(),line.quantity());
            for(var item:products){var product=item.getAsJsonObject();String gtin=GtinNormalizer.requireProductionOrderable(product.get("gtin").getAsString());int quantity=product.get("quantity").getAsBigDecimal().intValueExact();if(!Objects.equals(expected.remove(gtin),quantity)
                    ||product.get("templateId").getAsInt()!=batch.group().templateId()||!"OPERATOR".equals(product.get("serialNumberType").getAsString()))throw new IllegalArgumentException();}
            if(!expected.isEmpty())throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalArgumentException("PURCHASE_PAYLOAD_DOES_NOT_MATCH_APPROVAL",invalid);}
    }
    private static String safe(String value) { return value == null ? null : ZnackSanitizer.message(value); }
    private static Instant instant(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
    private <T> T write(SqliteWriteTransaction.Work<T> work) {
        try { return SqliteWriteTransaction.run(work); } catch (SQLException error) { throw new IllegalStateException("PURCHASE_BATCH_STORAGE", error); }
    }
    private <T> T readOnly(SqliteWriteTransaction.Work<T> work) {
        try (Connection c = Database.getConnection()) { return work.run(c); } catch (SQLException error) { throw new IllegalStateException("PURCHASE_BATCH_STORAGE", error); }
    }
}
