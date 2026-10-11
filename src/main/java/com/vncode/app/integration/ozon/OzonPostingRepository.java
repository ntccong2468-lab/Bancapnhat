package com.vncode.app.integration.ozon;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vncode.app.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OzonPostingRepository {
    private static final Gson GSON = new Gson();

    public long countFiltered(int shop,String status,String text){try(var c=Database.getConnection()){return countFiltered(c,shop,status,text);}catch(SQLException e){throw new IllegalStateException("Cannot count Ozon postings");}}
    public long countFiltered(Connection c,int shop,String status,String text)throws SQLException{
        var q=new com.vncode.app.features.ozon.OzonFbsQuery(shop,status,text,"",0,100);registerSearch(c);
        try(var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_postings p WHERE "+filter(q))){bindFilter(p,q);try(var r=p.executeQuery()){return r.getLong(1);}}
    }
    public List<OzonPostingDto> findSortedPage(com.vncode.app.features.ozon.OzonFbsQuery q){try(var c=Database.getConnection()){return findSortedPage(c,q);}catch(SQLException e){throw new IllegalStateException("Cannot page Ozon postings");}}
    public List<OzonPostingDto> findSortedPage(Connection c,com.vncode.app.features.ozon.OzonFbsQuery q)throws SQLException{
        registerSearch(c);org.sqlite.Collation.create(c,"VN_NATURAL",new org.sqlite.Collation(){protected int xCompare(String a,String b){return com.vncode.app.shared.NaturalOrderComparator.compareIgnoreCase(a,b);}});
        var sort=new ArrayList<String>();if(!q.sort().isBlank())for(String field:q.sort().split(",")){String expression=switch(field){case "product"->"coalesce(nullif(pr.name,''),nullif(i.name,''),i.sku)";case "article"->"coalesce(nullif(pr.article,''),nullif(i.offer_id,''),i.sku)";case "color"->"pr.color";default->"pr.size";};sort.add("CASE WHEN "+expression+" IS NULL OR "+expression+"='' THEN 1 ELSE 0 END");sort.add(expression+" COLLATE VN_NATURAL");}sort.add("p.posting_number COLLATE VN_NATURAL");
        String sql="SELECT p.* FROM ozon_postings p LEFT JOIN ozon_posting_items i ON i.shop_id=p.shop_id AND i.posting_number=p.posting_number AND i.item_index=0 LEFT JOIN ozon_products pr ON pr.shop_id=i.shop_id AND pr.product_id=coalesce((SELECT own.product_id FROM ozon_products own WHERE own.shop_id=i.shop_id AND own.product_id=i.product_id),(SELECT own.product_id FROM ozon_products own WHERE own.shop_id=i.shop_id AND own.sku=i.sku ORDER BY own.product_id LIMIT 1)) WHERE "+filter(q)+" ORDER BY "+String.join(",",sort)+" LIMIT ? OFFSET ?";
        var rows=new ArrayList<OzonPostingDto>();try(var p=c.prepareStatement(sql)){int n=bindFilter(p,q);p.setInt(n++,q.limit());p.setInt(n,q.offset());try(var r=p.executeQuery()){while(r.next())rows.add(readPosting(c,q.shopId(),r,List.of()));}}
        if(rows.isEmpty())return List.of();var items=new java.util.HashMap<String,List<OzonPostingItemDto>>();String marks=String.join(",",java.util.Collections.nCopies(rows.size(),"?"));
        try(var p=c.prepareStatement("SELECT posting_number,item_index,product_id,sku,offer_id,name,quantity,currency_code,price FROM ozon_posting_items WHERE shop_id=? AND posting_number IN ("+marks+") ORDER BY posting_number,item_index")){p.setInt(1,q.shopId());int n=2;for(var row:rows)p.setString(n++,row.postingNumber());try(var r=p.executeQuery()){while(r.next())items.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(new OzonPostingItemDto(r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),r.getString(8),r.getString(9)));}}
        return rows.stream().map(p->new OzonPostingDto(p.postingNumber(),p.orderId(),p.orderNumber(),p.status(),p.substatus(),p.warehouseId(),p.shipmentAt(),p.inProcessAt(),p.lowerBarcode(),p.upperBarcode(),p.requirements(),p.availableActions(),p.shipAvailable(),items.getOrDefault(p.postingNumber(),List.of()))).toList();
    }
    private static void registerSearch(Connection c)throws SQLException{org.sqlite.Function.create(c,"vn_fold",new org.sqlite.Function(){protected void xFunc()throws SQLException{String v=value_text(0);result(v==null?"":java.text.Normalizer.normalize(v,java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT));}});}
    private static String filter(com.vncode.app.features.ozon.OzonFbsQuery q){return "p.shop_id=?"+(q.status().isBlank()?"":" AND p.status=?")+(q.text().isBlank()?"":" AND (vn_fold(p.posting_number) LIKE ? ESCAPE '\\' OR vn_fold(p.order_id) LIKE ? ESCAPE '\\' OR vn_fold(p.order_number) LIKE ? ESCAPE '\\' OR EXISTS(SELECT 1 FROM ozon_posting_items x WHERE x.shop_id=p.shop_id AND x.posting_number=p.posting_number AND (vn_fold(x.sku) LIKE ? ESCAPE '\\' OR vn_fold(x.offer_id) LIKE ? ESCAPE '\\' OR vn_fold(x.name) LIKE ? ESCAPE '\\')))");}
    private static int bindFilter(PreparedStatement p,com.vncode.app.features.ozon.OzonFbsQuery q)throws SQLException{int n=1;p.setInt(n++,q.shopId());if(!q.status().isBlank())p.setString(n++,q.status());if(!q.text().isBlank()){String s="%"+java.text.Normalizer.normalize(q.text(),java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";for(int i=0;i<6;i++)p.setString(n++,s);}return n;}

    public int upsertPage(int shopId, List<OzonPostingDto> postings) {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (OzonPostingDto posting : postings) upsert(connection, shopId, posting);
                connection.commit();
                return postings.size();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void upsertDetail(int shopId, OzonPostingDto posting) {
        upsertPage(shopId, List.of(posting));
    }

    public List<OzonPostingDto> findByStatus(int shopId, String status, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new IllegalArgumentException("Invalid Ozon posting pagination");
        }
        boolean all = status == null || status.isBlank();
        String sql = """
                SELECT posting_number,order_id,order_number,status,substatus,warehouse_id,shipment_at,in_process_at,
                       lower_barcode,upper_barcode,mandatory_mark_product_ids,optional_mark_product_ids,
                       unsupported_requirements,available_actions,ship_available
                FROM ozon_postings WHERE shop_id=?
                """ + (all ? "" : " AND status=? ")
                + " ORDER BY shipment_at DESC,posting_number DESC LIMIT ? OFFSET ?";
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setInt(parameter++, shopId);
            if (!all) statement.setString(parameter++, status.strip());
            statement.setInt(parameter++, limit);
            statement.setInt(parameter, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<OzonPostingDto> postings = new ArrayList<>();
                while (result.next()) postings.add(readPosting(connection, shopId, result));
                return List.copyOf(postings);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** The actionable FBS queue: orders still being packed plus orders ready for label printing. */
    public List<OzonPostingDto> findActive(int shopId, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new IllegalArgumentException("Invalid Ozon posting pagination");
        }
        String sql = """
                SELECT posting_number,order_id,order_number,status,substatus,warehouse_id,shipment_at,in_process_at,
                       lower_barcode,upper_barcode,mandatory_mark_product_ids,optional_mark_product_ids,
                       unsupported_requirements,available_actions,ship_available
                FROM ozon_postings
                WHERE shop_id=? AND status IN ('awaiting_packaging','awaiting_deliver')
                ORDER BY shipment_at DESC,posting_number DESC LIMIT ? OFFSET ?
                """;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<OzonPostingDto> values = new ArrayList<>();
                while (result.next()) values.add(readPosting(connection, shopId, result));
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<OzonPostingDto> findPage(
            int shopId, String statusFilter, String query, int limit, int offset) {
        if (shopId <= 0 || limit < 1 || limit > 1000 || offset < 0) {
            throw new IllegalArgumentException("Invalid Ozon posting pagination");
        }
        String normalizedStatus = statusFilter == null ? "all" : statusFilter.strip();
        boolean active = "active".equals(normalizedStatus);
        boolean all = "all".equals(normalizedStatus);
        if (!active && !all && normalizedStatus.isBlank()) {
            throw new IllegalArgumentException("Invalid Ozon posting status");
        }
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        String search = "%" + escapeLike(normalizedQuery) + "%";
        String statusSql = active
                ? " AND p.status IN ('awaiting_packaging','awaiting_deliver')"
                : all ? "" : " AND p.status=?";
        String searchSql = normalizedQuery.isEmpty() ? "" : """
                 AND (lower(p.posting_number) LIKE ? ESCAPE '\\'
                   OR lower(p.order_id) LIKE ? ESCAPE '\\'
                   OR lower(p.order_number) LIKE ? ESCAPE '\\'
                   OR EXISTS (SELECT 1 FROM ozon_posting_items i
                              WHERE i.shop_id=p.shop_id AND i.posting_number=p.posting_number
                                AND (lower(i.sku) LIKE ? ESCAPE '\\'
                                  OR lower(i.offer_id) LIKE ? ESCAPE '\\'
                                  OR lower(i.name) LIKE ? ESCAPE '\\')))
                """;
        String sql = """
                SELECT p.posting_number,p.order_id,p.order_number,p.status,p.substatus,p.warehouse_id,
                       p.shipment_at,p.in_process_at,p.lower_barcode,p.upper_barcode,
                       p.mandatory_mark_product_ids,p.optional_mark_product_ids,
                       p.unsupported_requirements,p.available_actions,p.ship_available
                FROM ozon_postings p WHERE p.shop_id=?
                """ + statusSql + searchSql
                + " ORDER BY p.shipment_at DESC,p.posting_number DESC LIMIT ? OFFSET ?";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setInt(parameter++, shopId);
            if (!active && !all) statement.setString(parameter++, normalizedStatus);
            if (!normalizedQuery.isEmpty()) {
                for (int index = 0; index < 6; index++) statement.setString(parameter++, search);
            }
            statement.setInt(parameter++, limit);
            statement.setInt(parameter, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<OzonPostingDto> values = new ArrayList<>();
                while (result.next()) values.add(readPosting(connection, shopId, result));
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public OzonPostingDto find(int shopId, String postingNumber) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT posting_number,order_id,order_number,status,substatus,warehouse_id,shipment_at,in_process_at,
                               lower_barcode,upper_barcode,mandatory_mark_product_ids,optional_mark_product_ids,
                               unsupported_requirements,available_actions,ship_available
                        FROM ozon_postings WHERE shop_id=? AND posting_number=?
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, OzonApiClient.requireExternalId(postingNumber, "posting number"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readPosting(connection, shopId, result) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void upsert(Connection connection, int shopId, OzonPostingDto posting) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ozon_postings(shop_id,posting_number,order_id,order_number,status,substatus,
                    warehouse_id,shipment_at,in_process_at,lower_barcode,upper_barcode,mandatory_mark_product_ids,
                    optional_mark_product_ids,unsupported_requirements,available_actions,ship_available,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,posting_number) DO UPDATE SET order_id=excluded.order_id,
                    order_number=excluded.order_number,status=excluded.status,substatus=excluded.substatus,
                    warehouse_id=excluded.warehouse_id,shipment_at=excluded.shipment_at,in_process_at=excluded.in_process_at,
                    lower_barcode=excluded.lower_barcode,upper_barcode=excluded.upper_barcode,
                    mandatory_mark_product_ids=excluded.mandatory_mark_product_ids,
                    optional_mark_product_ids=excluded.optional_mark_product_ids,
                    unsupported_requirements=excluded.unsupported_requirements,
                    available_actions=excluded.available_actions,ship_available=excluded.ship_available,
                    synced_at=excluded.synced_at
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, posting.postingNumber());
            statement.setString(3, posting.orderId());
            statement.setString(4, posting.orderNumber());
            statement.setString(5, posting.status());
            statement.setString(6, posting.substatus());
            statement.setString(7, posting.warehouseId());
            statement.setString(8, posting.shipmentAt());
            statement.setString(9, posting.inProcessAt());
            statement.setString(10, posting.lowerBarcode());
            statement.setString(11, posting.upperBarcode());
            statement.setString(12, GSON.toJson(posting.requirements().mandatoryMarkProductIds()));
            statement.setString(13, GSON.toJson(posting.requirements().optionalMarkProductIds()));
            statement.setString(14, GSON.toJson(posting.requirements().unsupportedRequirements()));
            statement.setString(15, GSON.toJson(posting.availableActions()));
            statement.setInt(16, posting.shipAvailable() ? 1 : 0);
            statement.setString(17, now);
            statement.executeUpdate();
        }
        try (PreparedStatement item = connection.prepareStatement("""
                INSERT INTO ozon_posting_items(shop_id,posting_number,item_index,product_id,sku,offer_id,name,
                    quantity,currency_code,price) VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,posting_number,item_index) DO UPDATE SET product_id=excluded.product_id,
                    sku=excluded.sku,offer_id=excluded.offer_id,name=excluded.name,quantity=excluded.quantity,
                    currency_code=excluded.currency_code,price=excluded.price
                """)) {
            for (OzonPostingItemDto value : posting.items()) {
                item.setInt(1, shopId);
                item.setString(2, posting.postingNumber());
                item.setInt(3, value.itemIndex());
                item.setString(4, value.productId());
                item.setString(5, value.sku());
                item.setString(6, value.offerId());
                item.setString(7, value.name());
                item.setInt(8, value.quantity());
                item.setString(9, value.currencyCode());
                item.setString(10, value.price());
                item.addBatch();
            }
            item.executeBatch();
        }
        // Do not cascade-delete an item already linked to a durable exemplar job. A changed remote
        // package then requires reconciliation instead of silently releasing or losing a KIZ link.
        try (PreparedStatement stale = connection.prepareStatement("""
                DELETE FROM ozon_posting_items
                WHERE shop_id=? AND posting_number=? AND item_index>=?
                  AND NOT EXISTS (
                    SELECT 1 FROM ozon_exemplars e
                    WHERE e.shop_id=ozon_posting_items.shop_id
                      AND e.posting_number=ozon_posting_items.posting_number
                      AND e.item_index=ozon_posting_items.item_index)
                """)) {
            stale.setInt(1, shopId);
            stale.setString(2, posting.postingNumber());
            stale.setInt(3, posting.items().size());
            stale.executeUpdate();
        }
    }

    private static OzonPostingDto readPosting(Connection connection, int shopId, ResultSet result) throws SQLException {
        return readPosting(connection,shopId,result,readItems(connection,shopId,result.getString("posting_number")));
    }
    private static OzonPostingDto readPosting(Connection connection,int shopId,ResultSet result,List<OzonPostingItemDto> items)throws SQLException {
        String postingNumber = result.getString("posting_number");
        OzonRequirements requirements = new OzonRequirements(
                jsonStrings(result.getString("mandatory_mark_product_ids")),
                jsonStrings(result.getString("optional_mark_product_ids")),
                jsonStrings(result.getString("unsupported_requirements")));
        return new OzonPostingDto(
                postingNumber,
                result.getString("order_id"),
                result.getString("order_number"),
                result.getString("status"),
                result.getString("substatus"),
                result.getString("warehouse_id"),
                result.getString("shipment_at"),
                result.getString("in_process_at"),
                result.getString("lower_barcode"),
                result.getString("upper_barcode"),
                requirements,
                jsonStrings(result.getString("available_actions")),
                result.getBoolean("ship_available"),
                items);
    }

    private static List<OzonPostingItemDto> readItems(Connection connection, int shopId, String postingNumber)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item_index,product_id,sku,offer_id,name,quantity,currency_code,price
                FROM ozon_posting_items WHERE shop_id=? AND posting_number=? ORDER BY item_index
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                List<OzonPostingItemDto> items = new ArrayList<>();
                while (result.next()) items.add(new OzonPostingItemDto(
                        result.getInt(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getInt(6), result.getString(7), result.getString(8)));
                return List.copyOf(items);
            }
        }
    }

    private static List<String> jsonStrings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = GSON.fromJson(json, new TypeToken<List<String>>() {}.getType());
            return values == null ? List.of() : List.copyOf(values);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
