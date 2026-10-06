package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PrintTemplateRepository {
    private final String tableName;

    public PrintTemplateRepository() {
        this("print_templates");
    }

    protected PrintTemplateRepository(String tableName) {
        this.tableName = tableName;
    }

    public List<TemplateRecord> findAll() {
        String sql = """
                SELECT id, name, page_width, page_height, is_default, layout_json
                FROM %s
                ORDER BY is_default DESC, name ASC
                """.formatted(tableName);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<TemplateRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Không thể tải danh sách template", e);
        }
    }

    public Optional<TemplateRecord> findById(int id) {
        String sql = """
                SELECT id, name, page_width, page_height, is_default, layout_json
                FROM %s
                WHERE id = ?
                """.formatted(tableName);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Không thể tải template", e);
        }
    }

    public Optional<TemplateRecord> findDefault() {
        String sql = """
                SELECT id, name, page_width, page_height, is_default, layout_json
                FROM %s
                WHERE is_default = 1
                LIMIT 1
                """.formatted(tableName);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Không thể tải template mặc định", e);
        }
    }

    public int insert(String name, double pageWidth, double pageHeight, boolean isDefault, String layoutJson) {
        String sql = """
                INSERT INTO %s(name, page_width, page_height, is_default, layout_json, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(tableName);
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            if (isDefault) {
                clearDefault(conn);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setDouble(2, pageWidth);
                ps.setDouble(3, pageHeight);
                ps.setInt(4, isDefault ? 1 : 0);
                ps.setString(5, layoutJson);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    conn.commit();
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
            throw new SQLException("Không thể lấy id template mới");
        } catch (SQLException e) {
            throw new RuntimeException("Không thể tạo template", e);
        }
    }

    public void update(int id, String name, double pageWidth, double pageHeight, boolean isDefault, String layoutJson) {
        String sql = """
                UPDATE %s
                SET name = ?, page_width = ?, page_height = ?, is_default = ?, layout_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.formatted(tableName);
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            if (isDefault) {
                clearDefault(conn);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setDouble(2, pageWidth);
                ps.setDouble(3, pageHeight);
                ps.setInt(4, isDefault ? 1 : 0);
                ps.setString(5, layoutJson);
                ps.setInt(6, id);
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Không thể cập nhật template", e);
        }
    }

    public void rename(int id, String name) {
        String sql = "UPDATE " + tableName + " SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        executeSimpleUpdate(sql, ps -> {
            ps.setString(1, name);
            ps.setInt(2, id);
        }, "Không thể đổi tên template");
    }

    public void delete(int id) {
        executeSimpleUpdate("DELETE FROM " + tableName + " WHERE id = ?", ps -> ps.setInt(1, id), "Không thể xóa template");
    }

    public void setDefault(int id) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                clearDefault(conn);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE " + tableName + " SET is_default = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Không thể đặt template mặc định", e);
        }
    }

    public int count() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Không thể đếm template", e);
        }
    }

    public void normalizePageSize(double pageWidth, double pageHeight) {
        String sql = """
                UPDATE %s
                SET page_width = ?, page_height = ?, updated_at = CURRENT_TIMESTAMP
                WHERE ABS(page_width - ?) > 0.01 OR ABS(page_height - ?) > 0.01
                """.formatted(tableName);
        executeSimpleUpdate(sql, ps -> {
            ps.setDouble(1, pageWidth);
            ps.setDouble(2, pageHeight);
            ps.setDouble(3, pageWidth);
            ps.setDouble(4, pageHeight);
        }, "Không thể chuẩn hóa kích thước template");
    }

    private void clearDefault(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + tableName + " SET is_default = 0 WHERE is_default = 1")) {
            ps.executeUpdate();
        }
    }

    private void executeSimpleUpdate(String sql, SqlConsumer consumer, String message) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            consumer.accept(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(message, e);
        }
    }

    private TemplateRecord mapRow(ResultSet rs) throws SQLException {
        return new TemplateRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getDouble("page_width"),
                rs.getDouble("page_height"),
                rs.getInt("is_default") == 1,
                rs.getString("layout_json")
        );
    }

    public record TemplateRecord(int id, String name, double pageWidth, double pageHeight, boolean isDefault, String layoutJson) {}

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(PreparedStatement ps) throws SQLException;
    }
}
