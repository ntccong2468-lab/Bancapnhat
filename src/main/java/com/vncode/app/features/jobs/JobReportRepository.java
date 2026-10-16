package com.vncode.app.features.jobs;

import com.vncode.app.config.Database;
import com.vncode.app.features.sync.SyncRunRepository;
import java.sql.*;
import java.util.*;

/** Allowlisted projections only; no job payloads, KIZ codes or upstream bodies are read. */
public final class JobReportRepository {
    public record Report(String id,int shopId,String shop,String market,String kind,String state,String step,int total,String updated,String error,boolean detailed) {}
    public record Line(String product,String variant,String sku,String article,String color,String size,String gtin,String state,String step,String error) {}
    public List<Report> list(){
        var out=new ArrayList<Report>();var names=new HashMap<Integer,String>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,name FROM shops");var r=p.executeQuery()){while(r.next())names.put(r.getInt(1),r.getString(2));}catch(SQLException e){throw new IllegalStateException("JOBS_READ_FAILED",e);}
        for(var run:new SyncRunRepository().list(0,0,500))out.add(new Report(run.id(),run.shopId(),names.getOrDefault(run.shopId(),""),run.marketplace(),run.kind(),run.state(),run.step(),run.total(),run.updated(),run.error(),true));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT j.*,s.name,s.marketplace FROM sync_streams j JOIN shops s ON s.id=j.shop_id WHERE j.last_attempt IS NOT NULL");var r=p.executeQuery()){
            while(r.next()){
                String code=r.getString("error_code"),state=!code.isBlank()?(code.startsWith("NETWORK_")||code.endsWith("COOLDOWN")||code.endsWith("RATE_LIMIT")||code.equals("GS1_SYNC_PARTIAL")||code.equals("READBACK_PENDING")||code.equals("ZNACK_SYNC_ALREADY_RUNNING")?"WAITING_REMOTE":"NEEDS_ACTION"):r.getString("last_success")==null||r.getString("last_attempt").compareTo(r.getString("last_success"))>0?"RUNNING":r.getString("checkpoint").isBlank()?"SUCCEEDED":"RUNNING";
                out.add(new Report("stream:"+r.getInt("shop_id")+":"+r.getString("stream"),r.getInt("shop_id"),r.getString("name"),r.getString("marketplace"),"sync",state,r.getString("stream"),0,r.getString("last_attempt"),code,false));
            }
        }catch(SQLException e){throw new IllegalStateException("JOBS_READ_FAILED",e);}
        legacy(out,"gtin_sync_items","gtin","status","product_id","variant_id","remote_task_id");
        legacy(out,"ozon_exemplar_jobs","orders","stage","posting_number","","safe_error_code");
        legacy(out,"znack_card_registrations","registration","status","nm_id","chrt_id","error_message");
        legacy(out,"ozon_card_registrations","registration","status","product_id","offer_id","error");
        legacy(out,"znack_purchase_pipelines","purchase","stage","gtin","","error_message");
        legacy(out,"product_edit_items","edit","state","card_id","","phase");
        legacy(out,"print_jobs","print","status","supply_id","","error_message");
        out.sort(Comparator.comparing(Report::updated,Comparator.nullsFirst(Comparator.naturalOrder())).reversed());return out.stream().limit(1000).toList();
    }
    private static void legacy(List<Report> out,String table,String kind,String state,String product,String variant,String issue){
        try(var c=Database.getConnection()){
            var columns=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())columns.add(r.getString("name"));}
            if(columns.isEmpty())return;
            String updated=columns.contains("updated_at")?"j.updated_at":columns.contains("printed_at")?"j.printed_at":columns.contains("created_at")?"j.created_at":"''";
            String error=kind.equals("edit")?"COALESCE((SELECT json_extract(v.value,'$.code') FROM json_each(j.payload,'$.issues') v LIMIT 1),'')":columns.contains(issue)?"j."+issue:"''";
            String sql="SELECT CAST(j.rowid AS TEXT),j.shop_id,s.name,s.marketplace,j."+state+","+updated+","+error+" FROM "+table+" j JOIN shops s ON s.id=j.shop_id ORDER BY "+updated+" DESC LIMIT 200";
            try(var p=c.prepareStatement(sql);var r=p.executeQuery()){while(r.next()){
                String raw=r.getString(5);if(raw==null||raw.equals("NOT_CREATED"))continue;
                String code=SyncRunRepository.code(r.getString(7));if(kind.equals("gtin"))code=""; // A task ID or phase is not an error.
                out.add(new Report(table+":"+r.getString(1),r.getInt(2),r.getString(3),r.getString(4),kind,normalized(raw),raw,1,Objects.toString(r.getString(6),""),code,false));
            }}
        }catch(SQLException e){throw new IllegalStateException("JOBS_READ_FAILED",e);}
    }
    public List<Line> lines(Report report,int offset){
        if(report.id().startsWith("stream:"))return offset==0?List.of(new Line("","","","","","","",report.state(),report.step(),report.error())):List.of();
        if(report.detailed())return new SyncRunRepository().items(report.id(),offset,300).stream().map(i->new Line(i.productId(),i.variantId(),i.sku(),i.article(),i.color(),i.size(),i.gtin(),i.state(),i.step(),i.error())).toList();
        if(offset>0)return List.of();
        var parts=report.id().split(":",2);String table=parts[0];
        if(parts.length!=2||!Set.of("gtin_sync_items","ozon_exemplar_jobs","znack_card_registrations","ozon_card_registrations","znack_purchase_pipelines","product_edit_items","print_jobs").contains(table)||!parts[1].matches("[0-9]+"))throw new IllegalArgumentException("JOB_ID_INVALID");
        try(var c=Database.getConnection()){
            var columns=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())columns.add(r.getString("name"));}
            var names=List.of("product_id","nm_id","card_id","posting_number","supply_id","variant_id","offer_id","chrt_id","sku","gtin");
            var select=new ArrayList<String>();for(var name:names)select.add(columns.contains(name)?name:"'' AS "+name);
            try(var p=c.prepareStatement("SELECT "+String.join(",",select)+" FROM "+table+" WHERE rowid=? AND shop_id=?")){
                p.setLong(1,Long.parseLong(parts[1]));p.setInt(2,report.shopId());try(var r=p.executeQuery()){if(!r.next())return List.of();
                    String product=first(r,"product_id","nm_id","card_id","posting_number","supply_id");String variant=first(r,"variant_id","offer_id","chrt_id");
                    return List.of(new Line(product,variant,Objects.toString(r.getString("sku"),""),"","","",Objects.toString(r.getString("gtin"),""),report.state(),report.step(),report.error().isBlank()?"LEGACY_DETAIL_NOT_RECORDED":report.error()));
                }
            }
        }catch(SQLException e){throw new IllegalStateException("JOBS_READ_FAILED",e);}
    }
    public Report refresh(Report report){
        if(!report.detailed())return list().stream().filter(r->r.id().equals(report.id())).findFirst().orElse(report);
        return new SyncRunRepository().find(report.id()).map(r->new Report(r.id(),r.shopId(),report.shop(),r.marketplace(),r.kind(),r.state(),r.step(),r.total(),r.updated(),r.error(),true)).orElse(report);
    }
    private static String first(ResultSet r,String... names)throws SQLException{for(String name:names){String s=r.getString(name);if(s!=null&&!s.isBlank())return s;}return "";}
    public static String normalized(String source){
        String state=source.toUpperCase(Locale.ROOT);
        if(Set.of("SUCCESS","SUCCEEDED","PRINTED","ACCEPTED","PUBLISHED","RECONCILED","INTRODUCED","COMPLETED","DONE").contains(state))return "SUCCEEDED";
        if(state.equals("AWAITING_CONFIRMATION"))return "AWAITING_CONFIRMATION";
        if(state.equals("REMOTE_PROCESSING"))return "WAITING_REMOTE";
        if(state.equals("CANCELLED"))return "CANCELLED";
        if(state.contains("RECONCIL")||state.contains("UNKNOWN")||state.contains("UNCONFIRMED"))return "RECONCILING";
        if(state.contains("ERROR")||state.contains("FAIL")||state.contains("REJECT"))return "FAILED";
        if(state.equals("PDF_READY")||state.contains("REVIEW")||state.contains("NEEDS"))return "NEEDS_ACTION";
        if(state.equals("QUEUED")||state.equals("READY"))return "QUEUED";
        if(Set.of("RUNNING","SENDING","SIGNING","PROCESSING","PREPARED","PREPARING","VALIDATING","VALIDATED","CREATED","RESERVED","SET_PENDING","VERIFYING","FEED_SUBMITTED","DOWNLOADING_CODES","PDF_GENERATED","ATTRIBUTES_PENDING","GTIN_PENDING").contains(state))return "RUNNING";
        if(state.contains("WAIT")||state.contains("POLL"))return "WAITING_REMOTE";
        return "NEEDS_ACTION";
    }
}
