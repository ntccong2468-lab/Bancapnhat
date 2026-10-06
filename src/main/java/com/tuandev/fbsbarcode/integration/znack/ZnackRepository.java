package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class ZnackRepository {
    private static final Gson GSON = new Gson();
    private static final String SETTINGS_UPSERT = """
            INSERT INTO znack_settings(shop_id,true_api_base_url,suz_base_url,oms_id,oms_connection,participant_inn,
            producer_inn,owner_inn,signer_executable,signer_certificate,signer_arguments_json,document_number,
            document_date,pdf_folder,auto_introduction,certificate_list_executable,certificate_list_arguments_json,
            certificate_metadata_json,signer_tested_at,certmgr_path,cryptcp_path,csptest_path,cryptopro_timeout_seconds,
            document_expiry_date,document_type,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(shop_id) DO UPDATE SET true_api_base_url=excluded.true_api_base_url,suz_base_url=excluded.suz_base_url,
            oms_id=excluded.oms_id,oms_connection=excluded.oms_connection,participant_inn=excluded.participant_inn,
            producer_inn=excluded.producer_inn,owner_inn=excluded.owner_inn,signer_executable=excluded.signer_executable,
            signer_certificate=excluded.signer_certificate,signer_arguments_json=excluded.signer_arguments_json,
            document_number=excluded.document_number,document_date=excluded.document_date,pdf_folder=excluded.pdf_folder,
            auto_introduction=excluded.auto_introduction,certificate_list_executable=excluded.certificate_list_executable,
            certificate_list_arguments_json=excluded.certificate_list_arguments_json,certificate_metadata_json=excluded.certificate_metadata_json,
            signer_tested_at=excluded.signer_tested_at,certmgr_path=excluded.certmgr_path,cryptcp_path=excluded.cryptcp_path,
            csptest_path=excluded.csptest_path,cryptopro_timeout_seconds=excluded.cryptopro_timeout_seconds,
            document_expiry_date=excluded.document_expiry_date,document_type=excluded.document_type,
            updated_at=excluded.updated_at
            """;
    private final ShopContext shop;

    public ZnackRepository(ShopContext shop) {
        this.shop = Objects.requireNonNull(shop);
    }

    public ShopContext shop() { return shop; }

    public Settings getSettings() {
        try (Connection c=Database.getConnection()) {
            return getSettings(c);
        }catch(SQLException e){throw new RuntimeException(e);}
    }

    public void saveSettings(Settings s) {
        try(Connection c=Database.getConnection()){
            saveSettings(c,s);
        }catch(SQLException e){throw new RuntimeException(e);}
    }

    /**
     * Persists a successfully tested selector and its audit entry in one optimistic transaction.
     * A different signing identity owns a different Znack catalog, so its predecessor is retired and
     * every marketplace mapping is cleared atomically before the new identity becomes active.
     */
    public CertificateVerificationResult saveVerifiedCertificate(Settings expected, Settings verified) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(verified, "verified");
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                if (!expected.equals(getSettings(connection))) throw new SettingsConflictException();
                boolean signerChanged = signerChanged(expected, verified);
                CatalogReset catalogReset = signerChanged
                        ? resetCatalogForSignerChange(connection)
                        : CatalogReset.EMPTY;
                saveSettings(connection, verified);
                try (PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO znack_operation_logs
                        (shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                        VALUES(?,?, 'SIGNATURE_TEST',NULL,'INFO','VERIFIED',NULL,?)
                        """)) {
                    audit.setInt(1, shop.shopId());
                    audit.setString(2, shop.shopName());
                    audit.setString(3, Instant.now().toString());
                    audit.executeUpdate();
                }
                if (signerChanged) insertSignerChangeAudit(connection, catalogReset);
                transaction.execute("COMMIT");
                return new CertificateVerificationResult(signerChanged, catalogReset.mappingCount(),
                        catalogReset.archivedProductCount(), catalogReset.deletedProductCount(),
                        catalogReset.archivedCodeCount());
            } catch (SQLException | RuntimeException error) {
                transaction.execute("ROLLBACK");
                throw error;
            }
        } catch (SettingsConflictException | SignerChangeBlockedException error) {
            throw error;
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    private CatalogReset resetCatalogForSignerChange(Connection connection) throws SQLException {
        if (hasSignerChangeBlocker(connection)) throw new SignerChangeBlockedException();
        int mappings = deleteForShop(connection, "znack_gtin_mapping_rules")
                + deleteForShop(connection, "ozon_product_gtin_mappings")
                + deleteForShop(connection, "ozon_article_gtin_mappings");
        String now = Instant.now().toString();
        int archivedCodes;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE kiz_codes
                SET status='ARCHIVED',reservation_token=NULL,reserved_at=NULL,
                    reservation_recoverable=NULL,updated_at=?
                WHERE shop_id=? AND status='AVAILABLE'
                """)) {
            statement.setString(1, now);
            statement.setInt(2, shop.shopId());
            archivedCodes = statement.executeUpdate();
        }
        int archived;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE znack_products
                SET deleted_at=COALESCE(deleted_at,?),identity_archived_at=?
                WHERE shop_id=?
                """)) {
            statement.setString(1, now);
            statement.setString(2, now);
            statement.setInt(3, shop.shopId());
            archived = statement.executeUpdate();
        }
        int deleted;
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM znack_products
                WHERE shop_id=?
                  AND NOT EXISTS(SELECT 1 FROM kiz_orders orders
                    WHERE orders.shop_id=znack_products.shop_id AND orders.gtin=znack_products.gtin)
                  AND NOT EXISTS(SELECT 1 FROM znack_purchase_pipelines pipelines
                    WHERE pipelines.shop_id=znack_products.shop_id AND pipelines.gtin=znack_products.gtin)
                """)) {
            statement.setInt(1, shop.shopId());
            deleted = statement.executeUpdate();
        }
        return new CatalogReset(mappings, archived - deleted, deleted, archivedCodes);
    }

    private boolean hasSignerChangeBlocker(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM znack_purchase_pipelines
                WHERE shop_id=?
                  AND stage NOT IN ('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_FAILED',
                                    'INTRODUCTION_SKIPPED_MISSING_DOCUMENTS',
                                    'INTRODUCTION_SKIPPED_MISSING_METADATA')
                UNION ALL
                SELECT 1 FROM kiz_codes WHERE shop_id=? AND status='RESERVED'
                LIMIT 1
                """)) {
            statement.setInt(1, shop.shopId());
            statement.setInt(2, shop.shopId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private int deleteForShop(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE shop_id=?")) {
            statement.setInt(1, shop.shopId());
            return statement.executeUpdate();
        }
    }

    private void insertSignerChangeAudit(Connection connection, CatalogReset reset) throws SQLException {
        try (PreparedStatement audit = connection.prepareStatement("""
                INSERT INTO znack_operation_logs
                (shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                VALUES(?,?, 'SIGNER_CHANGE',NULL,'INFO',?,NULL,?)
                """)) {
            audit.setInt(1, shop.shopId());
            audit.setString(2, shop.shopName());
            audit.setString(3, "Catalog reset; mappings=" + reset.mappingCount()
                    + "; archived_products=" + reset.archivedProductCount()
                    + "; deleted_products=" + reset.deletedProductCount()
                    + "; archived_codes=" + reset.archivedCodeCount());
            audit.setString(4, Instant.now().toString());
            audit.executeUpdate();
        }
    }

    private static boolean signerChanged(Settings previous, Settings next) {
        String previousSelector = value(previous.signerCertificate()).trim();
        String nextSelector = value(next.signerCertificate()).trim();
        if (previousSelector.isBlank()) return false;
        if (previousSelector.equalsIgnoreCase(nextSelector)) return false;
        String previousThumbprint = certificateThumbprint(previous);
        String nextThumbprint = certificateThumbprint(next);
        return previousThumbprint.isBlank() || nextThumbprint.isBlank()
                || !previousThumbprint.equals(nextThumbprint);
    }

    private static boolean sameSigningIdentity(Settings first, Settings second) {
        String firstSelector = value(first.signerCertificate()).trim();
        String secondSelector = value(second.signerCertificate()).trim();
        if (firstSelector.equalsIgnoreCase(secondSelector)) return true;
        String firstThumbprint = certificateThumbprint(first);
        String secondThumbprint = certificateThumbprint(second);
        return !firstThumbprint.isBlank() && firstThumbprint.equals(secondThumbprint);
    }

    private static String certificateThumbprint(Settings settings) {
        try {
            JsonObject metadata = com.google.gson.JsonParser.parseString(
                    value(settings.certificateMetadataJson())).getAsJsonObject();
            if (metadata.has("thumbprint") && !metadata.get("thumbprint").isJsonNull()) {
                String thumbprint = normalizeThumbprint(metadata.get("thumbprint").getAsString());
                if (!thumbprint.isBlank()) return thumbprint;
            }
        } catch (RuntimeException ignored) {
            // Older settings may contain no JSON metadata; selector equality was checked above.
        }
        return "";
    }

    private static String normalizeThumbprint(String value) {
        return value(value).replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record CertificateVerificationResult(boolean signerChanged, int clearedMappingCount,
                                                int archivedProductCount, int deletedProductCount,
                                                int archivedCodeCount) {
    }

    private record CatalogReset(int mappingCount, int archivedProductCount, int deletedProductCount,
                                int archivedCodeCount) {
        private static final CatalogReset EMPTY = new CatalogReset(0, 0, 0, 0);
    }

    private Settings getSettings(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM znack_settings WHERE shop_id=?")) {
            statement.setInt(1, shop.shopId());
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) return Settings.empty();
                return new Settings(r.getString("true_api_base_url"),r.getString("suz_base_url"),r.getString("oms_id"),r.getString("oms_connection"),
                        r.getString("participant_inn"),r.getString("producer_inn"),r.getString("owner_inn"),r.getString("signer_executable"),
                        r.getString("signer_certificate"),r.getString("signer_arguments_json"),r.getString("document_number"),r.getString("document_date"),
                        r.getString("pdf_folder"),r.getInt("auto_introduction")!=0,r.getString("certificate_list_executable"),
                        r.getString("certificate_list_arguments_json"),r.getString("certificate_metadata_json"),instant(r.getString("signer_tested_at")),
                        r.getString("certmgr_path"),r.getString("cryptcp_path"),r.getString("csptest_path"),r.getInt("cryptopro_timeout_seconds"),
                        r.getString("document_expiry_date"),r.getString("document_type"));
            }
        }
    }

    private void saveSettings(Connection connection, Settings s) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SETTINGS_UPSERT)) {
            int i=1;ps.setInt(i++,shop.shopId());ps.setString(i++,s.trueApiBaseUrl());ps.setString(i++,s.suzBaseUrl());ps.setString(i++,s.omsId());
            ps.setString(i++,s.omsConnection());ps.setString(i++,s.participantInn());ps.setString(i++,s.producerInn());ps.setString(i++,s.ownerInn());
            ps.setString(i++,s.signerExecutable());ps.setString(i++,s.signerCertificate());ps.setString(i++,s.signerArgumentsJson());
            ps.setString(i++,s.documentNumber());ps.setString(i++,s.documentDate());ps.setString(i++,s.pdfFolder());ps.setInt(i++,s.autoIntroduction()?1:0);
            ps.setString(i++,s.certificateListExecutable());ps.setString(i++,s.certificateListArgumentsJson());ps.setString(i++,s.certificateMetadataJson());
            ps.setString(i++,s.signerTestedAt()==null?null:s.signerTestedAt().toString());ps.setString(i++,s.certmgrPath());ps.setString(i++,s.cryptcpPath());
            ps.setString(i++,s.csptestPath());ps.setInt(i++,s.resolvedCryptoProTimeoutSeconds());ps.setString(i++,s.documentExpiryDate());
            ps.setString(i++,s.documentType());ps.setString(i,Instant.now().toString());ps.executeUpdate();
        }
    }

    public static final class SettingsConflictException extends RuntimeException {
        public SettingsConflictException() {
            super("Znack settings changed concurrently.");
        }
    }

    public static final class SignerChangeBlockedException extends RuntimeException {
        public SignerChangeBlockedException() {
            super("Finish active KIZ work before changing the signing certificate.");
        }
    }

    public static final class StaleSignerException extends RuntimeException {
        public StaleSignerException() {
            super("Discarded a GTIN sync that belongs to the previous signing certificate.");
        }
    }

    public void upsertProducts(List<Product> products) {
        upsertProducts(products, null);
    }

    /** Rejects a late catalog response if the shop switched signing identity while it was loading. */
    public void upsertProducts(List<Product> products, Settings expectedSigner) {
        String sql="""
                INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,certificate_type,certificate_number,
                                           certificate_date,production_date,good_mark_flag,good_turn_flag,
                                           card_status,card_detailed_status,category,readiness_checked_at,cis_type,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,gtin) DO UPDATE SET
                  product_name=COALESCE(NULLIF(excluded.product_name,''),znack_products.product_name),
                  tn_ved=COALESCE(NULLIF(excluded.tn_ved,''),znack_products.tn_ved),
                  category=COALESCE(NULLIF(excluded.category,''),znack_products.category),
                  certificate_type=COALESCE(NULLIF(znack_products.certificate_type,''),excluded.certificate_type),
                  certificate_number=COALESCE(NULLIF(znack_products.certificate_number,''),excluded.certificate_number),
                  certificate_date=COALESCE(NULLIF(znack_products.certificate_date,''),excluded.certificate_date),
                  production_date=COALESCE(NULLIF(znack_products.production_date,''),excluded.production_date),
                  good_mark_flag=COALESCE(excluded.good_mark_flag,znack_products.good_mark_flag),
                  good_turn_flag=COALESCE(excluded.good_turn_flag,znack_products.good_turn_flag),
                  card_status=COALESCE(NULLIF(excluded.card_status,''),znack_products.card_status),
                  card_detailed_status=COALESCE(NULLIF(excluded.card_detailed_status,''),znack_products.card_detailed_status),
                  readiness_checked_at=COALESCE(excluded.readiness_checked_at,znack_products.readiness_checked_at),
                  cis_type=COALESCE(NULLIF(excluded.cis_type,''),znack_products.cis_type),
                  deleted_at=CASE WHEN znack_products.identity_archived_at IS NOT NULL
                                  THEN NULL ELSE znack_products.deleted_at END,
                  identity_archived_at=NULL,
                  synced_at=excluded.synced_at
                """;
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                if (expectedSigner != null) {
                    Settings currentSigner = getSettings(connection);
                    if (!value(currentSigner.signerCertificate()).isBlank()
                            && !sameSigningIdentity(currentSigner, expectedSigner)) {
                        throw new StaleSignerException();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Product product : products) {
                        int index = 1;
                        statement.setInt(index++, shop.shopId());
                        statement.setString(index++, GtinNormalizer.normalize(product.gtin()));
                        statement.setString(index++, product.productName());
                        statement.setString(index++, product.tnVed());
                        statement.setString(index++, product.certificateType());
                        statement.setString(index++, product.certificateNumber());
                        statement.setString(index++, product.certificateDate());
                        statement.setString(index++, product.productionDate());
                        nullableBoolean(statement, index++, product.goodMarkFlag());
                        nullableBoolean(statement, index++, product.goodTurnFlag());
                        statement.setString(index++, product.cardStatus());
                        statement.setString(index++, product.cardDetailedStatus());
                        statement.setString(index++, product.category());
                        statement.setString(index++, product.readinessCheckedAt() == null
                                ? null : product.readinessCheckedAt().toString());
                        statement.setString(index++, product.cisType());
                        statement.setString(index, Instant.now().toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) {
                transaction.execute("ROLLBACK");
                throw error;
            }
        } catch (StaleSignerException error) {
            throw error;
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }
    public int pruneTechnicalProducts(){
        String deleteProducts="""
                DELETE FROM znack_products
                WHERE shop_id=? AND gtin LIKE '029%'
                  AND NOT EXISTS(SELECT 1 FROM kiz_orders o WHERE o.shop_id=znack_products.shop_id AND o.gtin=znack_products.gtin)
                  AND NOT EXISTS(SELECT 1 FROM znack_purchase_pipelines p WHERE p.shop_id=znack_products.shop_id AND p.gtin=znack_products.gtin)
                """;
        try(Connection c=Database.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement mappings=c.prepareStatement("DELETE FROM znack_gtin_mapping_rules WHERE shop_id=? AND gtin LIKE '029%'");
                PreparedStatement products=c.prepareStatement(deleteProducts)){
                mappings.setInt(1,shop.shopId());mappings.executeUpdate();
                products.setInt(1,shop.shopId());int removed=products.executeUpdate();
                c.commit();return removed;
            }catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    public int deleteUnpublishedProducts(List<String> gtins){
        if(gtins==null||gtins.isEmpty())return 0;
        String sql="""
                DELETE FROM znack_products
                WHERE shop_id=? AND gtin=? AND deleted_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM kiz_orders o WHERE o.shop_id=znack_products.shop_id AND o.gtin=znack_products.gtin)
                  AND NOT EXISTS(SELECT 1 FROM znack_purchase_pipelines p WHERE p.shop_id=znack_products.shop_id AND p.gtin=znack_products.gtin)
                """;
        try(Connection c=Database.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement(sql)){
                int removed=0;
                for(String gtin:gtins){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));removed+=ps.executeUpdate();}
                c.commit();return removed;
            }catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    /**
     * Permanently deletes a GTIN and everything attached to it for this shop: category mapping rules,
     * purchase pipelines (in-flight buy tasks included), KIZ orders, their downloaded KIZ codes and
     * introduction documents. There are no guards — the GTIN is removed regardless of state. Children are
     * deleted before parents and {@code defer_foreign_keys} is enabled so cross-table references never
     * block the transaction.
     */
    public void deleteProduct(String gtin){
        String g=GtinNormalizer.normalize(gtin);int shopId=shop.shopId();
        try(Connection c=Database.getConnection()){
            c.setAutoCommit(false);
            try(Statement defer=c.createStatement()){defer.execute("PRAGMA defer_foreign_keys=ON");}
            try(
                PreparedStatement codesByOrder=c.prepareStatement("DELETE FROM kiz_codes WHERE shop_id=? AND order_id IN (SELECT id FROM kiz_orders WHERE shop_id=? AND gtin=?)");
                PreparedStatement codesByGtin=c.prepareStatement("DELETE FROM kiz_codes WHERE shop_id=? AND gtin=?");
                PreparedStatement documents=c.prepareStatement("DELETE FROM znack_documents WHERE shop_id=? AND order_id IN (SELECT id FROM kiz_orders WHERE shop_id=? AND gtin=?)");
                PreparedStatement pipelines=c.prepareStatement("DELETE FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=?");
                PreparedStatement orders=c.prepareStatement("DELETE FROM kiz_orders WHERE shop_id=? AND gtin=?");
                PreparedStatement mappings=c.prepareStatement("DELETE FROM znack_gtin_mapping_rules WHERE shop_id=? AND gtin=?");
                PreparedStatement product=c.prepareStatement("DELETE FROM znack_products WHERE shop_id=? AND gtin=?")){
                codesByOrder.setInt(1,shopId);codesByOrder.setInt(2,shopId);codesByOrder.setString(3,g);codesByOrder.executeUpdate();
                codesByGtin.setInt(1,shopId);codesByGtin.setString(2,g);codesByGtin.executeUpdate();
                documents.setInt(1,shopId);documents.setInt(2,shopId);documents.setString(3,g);documents.executeUpdate();
                pipelines.setInt(1,shopId);pipelines.setString(2,g);pipelines.executeUpdate();
                orders.setInt(1,shopId);orders.setString(2,g);orders.executeUpdate();
                mappings.setInt(1,shopId);mappings.setString(2,g);mappings.executeUpdate();
                product.setInt(1,shopId);product.setString(2,g);product.executeUpdate();
                c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    public List<Product> findProducts(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_products WHERE shop_id=? AND gtin NOT LIKE '029%' AND deleted_at IS NULL AND identity_archived_at IS NULL ORDER BY gtin")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<Product> o=new ArrayList<>();while(r.next())o.add(product(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<Product> findDeletedProducts(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_products WHERE shop_id=? AND gtin NOT LIKE '029%' AND deleted_at IS NOT NULL AND identity_archived_at IS NULL ORDER BY gtin")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<Product> o=new ArrayList<>();while(r.next())o.add(product(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    /**
     * Hides GTINs and releases every WB/Ozon mapping they own in the same transaction. A later
     * catalog sync keeps them hidden until explicitly restored, but removed mappings stay removed.
     */
    public void softDeleteProducts(List<String> gtins) {
        if (gtins == null || gtins.isEmpty()) return;
        List<String> normalized = gtins.stream().map(GtinNormalizer::normalize).distinct().toList();
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE znack_products SET deleted_at=? WHERE shop_id=? AND gtin=?")) {
                String deletedAt = Instant.now().toString();
                for (String gtin : normalized) {
                    update.setString(1, deletedAt);
                    update.setInt(2, shop.shopId());
                    update.setString(3, gtin);
                    update.addBatch();
                }
                update.executeBatch();
                ZnackMappingLifecycle.removeForGtins(connection, shop.shopId(), normalized);
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) {
                transaction.execute("ROLLBACK");
                throw error;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }
    public void restoreProducts(List<String> gtins){setDeletedAt(gtins,null);}
    private void setDeletedAt(List<String> gtins,String deletedAt){
        if(gtins==null||gtins.isEmpty())return;
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE znack_products SET deleted_at=? WHERE shop_id=? AND gtin=?")){
            c.setAutoCommit(false);
            for(String gtin:gtins){ps.setString(1,deletedAt);ps.setInt(2,shop.shopId());ps.setString(3,GtinNormalizer.normalize(gtin));ps.addBatch();}
            ps.executeBatch();c.commit();
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    public Optional<Product> findProduct(String gtin){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_products WHERE shop_id=? AND gtin=? AND identity_archived_at IS NULL")){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(product(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateProductCisType(String gtin,String cisType){execute("UPDATE znack_products SET cis_type=? WHERE shop_id=? AND gtin=?",ps->{ps.setString(1,cisType);ps.setInt(2,shop.shopId());ps.setString(3,GtinNormalizer.normalize(gtin));});}
    public void updateProductDocuments(String gtin,List<GoodsDocument> documents){
        LinkedHashSet<GoodsDocument> distinct=new LinkedHashSet<>();
        if(documents!=null)for(GoodsDocument document:documents)if(document!=null&&document.complete())distinct.add(document);
        List<GoodsDocument> snapshot=List.copyOf(distinct);
        GoodsDocument first=snapshot.isEmpty()?null:snapshot.getFirst();
        execute("UPDATE znack_products SET permit_documents_json=?,certificate_type=?,certificate_number=?,certificate_date=? WHERE shop_id=? AND gtin=?",ps->{
            ps.setString(1,GSON.toJson(snapshot));
            ps.setString(2,first==null?null:first.type());
            ps.setString(3,first==null?null:first.number());
            ps.setString(4,first==null?null:first.date());
            ps.setInt(5,shop.shopId());
            ps.setString(6,GtinNormalizer.normalize(gtin));
        });
    }
    public void updateProductLabelMetadata(String gtin,String gender,String size){
        execute("UPDATE znack_products SET gender=?,size=? WHERE shop_id=? AND gtin=?",ps->{
            ps.setString(1,blankToNull(gender));
            ps.setString(2,blankToNull(size));
            ps.setInt(3,shop.shopId());
            ps.setString(4,GtinNormalizer.normalize(gtin));
        });
    }
    public void updateProductMetadata(Product p){execute("UPDATE znack_products SET tn_ved=?,certificate_type=?,certificate_number=?,certificate_date=?,production_date=? WHERE shop_id=? AND gtin=?",ps->{ps.setString(1,p.tnVed());ps.setString(2,p.certificateType());ps.setString(3,p.certificateNumber());ps.setString(4,p.certificateDate());ps.setString(5,p.productionDate());ps.setInt(6,shop.shopId());ps.setString(7,GtinNormalizer.normalize(p.gtin()));});}
    public void updateProductReadiness(Product p){execute("UPDATE znack_products SET product_name=COALESCE(NULLIF(?,''),product_name),good_mark_flag=?,good_turn_flag=?,card_status=?,card_detailed_status=?,readiness_checked_at=? WHERE shop_id=? AND gtin=?",ps->{ps.setString(1,p.productName());nullableBoolean(ps,2,p.goodMarkFlag());nullableBoolean(ps,3,p.goodTurnFlag());ps.setString(4,p.cardStatus());ps.setString(5,p.cardDetailedStatus());ps.setString(6,p.readinessCheckedAt()==null?null:p.readinessCheckedAt().toString());ps.setInt(7,shop.shopId());ps.setString(8,GtinNormalizer.normalize(p.gtin()));});}

    public long createDraft(String gtin,int quantity){String now=Instant.now().toString();try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));ps.setInt(3,quantity);ps.setString(4,OrderStatus.DRAFT.name());ps.setString(5,now);ps.setString(6,now);ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateOrder(long id,String external,String remote,OrderStatus status,String error){execute("UPDATE kiz_orders SET external_order_id=COALESCE(?,external_order_id),remote_status=?,local_status=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{ps.setString(1,external);ps.setString(2,remote);ps.setString(3,status.name());ps.setString(4,ZnackSanitizer.message(error));ps.setString(5,Instant.now().toString());ps.setInt(6,shop.shopId());ps.setLong(7,id);});}
    public Optional<KizOrder> findOrder(long id){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_orders WHERE shop_id=? AND id=?")){ps.setInt(1,shop.shopId());ps.setLong(2,id);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(order(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<KizOrder> findOrders(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_orders WHERE shop_id=? ORDER BY id DESC")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<KizOrder> o=new ArrayList<>();while(r.next())o.add(order(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}

    public int insertCodes(long orderId,String gtin,DownloadedCodes d){String sql="INSERT OR IGNORE INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){c.setAutoCommit(false);int n=0;String now=Instant.now().toString();for(String raw:d.codes()){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);ps.setString(3,raw);ps.setString(4,ZnackSanitizer.displayCode(raw));ps.setString(5,GtinNormalizer.normalize(gtin));ps.setString(6,d.blockId());ps.setString(7,KizInventoryStatus.AVAILABLE.name());ps.setString(8,KizLegalStatus.RECEIVED.name());ps.setString(9,now);ps.setString(10,now);n+=ps.executeUpdate();}c.commit();return n;}catch(SQLException e){throw new RuntimeException(e);}}
    public List<KizCode> findCodes(long orderId){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_codes WHERE shop_id=? AND order_id=? ORDER BY id")){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);try(ResultSet r=ps.executeQuery()){List<KizCode> o=new ArrayList<>();while(r.next())o.add(code(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public void markCodes(long orderId,KizLegalStatus status,String pdfPath,Long documentId){execute("UPDATE kiz_codes SET legal_status=?,document_id=COALESCE(?,document_id),updated_at=? WHERE shop_id=? AND order_id=?",ps->{ps.setString(1,status.name());if(documentId==null)ps.setNull(2,Types.BIGINT);else ps.setLong(2,documentId);ps.setString(3,Instant.now().toString());ps.setInt(4,shop.shopId());ps.setLong(5,orderId);});}

    public long createPipeline(String gtin,int quantity){return createPipeline(gtin,quantity,null);}
    public long createPipeline(String gtin,int quantity,String requestKey){return insertPipeline(gtin,quantity,requestKey,PurchaseStage.VALIDATING);}
    /** Persists a FIFO request without rejecting a second click for the same GTIN. */
    public long enqueuePipeline(String gtin,int quantity,String requestKey){
        String normalized=GtinNormalizer.normalize(gtin);
        String key=requestKey==null||requestKey.isBlank()?java.util.UUID.randomUUID().toString():requestKey;
        String now=Instant.now().toString();
        try(Connection c=Database.getConnection();Statement tx=c.createStatement()){
            tx.execute("BEGIN IMMEDIATE");
            try{
                requireActiveProduct(c, normalized);
                boolean busy;
                try(PreparedStatement active=c.prepareStatement("""
                        SELECT 1 FROM znack_purchase_pipelines
                        WHERE shop_id=? AND gtin=?
                          AND stage IN ('QUEUED','VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                        LIMIT 1
                        """)){
                    active.setInt(1,shop.shopId());active.setString(2,normalized);
                    try(ResultSet rows=active.executeQuery()){busy=rows.next();}
                }
                long id=insertPipeline(c,normalized,quantity,key,busy?PurchaseStage.QUEUED:PurchaseStage.VALIDATING,now);
                tx.execute("COMMIT");
                return id;
            }catch(SQLException error){tx.execute("ROLLBACK");throw error;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    private void requireActiveProduct(Connection connection,String gtin)throws SQLException{
        try(PreparedStatement product=connection.prepareStatement("""
                SELECT 1 FROM znack_products
                WHERE shop_id=? AND gtin=? AND deleted_at IS NULL AND identity_archived_at IS NULL
                """)){
            product.setInt(1,shop.shopId());product.setString(2,gtin);
            try(ResultSet rows=product.executeQuery()){
                if(!rows.next())throw new IllegalArgumentException(
                        "GTIN is no longer active for the selected signing certificate.");
            }
        }
    }
    private long insertPipeline(String gtin,int quantity,String requestKey,PurchaseStage stage){
        String key=requestKey==null||requestKey.isBlank()?java.util.UUID.randomUUID().toString():requestKey;
        String now=Instant.now().toString();
        try(Connection c=Database.getConnection()){
            return insertPipeline(c,GtinNormalizer.normalize(gtin),quantity,key,stage,now);
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    private long insertPipeline(Connection c,String gtin,int quantity,String requestKey,PurchaseStage stage,String now)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,request_key,stage,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            ps.setInt(1,shop.shopId());ps.setString(2,gtin);ps.setInt(3,quantity);ps.setString(4,requestKey);
            ps.setString(5,stage.name());ps.setString(6,now);ps.setString(7,now);ps.executeUpdate();
            try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public void updatePipeline(long id,Long orderId,PurchaseStage stage,String error){execute("UPDATE znack_purchase_pipelines SET order_id=COALESCE(?,order_id),stage=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{if(orderId==null)ps.setNull(1,Types.BIGINT);else ps.setLong(1,orderId);ps.setString(2,stage.name());ps.setString(3,ZnackSanitizer.message(error));ps.setString(4,Instant.now().toString());ps.setInt(5,shop.shopId());ps.setLong(6,id);});}
    public Optional<ZnackPurchasePipelineState> findActivePipeline(String gtin){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? AND stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES') ORDER BY id LIMIT 1";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findActivePipelines(){String sql="""
            SELECT * FROM znack_purchase_pipelines
            WHERE shop_id=?
              AND stage NOT IN ('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_FAILED',
                'INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA',
                'WAITING_INTRODUCTION_DOCUMENTS')
              AND NOT (
                stage='WAITING_INTRODUCTION_READINESS'
                AND lower(COALESCE(error_message,'')) LIKE '%declaration or certificate%'
                AND (lower(COALESCE(error_message,'')) LIKE '%no active%'
                     OR lower(COALESCE(error_message,'')) LIKE '%missing%')
              )
            ORDER BY id
            """;try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<ZnackPurchasePipelineState> activateNextQueuedPipeline(String gtin){
        String normalized=GtinNormalizer.normalize(gtin);
        try(Connection c=Database.getConnection();Statement tx=c.createStatement()){
            tx.execute("BEGIN IMMEDIATE");
            try{
                try(PreparedStatement active=c.prepareStatement("""
                        SELECT 1 FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=?
                          AND stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                        LIMIT 1
                        """)){
                    active.setInt(1,shop.shopId());active.setString(2,normalized);
                    try(ResultSet rows=active.executeQuery()){if(rows.next()){tx.execute("COMMIT");return Optional.empty();}}
                }
                long id;
                try(PreparedStatement queued=c.prepareStatement("SELECT id FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? AND stage='QUEUED' ORDER BY id LIMIT 1")){
                    queued.setInt(1,shop.shopId());queued.setString(2,normalized);
                    try(ResultSet rows=queued.executeQuery()){if(!rows.next()){tx.execute("COMMIT");return Optional.empty();}id=rows.getLong(1);}
                }
                try(PreparedStatement update=c.prepareStatement("UPDATE znack_purchase_pipelines SET stage='VALIDATING',error_message=NULL,updated_at=? WHERE shop_id=? AND id=? AND stage='QUEUED'")){
                    update.setString(1,Instant.now().toString());update.setInt(2,shop.shopId());update.setLong(3,id);
                    if(update.executeUpdate()!=1)throw new SQLException("Queued Znack purchase changed while activating.");
                }
                ZnackPurchasePipelineState activated;
                try(PreparedStatement selected=c.prepareStatement("SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND id=?")){
                    selected.setInt(1,shop.shopId());selected.setLong(2,id);
                    try(ResultSet rows=selected.executeQuery()){if(!rows.next())throw new SQLException("Activated Znack purchase was not found.");activated=pipeline(rows);}
                }
                tx.execute("COMMIT");return Optional.of(activated);
            }catch(SQLException error){tx.execute("ROLLBACK");throw error;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    public Optional<KizOrder> findLatestUnlinkedOrder(String gtin,int quantity,Instant notBefore){String sql="""
            SELECT o.* FROM kiz_orders o
            WHERE o.shop_id=? AND o.gtin=? AND o.quantity=?
              AND o.created_at>=?
              AND NOT EXISTS(SELECT 1 FROM znack_purchase_pipelines p WHERE p.shop_id=o.shop_id AND p.order_id=o.id)
            ORDER BY o.id DESC LIMIT 1
            """;try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));ps.setInt(3,quantity);ps.setString(4,notBefore.toString());try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(order(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<ZnackPurchasePipelineState> findLatestIntroductionFailedPipeline(String gtin){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? AND stage='INTRODUCTION_FAILED' ORDER BY id DESC LIMIT 1";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findSkippedIntroductionPipelines(){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND stage IN ('INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA') ORDER BY id";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findWaitingIntroductionDocumentPipelines(){String sql="""
            SELECT * FROM znack_purchase_pipelines
            WHERE shop_id=? AND (
              stage='WAITING_INTRODUCTION_DOCUMENTS'
              OR (
                stage='WAITING_INTRODUCTION_READINESS'
                AND lower(COALESCE(error_message,'')) LIKE '%declaration or certificate%'
                AND (lower(COALESCE(error_message,'')) LIKE '%no active%'
                     OR lower(COALESCE(error_message,'')) LIKE '%missing%')
              )
            )
            ORDER BY id
            """;try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findLegacyRejectedIntroductionPipelines(){String sql="""
            SELECT p.* FROM znack_purchase_pipelines p
            WHERE p.shop_id=? AND p.stage='FAILED' AND p.error_message LIKE '%HTTP 422%'
              AND EXISTS (
                SELECT 1 FROM znack_documents d
                WHERE d.shop_id=p.shop_id AND d.order_id=p.order_id AND d.external_document_id IS NULL
                  AND d.status='FAILED'
                  AND d.id=(SELECT MAX(latest.id) FROM znack_documents latest
                            WHERE latest.shop_id=p.shop_id AND latest.order_id=p.order_id)
              )
            ORDER BY p.id
            """;try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findLegacyPrimitiveDocumentResponsePipelines(){String sql="""
            SELECT p.* FROM znack_purchase_pipelines p
            WHERE p.shop_id=? AND p.stage='FAILED'
              AND EXISTS (
                SELECT 1 FROM znack_documents d
                WHERE d.shop_id=p.shop_id AND d.order_id=p.order_id AND d.external_document_id IS NULL
                  AND d.status='FAILED' AND d.error_message LIKE '%Not a JSON Object:%'
                  AND d.id=(SELECT MAX(latest.id) FROM znack_documents latest
                            WHERE latest.shop_id=p.shop_id AND latest.order_id=p.order_id)
              )
            ORDER BY p.id
            """;try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<ZnackPurchasePipelineState> findPipeline(long id){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND id=?")){ps.setInt(1,shop.shopId());ps.setLong(2,id);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<ZnackPurchasePipelineState> findPipelineByRequestKey(String requestKey){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND request_key=?")){ps.setInt(1,shop.shopId());ps.setString(2,requestKey);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}

    public long createDocument(long orderId,String payload){String now=Instant.now().toString();try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO znack_documents(shop_id,order_id,document_type,payload_json,status,created_at,updated_at) VALUES(?,?,'LP_INTRODUCE_GOODS',?,'DRAFT',?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);ps.setString(3,payload);ps.setString(4,now);ps.setString(5,now);ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateDocument(long id,String external,String status,String error){execute("UPDATE znack_documents SET external_document_id=COALESCE(?,external_document_id),status=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{ps.setString(1,external);ps.setString(2,status);ps.setString(3,ZnackSanitizer.message(error));ps.setString(4,Instant.now().toString());ps.setInt(5,shop.shopId());ps.setLong(6,id);});}
    public Optional<Document> findLatestDocument(long orderId){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT id,order_id,payload_json,external_document_id,status,error_message FROM znack_documents WHERE shop_id=? AND order_id=? ORDER BY id DESC LIMIT 1")){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(new Document(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6))):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public boolean latestDocumentIsLegacyHttpRejection(long orderId){String sql="SELECT 1 FROM znack_documents WHERE shop_id=? AND order_id=? AND external_document_id IS NULL AND status='FAILED' AND error_message LIKE '%HTTP 422%' AND id=(SELECT MAX(id) FROM znack_documents WHERE shop_id=? AND order_id=?)";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);ps.setInt(3,shop.shopId());ps.setLong(4,orderId);try(ResultSet r=ps.executeQuery()){return r.next();}}catch(SQLException e){throw new RuntimeException(e);}}

    public void log(String action,String entity,String severity,String message,Integer httpStatus){execute("INSERT INTO znack_operation_logs(shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at) VALUES(?,?,?,?,?,?,?,?)",ps->{ps.setInt(1,shop.shopId());ps.setString(2,shop.shopName());ps.setString(3,action);ps.setString(4,entity);ps.setString(5,severity);ps.setString(6,ZnackSanitizer.message(message));if(httpStatus==null)ps.setNull(7,Types.INTEGER);else ps.setInt(7,httpStatus);ps.setString(8,Instant.now().toString());});}
    public List<OperationLog> findLogs(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_operation_logs WHERE shop_id=? ORDER BY id DESC")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<OperationLog> o=new ArrayList<>();while(r.next()){int h=r.getInt("http_status");o.add(new OperationLog(r.getLong("id"),r.getInt("shop_id"),r.getString("shop_name"),r.getString("action"),r.getString("entity_reference"),r.getString("severity"),r.getString("message"),r.wasNull()?null:h,Instant.parse(r.getString("created_at"))));}return o;}}catch(SQLException e){throw new RuntimeException(e);}}

    private void execute(String sql,SqlBinder binder){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){binder.bind(ps);ps.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);}}
    private KizOrder order(ResultSet r)throws SQLException{return new KizOrder(r.getLong("id"),r.getString("external_order_id"),r.getString("gtin"),r.getInt("quantity"),r.getString("remote_status"),OrderStatus.valueOf(r.getString("local_status")),r.getString("error_message"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private Product product(ResultSet r)throws SQLException{return new Product(r.getString("gtin"),r.getString("product_name"),r.getString("tn_ved"),r.getString("certificate_type"),r.getString("certificate_number"),r.getString("certificate_date"),r.getString("production_date"),nullableBoolean(r,"good_mark_flag"),nullableBoolean(r,"good_turn_flag"),r.getString("card_status"),r.getString("card_detailed_status"),r.getString("category"),instant(r.getString("readiness_checked_at")),r.getString("cis_type"),documents(r.getString("permit_documents_json")));}
    private static List<GoodsDocument> documents(String json){
        if(json==null||json.isBlank())return List.of();
        try{
            GoodsDocument[] documents=GSON.fromJson(json,GoodsDocument[].class);
            return documents==null?List.of():List.of(documents);
        }catch(JsonParseException|NullPointerException error){return List.of();}
    }
    private KizCode code(ResultSet r)throws SQLException{long d=r.getLong("document_id");Long documentId=r.wasNull()?null:d;String legal=r.getString("legal_status");return new KizCode(r.getLong("id"),r.getLong("order_id"),r.getString("raw_code"),r.getString("display_code"),r.getString("gtin"),r.getString("block_id"),r.getString("pdf_path"),documentId,KizInventoryStatus.valueOf(r.getString("status")),legal==null||legal.isBlank()?null:KizLegalStatus.valueOf(legal));}
    private ZnackPurchasePipelineState pipeline(ResultSet r)throws SQLException{long orderId=r.getLong("order_id");boolean orderNull=r.wasNull();return new ZnackPurchasePipelineState(r.getLong("id"),r.getInt("shop_id"),r.getString("gtin"),r.getInt("quantity"),orderNull?null:orderId,PurchaseStage.valueOf(r.getString("stage")),r.getString("error_message"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private static Instant instant(String value){return value==null||value.isBlank()?null:Instant.parse(value);}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.strip();}
    private static Boolean nullableBoolean(ResultSet r,String column)throws SQLException{int value=r.getInt(column);return r.wasNull()?null:value!=0;}
    private static void nullableBoolean(PreparedStatement ps,int index,Boolean value)throws SQLException{if(value==null)ps.setNull(index,Types.INTEGER);else ps.setInt(index,value?1:0);}
    @FunctionalInterface private interface SqlBinder{void bind(PreparedStatement ps)throws SQLException;}
}
