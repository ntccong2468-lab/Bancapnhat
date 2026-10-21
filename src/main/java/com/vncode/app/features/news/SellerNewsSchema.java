package com.vncode.app.features.news;
import java.sql.*;
public final class SellerNewsSchema {
 private SellerNewsSchema(){}
 public static void ensureTables(Connection c)throws SQLException {try(var s=c.createStatement()){
  s.execute("CREATE TABLE IF NOT EXISTS seller_news(shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,owner TEXT NOT NULL,source TEXT NOT NULL,source_id TEXT NOT NULL,revision TEXT NOT NULL,published_at TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,source_url TEXT NOT NULL,read_revision TEXT,PRIMARY KEY(shop_id,marketplace,owner,source,source_id))");
  s.execute("CREATE INDEX IF NOT EXISTS idx_seller_news_owner_date ON seller_news(shop_id,marketplace,owner,published_at DESC)");
  s.execute("CREATE TABLE IF NOT EXISTS seller_news_refresh(shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,owner TEXT NOT NULL,checked_at TEXT,retry_at TEXT,cursor TEXT NOT NULL DEFAULT '',PRIMARY KEY(shop_id,marketplace,owner))");
 }}
}
