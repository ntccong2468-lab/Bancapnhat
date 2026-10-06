package com.tuandev.fbsbarcode.integration.license;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lưu license file đã ký vào appDataDir để dùng offline (ghi atomic qua file tạm). */
public class LicenseStorage {

    private static final Logger log = LoggerFactory.getLogger(LicenseStorage.class);

    private final Path file;
    private final Gson gson = new Gson();

    public LicenseStorage(Path file) {
        this.file = file;
    }

    public synchronized void save(SignedLicenseFile licenseFile) {
        try {
            Files.createDirectories(file.getParent());
            Path staging = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(staging, gson.toJson(licenseFile), StandardCharsets.UTF_8);
            try {
                Files.move(
                        staging,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Không lưu được license file: {}", e.getMessage());
        }
    }

    public synchronized Optional<SignedLicenseFile> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(gson.fromJson(json, SignedLicenseFile.class));
        } catch (IOException | JsonSyntaxException e) {
            log.warn("Không đọc được license file: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Không xóa được license file: {}", e.getMessage());
        }
    }
}
