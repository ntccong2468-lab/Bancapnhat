package com.vncode.app.features.listingcopy;

import java.sql.*;
public final class ListingCopySchema {
    private ListingCopySchema(){}
    public static void ensureTables(Connection c)throws SQLException{try(var s=c.createStatement()){
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_jobs(
            id TEXT PRIMARY KEY, source_shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
            destination_shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
            marketplace TEXT NOT NULL, owner TEXT NOT NULL, state TEXT NOT NULL, approved_hash TEXT,
            payload TEXT NOT NULL, updated_at TEXT NOT NULL)
          """);
        s.execute("CREATE INDEX IF NOT EXISTS listing_copy_owner ON listing_copy_jobs(destination_shop_id,owner,updated_at DESC)");
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_articles(
            destination_shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,article TEXT NOT NULL,
            job_id TEXT NOT NULL REFERENCES listing_copy_jobs(id) ON DELETE CASCADE,
            PRIMARY KEY(destination_shop_id,marketplace,article))
          """);
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_events(id INTEGER PRIMARY KEY,job_id TEXT NOT NULL REFERENCES listing_copy_jobs(id) ON DELETE CASCADE,state TEXT NOT NULL,phase TEXT NOT NULL,created_at TEXT NOT NULL)
          """);
    }}
}
