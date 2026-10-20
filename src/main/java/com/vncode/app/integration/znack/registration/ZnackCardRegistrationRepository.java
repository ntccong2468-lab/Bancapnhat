package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.SearchCriteria;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.RegistrationAction;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ZnackCardRegistrationRepository {
    /** Atomically owns the exact loaded snapshot before any remote mutation. */
    public boolean claim(int shopId, Sku sku, RegistrationAction action) {
        if (sku == null || action == null) return false;
        boolean allowed = switch (action) {
            case REGISTER -> sku.canRegister();
            case REQUEST_NEW_GTIN -> sku.needsGtinReview();
            case RESUME -> sku.canResume();
            case SIGN_APPROVED -> sku.canSignApproved();
            case REGISTER_NEW -> false; // Replacement claims require the preserved active record transaction.
        };
        if (!allowed) return false;
        boolean newRow = action == RegistrationAction.REGISTER && sku.status() == Status.NOT_CREATED
                && !sku.hasIdentity() && sku.registrationRevision().isBlank();
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,
                         status,created_at,updated_at)
                     SELECT ?,?,?,?,?,?,?,?
                     WHERE EXISTS(SELECT 1 FROM wb_product_sizes s JOIN shops sh ON sh.id=s.shop_id
                                  WHERE s.shop_id=? AND s.chrt_id=? AND s.nm_id=?
                                    AND sh.marketplace='WILDBERRIES')
                       AND (?=1 OR EXISTS(SELECT 1 FROM znack_card_registrations r
                                          WHERE r.shop_id=? AND r.chrt_id=?))
                     ON CONFLICT(shop_id,chrt_id) DO UPDATE SET status=excluded.status,error_message=NULL,
                         updated_at=excluded.updated_at
                     WHERE znack_card_registrations.nm_id=excluded.nm_id
                       AND znack_card_registrations.status=?
                       AND COALESCE(TRIM(znack_card_registrations.gtin),'')=?
                       AND COALESCE(TRIM(znack_card_registrations.feed_id),'')=?
                       AND COALESCE(znack_card_registrations.good_id,-1)=?
                       AND znack_card_registrations.wb_updated=?
                       AND znack_card_registrations.updated_at=?
                     """)) {
            int index=1;
            statement.setInt(index++,shopId); statement.setLong(index++,sku.chrtId());
            statement.setLong(index++,sku.nmId()); statement.setString(index++,sku.vendorCode());
            statement.setString(index++,sku.sourceBarcode());
            statement.setString(index++,action==RegistrationAction.RESUME&&sku.status()==Status.READY_TO_SUBMIT&&!sku.hasFeed()?Status.READY_TO_SUBMIT.name():Status.CHECKING.name());
            statement.setString(index++,now); statement.setString(index++,now);
            statement.setInt(index++,shopId); statement.setLong(index++,sku.chrtId());
            statement.setLong(index++,sku.nmId()); statement.setInt(index++,newRow?1:0);
            statement.setInt(index++,shopId); statement.setLong(index++,sku.chrtId());
            statement.setString(index++,sku.status().name()); statement.setString(index++,value(sku.gtin()).trim());
            statement.setString(index++,value(sku.feedId()).trim());
            statement.setLong(index++,sku.goodId()==null?-1:sku.goodId());
            statement.setInt(index++,sku.wbUpdated()?1:0); statement.setString(index,sku.registrationRevision());
            return statement.executeUpdate()==1;
        } catch(SQLException error) { throw new RuntimeException(error); }
    }

    public ZnackRegistrationScope scope(int shopId,long chrtId) {
        try(var c=Database.getConnection();var s=c.prepareStatement("SELECT participant_inn,environment,fingerprint FROM znack_card_registration_scopes WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1,shopId);s.setLong(2,chrtId);
            try(var r=s.executeQuery()){return r.next()?new ZnackRegistrationScope(r.getString(1),r.getString(2),r.getString(3)):null;}
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** Binding is insert-only; a historical request can never silently become another owner's. */
    public void bindScope(int shopId,long chrtId,ZnackRegistrationScope scope) {
        try(var c=Database.getConnection();var s=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,created_at) VALUES(?,?,?,?,?,?)")) {
            s.setInt(1,shopId);s.setLong(2,chrtId);s.setString(3,scope.inn());s.setString(4,scope.environment());s.setString(5,scope.fingerprint());s.setString(6,Instant.now().toString());s.executeUpdate();
        }catch(SQLException error){throw new RuntimeException(error);}
        if(!scope.equals(scope(shopId,chrtId)))throw new ZnackRegistrationScope.ScopeChangedException();
    }

    public boolean hasPendingReplacement(int shopId,long chrtId) {
        try(var c=Database.getConnection();var s=c.prepareStatement("SELECT 1 FROM znack_card_registration_replacements WHERE shop_id=? AND chrt_id=? AND state='CANDIDATE'")) {
            s.setInt(1,shopId);s.setLong(2,chrtId);try(var r=s.executeQuery()){return r.next();}
        }catch(SQLException error){throw new RuntimeException(error);}
    }
    public String ownerHash(int shopId,long chrtId) {
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT owner_hash FROM znack_card_registration_scopes WHERE shop_id=? AND chrt_id=?")){
            p.setInt(1,shopId);p.setLong(2,chrtId);try(var r=p.executeQuery()){return r.next()?r.getString(1):"";}
        }catch(SQLException unavailable){throw new IllegalStateException("Cannot check registration owner");}
    }
    public void bindScope(com.vncode.app.models.Shop shop,long chrtId,ZnackRegistrationScope scope) {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,created_at,owner_hash) VALUES(?,?,?,?,?,?,?)")){
            p.setInt(1,shop.getId());p.setLong(2,chrtId);p.setString(3,scope.inn());p.setString(4,scope.environment());p.setString(5,scope.fingerprint());p.setString(6,java.time.Instant.now().toString());p.setString(7,owner);p.executeUpdate();
        }catch(SQLException unavailable){throw new IllegalStateException("Cannot bind registration owner");}
        if(!scope.equals(scope(shop.getId(),chrtId))||!owner.equals(ownerHash(shop.getId(),chrtId)))throw new ZnackRegistrationScope.ScopeChangedException();
    }
    public boolean matchesPublished(int shopId,Sku sku) {
        try(var c=Database.getConnection();var s=c.prepareStatement("SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND nm_id=? AND status='PUBLISHED' AND gtin=? AND updated_at=?")) {
            s.setInt(1,shopId);s.setLong(2,sku.chrtId());s.setLong(3,sku.nmId());s.setString(4,sku.gtin());s.setString(5,sku.registrationRevision());
            try(var r=s.executeQuery()){return r.next();}
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** Explicit new-card review preserves the old card and all inventory/mappings. */
    public boolean claimReplacement(int shopId,Sku sku) {
        if(sku==null || !sku.canRegisterNew())return false;
        if(sku.status()==Status.ERROR)return claimRejectedReplacement(shopId,sku);
        try(var c=Database.getConnection();var transaction=c.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String now=Instant.now().toString();
                try(var s=c.prepareStatement("""
                    INSERT INTO znack_card_registration_replacements(shop_id,chrt_id,active_gtin,active_good_id,active_feed_id,active_payload_json,active_wb_updated,state,created_at,updated_at)
                    SELECT shop_id,chrt_id,gtin,good_id,feed_id,payload_json,wb_updated,'CANDIDATE',?,?
                    FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND nm_id=? AND status='PUBLISHED'
                      AND gtin=? AND updated_at=?
                    ON CONFLICT(shop_id,chrt_id) DO UPDATE SET active_gtin=excluded.active_gtin,active_good_id=excluded.active_good_id,
                      active_feed_id=excluded.active_feed_id,active_payload_json=excluded.active_payload_json,
                      active_wb_updated=excluded.active_wb_updated,state='CANDIDATE',updated_at=excluded.updated_at
                    WHERE znack_card_registration_replacements.state='ACTIVE'
                    """)) {
                    s.setString(1,now);s.setString(2,now);s.setInt(3,shopId);s.setLong(4,sku.chrtId());s.setLong(5,sku.nmId());s.setString(6,sku.gtin());s.setString(7,sku.registrationRevision());
                    if(s.executeUpdate()!=1){transaction.execute("ROLLBACK");return false;}
                }
                try(var s=c.prepareStatement("UPDATE znack_card_registrations SET gtin=NULL,good_id=NULL,feed_id=NULL,payload_json=NULL,status='CHECKING',wb_updated=0,error_message=NULL,updated_at=? WHERE shop_id=? AND chrt_id=?")) {
                    s.setString(1,now);s.setInt(2,shopId);s.setLong(3,sku.chrtId());s.executeUpdate();
                }
                try(var s=c.prepareStatement("DELETE FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=?")){s.setInt(1,shopId);s.setLong(2,sku.chrtId());s.executeUpdate();}
                transaction.execute("COMMIT");return true;
            }catch(SQLException|RuntimeException error){transaction.execute("ROLLBACK");throw error;}
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** Keep every old card; only a reviewed new group can clear a known submitted checkpoint. */
    public boolean claimGroupReplacement(int shopId,Sku sku,String tnved){return claimArchivedReplacement(shopId,sku,tnved,false);}
    /** Call only after a fresh remote read proves this exact feed was rejected. */
    public boolean claimRejectedReplacement(int shopId,Sku sku){return claimArchivedReplacement(shopId,sku,"",true);}
    private boolean claimArchivedReplacement(int shopId,Sku sku,String tnved,boolean rejectedRecovery) {
        if(rejectedRecovery&&(sku==null||sku.status()!=Status.ERROR))return false;
        if(sku==null||!sku.hasGtin()||!sku.hasFeed()||!TnvedGroupChange.replaceable(sku.status()))return false;
        try {return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            try(var p=c.prepareStatement("SELECT owner_hash FROM znack_card_registration_scopes WHERE shop_id=? AND chrt_id=?")) {
                p.setInt(1,shopId);p.setLong(2,sku.chrtId());try(var r=p.executeQuery()){if(r.next()&&!value(r.getString(1)).isBlank()&&!r.getString(1).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES)))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");}
            }
            String oldCode="";
            try(var p=c.prepareStatement("SELECT tnved,payload_json FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND nm_id=? AND gtin=? AND status=? AND updated_at=?")) {
                p.setInt(1,shopId);p.setLong(2,sku.chrtId());p.setLong(3,sku.nmId());p.setString(4,sku.gtin());p.setString(5,sku.status().name());p.setString(6,sku.registrationRevision());
                try(var r=p.executeQuery()){if(!r.next())return false;oldCode=value(r.getString(1));if(oldCode.isBlank())oldCode=TnvedGroupChange.payloadCode(r.getString(2));}
            }
            if(!rejectedRecovery&&!TnvedGroupChange.changed(oldCode,tnved))return false;
            try(var p=c.prepareStatement("SELECT 1 FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=? AND state IN ('SENDING','RECONCILE_REQUIRED')")) {
                p.setInt(1,shopId);p.setLong(2,sku.chrtId());try(var r=p.executeQuery()){if(r.next())return false;}
            }
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_history SELECT shop_id,chrt_id,nm_id,gtin,good_id,feed_id,payload_json,tnved,status,error_message,? FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?")) {
                p.setString(1,now);p.setInt(2,shopId);p.setLong(3,sku.chrtId());p.executeUpdate();
            }
            if(sku.status()==Status.PUBLISHED)try(var p=c.prepareStatement("""
                INSERT INTO znack_card_registration_replacements(shop_id,chrt_id,active_gtin,active_good_id,active_feed_id,active_payload_json,active_wb_updated,state,created_at,updated_at)
                SELECT shop_id,chrt_id,gtin,good_id,feed_id,payload_json,wb_updated,'CANDIDATE',?,? FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?
                ON CONFLICT(shop_id,chrt_id) DO UPDATE SET active_gtin=excluded.active_gtin,active_good_id=excluded.active_good_id,active_feed_id=excluded.active_feed_id,active_payload_json=excluded.active_payload_json,active_wb_updated=excluded.active_wb_updated,state='CANDIDATE',updated_at=excluded.updated_at
                WHERE znack_card_registration_replacements.state='ACTIVE'
                """)) {p.setString(1,now);p.setString(2,now);p.setInt(3,shopId);p.setLong(4,sku.chrtId());p.executeUpdate();}
            try(var p=c.prepareStatement("UPDATE znack_card_registrations SET gtin=NULL,good_id=NULL,feed_id=NULL,payload_json=NULL,status='CHECKING',wb_updated=0,error_message=NULL,updated_at=? WHERE shop_id=? AND chrt_id=?")) {
                p.setString(1,now);p.setInt(2,shopId);p.setLong(3,sku.chrtId());p.executeUpdate();
            }
            try(var p=c.prepareStatement("DELETE FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=?")){p.setInt(1,shopId);p.setLong(2,sku.chrtId());p.executeUpdate();}
            return true;
        });}catch(SQLException failed){throw new IllegalStateException("REGISTRATION_STORAGE",failed);}
    }

    public String activeGtin(int shopId,long chrtId) {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
            SELECT CASE WHEN p.state='CANDIDATE' THEN p.active_gtin WHEN r.status='PUBLISHED' THEN r.gtin ELSE '' END
            FROM znack_card_registrations r LEFT JOIN znack_card_registration_replacements p
              ON p.shop_id=r.shop_id AND p.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.chrt_id=?
            """)) {
            s.setInt(1,shopId);s.setLong(2,chrtId);try(var r=s.executeQuery()){return r.next()?value(r.getString(1)):"";}
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** One scoped read for large WB shipments; ambiguous aliases are excluded, never guessed. */
    public java.util.Map<String,String> activeGtinsByBarcode(int shopId) {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
            SELECT b.sku,CASE WHEN p.state='CANDIDATE' THEN p.active_gtin
                             WHEN r.status='PUBLISHED' THEN r.gtin ELSE '' END AS active_gtin
            FROM wb_product_size_skus b JOIN shops sh ON sh.id=b.shop_id AND sh.marketplace='WILDBERRIES'
            JOIN znack_card_registrations r ON r.shop_id=b.shop_id AND r.chrt_id=b.chrt_id
            LEFT JOIN znack_card_registration_replacements p ON p.shop_id=r.shop_id AND p.chrt_id=r.chrt_id
            WHERE b.shop_id=?
            """)) {
            s.setInt(1,shopId);var result=new java.util.LinkedHashMap<String,String>();var ambiguous=new java.util.HashSet<String>();
            try(var r=s.executeQuery()) {
                while(r.next()) {
                    String barcode=value(r.getString(1)),gtin=value(r.getString(2));if(barcode.isBlank() || gtin.isBlank())continue;
                    String prior=result.putIfAbsent(barcode,gtin);if(prior!=null && !prior.equals(gtin))ambiguous.add(barcode);
                }
            }
            com.vncode.app.features.kizmapping.VerifiedKizMappings.read(c,shopId,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES).forEach(result::putIfAbsent);
            ambiguous.forEach(result::remove);return java.util.Map.copyOf(result);
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** Call only after a fresh remote marketplace read proves the signed candidate is present. */
    public boolean completeReplacement(int shopId,long chrtId,String candidateGtin) {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
            UPDATE znack_card_registration_replacements SET state='ACTIVE',updated_at=?
            WHERE shop_id=? AND chrt_id=? AND state='CANDIDATE'
              AND EXISTS(SELECT 1 FROM znack_card_registrations r WHERE r.shop_id=? AND r.chrt_id=? AND r.status='PUBLISHED' AND r.gtin=?)
            """)) {
            s.setString(1,Instant.now().toString());s.setInt(2,shopId);s.setLong(3,chrtId);s.setInt(4,shopId);s.setLong(5,chrtId);s.setString(6,candidateGtin);return s.executeUpdate()==1;
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    public record SigningCheckpoint(String gtin, ZnackNationalCatalogService.SigningDocument document,String state) {}
    public SigningCheckpoint signingCheckpoint(int shopId,long chrtId) {
        try(var c=Database.getConnection();var s=c.prepareStatement("SELECT gtin,good_id,document_xml,document_sha256,state FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1,shopId);s.setLong(2,chrtId);try(var r=s.executeQuery()){return r.next()?new SigningCheckpoint(r.getString(1),new ZnackNationalCatalogService.SigningDocument(r.getLong(2),r.getBytes(3),r.getString(4)),r.getString(5)):null;}
        }catch(SQLException error){throw new RuntimeException(error);}
    }
    public void prepareSignature(int shopId,long chrtId,String gtin,ZnackNationalCatalogService.SigningDocument document) {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
            INSERT INTO znack_card_registration_signatures(shop_id,chrt_id,gtin,good_id,document_xml,document_sha256,state,created_at,updated_at)
            SELECT ?,?,?,?,?,?,'PREPARED',?,? WHERE EXISTS(SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND gtin=? AND status='READY_TO_SIGN' AND (good_id IS NULL OR good_id=?))
            ON CONFLICT(shop_id,chrt_id) DO NOTHING
            """)) {
            String now=Instant.now().toString();s.setInt(1,shopId);s.setLong(2,chrtId);s.setString(3,gtin);s.setLong(4,document.goodId());s.setBytes(5,document.xml());s.setString(6,document.sha256());s.setString(7,now);s.setString(8,now);s.setInt(9,shopId);s.setLong(10,chrtId);s.setString(11,gtin);s.setLong(12,document.goodId());s.executeUpdate();
        }catch(SQLException error){throw new RuntimeException(error);}
        var stored=signingCheckpoint(shopId,chrtId);
        if(stored==null || !stored.gtin().equals(gtin) || !stored.document().sha256().equals(document.sha256())) throw new IllegalStateException("Signing review changed. Refresh the card before continuing.");
    }
    public boolean claimSignatureSend(int shopId,long chrtId,String sha256) {
        try(var c=Database.getConnection();var s=c.prepareStatement("UPDATE znack_card_registration_signatures SET state='SENDING',updated_at=? WHERE shop_id=? AND chrt_id=? AND document_sha256=? AND state='PREPARED'")) {
            s.setString(1,Instant.now().toString());s.setInt(2,shopId);s.setLong(3,chrtId);s.setString(4,sha256);return s.executeUpdate()==1;
        }catch(SQLException error){throw new RuntimeException(error);}
    }
    public void finishSignature(int shopId,long chrtId,boolean confirmed) {
        try(var c=Database.getConnection();var s=c.prepareStatement("UPDATE znack_card_registration_signatures SET state=?,updated_at=? WHERE shop_id=? AND chrt_id=? AND (state='SENDING' OR (?=1 AND state='RECONCILE_REQUIRED'))")) {
            s.setString(1,confirmed?"CONFIRMED":"RECONCILE_REQUIRED");s.setString(2,Instant.now().toString());s.setInt(3,shopId);s.setLong(4,chrtId);s.setInt(5,confirmed?1:0);s.executeUpdate();
        }catch(SQLException error){throw new RuntimeException(error);}
    }

    /** Called only when the transport guard proves the signing POST was never dispatched. */
    public void releaseUnsentSignature(int shopId,long chrtId,String sha256){
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=? AND document_sha256=? AND state='SENDING'")){
            p.setInt(1,shopId);p.setLong(2,chrtId);p.setString(3,sha256);p.executeUpdate();
        }catch(SQLException error){throw new IllegalStateException("Cannot release unsent signing review",error);}
    }

    public Status checkpointStatus(int shopId,long chrtId) {
        return checkpoint(shopId,chrtId).status();
    }

    public record Checkpoint(Status status,boolean hasGtin,boolean hasFeed) { }

    public Checkpoint checkpoint(int shopId,long chrtId) {
        try(Connection c=Database.getConnection();PreparedStatement s=c.prepareStatement(
                "SELECT status,gtin,feed_id FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1,shopId);s.setLong(2,chrtId);
            try(ResultSet r=s.executeQuery()) {
                return r.next()?new Checkpoint(parseStatus(r.getString(1)),!value(r.getString(2)).isBlank(),
                        !value(r.getString(3)).isBlank()):new Checkpoint(Status.NOT_CREATED,false,false);
            }
        }catch(SQLException error){throw new RuntimeException(error);}
    }
    private static final String SELECT = """
            SELECT c.nm_id, s.chrt_id, COALESCE(c.subject_id, 0) AS subject_id,
                   c.vendor_code, c.subject_name, c.brand, c.title,
                   COALESCE(c.need_kiz, 0) AS need_kiz,
                   s.tech_size, s.wb_size,
                   GROUP_CONCAT(sku.sku, char(31)) AS barcodes,
                   p.c246x328_url, p.c516x688_url, p.square_url, p.big_url, p.hq_url, p.tm_url,
                   (SELECT COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$'))
                    FROM wb_product_characteristics ch
                    WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                      AND ch.name IN ('Цвет','цвет','Цвет товара','цвет товара','Color','color','Màu sắc','màu sắc')
                    ORDER BY CASE ch.characteristic_id WHEN 14177449 THEN 0 ELSE 1 END LIMIT 1) AS color_value,
                   r.gtin, r.good_id, r.feed_id, r.status, r.error_message,
                   r.updated_at AS registration_revision, COALESCE(r.wb_updated, 0) AS wb_updated
            FROM wb_product_cards c
            JOIN wb_product_sizes s ON s.shop_id=c.shop_id AND s.nm_id=c.nm_id
            LEFT JOIN wb_product_size_skus sku ON sku.shop_id=s.shop_id AND sku.chrt_id=s.chrt_id
            LEFT JOIN wb_product_photos p ON p.shop_id=c.shop_id AND p.nm_id=c.nm_id AND p.photo_index=0
            LEFT JOIN znack_card_registrations r ON r.shop_id=c.shop_id AND r.chrt_id=s.chrt_id
            WHERE c.shop_id=?
            """;

    public List<String> findSubjects(int shopId) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT subject_name FROM wb_product_cards
                     WHERE shop_id=? AND TRIM(COALESCE(subject_name, ''))<>''
                     ORDER BY subject_name COLLATE NOCASE
                     """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) values.add(result.getString(1));
                return values;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public List<Sku> search(SearchCriteria criteria) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(criteria.shopId());
        StringBuilder sql = new StringBuilder(SELECT);
        String query = value(criteria.query()).trim().toLowerCase(Locale.ROOT);
        if (!query.isBlank()) {
            sql.append(" AND (CAST(c.nm_id AS TEXT) LIKE ? OR LOWER(c.vendor_code) LIKE ? OR LOWER(c.title) LIKE ? OR EXISTS (SELECT 1 FROM wb_product_size_skus q WHERE q.shop_id=s.shop_id AND q.chrt_id=s.chrt_id AND LOWER(q.sku) LIKE ?))");
            String like = "%" + query + "%";
            Collections.addAll(parameters, like, like, like, like);
        }
        List<String> subjects = criteria.subjects() == null ? List.of() : criteria.subjects().stream()
                .filter(item -> item != null && !item.isBlank()).distinct().toList();
        if (!subjects.isEmpty()) {
            sql.append(" AND c.subject_name IN (")
                    .append(String.join(",", Collections.nCopies(subjects.size(), "?"))).append(")");
            parameters.addAll(subjects);
        }
        String status = value(criteria.status()).trim();
        if (!status.isBlank() && !"ALL".equals(status)) {
            if ("NOT_CREATED".equals(status)) sql.append(" AND r.status IS NULL");
            else if("GTIN_REVIEW_REQUIRED".equals(status)) {
                sql.append(" AND NULLIF(TRIM(r.gtin),'') IS NULL AND r.good_id IS NULL")
                        .append(" AND NULLIF(TRIM(r.feed_id),'') IS NULL AND COALESCE(r.wb_updated,0)=0")
                        .append(" AND r.status IN ('CHECKING','ALLOCATING_GTIN','GTIN_REVIEW_REQUIRED','ERROR')");
            }
            else if("FEED_REVIEW_REQUIRED".equals(status)) {
                sql.append(" AND NULLIF(TRIM(r.gtin),'') IS NOT NULL AND (r.status IN ('FEED_SUBMITTING','FEED_REVIEW_REQUIRED')")
                        .append(" OR (NULLIF(TRIM(r.feed_id),'') IS NULL AND r.status IN ('ERROR','GTIN_GENERATED','CHECKING')))");
            }
            else if ("IN_PROGRESS".equals(status)) {
                sql.append(" AND r.status NOT IN ('PUBLISHED','ERROR')");
            }
            else if ("COMPLETED".equals(status)) {
                sql.append(" AND r.status='PUBLISHED'");
            }
            else {
                sql.append(" AND r.status=?");
                parameters.add(status);
            }
        }
        sql.append(" GROUP BY c.shop_id,c.nm_id,s.chrt_id ORDER BY c.vendor_code COLLATE NOCASE,s.tech_size COLLATE NOCASE,c.nm_id LIMIT ? OFFSET ?");
        parameters.add(Math.max(1, criteria.limit()));
        parameters.add(Math.max(0, criteria.offset()));
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            try (ResultSet result = statement.executeQuery()) {
                List<Sku> values = new ArrayList<>();
                while (result.next()) values.add(map(result));
                return values;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }
    public Sku find(int shopId,long chrtId) {
        try(var c=Database.getConnection();var p=c.prepareStatement(SELECT+" AND s.chrt_id=? GROUP BY c.shop_id,c.nm_id,s.chrt_id")) {
            p.setInt(1,shopId);p.setLong(2,chrtId);try(var r=p.executeQuery()){return r.next()?map(r):null;}
        }catch(SQLException e){throw new IllegalStateException("REGISTRY_READ_FAILED",e);}
    }
    public List<Sku> pendingStatus(int shopId,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("REGISTRY_POLL_LIMIT");
        String sql=SELECT+" AND ((r.status IN ('FEED_SUBMITTED','PROCESSING','READY_TO_SIGN','SIGNING') OR r.status='ERROR' AND LOWER(TRIM(COALESCE(r.error_message,''))) IN ('registration interrupted','registration_interrupted','catalog_card_not_created_after_wait')) AND NULLIF(r.feed_id,'') IS NOT NULL OR r.status='READY_TO_SUBMIT' AND NULLIF(r.gtin,'') IS NOT NULL AND NULLIF(r.feed_id,'') IS NULL AND NULLIF(r.payload_json,'') IS NOT NULL) GROUP BY c.shop_id,c.nm_id,s.chrt_id ORDER BY CASE WHEN r.status='READY_TO_SIGN' THEN CASE WHEN EXISTS(SELECT 1 FROM znack_registration_signing_consents consent WHERE consent.shop_id=r.shop_id AND consent.marketplace='WILDBERRIES' AND consent.product_id=CAST(r.nm_id AS TEXT) AND consent.variant_id=CAST(r.chrt_id AS TEXT) AND consent.gtin=r.gtin AND consent.owner=? AND consent.enabled=1) THEN 0 ELSE 2 END ELSE 0 END,r.updated_at LIMIT ?";
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)){p.setInt(1,shopId);p.setString(2,com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES));p.setInt(3,limit);var rows=new ArrayList<Sku>();try(var r=p.executeQuery()){while(r.next())rows.add(map(r));}return List.copyOf(rows);}catch(SQLException e){throw new IllegalStateException("REGISTRY_READ_FAILED",e);}
    }
    public Instant submissionStarted(int shopId,Sku sku){
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT e.created_at FROM sync_run_events e JOIN sync_run_items i ON i.id=e.item_id JOIN sync_runs run ON run.id=i.run_id WHERE run.shop_id=? AND run.kind='znack_registration' AND i.variant_id=? AND i.gtin=? AND json_extract(i.evidence_json,'$.feedId')=? AND e.step='FEED_SUBMITTED' ORDER BY e.created_at LIMIT 1")){
            p.setInt(1,shopId);p.setString(2,Long.toString(sku.chrtId()));p.setString(3,sku.gtin());p.setString(4,sku.feedId());try(var r=p.executeQuery()){if(r.next())return parseStart(r.getString(1));}
            try(var old=c.prepareStatement("SELECT created_at FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND gtin=? AND feed_id=?")){
                old.setInt(1,shopId);old.setLong(2,sku.chrtId());old.setString(3,sku.gtin());old.setString(4,sku.feedId());try(var r=old.executeQuery()){return r.next()?parseStart(r.getString(1)):Instant.now();}
            }
        }catch(SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
    }
    private static Instant parseStart(String text){try{return Instant.parse(text);}catch(RuntimeException unknown){return Instant.now();}}
    /** Revisit old success labels which have no fresh, owner-bound publication observation. */
    public List<Sku> unconfirmedPublications(com.vncode.app.models.Shop shop,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("REGISTRY_POLL_LIMIT");
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        // Shops without a historical owner-bound success need no extra Catalog authentication.
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM znack_card_registrations r JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.status='PUBLISHED' AND NULLIF(r.feed_id,'') IS NOT NULL AND s.owner_hash=? LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,owner);try(var r=p.executeQuery()){if(!r.next())return List.of();}
        }catch(SQLException error){throw new IllegalStateException("REGISTRY_READ_FAILED",error);}
        String source=com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop);
        String sql=SELECT+" AND r.status='PUBLISHED' AND NULLIF(r.feed_id,'') IS NOT NULL AND EXISTS(SELECT 1 FROM znack_card_registration_scopes scope WHERE scope.shop_id=r.shop_id AND scope.chrt_id=r.chrt_id AND scope.owner_hash=?) AND NOT EXISTS(SELECT 1 FROM gtin_sync_catalog g WHERE g.shop_id=r.shop_id AND g.marketplace='WILDBERRIES' AND substr('00000000000000'||g.gtin,-14)=substr('00000000000000'||r.gtin,-14) AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1 AND json_extract(g.data_json,'$.owner')=? AND json_extract(g.data_json,'$.source')=? AND json_extract(g.data_json,'$.syncedAt')>=?) GROUP BY c.shop_id,c.nm_id,s.chrt_id ORDER BY r.updated_at LIMIT ?";
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)){
            p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,owner);p.setString(4,source);p.setString(5,Instant.now().minus(java.time.Duration.ofHours(24)).toString());p.setInt(6,limit);
            var rows=new ArrayList<Sku>();try(var r=p.executeQuery()){while(r.next())rows.add(map(r));}return List.copyOf(rows);
        }catch(SQLException error){throw new IllegalStateException("REGISTRY_READ_FAILED",error);}
    }

    public List<WbCharacteristic> characteristics(int shopId, long nmId) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT characteristic_id,name,value_json FROM wb_product_characteristics
                     WHERE shop_id=? AND nm_id=? ORDER BY characteristic_id
                     """)) {
            statement.setInt(1, shopId);
            statement.setLong(2, nmId);
            try (ResultSet result = statement.executeQuery()) {
                List<WbCharacteristic> values = new ArrayList<>();
                while (result.next()) values.add(new WbCharacteristic(result.getInt(1),
                        value(result.getString(2)), jsonValues(result.getString(3))));
                return values;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public Set<String> claimedGtins() {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT gtin FROM znack_card_registrations WHERE TRIM(COALESCE(gtin, ''))<>''
                     UNION SELECT active_gtin FROM znack_card_registration_replacements
                     UNION SELECT gtin FROM ozon_card_registrations WHERE gtin<>''
                     UNION SELECT active_gtin FROM ozon_card_registrations WHERE active_gtin<>''
                     UNION SELECT gtin FROM znack_card_registration_history WHERE gtin<>''
                     UNION SELECT gtin FROM ozon_card_registration_history WHERE gtin<>''
                     """)) {
            try (ResultSet result = statement.executeQuery()) {
                Set<String> values = new LinkedHashSet<>();
                while (result.next()) values.add(result.getString(1));
                return Set.copyOf(values);
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public void saveGenerated(int shopId, Sku sku, String gtin, String tnved, long categoryId,
                              String goodName, String payloadJson) {
        saveGenerated(shopId,sku,gtin,tnved,categoryId,goodName,payloadJson,Status.GTIN_GENERATED);
    }
    /** New checkpoints prove that dispatch has not begun, atomically with saving the allocated GTIN. */
    public void saveReadyToSubmitGenerated(int shopId,Sku sku,String gtin,String tnved,long categoryId,String goodName,String payloadJson) {
        saveGenerated(shopId,sku,gtin,tnved,categoryId,goodName,payloadJson,Status.READY_TO_SUBMIT);
    }
    private void saveGenerated(int shopId,Sku sku,String gtin,String tnved,long categoryId,String goodName,String payloadJson,Status checkpoint) {
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,
                         gtin,tnved,category_id,good_name,payload_json,status,created_at,updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?, ?,?)
                     ON CONFLICT(shop_id,chrt_id) DO UPDATE SET gtin=excluded.gtin,tnved=excluded.tnved,
                         category_id=excluded.category_id,good_name=excluded.good_name,payload_json=excluded.payload_json,
                         status=excluded.status,error_message=NULL,updated_at=excluded.updated_at
                    WHERE znack_card_registrations.status <> 'PUBLISHED'
                      AND (NULLIF(TRIM(znack_card_registrations.gtin),'') IS NULL
                           OR znack_card_registrations.gtin=excluded.gtin)
                     """)) {
            int index = 1;
            statement.setInt(index++, shopId);
            statement.setLong(index++, sku.chrtId());
            statement.setLong(index++, sku.nmId());
            statement.setString(index++, sku.vendorCode());
            statement.setString(index++, sku.sourceBarcode());
            statement.setString(index++, gtin);
            statement.setString(index++, tnved);
            statement.setLong(index++, categoryId);
            statement.setString(index++, goodName);
            statement.setString(index++, payloadJson);
            statement.setString(index++, checkpoint.name());
            statement.setString(index++, now);
            statement.setString(index, now);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Registration identity has changed or is already published.");
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    /** A server-confirmed rejected feed is retained before resetting its remote identity. */
    public boolean prepareRejectedRetry(com.vncode.app.models.Shop shop,Sku sku,String gtin,String oldFeed,Long rejectedGoodId,
            String tnved,long category,String name,String payload,String rejection){
        if(oldFeed==null||oldFeed.isBlank()||gtin==null||gtin.isBlank()||payload.length()>2*1024*1024)return false;
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(!com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("Listing owner changed");
            try(var p=c.prepareStatement("SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND nm_id=? AND gtin=? AND feed_id=? AND status<>'PUBLISHED'")){
                p.setInt(1,shop.getId());p.setLong(2,sku.chrtId());p.setLong(3,sku.nmId());p.setString(4,gtin);p.setString(5,oldFeed);try(var r=p.executeQuery()){if(!r.next())return false;}
            }
            try(var p=c.prepareStatement("SELECT 1 FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=? AND state IN ('SENDING','RECONCILE_REQUIRED')")){
                p.setInt(1,shop.getId());p.setLong(2,sku.chrtId());try(var r=p.executeQuery()){if(r.next())return false;}
            }
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_history SELECT shop_id,chrt_id,nm_id,gtin,COALESCE(?,good_id),feed_id,payload_json,tnved,'ERROR',?,? FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?")){
                if(rejectedGoodId==null)p.setNull(1,java.sql.Types.BIGINT);else p.setLong(1,rejectedGoodId);p.setString(2,CatalogFeedback.display(rejection));p.setString(3,now);p.setInt(4,shop.getId());p.setLong(5,sku.chrtId());p.executeUpdate();
            }
            try(var p=c.prepareStatement("UPDATE znack_card_registrations SET feed_id=NULL,good_id=COALESCE(?,good_id),tnved=?,category_id=?,good_name=?,payload_json=?,status='READY_TO_SUBMIT',error_message=NULL,updated_at=? WHERE shop_id=? AND chrt_id=?")){
                if(rejectedGoodId==null)p.setNull(1,java.sql.Types.BIGINT);else p.setLong(1,rejectedGoodId);
                p.setString(2,tnved);p.setLong(3,category);p.setString(4,name);p.setString(5,payload);p.setString(6,now);p.setInt(7,shop.getId());p.setLong(8,sku.chrtId());p.executeUpdate();
            }
            try(var p=c.prepareStatement("DELETE FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=?")){p.setInt(1,shop.getId());p.setLong(2,sku.chrtId());p.executeUpdate();}
            return true;
        });}catch(SQLException unavailable){throw new IllegalStateException("SYNC_STORAGE",unavailable);}
    }

    public void updateProgress(int shopId, long chrtId, Status status, String feedId, Long goodId,
                               String errorMessage, Boolean wbUpdated) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE znack_card_registrations SET status=?,feed_id=COALESCE(?,feed_id),
                         good_id=COALESCE(?,good_id),error_message=?,
                         wb_updated=COALESCE(?,wb_updated),updated_at=? WHERE shop_id=? AND chrt_id=? AND status<>'PUBLISHED'
                     """)) {
            connection.setAutoCommit(false);
            statement.setString(1, status.name());
            statement.setString(2, feedId);
            if (goodId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, goodId);
            statement.setString(4, errorMessage);
            if (wbUpdated == null) statement.setNull(5, java.sql.Types.INTEGER); else statement.setInt(5, wbUpdated ? 1 : 0);
            statement.setString(6, Instant.now().toString());
            statement.setInt(7, shopId);
            statement.setLong(8, chrtId);
            int changed=statement.executeUpdate();
            if(changed>0&&status==Status.PUBLISHED)com.vncode.app.features.sync.SyncStreamStore.request(connection,shopId,"ZNACK");
            connection.commit();
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }
    /** Correct a legacy PUBLISHED label only after an authoritative read of this exact feed.
     * Preserve every remote identity and the signature checkpoint; never allocate another GTIN. */
    public boolean reconcilePublication(com.vncode.app.models.Shop shop,Sku saved,Status actual,String error){
        if(!Set.of(Status.PROCESSING,Status.READY_TO_SIGN,Status.SIGNING,Status.ERROR).contains(actual)
                ||saved.status()!=Status.PUBLISHED||!saved.hasFeed()||!saved.hasGtin())throw new IllegalArgumentException("CATALOG_RECONCILIATION_INVALID");
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            try(var p=c.prepareStatement("UPDATE znack_card_registrations SET status=?,error_message=?,updated_at=? WHERE shop_id=? AND nm_id=? AND chrt_id=? AND status='PUBLISHED' AND gtin=? AND feed_id=? AND updated_at=?")){
                p.setString(1,actual.name());p.setString(2,error);p.setString(3,Instant.now().toString());p.setInt(4,shop.getId());p.setLong(5,saved.nmId());p.setLong(6,saved.chrtId());p.setString(7,saved.gtin());p.setString(8,saved.feedId());p.setString(9,saved.registrationRevision());return p.executeUpdate()==1;
            }
        });}catch(SQLException unavailable){throw new IllegalStateException("SYNC_STORAGE",unavailable);}
    }

    public String payload(int shopId, long chrtId) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT payload_json FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?
                     """)) {
            statement.setInt(1, shopId);
            statement.setLong(2, chrtId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? value(result.getString(1)) : "";
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    private static Sku map(ResultSet result) throws SQLException {
        String status = result.getString("status");
        return new Sku(result.getLong("nm_id"), result.getLong("chrt_id"), result.getInt("subject_id"),
                result.getString("vendor_code"),
                result.getString("subject_name"), result.getString("brand"), result.getString("title"),
                result.getString("color_value"), preferredSize(result.getString("tech_size"), result.getString("wb_size")),
                split(result.getString("barcodes")), first(result.getString("c516x688_url"), result.getString("big_url"),
                result.getString("c246x328_url"), result.getString("square_url"), result.getString("hq_url"), result.getString("tm_url")),
                result.getInt("need_kiz") != 0, result.getString("gtin"), nullableLong(result, "good_id"),
                result.getString("feed_id"), parseStatus(status),
                result.getString("error_message"), result.getInt("wb_updated") != 0,
                result.getString("wb_size"), result.getString("registration_revision"));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Status parseStatus(String value) {
        if (value == null || value.isBlank()) return Status.NOT_CREATED;
        try { return Status.valueOf(value); }
        catch (IllegalArgumentException ignored) { return Status.ERROR; }
    }

    private static List<String> jsonValues(String json) {
        try {
            JsonElement element = JsonParser.parseString(json == null ? "null" : json);
            List<String> values = new ArrayList<>();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(item -> values.add(item.isJsonPrimitive() ? item.getAsString() : item.toString()));
            else if (element.isJsonPrimitive()) values.add(element.getAsString());
            return values;
        } catch (RuntimeException ignored) {
            return List.of(value(json));
        }
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(String.valueOf((char) 31)));
    }

    private static String first(String... values) {
        for (String item : values) if (item != null && !item.isBlank()) return item;
        return "";
    }


    private static String preferredSize(String techSize, String wbSize) {
        String technical = value(techSize).trim();
        if (!technical.isBlank() && !"0".equals(technical)) return technical;
        return first(wbSize, technical);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
