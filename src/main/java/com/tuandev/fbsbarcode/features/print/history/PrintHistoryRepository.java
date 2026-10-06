package com.tuandev.fbsbarcode.features.print.history;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PrintHistoryRepository {

    public long insertSuccessfulJob(int shopId,
                                    String shopName,
                                    String supplyId,
                                    String supplyName,
                                    String printedAt,
                                    int itemCount,
                                    Integer templateId,
                                    String templateName,
                                    String templateLayoutJson,
                                    List<PrintHistoryItem> items) {
        return insertSuccessfulJob(shopId, shopName, "WILDBERRIES", supplyId, supplyName, printedAt,
                itemCount, templateId, templateName, templateLayoutJson, items);
    }

    public long insertSuccessfulJob(int shopId,
                                    String shopName,
                                    String marketplace,
                                    String supplyId,
                                    String supplyName,
                                    String printedAt,
                                    int itemCount,
                                    Integer templateId,
                                    String templateName,
                                    String templateLayoutJson,
                                    List<PrintHistoryItem> items) {
        return insertJob(shopId, shopName, marketplace, supplyId, supplyName, printedAt, itemCount,
                templateId, templateName, templateLayoutJson, "success", null, items);
    }

    public long insertFailedJob(int shopId,
                                String shopName,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                int itemCount,
                                Integer templateId,
                                String templateName,
                                String templateLayoutJson,
                                String errorMessage) {
        return insertFailedJob(shopId, shopName, "WILDBERRIES", supplyId, supplyName, printedAt,
                itemCount, templateId, templateName, templateLayoutJson, errorMessage);
    }

    public long insertFailedJob(int shopId,
                                String shopName,
                                String marketplace,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                int itemCount,
                                Integer templateId,
                                String templateName,
                                String templateLayoutJson,
                                String errorMessage) {
        return insertJob(shopId, shopName, marketplace, supplyId, supplyName, printedAt, itemCount,
                templateId, templateName, templateLayoutJson, "failed", errorMessage, List.of());
    }

    private long insertJob(int shopId,
                           String shopName,
                           String marketplace,
                           String supplyId,
                           String supplyName,
                           String printedAt,
                           int itemCount,
                           Integer templateId,
                           String templateName,
                           String templateLayoutJson,
                           String status,
                           String errorMessage,
                           List<PrintHistoryItem> items) {
        String jobSql = """
                INSERT INTO print_jobs(
                    shop_id, shop_name, supply_id, supply_name, printed_at, item_count,
                    template_id, template_name, template_layout_json, status, error_message, marketplace
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String itemSql = """
                INSERT INTO print_job_items(
                    print_job_id, sort_index, order_id, brand, name, subject_name, size, ru_size, color,
                    article, barcode, sticker, sticker_code, kiz, image_cache_key, external_order_id, external_item_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement jobPs = conn.prepareStatement(jobSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement itemPs = conn.prepareStatement(itemSql)) {
                jobPs.setInt(1, shopId);
                jobPs.setString(2, shopName);
                jobPs.setString(3, supplyId);
                jobPs.setString(4, supplyName);
                jobPs.setString(5, printedAt == null || printedAt.isBlank() ? Instant.now().toString() : printedAt);
                jobPs.setInt(6, itemCount);
                if (templateId == null) {
                    jobPs.setObject(7, null);
                } else {
                    jobPs.setInt(7, templateId);
                }
                jobPs.setString(8, templateName);
                jobPs.setString(9, templateLayoutJson);
                jobPs.setString(10, status);
                jobPs.setString(11, errorMessage);
                jobPs.setString(12, "OZON".equalsIgnoreCase(marketplace) ? "OZON" : "WILDBERRIES");
                jobPs.executeUpdate();

                long jobId;
                try (ResultSet keys = jobPs.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Cannot create print job history");
                    }
                    jobId = keys.getLong(1);
                }

                for (PrintHistoryItem item : items) {
                    itemPs.setLong(1, jobId);
                    itemPs.setInt(2, item.sortIndex());
                    itemPs.setLong(3, item.orderId());
                    itemPs.setString(4, item.brand());
                    itemPs.setString(5, item.name());
                    itemPs.setString(6, item.subjectName());
                    itemPs.setString(7, item.size());
                    itemPs.setString(8, item.ruSize());
                    itemPs.setString(9, item.color());
                    itemPs.setString(10, item.article());
                    itemPs.setString(11, item.barcode());
                    itemPs.setString(12, item.sticker());
                    itemPs.setString(13, item.stickerCode());
                    itemPs.setString(14, item.kiz());
                    itemPs.setString(15, item.imageCacheKey());
                    itemPs.setString(16, item.externalOrderId());
                    itemPs.setString(17, item.externalItemId());
                    itemPs.addBatch();
                }
                if (!items.isEmpty()) {
                    itemPs.executeBatch();
                }

                conn.commit();
                return jobId;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PrintHistoryJobSummary> findJobsByShop(int shopId) {
        List<PrintHistoryJobSummary> result = new ArrayList<>();
        String sql = """
                SELECT id, shop_id, shop_name, supply_id, supply_name, printed_at, item_count,
                       template_id, template_name, template_layout_json, status, error_message, marketplace
                FROM print_jobs
                WHERE shop_id = ?
                ORDER BY printed_at DESC, id DESC
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Integer templateId = rs.getObject("template_id") == null ? null : rs.getInt("template_id");
                result.add(new PrintHistoryJobSummary(
                        rs.getLong("id"),
                        rs.getInt("shop_id"),
                        rs.getString("shop_name"),
                        rs.getString("supply_id"),
                        rs.getString("supply_name"),
                        rs.getString("printed_at"),
                        rs.getInt("item_count"),
                        templateId,
                        rs.getString("template_name"),
                        rs.getString("template_layout_json"),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getString("marketplace")
                ));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PrintHistoryItem> findItems(long printJobId) {
        List<PrintHistoryItem> result = new ArrayList<>();
        String sql = """
                SELECT print_job_id, sort_index, order_id, brand, name, subject_name, size, color,
                       ru_size, article, barcode, sticker, sticker_code, kiz, image_cache_key,
                       external_order_id, external_item_id
                FROM print_job_items
                WHERE print_job_id = ?
                ORDER BY sort_index
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, printJobId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new PrintHistoryItem(
                        rs.getLong("print_job_id"),
                        rs.getInt("sort_index"),
                        rs.getLong("order_id"),
                        rs.getString("brand"),
                        rs.getString("name"),
                        rs.getString("subject_name"),
                        rs.getString("size"),
                        rs.getString("ru_size"),
                        rs.getString("color"),
                        rs.getString("article"),
                        rs.getString("barcode"),
                        rs.getString("sticker"),
                        rs.getString("sticker_code"),
                        rs.getString("kiz"),
                        rs.getString("image_cache_key"),
                        rs.getString("external_order_id"),
                        rs.getString("external_item_id")
                ));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasSuccessfulJobForSupply(int shopId, String supplyId) {
        String sql = """
                SELECT 1
                FROM print_jobs
                WHERE shop_id = ? AND supply_id = ? AND status = 'success'
                LIMIT 1
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
