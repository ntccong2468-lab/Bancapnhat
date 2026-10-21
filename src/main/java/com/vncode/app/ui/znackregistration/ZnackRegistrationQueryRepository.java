package com.vncode.app.ui.znackregistration;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.sync.RegistrationCardIndex;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Read-only, bounded registration queries. Filtering/counting never depends on loaded FX rows. */
public final class ZnackRegistrationQueryRepository {
    public static final int PAGE_SIZE = 300;
    public static final int MAX_SELECTION = 100_000;
    public record Details(String gender,String tnved,boolean pendingReplacement,boolean groupChanged) {
        public Details(String gender,String tnved){this(gender,tnved,false,false);}
        public Details(String gender,String tnved,boolean pending){this(gender,tnved,pending,false);}
    }
    public record Summary(long total,long published,long errors,long readyToSign,long waiting,
                          long catalog,long catalogPublished,long catalogUnlinked,Instant syncedAt) {}
    public record Page(List<Sku> rows,Map<Long,Details> details,long filtered,Summary summary) {}
    private record Query(String where,List<Object> parameters) {}
    private final String receipt;
    private final com.vncode.app.features.sync.RegistrationCardIndex observations;
    public ZnackRegistrationQueryRepository(){this("");}
    public ZnackRegistrationQueryRepository(String receipt){this(receipt,null);}
    public ZnackRegistrationQueryRepository(String receipt,com.vncode.app.features.sync.RegistrationCardIndex observations){this.receipt=receipt==null?"":receipt;this.observations=observations;}
    private static final String FROM = """
            FROM wb_product_cards c
            JOIN wb_product_sizes s ON s.shop_id=c.shop_id AND s.nm_id=c.nm_id
            LEFT JOIN znack_card_registrations r ON r.shop_id=s.shop_id AND r.chrt_id=s.chrt_id
            WHERE c.shop_id=?
            """;
    private static final String SELECT = """
            SELECT c.nm_id,s.chrt_id,COALESCE(c.subject_id,0) subject_id,c.vendor_code,c.subject_name,
                   c.brand,c.title,COALESCE(c.need_kiz,0) need_kiz,s.tech_size,s.wb_size,
                   (SELECT GROUP_CONCAT(q.sku,char(31)) FROM wb_product_size_skus q
                     WHERE q.shop_id=s.shop_id AND q.chrt_id=s.chrt_id) barcodes,
                   (SELECT COALESCE(NULLIF(p.c516x688_url,''),NULLIF(p.big_url,''),NULLIF(p.c246x328_url,''),
                          NULLIF(p.square_url,''),NULLIF(p.hq_url,''),p.tm_url)
                     FROM wb_product_photos p WHERE p.shop_id=c.shop_id AND p.nm_id=c.nm_id AND p.photo_index=0) image_url,
                   (SELECT ch.value_json FROM wb_product_characteristics ch
                    WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                      AND ch.name IN ('Цвет','цвет','Цвет товара','цвет товара','Color','color','Màu sắc','màu sắc')
                    ORDER BY CASE ch.characteristic_id WHEN 14177449 THEN 0 ELSE 1 END LIMIT 1) color_json,
                   r.tnved tnved_json,
                   (SELECT own.payload FROM product_snapshots own WHERE own.shop_id=c.shop_id AND own.marketplace='WILDBERRIES' AND own.card_id=CAST(c.nm_id AS TEXT)
                     AND json_extract(own.payload,'$.revision') LIKE 'live:%' AND json_extract(own.payload,'$.metadata.owner')=(SELECT scope.owner_hash FROM znack_card_registration_scopes scope WHERE scope.shop_id=s.shop_id AND scope.chrt_id=s.chrt_id)
                     ORDER BY own.checked_at DESC LIMIT 1) current_snapshot,
                   (SELECT ch.value_json FROM wb_product_characteristics ch WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id AND (LOWER(ch.name) LIKE '%tnved%' OR REPLACE(ch.name,' ','') LIKE '%ТНВЭД%' OR REPLACE(ch.name,' ','') LIKE '%Тнвэд%') ORDER BY ch.characteristic_id LIMIT 1) current_tnved_json,
                   r.gtin,r.good_id,r.feed_id,r.status,r.error_message,COALESCE(r.wb_updated,0) wb_updated,
                   r.updated_at registration_revision,
                   EXISTS(SELECT 1 FROM znack_card_registration_replacements replacement
                          WHERE replacement.shop_id=s.shop_id AND replacement.chrt_id=s.chrt_id AND replacement.state='CANDIDATE') pending_replacement
            """;

    public Page page(SearchCriteria criteria) {
        if(observations!=null&&!safe(criteria.status()).isBlank()&&!"ALL".equals(criteria.status()))return observedPage(criteria);
        Query query=filter(criteria);
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);requireOwner(c,criteria.shopId());
            long total=count(c,query);
            var rows=new ArrayList<Sku>();var details=new LinkedHashMap<Long,Details>();
            var params=new ArrayList<>(query.parameters());params.add(Math.min(PAGE_SIZE,Math.max(1,criteria.limit())));params.add(Math.max(0,criteria.offset()));
            try(var p=prepare(c,SELECT+query.where()+" ORDER BY c.vendor_code COLLATE NOCASE,s.tech_size COLLATE NOCASE,c.nm_id,s.chrt_id LIMIT ? OFFSET ?",params);
                var r=p.executeQuery()) {
                while(r.next()) {
                    Sku row=map(r);rows.add(row);
                    details.put(row.chrtId(),new Details("",currentTnved(r),r.getBoolean("pending_replacement"),groupChanged(r)));
                }
            }
            loadDetails(c,criteria.shopId(),rows,details);
            Summary summary=summary(c,criteria.shopId());c.commit();
            return new Page(List.copyOf(rows),Map.copyOf(details),total,summary);
        }catch(SQLException error){throw new IllegalStateException("registration_read_error",error);}
    }

    /** Freeze exact owned revisions off the FX thread. Repository claims recheck each before mutation. */
    public List<Sku> allEligible(SearchCriteria criteria) {return all(criteria,0);}
    public List<Sku> allSignable(SearchCriteria criteria) {return all(criteria,1);}
    public List<Sku> allSelectable(SearchCriteria criteria){return all(criteria,2);}
    public List<Sku> allReplaceable(SearchCriteria criteria){return all(criteria,3);}
    private List<Sku> all(SearchCriteria criteria,int mode) {
        Query query=filter(criteria);var rows=new ArrayList<Sku>();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);requireOwner(c,criteria.shopId());
            if(count(c,query)>MAX_SELECTION)throw new IllegalArgumentException("registration_selection_limit");
            try(var p=prepare(c,SELECT+query.where()+" ORDER BY c.vendor_code COLLATE NOCASE,s.tech_size COLLATE NOCASE,c.nm_id,s.chrt_id",query.parameters());var r=p.executeQuery()) {
                while(r.next()) {Sku row=map(r);if(!matches(criteria,row))continue;var view=observations==null?null:view(row);boolean change=groupChanged(r);boolean register=!row.canReviewExistingDraft()&&(row.canRegister()||row.canResume()||row.needsGtinReview())||change;boolean replace=row.canRegisterNew()&&(!r.getBoolean("pending_replacement")||row.status()==Status.ERROR);
                    if(view!=null){register=view.canReviewDraft()&&!row.hasFeed()||register&&!view.blocked()&&(!view.published()||change);replace=replace&&!view.blocked()&&(view.published()||row.status()==Status.ERROR);}if(switch(mode){case 1->row.canSignApproved()&&(view==null||(!view.blocked()&&!view.published()));case 2->register||replace;case 3->replace;default->register;})rows.add(row);}
            }
            c.commit();return List.copyOf(rows);
        }catch(SQLException error){throw new IllegalStateException("registration_read_error",error);}
    }

    private Query filter(SearchCriteria criteria) {
        var params=new ArrayList<Object>();params.add(criteria.shopId());var sql=new StringBuilder(FROM);
        if(!receipt.isBlank()){sql.append(" AND EXISTS(SELECT 1 FROM sync_run_items member JOIN sync_runs source ON source.id=member.run_id WHERE source.id=? AND source.state='SUCCEEDED' AND source.shop_id=c.shop_id AND member.product_id=CAST(c.nm_id AS TEXT) AND member.variant_id=CAST(s.chrt_id AS TEXT))");params.add(receipt);}
        String query=safe(criteria.query()).trim().toLowerCase(Locale.ROOT);
        if(!query.isBlank()) {
            sql.append(" AND (CAST(c.nm_id AS TEXT) LIKE ? OR LOWER(c.vendor_code) LIKE ? OR LOWER(c.title) LIKE ? OR LOWER(r.gtin) LIKE ? OR EXISTS(SELECT 1 FROM wb_product_size_skus q WHERE q.shop_id=s.shop_id AND q.chrt_id=s.chrt_id AND LOWER(q.sku) LIKE ?))");
            Collections.addAll(params,"%"+query+"%","%"+query+"%","%"+query+"%","%"+query+"%","%"+query+"%");
        }
        var subjects=criteria.subjects()==null?List.<String>of():criteria.subjects().stream().filter(v->v!=null&&!v.isBlank()).distinct().toList();
        if(!subjects.isEmpty()) {sql.append(" AND c.subject_name IN (").append(String.join(",",Collections.nCopies(subjects.size(),"?"))).append(")");params.addAll(subjects);}
        String status=observations==null?safe(criteria.status()):"ALL";
        switch(status) {
            case "","ALL" -> {}
            case "NOT_CREATED" -> sql.append(" AND r.good_id IS NULL AND NULLIF(TRIM(r.feed_id),'') IS NULL AND COALESCE(r.wb_updated,0)=0 AND ((COALESCE(r.status,'NOT_CREATED') IN ('NOT_CREATED','PREFLIGHT_ERROR','CHECKING') AND NULLIF(TRIM(r.gtin),'') IS NULL) OR r.status IN ('READY_TO_SUBMIT','PRE_SUBMIT_ERROR'))");
            case "GTIN_REVIEW_REQUIRED" -> sql.append(" AND NULLIF(TRIM(r.gtin),'') IS NULL AND r.good_id IS NULL AND NULLIF(TRIM(r.feed_id),'') IS NULL AND COALESCE(r.wb_updated,0)=0 AND r.status IN ('CHECKING','ALLOCATING_GTIN','GTIN_REVIEW_REQUIRED','ERROR')");
            case "FEED_REVIEW_REQUIRED" -> sql.append(" AND NULLIF(TRIM(r.gtin),'') IS NOT NULL AND (r.status IN ('FEED_SUBMITTING','FEED_REVIEW_REQUIRED') OR (NULLIF(TRIM(r.feed_id),'') IS NULL AND r.status IN ('ERROR','GTIN_GENERATED','CHECKING')))");
            case "IN_PROGRESS" -> sql.append(" AND NULLIF(TRIM(r.feed_id),'') IS NOT NULL AND r.status IN ('FEED_SUBMITTED','PROCESSING','READY_TO_SIGN','SIGNING')");
            case "ERROR" -> sql.append(" AND r.status='ERROR' AND (r.good_id IS NOT NULL OR NULLIF(TRIM(r.feed_id),'') IS NOT NULL)");
            case "COMPLETED" -> sql.append(" AND r.status='PUBLISHED'");
            default -> {sql.append(" AND r.status=?");params.add(status);}
        }
        return new Query(sql.toString(),List.copyOf(params));
    }

    private com.vncode.app.features.sync.RegistrationCardIndex.View view(Sku row){return observations.view(row.status(),row.gtin(),row.barcodes(),row.vendorCode(),row.color(),row.size(),row.wbSize(),observations.ownsWb(row.chrtId()));}
    private boolean matches(SearchCriteria criteria,Sku row){
        if(observations==null)return true;var state=view(row);
        boolean onMarketplace=state.published()&&com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(row.barcodes(),state.gtin());
        return switch(safe(criteria.status())){
            case "", "ALL" -> true;
            case "COMPLETED" -> onMarketplace;
            case "NOT_CREATED" -> !state.published()&&!state.blocked()&&RegistrationCardIndex.notSubmitted(row);
            case "IN_PROGRESS" -> RegistrationCardIndex.awaitingCatalog(state,row.status(),row.feedId())
                    ||!onMarketplace&&!state.blocked()&&state.published();
            case "ERROR" -> RegistrationCardIndex.rejectedCard(state,row.status(),row.goodId(),row.feedId());
            case "GTIN_REVIEW_REQUIRED" -> !state.blocked()&&!state.published()&&row.needsGtinReview();
            case "FEED_REVIEW_REQUIRED" -> !state.blocked()&&!state.published()&&row.needsFeedReview();
            default -> state.status().equals("status."+criteria.status().toLowerCase(Locale.ROOT));
        };
    }
    /** Effective status filtering stays off FX and never expands the visible page beyond 300 rows. */
    private Page observedPage(SearchCriteria criteria){
        Query query=filter(criteria);var rows=new ArrayList<Sku>();var details=new LinkedHashMap<Long,Details>();long total=0,scanned=0;
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);requireOwner(c,criteria.shopId());
            try(var p=prepare(c,SELECT+query.where()+" ORDER BY c.vendor_code COLLATE NOCASE,s.tech_size COLLATE NOCASE,c.nm_id,s.chrt_id",query.parameters());var result=p.executeQuery()){
                while(result.next()){
                    if(++scanned>MAX_SELECTION)throw new IllegalArgumentException("registration_selection_limit");
                    Sku row=map(result);if(!matches(criteria,row))continue;
                    if(total++>=Math.max(0,criteria.offset())&&rows.size()<Math.min(PAGE_SIZE,Math.max(1,criteria.limit()))){rows.add(row);details.put(row.chrtId(),new Details("",currentTnved(result),result.getBoolean("pending_replacement"),groupChanged(result)));}
                }
            }
            loadDetails(c,criteria.shopId(),rows,details);var summary=summary(c,criteria.shopId());c.commit();return new Page(List.copyOf(rows),Map.copyOf(details),total,summary);
        }catch(SQLException error){throw new IllegalStateException("registration_read_error",error);}
    }

    private static void loadDetails(Connection c,int shopId,List<Sku> rows,Map<Long,Details> target)throws SQLException {
        var ids=rows.stream().map(Sku::nmId).distinct().toList();if(ids.isEmpty())return;
        var parameters=new ArrayList<Object>();parameters.add(shopId);parameters.addAll(ids);
        var byProduct=new HashMap<Long,Details>();
        try(var p=prepare(c,"SELECT nm_id,name,value_json FROM wb_product_characteristics WHERE shop_id=? AND nm_id IN ("
                +String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY characteristic_id",parameters);var r=p.executeQuery()) {
            while(r.next()) {
                long id=r.getLong(1);String name=safe(r.getString(2)).toLowerCase(Locale.ROOT);var previous=byProduct.getOrDefault(id,new Details("",""));
                String gender=previous.gender(),tnved=previous.tnved();String value=jsonText(r.getString(3));
                if(gender.isBlank()&&(name.equals("пол")||name.contains("gender")||name.contains("giới tính")))gender=value;
                if(tnved.isBlank()&&(name.contains("тн вэд")||name.contains("тнвэд")||name.contains("tnved")))tnved=value;
                byProduct.put(id,new Details(gender,tnved));
            }
        }
        for(Sku row:rows) {var fetched=byProduct.getOrDefault(row.nmId(),new Details("",""));var saved=target.get(row.chrtId());
            target.put(row.chrtId(),new Details(fetched.gender(),saved.tnved().isBlank()?fetched.tnved():saved.tnved(),saved.pendingReplacement(),saved.groupChanged()));
        }
    }

    private static long count(Connection c,Query query)throws SQLException {
        try(var p=prepare(c,"SELECT COUNT(*) "+query.where(),query.parameters());var r=p.executeQuery()){return r.next()?r.getLong(1):0;}
    }
    private Summary summary(Connection c,int shopId)throws SQLException {
        long total=0,published=0,errors=0,ready=0,waiting=0,catalog=0,catalogPublished=0,unlinked=0;Instant synced=null;
        var scope=filter(new SearchCriteria(shopId,"",List.of(),"ALL",PAGE_SIZE,0));
        if(observations!=null){
            try(var p=prepare(c,SELECT+scope.where(),scope.parameters());var rows=p.executeQuery()){
                while(rows.next()){
                    var row=map(rows);var state=view(row);total++;
                    if(state.published())published++;
                    else if(RegistrationCardIndex.awaitingCatalog(state,row.status(),row.feedId())){if(row.status()==Status.READY_TO_SIGN)ready++;else waiting++;}
                    else if(RegistrationCardIndex.rejectedCard(state,row.status(),row.goodId(),row.feedId()))errors++;
                    else if(RegistrationCardIndex.confirmedSubmission(row.status(),row.feedId())) {
                        if(row.status()==Status.READY_TO_SIGN)ready++;else waiting++;
                    }
                }
            }
        }else{
        try(var p=prepare(c,"SELECT COALESCE(r.status,'NOT_CREATED') state,COUNT(*) n,NULLIF(TRIM(r.feed_id),'') IS NOT NULL has_feed,NULLIF(TRIM(r.gtin),'') IS NOT NULL has_gtin,r.good_id IS NOT NULL has_good "+scope.where()+" GROUP BY state,has_feed,has_gtin,has_good",scope.parameters());var r=p.executeQuery()) {
            while(r.next()) {long n=r.getLong(2);String state=r.getString(1);total+=n;Status status;
                try{status=Status.valueOf(state);}catch(IllegalArgumentException invalid){status=Status.ERROR;}
                if(status==Status.PUBLISHED)published+=n;
                else if(status==Status.ERROR&&(r.getBoolean("has_feed")||r.getBoolean("has_good")))errors+=n;
                else if(RegistrationCardIndex.confirmedSubmission(status,r.getBoolean("has_feed")?"accepted":"")) {
                    if(status==Status.READY_TO_SIGN)ready+=n;else waiting+=n;
                }
            }
        }
        }
        try(var p=prepare(c,"""
                SELECT g.data_json,
                  EXISTS(SELECT 1 FROM znack_card_registrations r WHERE r.shop_id=g.shop_id AND LTRIM(r.gtin,'0')=LTRIM(g.gtin,'0'))
                   OR EXISTS(SELECT 1 FROM wb_product_size_skus q WHERE q.shop_id=g.shop_id AND LTRIM(q.sku,'0')=LTRIM(g.gtin,'0'))
                   OR EXISTS(SELECT 1 FROM gtin_sync_mappings m WHERE m.shop_id=g.shop_id AND m.marketplace=g.marketplace AND LTRIM(m.gtin,'0')=LTRIM(g.gtin,'0')) linked
                FROM gtin_sync_catalog g WHERE g.shop_id=? AND g.marketplace='WILDBERRIES'
                """,List.of(shopId));var r=p.executeQuery()) {
            while(r.next()) {
                catalog++;if(!r.getBoolean(2))unlinked++;
                try {var row=JsonParser.parseString(r.getString(1)).getAsJsonObject();if(row.has("published")&&row.get("published").getAsBoolean())catalogPublished++;
                    Instant at=Instant.parse(row.get("syncedAt").getAsString());if(synced==null||at.isAfter(synced))synced=at;
                }catch(RuntimeException ignored) { /* Invalid cached metadata remains unverified. */ }
            }
        }
        return new Summary(total,published,errors,ready,waiting,catalog,catalogPublished,unlinked,synced);
    }
    private static void requireOwner(Connection c,int shopId)throws SQLException {
        try(var p=prepare(c,"SELECT 1 FROM shops WHERE id=? AND marketplace='WILDBERRIES'",List.of(shopId));var r=p.executeQuery()) {
            if(!r.next())throw new IllegalArgumentException("shop_mismatch");
        }
    }
    private static PreparedStatement prepare(Connection c,String sql,List<?> parameters)throws SQLException {
        var p=c.prepareStatement(sql);for(int i=0;i<parameters.size();i++)p.setObject(i+1,parameters.get(i));return p;
    }
    private static String currentTnved(ResultSet row)throws SQLException {
        String snapshot=com.vncode.app.integration.znack.registration.TnvedGroupChange.snapshotCode(row.getString("current_snapshot"));
        if(!snapshot.isBlank())return snapshot;String nativeValue=jsonText(row.getString("current_tnved_json"));return nativeValue.isBlank()?jsonText(row.getString("tnved_json")):nativeValue;
    }
    private static boolean groupChanged(ResultSet row)throws SQLException {
        Status status;try{status=Status.valueOf(safe(row.getString("status")));}catch(RuntimeException invalid){return false;}
        return com.vncode.app.integration.znack.registration.TnvedGroupChange.replaceable(status)&&!safe(row.getString("feed_id")).isBlank()&&com.vncode.app.integration.znack.registration.TnvedGroupChange.changed(jsonText(row.getString("tnved_json")),currentTnved(row));
    }
    private static Sku map(ResultSet r)throws SQLException {
        String technical=safe(r.getString("tech_size"));String size=technical.isBlank()||"0".equals(technical)?safe(r.getString("wb_size")):technical;
        long goodId=r.getLong("good_id");Long good=r.wasNull()?null:goodId;Status status;
        try{status=Status.valueOf(safe(r.getString("status")));}catch(IllegalArgumentException ignored){status=r.getString("status")==null?Status.NOT_CREATED:Status.ERROR;}
        String barcodes=safe(r.getString("barcodes"));
        return new Sku(r.getLong("nm_id"),r.getLong("chrt_id"),r.getInt("subject_id"),r.getString("vendor_code"),r.getString("subject_name"),r.getString("brand"),r.getString("title"),jsonText(r.getString("color_json")),size,
                barcodes.isBlank()?List.of():List.of(barcodes.split(String.valueOf((char)31))),r.getString("image_url"),r.getBoolean("need_kiz"),r.getString("gtin"),good,r.getString("feed_id"),status,r.getString("error_message"),r.getBoolean("wb_updated"),r.getString("wb_size"),r.getString("registration_revision"),groupChanged(r));
    }
    private static String jsonText(String text) {
        if(text==null)return "";
        try {var json=JsonParser.parseString(text);if(json.isJsonArray())return json.getAsJsonArray().isEmpty()?"":json.getAsJsonArray().get(0).getAsString();if(json.isJsonPrimitive())return json.getAsString();}
        catch(RuntimeException ignored){}
        return text;
    }
    private static String safe(String value){return value==null?"":value;}
}
