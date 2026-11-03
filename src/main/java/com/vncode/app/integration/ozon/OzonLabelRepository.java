package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class OzonLabelRepository {
    java.util.Map<String,LabelJob> findMany(int shopId,java.util.List<String> numbers) {
        var result=new java.util.LinkedHashMap<String,LabelJob>();
        try(var connection=Database.getConnection()) {
            for(int offset=0;offset<numbers.size();offset+=500) {
                var page=numbers.subList(offset,Math.min(offset+500,numbers.size()));
                String sql="SELECT id,shop_id,posting_number,task_id,status,output_path,safe_error_code,created_at,updated_at FROM ozon_label_jobs WHERE shop_id=? AND posting_number IN ("+String.join(",",java.util.Collections.nCopies(page.size(),"?"))+")";
                try(var statement=connection.prepareStatement(sql)) {
                    statement.setInt(1,shopId);
                    for(int i=0;i<page.size();i++)statement.setString(i+2,page.get(i));
                    try(var rows=statement.executeQuery()) {while(rows.next()) {
                        var job=new LabelJob(rows.getLong(1),rows.getInt(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8),rows.getString(9));
                        result.put(job.postingNumber(),job);
                    }}
                }
            }
            return java.util.Collections.unmodifiableMap(result);
        }catch(SQLException failure){throw new IllegalStateException("OZON_LABEL_STORAGE",failure);}
    }

    void createMissingBatch(int shopId,java.util.List<String> numbers) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            try(var statement=connection.prepareStatement("INSERT OR IGNORE INTO ozon_label_jobs(shop_id,posting_number,status,created_at,updated_at) VALUES(?,?,'CREATED',?,?)")) {
                String now=Instant.now().toString();
                for(String number:numbers){statement.setInt(1,shopId);statement.setString(2,OzonApiClient.requireExternalId(number,"posting number"));statement.setString(3,now);statement.setString(4,now);statement.addBatch();}
                statement.executeBatch();
            }return null;
        });}catch(SQLException failure){throw new IllegalStateException("OZON_LABEL_STORAGE",failure);}
    }

    /** Short batch transactions; no network or PDF work under the writer lock. */
    void updateBatch(int shopId,java.util.List<String> numbers,String task,String status,String error) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            try(var statement=connection.prepareStatement("UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,safe_error_code=?,updated_at=? WHERE shop_id=? AND posting_number=? AND status NOT IN ('ISOLATING','ISOLATE_REQUIRED')")) {
                for(String number:numbers){statement.setString(1,task);statement.setString(2,status);statement.setString(3,error);statement.setString(4,Instant.now().toString());statement.setInt(5,shopId);statement.setString(6,number);statement.addBatch();}
                for(int count:statement.executeBatch())if(count!=1)throw new SQLException("OZON_LABEL_BATCH_CHANGED");
            }
            // Freeze the acknowledged shared task's original members in the
            // same transaction as its receipt; later retries move individual
            // postings to new tasks and must not rewrite this evidence.
            if(isSharedTask(task))snapshotTaskMembers(connection,shopId,task);
            return null;
        });}catch(SQLException error1){throw new IllegalStateException("OZON_LABEL_STORAGE",error1);}
    }
    /** Preserve one shared task while recording confirmed partial results atomically. */
    void updateBatchResult(int shopId,java.util.List<String> numbers,String task,java.util.Set<String> unprinted) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            try(var statement=connection.prepareStatement("UPDATE ozon_label_jobs SET status=?,safe_error_code=?,updated_at=? WHERE shop_id=? AND posting_number=? AND task_id=? AND status NOT IN ('ISOLATING','ISOLATE_REQUIRED')")) {
                String now=Instant.now().toString();
                for(String number:numbers){boolean rejected=unprinted.contains(number);statement.setString(1,rejected?"FAILED":"READY");statement.setString(2,rejected?"OZON_LABEL_UNPRINTED":null);statement.setString(3,now);statement.setInt(4,shopId);statement.setString(5,number);statement.setString(6,task);statement.addBatch();}
                for(int count:statement.executeBatch())if(count!=1)throw new SQLException("OZON_LABEL_BATCH_CHANGED");
            }return null;
        });}catch(SQLException error){throw new IllegalStateException("OZON_LABEL_STORAGE",error);}
    }
    void claimBatch(int shopId,java.util.List<String> numbers) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            // Older app versions have no immutable task snapshot. Capture all
            // still-known members before detaching a rejected posting.
            var previousTasks=new java.util.HashSet<String>();
            for(int offset=0;offset<numbers.size();offset+=500){
                var page=numbers.subList(offset,Math.min(offset+500,numbers.size()));
                String marks=String.join(",",java.util.Collections.nCopies(page.size(),"?"));
                try(var query=connection.prepareStatement("SELECT DISTINCT task_id FROM ozon_label_jobs WHERE shop_id=? AND posting_number IN ("+marks+")")){
                    query.setInt(1,shopId);for(int i=0;i<page.size();i++)query.setString(i+2,page.get(i));
                    try(var rows=query.executeQuery()){while(rows.next())if(isSharedTask(rows.getString(1)))previousTasks.add(rows.getString(1));}
                }
            }
            for(String previous:previousTasks)snapshotTaskMembers(connection,shopId,previous);
            // Completed, originally single-posting tasks can be replaced by one shared batch.
            // Isolated labels retain their receipt; an uncertain task must never be recreated.
            try(var statement=connection.prepareStatement("UPDATE ozon_label_jobs SET task_id=NULL,status='RECONCILE_REQUIRED',updated_at=? WHERE shop_id=? AND posting_number=? AND (((task_id IS NULL OR task_id='') AND status IN ('CREATED','FAILED')) OR (status='READY' AND task_id LIKE 'v3s:%' AND safe_error_code IS NULL) OR (status='FAILED' AND (task_id LIKE 'v3:%' OR task_id LIKE 'v3s:%') AND safe_error_code='OZON_LABEL_UNPRINTED'))")) {
                for(String number:numbers){statement.setString(1,Instant.now().toString());statement.setInt(2,shopId);statement.setString(3,number);statement.addBatch();}
                for(int count:statement.executeBatch())if(count!=1)throw new SQLException("OZON_LABEL_BATCH_CHANGED");
            }return null;
        });}catch(SQLException error){throw new IllegalStateException("OZON_LABEL_TASK_UNKNOWN",error);}
    }
    public LabelJob findOrCreate(int shopId, String postingNumber) {
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO ozon_label_jobs(shop_id,posting_number,status,created_at,updated_at)
                        VALUES(?,?,'CREATED',?,?)
                        """)) {
            insert.setInt(1, shopId);
            insert.setString(2, OzonApiClient.requireExternalId(postingNumber, "posting number"));
            insert.setString(3, now);
            insert.setString(4, now);
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return find(shopId, postingNumber);
    }

    java.util.List<String> taskPostings(int shopId,String task) {
        try(var c=Database.getConnection()) {
            var snapshot=readTaskMembers(c,shopId,task);
            var current=currentTaskMembers(c,shopId,task);
            if(snapshot!=null&&!new java.util.HashSet<>(snapshot).containsAll(current))throw new IllegalStateException("OZON_LABEL_MEMBERSHIP_CHANGED");
            return snapshot==null?current:snapshot;
        }catch(SQLException failure){throw new IllegalStateException("OZON_LABEL_STORAGE",failure);}
    }

    private static boolean isSharedTask(String task) {return task!=null&&task.matches("v3:[0-9]{1,19}");}
    private static String taskMembersKey(int shopId,String task) {
        if(shopId<=0||!isSharedTask(task))throw new IllegalArgumentException("OZON_LABEL_BATCH_TASK_CHANGED");
        return "ozon_label_task_members.v1:"+shopId+":"+task;
    }
    private static java.util.List<String> currentTaskMembers(Connection c,int shopId,String task)throws SQLException {
        try(var p=c.prepareStatement("SELECT posting_number FROM ozon_label_jobs WHERE shop_id=? AND task_id=? ORDER BY posting_number")) {
            p.setInt(1,shopId);p.setString(2,task);var result=new java.util.ArrayList<String>();
            try(var rows=p.executeQuery()){while(rows.next())result.add(rows.getString(1));}
            return java.util.List.copyOf(result);
        }
    }
    private static java.util.List<String> readTaskMembers(Connection c,int shopId,String task)throws SQLException {
        if(!isSharedTask(task))return null;
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
            p.setString(1,taskMembersKey(shopId,task));try(var r=p.executeQuery()){
                if(!r.next())return null;
                try {
                    var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(r.getString(1)));
                    reader.setStrictness(com.google.gson.Strictness.STRICT);
                    var parsed=com.google.gson.JsonParser.parseReader(reader);
                    if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IllegalArgumentException();
                    if(!parsed.isJsonArray())throw new IllegalArgumentException();
                    var result=new java.util.ArrayList<String>();var unique=new java.util.HashSet<String>();
                    for(var entry:parsed.getAsJsonArray()){
                        if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())throw new IllegalArgumentException();
                        String number=OzonApiClient.requireExternalId(entry.getAsString(),"posting number");
                        if(!unique.add(number))throw new IllegalArgumentException();result.add(number);
                    }
                    if(result.isEmpty()||result.size()>OzonLabelService.MAX_BATCH_POSTINGS)throw new IllegalArgumentException();
                    return java.util.List.copyOf(result);
                }catch(java.io.IOException|RuntimeException invalid){throw new IllegalStateException("OZON_LABEL_MEMBERSHIP_INVALID");}
            }
        }
    }
    private static void snapshotTaskMembers(Connection c,int shopId,String task)throws SQLException {
        if(!isSharedTask(task))return;
        var current=currentTaskMembers(c,shopId,task);var previous=readTaskMembers(c,shopId,task);
        if(previous!=null){if(!new java.util.HashSet<>(previous).containsAll(current))throw new SQLException("OZON_LABEL_MEMBERSHIP_CHANGED");return;}
        if(current.isEmpty()||current.size()>OzonLabelService.MAX_BATCH_POSTINGS)throw new SQLException("OZON_LABEL_MEMBERSHIP_INVALID");
        var array=new com.google.gson.JsonArray();
        for(String number:current)array.add(OzonApiClient.requireExternalId(number,"posting number"));
        try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?)")){
            p.setString(1,taskMembersKey(shopId,task));p.setString(2,array.toString());p.executeUpdate();
        }
    }

    /** Retain the shared receipt until a separate, single-posting task is acknowledged. */
    boolean claimIsolation(int shopId,String number,String originalTask) {
        return isolationState(shopId,number,originalTask,"ISOLATING",null,"READY","ISOLATE_REQUIRED","FAILED");
    }
    boolean isolationReceipt(int shopId,String number,String originalTask,String receipt) {
        return isolationState(shopId,number,originalTask,"POLLING",receipt,"ISOLATING");
    }
    void isolationRejected(int shopId,String number,String originalTask) {
        isolationState(shopId,number,originalTask,"ISOLATE_REQUIRED",null,"ISOLATING");
    }
    private boolean isolationState(int shopId,String number,String originalTask,String state,String receipt,String... expected) {
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(receipt!=null&&isSharedTask(originalTask)){
                try(var p=c.prepareStatement("SELECT 1 FROM ozon_label_jobs WHERE shop_id=? AND posting_number=? AND task_id=? AND status='ISOLATING'")){
                    p.setInt(1,shopId);p.setString(2,number);p.setString(3,originalTask);
                    try(var r=p.executeQuery()){if(!r.next())return false;}
                }
                snapshotTaskMembers(c,shopId,originalTask);
            }
            String sql="UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,safe_error_code=CASE WHEN status='FAILED' OR safe_error_code='label_failed' THEN 'label_failed' ELSE 'BATCH_PAGE_IDENTITY' END,updated_at=? WHERE shop_id=? AND posting_number=? AND task_id=? AND (status<>'FAILED' OR safe_error_code IN ('label_failed','OZON_LABEL_UNPRINTED')) AND status IN ("+String.join(",",java.util.Collections.nCopies(expected.length,"?"))+")";
            try(var p=c.prepareStatement(sql)){
                p.setString(1,receipt);p.setString(2,state);p.setString(3,Instant.now().toString());p.setInt(4,shopId);p.setString(5,number);p.setString(6,originalTask);
                for(int i=0;i<expected.length;i++)p.setString(7+i,expected[i]);return p.executeUpdate()==1;
            }
        });}catch(SQLException failure){throw new IllegalStateException("OZON_LABEL_STORAGE",failure);}
    }

    public LabelJob update(int shopId, String postingNumber, String taskId, String status, String path, String error) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,output_path=?,
                            safe_error_code=?,updated_at=? WHERE shop_id=? AND posting_number=?
                        """)) {
            statement.setString(1, taskId);
            statement.setString(2, safeStatus(status));
            statement.setString(3, path);
            statement.setString(4, error == null ? null : safeStatus(error));
            statement.setString(5, Instant.now().toString());
            statement.setInt(6, shopId);
            statement.setString(7, postingNumber);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Ozon label job disappeared.");
            return find(shopId, postingNumber);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public LabelJob find(int shopId, String postingNumber) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id,shop_id,posting_number,task_id,status,output_path,safe_error_code,created_at,updated_at
                        FROM ozon_label_jobs WHERE shop_id=? AND posting_number=?
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new LabelJob(
                        result.getLong(1), result.getInt(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getString(6), result.getString(7), result.getString(8), result.getString(9)) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String safeStatus(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}") ? value : "internal";
    }

    public record LabelJob(
            long id, int shopId, String postingNumber, String taskId, String status, String outputPath,
            String safeErrorCode, String createdAt, String updatedAt) {
    }
}
