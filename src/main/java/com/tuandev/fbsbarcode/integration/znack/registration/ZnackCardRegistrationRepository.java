package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.SearchCriteria;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;

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
                   r.gtin, r.good_id, r.feed_id, r.status, r.error_message, COALESCE(r.wb_updated, 0) AS wb_updated
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

    public Set<String> claimedGtins(int shopId) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT gtin FROM znack_card_registrations
                     WHERE shop_id=? AND TRIM(COALESCE(gtin, ''))<>''
                     """)) {
            statement.setInt(1, shopId);
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
            statement.executeUpdate();
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
                         wb_updated=COALESCE(?,wb_updated),updated_at=? WHERE shop_id=? AND chrt_id=?
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
                result.getString("error_message"), result.getInt("wb_updated") != 0, result.getString("wb_size"));
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
