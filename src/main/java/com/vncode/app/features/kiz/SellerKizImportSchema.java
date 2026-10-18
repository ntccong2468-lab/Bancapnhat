package com.vncode.app.features.kiz;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Seller uploads are separate stock occurrences. Purchased code uniqueness is unchanged. */
public final class SellerKizImportSchema {
    private SellerKizImportSchema(){}
    public static void codes(Connection c)throws SQLException {
        String sql=definition(c,"kiz_codes");
        if(!sql.contains("stock_instance"))try(var s=c.createStatement()){s.execute("ALTER TABLE kiz_codes ADD COLUMN stock_instance TEXT NOT NULL DEFAULT ''");}
        sql=definition(c,"kiz_codes");
        String replacement=sql.replaceAll("(?i)UNIQUE\\s*\\(\\s*shop_id\\s*,\\s*raw_code\\s*\\)\\s*,","");
        if(!replacement.equals(sql))rebuild(c,"kiz_codes",replacement);
        try(var s=c.createStatement()){
            s.execute("DROP INDEX IF EXISTS uq_kiz_codes_raw_code");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_kiz_codes_purchased_raw ON kiz_codes(raw_code) WHERE stock_instance=''");
        }
    }
    static void entries(Connection c)throws SQLException {
        String sql=definition(c,"kiz_import_entries");
        String replacement=sql.replaceAll("(?i)(cis\\s+TEXT\\s+NOT\\s+NULL)\\s+UNIQUE","$1");
        if(!replacement.equals(sql))rebuild(c,"kiz_import_entries",replacement);
        try(var s=c.createStatement()){s.execute("CREATE INDEX IF NOT EXISTS idx_kiz_import_cis ON kiz_import_entries(cis)");}
    }
    private static String definition(Connection c,String table)throws SQLException{
        try(var p=c.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")){p.setString(1,table);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("KIZ_SCHEMA_TABLE_MISSING");return r.getString(1);}}
    }
    /** Standard SQLite rebuild: identities, references, indexes and triggers survive atomically. */
    private static void rebuild(Connection c,String table,String sql)throws SQLException {
        if(!c.getAutoCommit())throw new SQLException("KIZ_SCHEMA_TRANSACTION_ACTIVE");
        var auxiliary=new ArrayList<String>();var columns=new ArrayList<String>();long sequence=0;
        try(var p=c.prepareStatement("SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL")){
            p.setString(1,table);try(var r=p.executeQuery()){while(r.next())auxiliary.add(r.getString(1));}
        }
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())columns.add('"'+r.getString("name")+'"');}
        try(var p=c.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")){p.setString(1,table);try(var r=p.executeQuery()){if(r.next())sequence=r.getLong(1);}}
        String temporary=table+"_seller_migration";
        String create=sql.replaceFirst("(?i)CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?[\"`]?"+Pattern.quote(table)+"[\"`]?", "CREATE TABLE "+temporary);
        if(create.equals(sql))throw new SQLException("KIZ_SCHEMA_DEFINITION_INVALID");
        try(var s=c.createStatement()){
            s.execute("PRAGMA foreign_keys=OFF");s.execute("BEGIN IMMEDIATE");
            try{
                s.execute(create);String names=String.join(",",columns);
                s.execute("INSERT INTO "+temporary+"("+names+") SELECT "+names+" FROM "+table);
                s.execute("DROP TABLE "+table);s.execute("ALTER TABLE "+temporary+" RENAME TO "+table);
                for(String statement:auxiliary)s.execute(statement);
                if(sequence>0)try(var p=c.prepareStatement("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name=?")){p.setLong(1,sequence);p.setString(2,table);p.executeUpdate();}
                // Validate the rebuilt table. Unrelated legacy tables can still refer
                // to their archived unscoped parents during the earlier migration.
                try(var r=s.executeQuery("PRAGMA foreign_key_check("+table+")")){if(r.next())throw new SQLException("KIZ_SCHEMA_REFERENCE_INVALID");}
                s.execute("COMMIT");
            }catch(SQLException failure){s.execute("ROLLBACK");throw failure;}
            finally{s.execute("PRAGMA foreign_keys=ON");}
        }
    }
}
