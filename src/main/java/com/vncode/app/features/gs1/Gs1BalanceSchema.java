package com.vncode.app.features.gs1;
import java.sql.*;
public final class Gs1BalanceSchema {
 private Gs1BalanceSchema(){}
 public static void ensureTables(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS gs1_balance(inn TEXT NOT NULL,environment TEXT NOT NULL,product_group TEXT NOT NULL,account_id TEXT NOT NULL,amount TEXT NOT NULL,currency TEXT NOT NULL,checked_at TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(inn,environment,product_group,account_id))");}}
}
