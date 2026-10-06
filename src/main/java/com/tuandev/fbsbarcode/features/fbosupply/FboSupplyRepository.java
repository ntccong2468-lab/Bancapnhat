package com.tuandev.fbsbarcode.features.fbosupply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class FboSupplyRepository {
    public record OzonSupplyRef(String supplyId, String bundleId) {
    }

    public void upsertWbSummaries(int shopId, JsonArray summaries) {
        String sql = """
                INSERT INTO wb_fbw_orders(
                    shop_id,preorder_id,supply_id,status_id,box_type_id,create_date,supply_date,
                    fact_date,updated_date,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,preorder_id) DO UPDATE SET
                    supply_id=excluded.supply_id,status_id=excluded.status_id,
                    box_type_id=excluded.box_type_id,create_date=excluded.create_date,
                    supply_date=excluded.supply_date,fact_date=excluded.fact_date,
                    updated_date=excluded.updated_date,synced_at=excluded.synced_at
                """;
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            String syncedAt = Instant.now().toString();
            for (JsonElement element : safeArray(summaries)) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String preorderId = string(object, "preorderID");
                if (preorderId == null) continue;
                statement.setInt(1, shopId);
                statement.setString(2, preorderId);
                setString(statement, 3, string(object, "supplyID"));
                setInteger(statement, 4, integer(object, "statusID"));
                setInteger(statement, 5, integer(object, "boxTypeID"));
                setString(statement, 6, string(object, "createDate"));
                setString(statement, 7, string(object, "supplyDate"));
                setString(statement, 8, string(object, "factDate"));
                setString(statement, 9, string(object, "updatedDate"));
                statement.setString(10, syncedAt);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void upsertWbDetail(int shopId, String preorderId, JsonObject detail, JsonArray goods) {
        String update = """
                UPDATE wb_fbw_orders SET
                    status_id=?,box_type_id=?,virtual_type_id=?,create_date=COALESCE(?,create_date),
                    supply_date=COALESCE(?,supply_date),fact_date=COALESCE(?,fact_date),
                    updated_date=COALESCE(?,updated_date),warehouse_id=?,warehouse_name=?,
                    actual_warehouse_id=?,actual_warehouse_name=?,transit_warehouse_id=?,transit_warehouse_name=?,
                    quantity=?,accepted_quantity=?,unloading_quantity=?,ready_for_sale_quantity=?,
                    depersonalized_quantity=?,acceptance_cost=?,acceptance_coefficient=?,storage_coefficient=?,
                    delivery_coefficient=?,reject_reason=?,detail_synced_at=?,synced_at=?
                WHERE shop_id=? AND preorder_id=?
                """;
        String insertItem = """
                INSERT INTO wb_fbw_order_items(
                    shop_id,preorder_id,item_key,barcode,vendor_code,nm_id,need_kiz,tnved,tech_size,color,
                    quantity,accepted_quantity,unloading_quantity,ready_for_sale_quantity)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement detailStatement = connection.prepareStatement(update);
             PreparedStatement deleteItems = connection.prepareStatement(
                     "DELETE FROM wb_fbw_order_items WHERE shop_id=? AND preorder_id=?");
             PreparedStatement itemStatement = connection.prepareStatement(insertItem)) {
            connection.setAutoCommit(false);
            String now = Instant.now().toString();
            setInteger(detailStatement, 1, integer(detail, "statusID"));
            setInteger(detailStatement, 2, integer(detail, "boxTypeID"));
            setInteger(detailStatement, 3, integer(detail, "virtualTypeID"));
            setString(detailStatement, 4, string(detail, "createDate"));
            setString(detailStatement, 5, string(detail, "supplyDate"));
            setString(detailStatement, 6, string(detail, "factDate"));
            setString(detailStatement, 7, string(detail, "updatedDate"));
            setString(detailStatement, 8, string(detail, "warehouseID"));
            setString(detailStatement, 9, string(detail, "warehouseName"));
            setString(detailStatement, 10, string(detail, "actualWarehouseID"));
            setString(detailStatement, 11, string(detail, "actualWarehouseName"));
            setString(detailStatement, 12, string(detail, "transitWarehouseID"));
            setString(detailStatement, 13, string(detail, "transitWarehouseName"));
            detailStatement.setInt(14, integerValue(detail, "quantity"));
            detailStatement.setInt(15, integerValue(detail, "acceptedQuantity"));
            detailStatement.setInt(16, integerValue(detail, "unloadingQuantity"));
            detailStatement.setInt(17, integerValue(detail, "readyForSaleQuantity"));
            detailStatement.setInt(18, integerValue(detail, "depersonalizedQuantity"));
            setInteger(detailStatement, 19, integer(detail, "acceptanceCost"));
            setString(detailStatement, 20, string(detail, "paidAcceptanceCoefficient"));
            setString(detailStatement, 21, string(detail, "storageCoef"));
            setString(detailStatement, 22, string(detail, "deliveryCoef"));
            setString(detailStatement, 23, string(detail, "rejectReason"));
            detailStatement.setString(24, now);
            detailStatement.setString(25, now);
            detailStatement.setInt(26, shopId);
            detailStatement.setString(27, preorderId);
            detailStatement.executeUpdate();

            deleteItems.setInt(1, shopId);
            deleteItems.setString(2, preorderId);
            deleteItems.executeUpdate();
            int index = 0;
            for (JsonElement element : safeArray(goods)) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String barcode = string(item, "barcode");
                String nmId = string(item, "nmID");
                String itemKey = (nmId == null ? "" : nmId) + ":" + (barcode == null ? index : barcode);
                itemStatement.setInt(1, shopId);
                itemStatement.setString(2, preorderId);
                itemStatement.setString(3, itemKey);
                setString(itemStatement, 4, barcode);
                setString(itemStatement, 5, string(item, "vendorCode"));
                setString(itemStatement, 6, nmId);
                setBoolean(itemStatement, 7, bool(item, "needKiz"));
                setString(itemStatement, 8, string(item, "tnved"));
                setString(itemStatement, 9, string(item, "techSize"));
                setString(itemStatement, 10, string(item, "color"));
                itemStatement.setInt(11, integerValue(item, "quantity"));
                itemStatement.setInt(12, integerValue(item, "acceptedQuantity"));
                itemStatement.setInt(13, integerValue(item, "unloadingQuantity"));
                itemStatement.setInt(14, integerValue(item, "readyForSaleQuantity"));
                itemStatement.addBatch();
                index++;
            }
            itemStatement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void upsertOzonDetails(int shopId, JsonObject response) {
        JsonArray orders = array(response, "orders");
        String orderSql = """
                INSERT INTO ozon_fbo_orders(
                    shop_id,order_id,order_number,state,created_date,state_updated_date,data_filling_deadline,
                    drop_off_warehouse_id,drop_off_warehouse_name,drop_off_warehouse_address,
                    timeslot_from,timeslot_to,timezone_name,is_virtual,is_pickup,is_econom,is_quant,is_super_fbo,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,order_id) DO UPDATE SET
                    order_number=excluded.order_number,state=excluded.state,created_date=excluded.created_date,
                    state_updated_date=excluded.state_updated_date,data_filling_deadline=excluded.data_filling_deadline,
                    drop_off_warehouse_id=excluded.drop_off_warehouse_id,
                    drop_off_warehouse_name=excluded.drop_off_warehouse_name,
                    drop_off_warehouse_address=excluded.drop_off_warehouse_address,
                    timeslot_from=excluded.timeslot_from,timeslot_to=excluded.timeslot_to,
                    timezone_name=excluded.timezone_name,is_virtual=excluded.is_virtual,is_pickup=excluded.is_pickup,
                    is_econom=excluded.is_econom,is_quant=excluded.is_quant,is_super_fbo=excluded.is_super_fbo,
                    synced_at=excluded.synced_at
                """;
        String supplySql = """
                INSERT INTO ozon_fbo_supplies(
                    shop_id,order_id,supply_id,bundle_id,state,is_crossdock,macrolocal_cluster_id,
                    storage_warehouse_id,storage_warehouse_name,storage_warehouse_address,marking_required,ettn_required)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,order_id,supply_id) DO UPDATE SET
                    bundle_id=excluded.bundle_id,state=excluded.state,is_crossdock=excluded.is_crossdock,
                    macrolocal_cluster_id=excluded.macrolocal_cluster_id,
                    storage_warehouse_id=excluded.storage_warehouse_id,
                    storage_warehouse_name=excluded.storage_warehouse_name,
                    storage_warehouse_address=excluded.storage_warehouse_address,
                    marking_required=excluded.marking_required,ettn_required=excluded.ettn_required
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement orderStatement = connection.prepareStatement(orderSql);
             PreparedStatement supplyStatement = connection.prepareStatement(supplySql)) {
            connection.setAutoCommit(false);
            String now = Instant.now().toString();
            for (JsonElement element : orders) {
                if (!element.isJsonObject()) continue;
                JsonObject order = element.getAsJsonObject();
                String orderId = string(order, "order_id");
                String state = string(order, "state");
                if (orderId == null || state == null) continue;
                JsonObject dropOff = object(order, "drop_off_warehouse");
                JsonObject timeslotContainer = object(order, "timeslot");
                JsonObject timeslot = object(timeslotContainer, "timeslot");
                JsonObject timezone = object(timeslotContainer, "timezone_info");
                JsonObject tags = object(order, "order_tags");
                orderStatement.setInt(1, shopId);
                orderStatement.setString(2, orderId);
                setString(orderStatement, 3, string(order, "order_number"));
                orderStatement.setString(4, state);
                setString(orderStatement, 5, string(order, "created_date"));
                setString(orderStatement, 6, string(order, "state_updated_date"));
                setString(orderStatement, 7, string(order, "data_filling_deadline"));
                setString(orderStatement, 8, string(dropOff, "warehouse_id"));
                setString(orderStatement, 9, string(dropOff, "name"));
                setString(orderStatement, 10, string(dropOff, "address"));
                setString(orderStatement, 11, string(timeslot, "from"));
                setString(orderStatement, 12, string(timeslot, "to"));
                setString(orderStatement, 13, string(timezone, "iana_name"));
                setBoolean(orderStatement, 14, bool(tags, "is_virtual"));
                setBoolean(orderStatement, 15, bool(tags, "is_pickup"));
                setBoolean(orderStatement, 16, bool(tags, "is_econom"));
                setBoolean(orderStatement, 17, bool(tags, "is_quant"));
                setBoolean(orderStatement, 18, bool(tags, "is_super_fbo"));
                orderStatement.setString(19, now);
                orderStatement.addBatch();

                for (JsonElement supplyElement : array(order, "supplies")) {
                    if (!supplyElement.isJsonObject()) continue;
                    JsonObject supply = supplyElement.getAsJsonObject();
                    String supplyId = string(supply, "supply_id");
                    if (supplyId == null) continue;
                    JsonObject warehouse = object(supply, "storage_warehouse");
                    JsonObject supplyTags = object(supply, "supply_tags");
                    supplyStatement.setInt(1, shopId);
                    supplyStatement.setString(2, orderId);
                    supplyStatement.setString(3, supplyId);
                    setString(supplyStatement, 4, string(supply, "bundle_id"));
                    setString(supplyStatement, 5, string(supply, "state"));
                    setBoolean(supplyStatement, 6, bool(supply, "is_crossdock"));
                    setString(supplyStatement, 7, string(supply, "macrolocal_cluster_id"));
                    setString(supplyStatement, 8, string(warehouse, "warehouse_id"));
                    setString(supplyStatement, 9, string(warehouse, "name"));
                    setString(supplyStatement, 10, string(warehouse, "address"));
                    setBoolean(supplyStatement, 11, bool(supplyTags, "is_marking_required"));
                    setBoolean(supplyStatement, 12, bool(supplyTags, "is_ettn_required"));
                    supplyStatement.addBatch();
                }
            }
            orderStatement.executeBatch();
            supplyStatement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void replaceOzonItems(int shopId, String orderId, String supplyId, JsonArray items) {
        String sql = """
                INSERT INTO ozon_fbo_supply_items(
                    shop_id,order_id,supply_id,item_key,sku,product_id,offer_id,barcode,name,image_url,
                    quantity,quant,volume_litres,shipment_type,placement_zone)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM ozon_fbo_supply_items WHERE shop_id=? AND order_id=? AND supply_id=?");
             PreparedStatement insert = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            delete.setInt(1, shopId);
            delete.setString(2, orderId);
            delete.setString(3, supplyId);
            delete.executeUpdate();
            int index = 0;
            for (JsonElement element : safeArray(items)) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String sku = string(item, "sku");
                String productId = string(item, "product_id");
                String itemKey = (sku == null ? "" : sku) + ":" + (productId == null ? index : productId);
                insert.setInt(1, shopId);
                insert.setString(2, orderId);
                insert.setString(3, supplyId);
                insert.setString(4, itemKey);
                setString(insert, 5, sku);
                setString(insert, 6, productId);
                setString(insert, 7, string(item, "offer_id"));
                setString(insert, 8, string(item, "barcode"));
                setString(insert, 9, string(item, "name"));
                setString(insert, 10, normalizeImageUrl(string(item, "icon_path")));
                insert.setInt(11, integerValue(item, "quantity"));
                setInteger(insert, 12, integer(item, "quant"));
                setDouble(insert, 13, decimal(item, "volume_in_litres"));
                setString(insert, 14, string(item, "shipment_type"));
                setString(insert, 15, string(item, "placement_zone"));
                insert.addBatch();
                index++;
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<FboSupplyOrder> findOrders(Shop shop) {
        if (shop == null) return List.of();
        return shop.getMarketplace() == Marketplace.OZON ? findOzonOrders(shop) : findWbOrders(shop);
    }

    private List<FboSupplyOrder> findWbOrders(Shop shop) {
        String sql = """
                SELECT preorder_id,supply_id,status_id,warehouse_name,supply_date,
                       COALESCE(updated_date,fact_date,create_date) changed_at,quantity,accepted_quantity,reject_reason
                FROM wb_fbw_orders WHERE shop_id=?
                ORDER BY COALESCE(updated_date,supply_date,create_date) DESC
                """;
        List<FboSupplyOrder> result = new ArrayList<>();
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shop.getId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String status = rows.getString("status_id");
                    result.add(new FboSupplyOrder(shop.getId(), Marketplace.WILDBERRIES,
                            rows.getString("preorder_id"), rows.getString("supply_id"), null, status,
                            FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, status),
                            rows.getString("warehouse_name"), rows.getString("supply_date"),
                            rows.getString("changed_at"), rows.getInt("quantity"), rows.getInt("accepted_quantity"),
                            rows.getString("reject_reason")));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return result;
    }

    private List<FboSupplyOrder> findOzonOrders(Shop shop) {
        String sql = """
                SELECT o.order_id,o.order_number,o.state,
                       COALESCE((SELECT s.storage_warehouse_name FROM ozon_fbo_supplies s
                                 WHERE s.shop_id=o.shop_id AND s.order_id=o.order_id
                                 AND s.storage_warehouse_name IS NOT NULL LIMIT 1),o.drop_off_warehouse_name) warehouse_name,
                       o.timeslot_from,o.state_updated_date,
                       COALESCE((SELECT SUM(i.quantity) FROM ozon_fbo_supply_items i
                                 WHERE i.shop_id=o.shop_id AND i.order_id=o.order_id),0) quantity
                FROM ozon_fbo_orders o WHERE o.shop_id=?
                ORDER BY COALESCE(o.state_updated_date,o.timeslot_from,o.created_date) DESC
                """;
        List<FboSupplyOrder> result = new ArrayList<>();
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shop.getId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String state = rows.getString("state");
                    result.add(new FboSupplyOrder(shop.getId(), Marketplace.OZON,
                            rows.getString("order_id"), null, rows.getString("order_number"), state,
                            FboSupplyStatusMapper.map(Marketplace.OZON, state), rows.getString("warehouse_name"),
                            rows.getString("timeslot_from"), rows.getString("state_updated_date"),
                            rows.getInt("quantity"), 0, null));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return result;
    }

    public List<FboSupplyItem> findItems(Shop shop, String orderId) {
        if (shop == null || orderId == null) return List.of();
        return shop.getMarketplace() == Marketplace.OZON
                ? findOzonItems(shop.getId(), orderId) : findWbItems(shop.getId(), orderId);
    }

    private List<FboSupplyItem> findWbItems(int shopId, String orderId) {
        String sql = """
                SELECT i.item_key,i.barcode,i.vendor_code,i.nm_id,i.need_kiz,i.tech_size,i.color,
                       i.quantity,i.accepted_quantity,
                       COALESCE(c.title,i.vendor_code) product_name,
                       (SELECT COALESCE(NULLIF(p.c246x328_url,''),NULLIF(p.big_url,''),
                                        NULLIF(p.c516x688_url,''),NULLIF(p.square_url,''),NULLIF(p.tm_url,''))
                        FROM wb_product_photos p
                        WHERE p.shop_id=i.shop_id AND CAST(p.nm_id AS TEXT)=i.nm_id
                        ORDER BY p.photo_index LIMIT 1) image_url
                FROM wb_fbw_order_items i
                LEFT JOIN wb_product_cards c ON c.shop_id=i.shop_id AND CAST(c.nm_id AS TEXT)=i.nm_id
                WHERE i.shop_id=? AND i.preorder_id=? ORDER BY i.vendor_code,i.tech_size,i.barcode
                """;
        List<FboSupplyItem> result = new ArrayList<>();
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Object mark = rows.getObject("need_kiz");
                    result.add(new FboSupplyItem(rows.getString("item_key"), rows.getString("image_url"),
                            rows.getString("product_name"), rows.getString("vendor_code"), rows.getString("nm_id"),
                            rows.getString("barcode"), rows.getString("tech_size"), rows.getString("color"),
                            rows.getInt("quantity"), rows.getInt("accepted_quantity"),
                            mark == null ? null : rows.getInt("need_kiz") != 0));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return result;
    }

    private List<FboSupplyItem> findOzonItems(int shopId, String orderId) {
        String sql = """
                SELECT i.item_key,COALESCE(NULLIF(p.primary_image_url,''),NULLIF(i.image_url,'')) image_url,
                       COALESCE(p.name,i.name) name,COALESCE(NULLIF(p.article,''),i.offer_id) article,
                       i.sku,i.barcode,p.size,p.color,i.quantity
                FROM ozon_fbo_supply_items i
                LEFT JOIN ozon_products p ON p.shop_id=i.shop_id
                  AND (p.product_id=i.product_id OR (i.product_id IS NULL AND p.sku=i.sku))
                WHERE i.shop_id=? AND i.order_id=?
                ORDER BY article,name,i.sku
                """;
        List<FboSupplyItem> result = new ArrayList<>();
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new FboSupplyItem(rows.getString("item_key"), rows.getString("image_url"),
                            rows.getString("name"), rows.getString("article"), rows.getString("sku"),
                            rows.getString("barcode"), rows.getString("size"), rows.getString("color"),
                            rows.getInt("quantity"), 0, null));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return result;
    }

    public List<OzonSupplyRef> findOzonSupplyRefs(int shopId, String orderId) {
        String sql = "SELECT supply_id,bundle_id FROM ozon_fbo_supplies WHERE shop_id=? AND order_id=? ORDER BY supply_id";
        List<OzonSupplyRef> result = new ArrayList<>();
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new OzonSupplyRef(rows.getString(1), rows.getString(2)));
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return result;
    }

    public void markSyncSuccess(int shopId, Marketplace marketplace, String cursor) {
        String table = marketplace == Marketplace.OZON ? "ozon_fbo_sync_state" : "wb_fbw_sync_state";
        String sql = marketplace == Marketplace.OZON
                ? "INSERT INTO " + table + "(shop_id,list_cursor,last_synced_at,last_error) VALUES(?,?,?,NULL) "
                    + "ON CONFLICT(shop_id) DO UPDATE SET list_cursor=excluded.list_cursor,last_synced_at=excluded.last_synced_at,last_error=NULL"
                : "INSERT INTO " + table + "(shop_id,last_synced_at,last_error) VALUES(?,?,NULL) "
                    + "ON CONFLICT(shop_id) DO UPDATE SET last_synced_at=excluded.last_synced_at,last_error=NULL";
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            if (marketplace == Marketplace.OZON) {
                setString(statement, 2, cursor);
                statement.setString(3, Instant.now().toString());
            } else {
                statement.setString(2, Instant.now().toString());
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void saveOzonCursor(int shopId, String cursor) {
        String sql = """
                INSERT INTO ozon_fbo_sync_state(shop_id,list_cursor) VALUES(?,?)
                ON CONFLICT(shop_id) DO UPDATE SET list_cursor=excluded.list_cursor
                """;
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            setString(statement, 2, cursor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public String findOzonCursor(int shopId) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT list_cursor FROM ozon_fbo_sync_state WHERE shop_id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public String findLastSyncedAt(int shopId, Marketplace marketplace) {
        String table = marketplace == Marketplace.OZON ? "ozon_fbo_sync_state" : "wb_fbw_sync_state";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_synced_at FROM " + table + " WHERE shop_id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static JsonArray safeArray(JsonArray value) {
        return value == null ? new JsonArray() : value;
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null) return new JsonArray();
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null) return new JsonObject();
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        String text;
        try {
            text = value.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
        text = text == null ? null : text.strip();
        return text == null || text.isEmpty() || text.length() > 4096 ? null : text;
    }

    private static String normalizeImageUrl(String value) {
        if (value == null) return null;
        return value.startsWith("//") ? "https:" + value : value;
    }

    private static Integer integer(JsonObject object, String key) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int integerValue(JsonObject object, String key) {
        Integer value = integer(object, key);
        return value == null ? 0 : Math.max(0, value);
    }

    private static Double decimal(JsonObject object, String key) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsDouble();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Boolean bool(JsonObject object, String key) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void setString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static void setBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value ? 1 : 0);
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setNull(index, Types.REAL); else statement.setDouble(index, value);
    }
}
