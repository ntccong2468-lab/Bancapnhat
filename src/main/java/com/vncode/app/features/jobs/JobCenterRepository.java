package com.vncode.app.features.jobs;

import com.vncode.app.config.Database;
import java.sql.SQLException;
import java.util.*;

/** Summaries only: no token, KIZ, payload, contact detail or raw upstream errors leave this reader. */
public final class JobCenterRepository {
    public record Row(int shopId,String shop,String kind,String state,long count) {}
    public List<Row> load() {
        var rows=new ArrayList<Row>();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);
            read(c,rows,"gtin_sync_items","status","gtin");
            read(c,rows,"ozon_exemplar_jobs","stage","orders");
            read(c,rows,"znack_card_registrations","status","registration");
            read(c,rows,"ozon_card_registrations","status","registration");
            read(c,rows,"znack_purchase_pipelines","stage","purchase");
            read(c,rows,"product_edit_items","state","edit");
            read(c,rows,"print_jobs","status","print");
            c.commit();return List.copyOf(rows);
        }catch(SQLException unavailable){throw new IllegalStateException("Unable to read job summaries");}
    }
    private static void read(java.sql.Connection c,List<Row> out,String table,String column,String kind)throws SQLException {
        // Identifiers are compile-time constants. The projection cannot expose seller payloads.
        String sql="SELECT s.id,s.name,j."+column+",count(*) FROM "+table+" j JOIN shops s ON s.id=j.shop_id GROUP BY s.id,s.name,j."+column;
        try(var p=c.prepareStatement(sql);var r=p.executeQuery()){while(r.next()) {
            String source=r.getString(3);String state=state(source);
            if(state!=null)out.add(new Row(r.getInt(1),r.getString(2),kind,state,r.getLong(4)));
        }}
    }
    private static String state(String source) {
        if(source==null)return null;source=source.toUpperCase(java.util.Locale.ROOT);
        if(Set.of("SUCCESS","SUCCEEDED","PRINTED","ACCEPTED","PUBLISHED","RECONCILED","READY","COMPLETED","DONE","CANCELLED","NOT_CREATED","INTRODUCED").contains(source))return null;
        if(source.equals("PDF_READY"))return "review";
        if(source.contains("RECONCILE")||source.contains("REVIEW")||source.contains("UNKNOWN")||source.contains("UNCONFIRMED"))return "review";
        if(source.contains("ERROR")||source.contains("FAIL")||source.contains("REJECT"))return "blocked";
        if(source.contains("READY")||source.contains("CREATED")||source.contains("QUEUED"))return "queued";
        return "running";
    }
}
