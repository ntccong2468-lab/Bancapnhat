package com.vncode.app.integration.license;

import com.vncode.app.shared.AtomicFilePublisher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.vncode.app.integration.license.LicenseApi.NetworkMode;

/** Non-secret transport preference; never changes the device key or license store. */
final class LicenseNetworkPreferences {
    private final Path file;
    LicenseNetworkPreferences(Path file) { this.file = file == null ? null : file.toAbsolutePath().normalize(); }
    NetworkMode load() {
        if (file == null) return NetworkMode.AUTOMATIC;
        try {
            if (Files.isSymbolicLink(file)) return NetworkMode.AUTOMATIC;
            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes(65);
                if (bytes.length > 64) return NetworkMode.AUTOMATIC;
                return NetworkMode.valueOf(new String(bytes, StandardCharsets.US_ASCII).strip());
            }
        } catch (IOException | IllegalArgumentException invalid) { return NetworkMode.AUTOMATIC; }
    }
    void save(NetworkMode mode) throws IOException {
        if (file == null) return;
        java.io.File staging = null;
        try {
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) throw new IOException();
            staging = AtomicFilePublisher.stagingFile(file.toFile(), ".tmp");
            Files.writeString(staging.toPath(), mode.name(), StandardCharsets.US_ASCII);
            AtomicFilePublisher.publish(staging, file.toFile());
        } catch (IOException failure) { throw new IOException("LICENSE_NETWORK_SETTINGS_FAILED"); }
        finally { AtomicFilePublisher.deleteQuietly(staging); }
    }
}
