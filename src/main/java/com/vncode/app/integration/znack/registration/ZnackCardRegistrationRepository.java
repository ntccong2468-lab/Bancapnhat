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
                     SELECT ?,?,?,?,?,'CHECKING',?,?
                     WHERE EXISTS(SELECT 1 FROM wb_product_sizes s JOIN shops sh ON sh.id=s.shop_id
                                  WHERE s.shop_id=? AND s.chrt_id=? AND s.nm_id=?
                                    AND sh.marketplace='WILDBERRIES')
                       AND (?=1 OR EXISTS(SELECT 1 FROM znack_card_registrations r
                                          WHERE r.shop_id=? AND r.chrt_id=?))
                     ON CONFLICT(shop_id,chrt_id) DO UPDATE SET status='CHECKING',error_message=NULL,
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
                      AND ch.characteristic_id IN (14177449, 204557)
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
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,
                         gtin,tnved,category_id,good_name,payload_json,status,created_at,updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,'GTIN_GENERATED',?,?)
                     ON CONFLICT(shop_id,chrt_id) DO UPDATE SET gtin=excluded.gtin,tnved=excluded.tnved,
                         category_id=excluded.category_id,good_name=excluded.good_name,payload_json=excluded.payload_json,
                         status='GTIN_GENERATED',error_message=NULL,updated_at=excluded.updated_at
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
            statement.setString(index++, now);
            statement.setString(index, now);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Registration identity has changed or is already published.");
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public void updateProgress(int shopId, long chrtId, Status status, String feedId, Long goodId,
                               String errorMessage, Boolean wbUpdated) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE znack_card_registrations SET status=?,feed_id=COALESCE(?,feed_id),
                         good_id=COALESCE(?,good_id),error_message=?,
                         wb_updated=COALESCE(?,wb_updated),updated_at=? WHERE shop_id=? AND chrt_id=? AND status<>'PUBLISHED'
                     """)) {
            statement.setString(1, status.name());
            statement.setString(2, feedId);
            if (goodId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, goodId);
            statement.setString(4, errorMessage);
            if (wbUpdated == null) statement.setNull(5, java.sql.Types.INTEGER); else statement.setInt(5, wbUpdated ? 1 : 0);
            statement.setString(6, Instant.now().toString());
            statement.setInt(7, shopId);
            statement.setLong(8, chrtId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
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
