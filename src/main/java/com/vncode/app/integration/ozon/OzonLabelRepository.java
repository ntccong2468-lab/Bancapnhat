package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class OzonLabelRepository {
    /** Short batch transactions; no network or PDF work under the writer lock. */
    void updateBatch(int shopId,java.util.List<String> numbers,String task,String status,String error) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            try(var statement=connection.prepareStatement("UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,safe_error_code=?,updated_at=? WHERE shop_id=? AND posting_number=? AND status NOT IN ('ISOLATING','ISOLATE_REQUIRED')")) {
                for(String number:numbers){statement.setString(1,task);statement.setString(2,status);statement.setString(3,error);statement.setString(4,Instant.now().toString());statement.setInt(5,shopId);statement.setString(6,number);statement.addBatch();}
                for(int count:statement.executeBatch())if(count!=1)throw new SQLException("OZON_LABEL_BATCH_CHANGED");
            }return null;
        });}catch(SQLException error1){throw new IllegalStateException("OZON_LABEL_STORAGE",error1);}
    }
    void claimBatch(int shopId,java.util.List<String> numbers) {
        try {com.vncode.app.config.SqliteWriteTransaction.run(connection->{
            try(var statement=connection.prepareStatement("UPDATE ozon_label_jobs SET status='RECONCILE_REQUIRED',updated_at=? WHERE shop_id=? AND posting_number=? AND (task_id IS NULL OR task_id='') AND status IN ('CREATED','FAILED')")) {
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
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT posting_number FROM ozon_label_jobs WHERE shop_id=? AND task_id=?")) {
            p.setInt(1,shopId);p.setString(2,task);var result=new java.util.ArrayList<String>();
            try(var rows=p.executeQuery()){while(rows.next())result.add(rows.getString(1));}return java.util.List.copyOf(result);
        }catch(SQLException failure){throw new IllegalStateException("OZON_LABEL_STORAGE",failure);}
    }

    /** Retain the shared receipt until a separate, single-posting task is acknowledged. */
    boolean claimIsolation(int shopId,String number,String originalTask) {
        return isolationState(shopId,number,originalTask,"ISOLATING",null,"READY","ISOLATE_REQUIRED");
    }
    boolean isolationReceipt(int shopId,String number,String originalTask,String receipt) {
        return isolationState(shopId,number,originalTask,"POLLING",receipt,"ISOLATING");
    }
    void isolationRejected(int shopId,String number,String originalTask) {
        isolationState(shopId,number,originalTask,"ISOLATE_REQUIRED",null,"ISOLATING");
    }
    private boolean isolationState(int shopId,String number,String originalTask,String state,String receipt,String... expected) {
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            String sql="UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,safe_error_code='BATCH_PAGE_IDENTITY',updated_at=? WHERE shop_id=? AND posting_number=? AND task_id=? AND status IN ("+String.join(",",java.util.Collections.nCopies(expected.length,"?"))+")";
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
