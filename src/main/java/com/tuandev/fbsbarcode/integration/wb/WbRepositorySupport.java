package com.tuandev.fbsbarcode.integration.wb;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class WbRepositorySupport {
    private WbRepositorySupport() {
    }

    static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    static void setNullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setInt(index, value);
        }
    }

    static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setDouble(index, value);
        }
    }

    static void setNullableBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setInt(index, value ? 1 : 0);
        }
    }

    static void deleteByKey(java.sql.Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }
}
