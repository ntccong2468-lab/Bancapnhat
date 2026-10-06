package com.tuandev.fbsbarcode.features.print.history;

import com.tuandev.fbsbarcode.config.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ImageCacheRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageCacheRepository.class);

    public byte[] findImage(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        String sql = "SELECT image_blob FROM image_cache WHERE cache_key = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return rs.getBytes("image_blob");
        } catch (SQLException e) {
            LOGGER.debug("Skipping cached image lookup for key {}", cacheKey, e);
            return null;
        }
    }

    public void saveImage(String cacheKey, String imageUrl, byte[] imageBlob, String contentType) {
        if (cacheKey == null || cacheKey.isBlank() || imageBlob == null || imageBlob.length == 0) {
            return;
        }
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO image_cache(cache_key, image_url, image_blob, content_type, updated_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(cache_key) DO UPDATE SET
                    image_url = excluded.image_url,
                    image_blob = excluded.image_blob,
                    content_type = excluded.content_type,
                    updated_at = excluded.updated_at,
                    last_used_at = excluded.last_used_at
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ps.setString(2, imageUrl == null ? cacheKey : imageUrl);
            ps.setBytes(3, imageBlob);
            ps.setString(4, contentType);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.debug("Skipping cached image save for key {}", cacheKey, e);
        }
    }

    public Map<String, byte[]> findImages(Collection<String> cacheKeys) {
        Map<String, byte[]> images = new HashMap<>();
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return images;
        }
        java.util.List<String> safeKeys = cacheKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (safeKeys.isEmpty()) {
            return images;
        }

        String placeholders = String.join(", ", java.util.Collections.nCopies(safeKeys.size(), "?"));
        String sql = "SELECT cache_key, image_blob FROM image_cache WHERE cache_key IN (" + placeholders + ")";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (String key : safeKeys) {
                ps.setString(index++, key);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                images.put(rs.getString("cache_key"), rs.getBytes("image_blob"));
            }
            return images;
        } catch (SQLException e) {
            LOGGER.debug("Skipping bulk cached image lookup for {} keys", safeKeys.size(), e);
            return images;
        }
    }
}
